use axum::{
    extract::{Request, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use clap::Parser;
use rmcp::{
    model::{
        CallToolRequestParams, CallToolResponse, CallToolResult, ContentBlock, Implementation,
        ListToolsResult, PaginatedRequestParams, ProtocolVersion, ServerCapabilities, ServerConfig,
        Tool,
    },
    service::RequestContext,
    transport::streamable_http_server::{
        session::local::LocalSessionManager, StreamableHttpServerConfig, StreamableHttpService,
    },
    ErrorData, ServerHandler,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    borrow::Cow,
    collections::HashMap,
    env,
    net::SocketAddr,
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    process::Command,
    sync::Mutex,
    time::{sleep, Instant},
};
use uuid::Uuid;

const SERVER_VERSION: &str = env!("CARGO_PKG_VERSION");
const DEFAULT_YIELD_MS: u64 = 1_000;
const MAX_YIELD_MS: u64 = 30_000;
const YIELD_POLL_INTERVAL_MS: u64 = 150;
const DEFAULT_TIMEOUT_MS: u64 = 10 * 60 * 1_000;
const MAX_TIMEOUT_MS: u64 = 24 * 60 * 60 * 1_000;
const WATCHER_TICK_MS: u64 = 1_000;
const HISTORY_LIMIT: &str = "50000";
const SESSION_IDLE_TTL_MS: u64 = 30 * 60 * 1_000;
const GC_INTERVAL_MS: u64 = 10 * 60 * 1_000;
const MARKER_PREFIX: &str = "__SHELL_MCP_EXIT__";
const DEFAULT_MAX_OUTPUT: usize = 12_000;
const MAX_OUTPUT: usize = 64_000;
const DEFAULT_ROWS: u16 = 24;
const DEFAULT_COLUMNS: u16 = 120;
const SESSION_PREFIX: &str = "shell-mcp-";

#[derive(Debug, Parser, Clone)]
#[command(
    name = "shell-mcp",
    version,
    about = "Interactive HTTP shell MCP server"
)]
struct Args {
    /// Address to listen on. Keep this on localhost for security.
    #[arg(long, env = "SHELL_MCP_BIND", default_value = "127.0.0.1")]
    bind: String,

    /// HTTP port used by the MCP endpoint.
    #[arg(long, env = "SHELL_MCP_PORT", default_value_t = 38741)]
    port: u16,

    /// Bearer token required by MCP clients. If omitted, local requests are unauthenticated.
    #[arg(long, env = "SHELL_MCP_TOKEN")]
    token: Option<String>,

    /// tmux executable. Useful when tmux uses a non-standard installation.
    #[arg(long, env = "SHELL_MCP_TMUX", default_value = "tmux")]
    tmux: String,

    /// Dedicated tmux socket name so MCP sessions do not collide with user sessions.
    #[arg(long, env = "SHELL_MCP_TMUX_SOCKET", default_value = "shell-mcp")]
    tmux_socket: String,
}

#[derive(Clone)]
struct AppState {
    config: Arc<Config>,
    sessions: Arc<Mutex<HashMap<Uuid, SessionState>>>,
}

#[derive(Clone)]
struct Config {
    token: Option<String>,
    tmux: String,
    tmux_socket: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct SessionState {
    name: String,
    timeout_ms: u64,
    #[serde(default)]
    last_active_ms: u64,
    #[serde(default)]
    exit_code: Option<i32>,
    #[serde(default)]
    timed_out: bool,
    current_marker: Uuid,
    #[serde(skip)]
    last_snapshot: String,
    #[serde(skip)]
    operation_lock: Arc<Mutex<()>>,
}

#[derive(Debug)]
struct ServerError(String);

impl ServerError {
    fn message(message: impl Into<String>) -> Self {
        Self(message.into())
    }
}

impl IntoResponse for ServerError {
    fn into_response(self) -> Response {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "error": self.0 })),
        )
            .into_response()
    }
}

#[derive(Clone)]
struct ShellMcpServer {
    state: AppState,
}

impl ServerHandler for ShellMcpServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
            .with_protocol_version(ProtocolVersion::V_2024_11_05)
            .with_server_info(Implementation::new("shell-mcp", SERVER_VERSION))
    }

    fn supported_protocol_versions(&self) -> Cow<'static, [ProtocolVersion]> {
        Cow::Owned(vec![
            ProtocolVersion::V_2024_11_05,
            ProtocolVersion::V_2025_03_26,
            ProtocolVersion::V_2025_06_18,
            ProtocolVersion::V_2025_11_25,
            ProtocolVersion::V_2026_07_28,
        ])
    }

    async fn list_tools(
        &self,
        _request: Option<PaginatedRequestParams>,
        _context: RequestContext<rmcp::RoleServer>,
    ) -> Result<ListToolsResult, ErrorData> {
        Ok(ListToolsResult::with_all_items(vec![
            exec_command_tool(),
            write_stdin_tool(),
        ]))
    }

    async fn call_tool(
        &self,
        request: CallToolRequestParams,
        _context: RequestContext<rmcp::RoleServer>,
    ) -> Result<CallToolResponse, ErrorData> {
        let args = request
            .arguments
            .map(Value::Object)
            .unwrap_or_else(|| json!({}));
        let result = match request.name.as_ref() {
            "exec_command" => execute_command(&self.state, &args).await,
            "write_stdin" => write_stdin(&self.state, &args).await,
            name => {
                return Err(ErrorData::invalid_params(
                    format!("Unknown tool: {name}"),
                    None,
                ));
            }
        };
        match result {
            Ok(text) => Ok(CallToolResult::success(vec![ContentBlock::text(text)]).into()),
            Err(error) => Ok(CallToolResult::error(vec![ContentBlock::text(error.0)]).into()),
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    let bind = args.bind.parse::<std::net::IpAddr>()?;
    if !bind.is_loopback() {
        eprintln!(
            "warning: binding MCP on {bind}; use 127.0.0.1 unless remote access is intentional"
        );
    }
    if args.token.is_none() {
        eprintln!("warning: no MCP token configured; any local app can call this server");
    }

    let restored = load_sessions();
    if !restored.is_empty() {
        println!("restored {} shell session(s) from disk", restored.len());
    }
    let state = AppState {
        config: Arc::new(Config {
            token: args.token,
            tmux: args.tmux,
            tmux_socket: args.tmux_socket,
        }),
        sessions: Arc::new(Mutex::new(restored)),
    };
    let unfinished: Vec<(Uuid, Uuid)> = state
        .sessions
        .lock()
        .await
        .iter()
        .filter(|(_, session)| session.exit_code.is_none())
        .map(|(session_id, session)| (*session_id, session.current_marker))
        .collect();
    for (session_id, current_marker) in unfinished {
        spawn_timeout_watcher(state.clone(), session_id, current_marker);
    }
    spawn_gc(state.clone());
    let _ = gc_once(&state).await;

    let router = build_router(state);
    let address = SocketAddr::new(bind, args.port);
    println!("shell-mcp listening on http://{address}/mcp");
    if let Some(token) = env::var_os("SHELL_MCP_TOKEN") {
        if token.is_empty() {
            eprintln!("warning: SHELL_MCP_TOKEN is empty");
        }
    }

    let listener = tokio::net::TcpListener::bind(address).await?;
    axum::serve(listener, router).await?;
    Ok(())
}

fn build_router(state: AppState) -> Router {
    let mcp_state = state.clone();
    let mcp_service = StreamableHttpService::new(
        move || {
            Ok(ShellMcpServer {
                state: mcp_state.clone(),
            })
        },
        Arc::new(LocalSessionManager::default()),
        StreamableHttpServerConfig::default()
            .with_legacy_session_mode(true)
            .with_json_response(true),
    );
    Router::new()
        .route("/health", get(health))
        .nest_service("/mcp", mcp_service)
        .layer(middleware::from_fn_with_state(
            state.clone(),
            authorize_request,
        ))
        .with_state(state)
}

async fn health() -> Response {
    Json(json!({
        "ok": true,
        "server": "shell-mcp",
        "version": SERVER_VERSION,
    }))
    .into_response()
}

fn exec_command_tool() -> Tool {
    Tool::new(
        "exec_command",
        "Run one shell command in an isolated interactive tmux session. If it is still running after yield_time_ms, use write_stdin with the returned session_id. Once completed, use exec_command for a new command.",
        json!({
            "type": "object",
            "properties": {
                "command": { "type": "string", "description": "Shell command to run" },
                "workdir": { "type": "string", "description": "Working directory. Defaults to the host user's home directory." },
                "timeout_ms": { "type": "integer", "description": "Idle timeout in milliseconds. Renewed by every poll or write_stdin call, so an actively used session never times out. Defaults to 600000." },
                "yield_time_ms": { "type": "integer", "description": "How long to wait before returning output. Defaults to 1000." },
                "max_output_chars": { "type": "integer", "description": "Maximum output characters returned to the model. Defaults to 12000." },
                "rows": { "type": "integer", "description": "Initial terminal height. Defaults to 24." },
                "columns": { "type": "integer", "description": "Initial terminal width. Defaults to 120." }
            },
            "required": ["command"]
        }).as_object().unwrap().clone(),
    )
}

fn write_stdin_tool() -> Tool {
    Tool::new(
        "write_stdin",
        "Continue a running exec_command session. Use empty chars to poll output, chars to send input, interrupt for Ctrl+C, close_stdin for EOF, or terminate to stop the command. A completed session only supports polling; use exec_command for a new command.",
        json!({
            "type": "object",
            "properties": {
                "session_id": { "type": "string", "description": "Session ID returned by exec_command" },
                "chars": { "type": "string", "description": "Text to write to the running command's stdin. After completion, use exec_command for a new command." },
                "yield_time_ms": { "type": "integer", "description": "How long to wait for output after writing or polling. Defaults to 1000." },
                "close_stdin": { "type": "boolean", "description": "Send EOF (Ctrl+D) after writing. Only use when the running program is waiting for EOF." },
                "interrupt": { "type": "boolean", "description": "Send Ctrl+C to the foreground process" },
                "terminate": { "type": "boolean", "description": "Terminate a running tmux command (reports exit_code 137). Already-completed commands keep their original exit code." },
                "rows": { "type": "integer", "description": "New terminal height; columns must also be provided." },
                "columns": { "type": "integer", "description": "New terminal width; rows must also be provided." }
            },
            "required": ["session_id"]
        }).as_object().unwrap().clone(),
    )
}

async fn execute_command(state: &AppState, args: &Value) -> Result<String, ServerError> {
    let command = required_string(args, "command")?;
    if command.trim().is_empty() {
        return Err(ServerError::message("command must not be empty"));
    }
    let cwd = resolve_workdir(args.get("workdir").and_then(Value::as_str))?;
    let yield_ms = bounded_u64(args, "yield_time_ms", DEFAULT_YIELD_MS, MAX_YIELD_MS);
    let timeout_ms = bounded_u64(args, "timeout_ms", DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS);
    let max_output = bounded_usize(args, "max_output_chars", DEFAULT_MAX_OUTPUT, MAX_OUTPUT);
    let rows = bounded_u16(args, "rows", DEFAULT_ROWS, 1, 500);
    let columns = bounded_u16(args, "columns", DEFAULT_COLUMNS, 1, 500);

    let session_id = Uuid::new_v4();
    // Keep the public continuation token out of tmux names and filesystem paths.
    let runtime_id = Uuid::new_v4();
    let session_name = format!("{SESSION_PREFIX}{runtime_id}");
    let script_dir = session_dir(runtime_id)?;
    std::fs::create_dir_all(&script_dir).map_err(|error| {
        ServerError::message(format!("cannot create session directory: {error}"))
    })?;
    let script_path = script_dir.join("command.sh");
    std::fs::write(
        &script_path,
        format!("#!/usr/bin/env bash\nset +e\n{command}\n"),
    )
    .map_err(|error| ServerError::message(format!("cannot write command script: {error}")))?;

    tmux(
        state,
        [
            "new-session".into(),
            "-d".into(),
            "-s".into(),
            session_name.clone(),
            "-c".into(),
            cwd.to_string_lossy().into_owned(),
            "-x".into(),
            columns.to_string(),
            "-y".into(),
            rows.to_string(),
            // tmux may start fish (or another user-selected shell). Replace
            // it with Bash so command startup is consistent.
            "exec bash".into(),
        ],
    )
    .await?;
    tmux(
        state,
        [
            "set-window-option".into(),
            "-t".into(),
            session_name.clone(),
            "history-limit".into(),
            HISTORY_LIMIT.into(),
        ],
    )
    .await?;
    // Keep the pane available for final output after its command process exits.
    tmux(
        state,
        [
            "set-window-option".into(),
            "-t".into(),
            session_name.clone(),
            "remain-on-exit".into(),
            "on".into(),
        ],
    )
    .await?;
    // The interactive shell may have already printed its prompt. Do not treat
    // that startup text as command output when deciding whether to yield.
    let initial_snapshot = capture_pane(state, &session_name).await?;
    let current_marker = Uuid::new_v4();
    // exec replaces the shell: once this command exits, delayed stdin cannot
    // turn into a new command at an idle shell prompt.
    let launch = one_shot_launch(&script_path, current_marker);
    send_literal(state, &session_name, &launch).await?;
    send_key(state, &session_name, "Enter").await?;

    let operation_lock = Arc::new(Mutex::new(()));
    let _operation_guard = operation_lock.lock().await;
    let session_state = SessionState {
        name: session_name.clone(),
        timeout_ms,
        last_active_ms: now_ms(),
        exit_code: None,
        timed_out: false,
        current_marker,
        last_snapshot: initial_snapshot,
        operation_lock: operation_lock.clone(),
    };
    state
        .sessions
        .lock()
        .await
        .insert(session_id, session_state.clone());
    persist_session(&session_id, &session_state);
    spawn_timeout_watcher(state.clone(), session_id, current_marker);

    let output = wait_for_session_output(state, session_id, max_output, yield_ms).await?;
    Ok(format_session_result(session_id, output))
}

async fn write_stdin(state: &AppState, args: &Value) -> Result<String, ServerError> {
    let session_id = required_uuid(args, "session_id")?;
    let operation_lock = session_operation_lock(state, session_id).await?;
    let _operation_guard = operation_lock.lock().await;
    let mut state_entry = ensure_session(state, session_id).await?;
    let name = state_entry.name.clone();
    let max_output = bounded_usize(args, "max_output_chars", DEFAULT_MAX_OUTPUT, MAX_OUTPUT);
    let yield_ms = bounded_u64(args, "yield_time_ms", DEFAULT_YIELD_MS, MAX_YIELD_MS);
    let chars = args.get("chars").and_then(Value::as_str);
    if !has_session(state, &name).await {
        if chars.is_some_and(|chars| !chars.is_empty()) {
            return Err(ServerError::message(
                "Shell command has completed; use exec_command for a new command",
            ));
        }
        let output = poll_session(state, session_id, max_output).await?;
        return Ok(format_session_result(session_id, output));
    }
    let interrupt = args
        .get("interrupt")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    if state_entry.exit_code.is_none() {
        // Refresh completion before handling control actions. The pane is
        // one-shot, so late input cannot run as a new shell command.
        let snapshot = match capture_pane(state, &name).await {
            Ok(snapshot) => snapshot,
            Err(error) => {
                if has_session(state, &name).await {
                    return Err(error);
                }
                if chars.is_some_and(|chars| !chars.is_empty()) {
                    return Err(ServerError::message(
                        "Shell command has completed; use exec_command for a new command",
                    ));
                }
                let output = poll_session(state, session_id, max_output).await?;
                return Ok(format_session_result(session_id, output));
            }
        };
        let completion =
            detect_completion(state, &name, &snapshot, state_entry.current_marker).await?;
        if let Some(code) = completion {
            update_exit_status(state, session_id, code, false).await;
            state_entry.exit_code = Some(code);
        }
    }
    if state_entry.exit_code.is_some() {
        if chars.is_some_and(|chars| !chars.is_empty()) {
            return Err(ServerError::message(
                "Shell command has completed; use exec_command for a new command",
            ));
        }
        let output = poll_session(state, session_id, max_output).await?;
        return Ok(format_session_result(session_id, output));
    }
    touch_session(state, &session_id).await;

    if args.get("rows").is_some() || args.get("columns").is_some() {
        let rows = args
            .get("rows")
            .and_then(Value::as_u64)
            .ok_or_else(|| ServerError::message("rows and columns must be provided together"))?;
        let columns = args
            .get("columns")
            .and_then(Value::as_u64)
            .ok_or_else(|| ServerError::message("rows and columns must be provided together"))?;
        resize_session(state, &name, rows as u16, columns as u16).await?;
    }
    if let Some(chars) = chars {
        if let Err(error) = send_text(state, &name, chars).await {
            if session_has_ended(state, &name).await {
                return Err(ServerError::message(
                    "Shell command has completed; use exec_command for a new command",
                ));
            }
            return Err(error);
        }
    }
    if interrupt {
        if let Err(error) = send_key(state, &name, "C-c").await {
            if !session_has_ended(state, &name).await {
                return Err(error);
            }
            let output = poll_session(state, session_id, max_output).await?;
            return Ok(format_session_result(session_id, output));
        }
    }
    if args
        .get("close_stdin")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        if let Err(error) = send_key(state, &name, "C-d").await {
            if !session_has_ended(state, &name).await {
                return Err(error);
            }
            let output = poll_session(state, session_id, max_output).await?;
            return Ok(format_session_result(session_id, output));
        }
    }
    if args
        .get("terminate")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        let pending = poll_session(state, session_id, max_output).await?;
        if matches!(pending.status, SessionStatus::Completed(_)) {
            return Ok(format_session_result(session_id, pending));
        }
        if let Err(error) = kill_session(state, &name).await {
            if has_session(state, &name).await {
                return Err(error);
            }
            let output = poll_session(state, session_id, max_output).await?;
            return Ok(format_session_result(session_id, output));
        }
        {
            let mut sessions = state.sessions.lock().await;
            if let Some(entry) = sessions.get_mut(&session_id) {
                entry.exit_code = Some(137);
                entry.last_active_ms = now_ms();
                let updated = entry.clone();
                drop(sessions);
                persist_session(&session_id, &updated);
            }
        }
        return Ok(format_session_result(
            session_id,
            PollResult {
                status: SessionStatus::Completed(137),
                output: pending.output,
                timed_out: false,
            },
        ));
    }

    let output = wait_for_session_output(state, session_id, max_output, yield_ms).await?;
    Ok(format_session_result(session_id, output))
}

/// Yield is a maximum wait, not an unconditional delay. Return when there is
/// new output or the command reaches a terminal state.
async fn wait_for_session_output(
    state: &AppState,
    session_id: Uuid,
    max_output: usize,
    yield_ms: u64,
) -> Result<PollResult, ServerError> {
    let started = Instant::now();
    let max_wait = Duration::from_millis(yield_ms);
    loop {
        let result = poll_session(state, session_id, max_output).await?;
        if !matches!(result.status, SessionStatus::Running) || !result.output.is_empty() {
            return Ok(result);
        }
        let remaining = max_wait.saturating_sub(started.elapsed());
        if remaining.is_zero() {
            return Ok(result);
        }
        sleep(remaining.min(Duration::from_millis(YIELD_POLL_INTERVAL_MS))).await;
    }
}

fn spawn_timeout_watcher(state: AppState, session_id: Uuid, current_marker: Uuid) {
    tokio::spawn(async move {
        loop {
            sleep(Duration::from_millis(WATCHER_TICK_MS)).await;
            let Ok(operation_lock) = session_operation_lock(&state, session_id).await else {
                return;
            };
            let _operation_guard = operation_lock.lock().await;
            let session = state.sessions.lock().await.get(&session_id).cloned();
            let Some(session) = session else { return };
            if session.current_marker != current_marker || session.exit_code.is_some() {
                // The command already finished; the GC task reaps idle sessions.
                return;
            }
            if now_ms().saturating_sub(session.last_active_ms) < session.timeout_ms {
                continue;
            }
            if has_session(&state, &session.name).await {
                if let Ok(snapshot) = capture_pane(&state, &session.name).await {
                    if let Ok(Some(code)) =
                        detect_completion(&state, &session.name, &snapshot, current_marker).await
                    {
                        update_exit_status(&state, session_id, code, false).await;
                        return;
                    }
                }
                if let Err(error) = kill_session(&state, &session.name).await {
                    eprintln!("cannot time out shell session {session_id}: {}", error.0);
                    continue;
                }
                update_exit_status(&state, session_id, 124, true).await;
            } else {
                update_exit_status(&state, session_id, 1, false).await;
            }
            return;
        }
    });
}

/// The caller holds this session's operation_lock while reading and updating
/// the pane cursor, so concurrent polls cannot duplicate or skip output.
async fn poll_session(
    state: &AppState,
    session_id: Uuid,
    max_output: usize,
) -> Result<PollResult, ServerError> {
    let session = ensure_session(state, session_id).await?;
    if !has_session(state, &session.name).await {
        return Ok(poll_missing_session(state, session_id, &session).await);
    }
    let snapshot = match capture_pane(state, &session.name).await {
        Ok(snapshot) => snapshot,
        Err(error) => {
            // tmux may disappear between has-session and capture-pane.
            if !has_session(state, &session.name).await {
                return Ok(poll_missing_session(state, session_id, &session).await);
            }
            return Err(error);
        }
    };
    let delta = snapshot_delta(&session.last_snapshot, &snapshot);
    let completion =
        detect_completion(state, &session.name, &snapshot, session.current_marker).await?;
    let mut refreshed = None;
    {
        let mut sessions = state.sessions.lock().await;
        if let Some(entry) = sessions.get_mut(&session_id) {
            entry.last_active_ms = now_ms();
            entry.last_snapshot = snapshot;
            if let Some(code) = completion {
                entry.exit_code = Some(code);
                entry.timed_out = false;
            }
            refreshed = Some(entry.clone());
        }
    }
    let session = refreshed
        .ok_or_else(|| ServerError::message(format!("Shell session {session_id} is not known")))?;
    persist_session(&session_id, &session);
    let status = if let Some(code) = session.exit_code {
        SessionStatus::Completed(code)
    } else {
        SessionStatus::Running
    };
    Ok(PollResult {
        status,
        output: limit_output(&strip_marker(&delta, session.current_marker), max_output),
        timed_out: session.timed_out,
    })
}

async fn poll_missing_session(
    state: &AppState,
    session_id: Uuid,
    session: &SessionState,
) -> PollResult {
    let code = session.exit_code.unwrap_or(1);
    let mut updated = None;
    {
        let mut sessions = state.sessions.lock().await;
        if let Some(entry) = sessions.get_mut(&session_id) {
            if entry.exit_code.is_none() {
                entry.exit_code = Some(code);
            }
            entry.last_active_ms = now_ms();
            updated = Some(entry.clone());
        }
    }
    if let Some(updated) = updated {
        persist_session(&session_id, &updated);
    }
    PollResult {
        status: SessionStatus::Completed(code),
        output: String::new(),
        timed_out: session.timed_out,
    }
}

#[derive(Debug)]
struct PollResult {
    status: SessionStatus,
    output: String,
    timed_out: bool,
}

#[derive(Debug)]
enum SessionStatus {
    Running,
    Completed(i32),
}

fn format_session_result(session_id: Uuid, result: PollResult) -> String {
    let (status, exit_code) = match result.status {
        SessionStatus::Running => ("running", None),
        SessionStatus::Completed(code) => ("completed", Some(code)),
    };
    let mut value = json!({
        "status": status,
        "session_id": session_id.to_string(),
        "stdout": result.output,
        "stderr": "",
        "pty": true,
    });
    if let Some(code) = exit_code {
        value["exit_code"] = json!(code);
        if result.timed_out {
            value["timed_out"] = json!(true);
        }
    }
    serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string())
}

async fn ensure_session(state: &AppState, session_id: Uuid) -> Result<SessionState, ServerError> {
    state
        .sessions
        .lock()
        .await
        .get(&session_id)
        .cloned()
        .ok_or_else(|| ServerError::message(format!("Shell session {session_id} is not known")))
}

async fn session_operation_lock(
    state: &AppState,
    session_id: Uuid,
) -> Result<Arc<Mutex<()>>, ServerError> {
    state
        .sessions
        .lock()
        .await
        .get(&session_id)
        .map(|session| session.operation_lock.clone())
        .ok_or_else(|| ServerError::message(format!("Shell session {session_id} is not known")))
}

async fn tmux<I>(state: &AppState, args: I) -> Result<std::process::Output, ServerError>
where
    I: IntoIterator<Item = String>,
{
    let output = Command::new(&state.config.tmux)
        .arg("-L")
        .arg(&state.config.tmux_socket)
        .args(args)
        .output()
        .await
        .map_err(|error| ServerError::message(format!("failed to start tmux: {error}")))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(ServerError::message(if stderr.is_empty() {
            format!("tmux exited with {}", output.status)
        } else {
            stderr
        }));
    }
    Ok(output)
}

async fn has_session(state: &AppState, name: &str) -> bool {
    Command::new(&state.config.tmux)
        .arg("-L")
        .arg(&state.config.tmux_socket)
        .args(["has-session", "-t", name])
        .output()
        .await
        .map(|output| output.status.success())
        .unwrap_or(false)
}

async fn pane_dead_status(state: &AppState, name: &str) -> Result<Option<i32>, ServerError> {
    let output = tmux(
        state,
        [
            "display-message".into(),
            "-p".into(),
            "-t".into(),
            name.into(),
            "#{pane_dead} #{pane_dead_status}".into(),
        ],
    )
    .await?;
    let line = String::from_utf8_lossy(&output.stdout);
    let mut parts = line.split_whitespace();
    Ok(if parts.next() == Some("1") {
        Some(parts.next().and_then(|code| code.parse().ok()).unwrap_or(1))
    } else {
        None
    })
}

async fn session_has_ended(state: &AppState, name: &str) -> bool {
    !has_session(state, name).await || pane_dead_status(state, name).await.ok().flatten().is_some()
}

async fn detect_completion(
    state: &AppState,
    name: &str,
    snapshot: &str,
    marker: Uuid,
) -> Result<Option<i32>, ServerError> {
    if let Some(code) = parse_exit_code(snapshot, marker) {
        return Ok(Some(code));
    }
    match pane_dead_status(state, name).await {
        Ok(code) => Ok(code),
        Err(_) if !has_session(state, name).await => Ok(Some(1)),
        Err(error) => Err(error),
    }
}

async fn capture_pane(state: &AppState, name: &str) -> Result<String, ServerError> {
    let output = tmux(
        state,
        [
            "capture-pane".into(),
            "-p".into(),
            "-J".into(),
            "-S".into(),
            "-".into(),
            "-t".into(),
            name.into(),
        ],
    )
    .await?;
    Ok(String::from_utf8_lossy(&output.stdout).replace("\r\n", "\n"))
}

async fn send_literal(state: &AppState, name: &str, text: &str) -> Result<(), ServerError> {
    tmux(
        state,
        [
            "send-keys".into(),
            "-t".into(),
            name.into(),
            "-l".into(),
            "--".into(),
            text.into(),
        ],
    )
    .await
    .map(|_| ())
}

async fn send_text(state: &AppState, name: &str, text: &str) -> Result<(), ServerError> {
    let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = normalized.split('\n').collect();
    for (index, line) in lines.iter().enumerate() {
        if !line.is_empty() {
            send_literal(state, name, line).await?;
        }
        if index + 1 < lines.len() {
            send_key(state, name, "Enter").await?;
        }
    }
    Ok(())
}

async fn send_key(state: &AppState, name: &str, key: &str) -> Result<(), ServerError> {
    tmux(
        state,
        ["send-keys".into(), "-t".into(), name.into(), key.into()],
    )
    .await
    .map(|_| ())
}

async fn resize_session(
    state: &AppState,
    name: &str,
    rows: u16,
    columns: u16,
) -> Result<(), ServerError> {
    if !(1..=500).contains(&rows) || !(1..=500).contains(&columns) {
        return Err(ServerError::message(
            "terminal rows and columns must be between 1 and 500",
        ));
    }
    tmux(
        state,
        [
            "resize-window".into(),
            "-t".into(),
            name.into(),
            "-x".into(),
            columns.to_string(),
            "-y".into(),
            rows.to_string(),
        ],
    )
    .await
    .map(|_| ())
}

async fn kill_session(state: &AppState, name: &str) -> Result<(), ServerError> {
    tmux(state, ["kill-session".into(), "-t".into(), name.into()])
        .await
        .map(|_| ())
}

fn session_dir(runtime_id: Uuid) -> Result<PathBuf, ServerError> {
    let home = env::var_os("HOME").map(PathBuf::from).ok_or_else(|| {
        ServerError::message(
            "HOME is not set; start the MCP server from the host shell environment",
        )
    })?;
    Ok(home
        .join(".cache")
        .join("shell-mcp")
        .join("sessions")
        .join(runtime_id.to_string()))
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn cache_root() -> Result<PathBuf, ServerError> {
    let home = env::var_os("HOME").map(PathBuf::from).ok_or_else(|| {
        ServerError::message(
            "HOME is not set; start the MCP server from the host shell environment",
        )
    })?;
    Ok(home.join(".cache").join("shell-mcp"))
}

fn state_dir() -> Result<PathBuf, ServerError> {
    Ok(cache_root()?.join("state"))
}

fn persist_session(session_id: &Uuid, session: &SessionState) {
    let Ok(dir) = state_dir() else {
        return;
    };
    if let Err(error) = std::fs::create_dir_all(&dir) {
        eprintln!("cannot create shell state directory: {error}");
        return;
    }
    if let Ok(text) = serde_json::to_string(session) {
        let path = dir.join(format!("{session_id}.json"));
        let temporary = dir.join(format!(".{session_id}.{}.tmp", Uuid::new_v4()));
        let result = std::fs::write(&temporary, text).and_then(|_| {
            let rename = std::fs::rename(&temporary, &path);
            #[cfg(windows)]
            let rename = rename.or_else(|_| {
                let _ = std::fs::remove_file(&path);
                std::fs::rename(&temporary, &path)
            });
            rename
        });
        if let Err(error) = result {
            eprintln!("cannot persist shell session {session_id}: {error}");
            let _ = std::fs::remove_file(temporary);
        }
    }
}

fn remove_state_file(session_id: &Uuid) {
    if let Ok(dir) = state_dir() {
        let _ = std::fs::remove_file(dir.join(format!("{session_id}.json")));
    }
}

fn load_sessions() -> HashMap<Uuid, SessionState> {
    let mut sessions = HashMap::new();
    let Ok(dir) = state_dir() else {
        return sessions;
    };
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return sessions;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|ext| ext.to_str()) != Some("json") {
            continue;
        }
        let Some(stem) = path.file_stem().and_then(|stem| stem.to_str()) else {
            continue;
        };
        let Ok(session_id) = Uuid::parse_str(stem) else {
            continue;
        };
        let Ok(text) = std::fs::read_to_string(&path) else {
            continue;
        };
        if let Ok(session) = serde_json::from_str::<SessionState>(&text) {
            sessions.insert(session_id, session);
        }
    }
    sessions
}

async fn touch_session(state: &AppState, session_id: &Uuid) {
    let mut sessions = state.sessions.lock().await;
    if let Some(entry) = sessions.get_mut(session_id) {
        entry.last_active_ms = now_ms();
        let updated = entry.clone();
        drop(sessions);
        persist_session(session_id, &updated);
    }
}

async fn update_exit_status(state: &AppState, session_id: Uuid, code: i32, timed_out: bool) {
    let mut sessions = state.sessions.lock().await;
    if let Some(entry) = sessions.get_mut(&session_id) {
        entry.exit_code = Some(code);
        entry.timed_out = timed_out;
        entry.last_active_ms = now_ms();
        let updated = entry.clone();
        drop(sessions);
        persist_session(&session_id, &updated);
    }
}

fn spawn_gc(state: AppState) {
    tokio::spawn(async move {
        loop {
            sleep(Duration::from_millis(GC_INTERVAL_MS)).await;
            let _ = gc_once(&state).await;
        }
    });
}

fn should_reap_session(session: &SessionState, now: u64) -> bool {
    session.exit_code.is_some() && now.saturating_sub(session.last_active_ms) > SESSION_IDLE_TTL_MS
}

/// Reap completed sessions idle past the TTL, then remove old script
/// directories only when they have no state and no live tmux session.
async fn gc_once(state: &AppState) -> Result<(), ServerError> {
    let now = now_ms();
    let expired: Vec<Uuid> = {
        let sessions = state.sessions.lock().await;
        sessions
            .iter()
            .filter(|(_, session)| should_reap_session(session, now))
            .map(|(session_id, _)| *session_id)
            .collect()
    };
    for session_id in expired {
        let Ok(operation_lock) = session_operation_lock(state, session_id).await else {
            continue;
        };
        let _operation_guard = operation_lock.lock().await;
        let name = {
            let sessions = state.sessions.lock().await;
            if sessions
                .get(&session_id)
                .is_some_and(|session| should_reap_session(session, now_ms()))
            {
                sessions
                    .get(&session_id)
                    .map(|session| session.name.clone())
            } else {
                None
            }
        };
        let Some(name) = name else { continue };
        if has_session(state, &name).await {
            if let Err(error) = kill_session(state, &name).await {
                eprintln!("cannot reap shell session {session_id}: {}", error.0);
                continue;
            }
        }
        state.sessions.lock().await.remove(&session_id);
        if let Some(runtime_id) = name
            .strip_prefix(SESSION_PREFIX)
            .and_then(|runtime| Uuid::parse_str(runtime).ok())
        {
            if let Ok(path) = session_dir(runtime_id) {
                let _ = std::fs::remove_dir_all(path);
            }
        }
        remove_state_file(&session_id);
    }
    let referenced: Vec<String> = state
        .sessions
        .lock()
        .await
        .values()
        .filter_map(|session| session.name.strip_prefix(SESSION_PREFIX).map(str::to_owned))
        .collect();
    if let Ok(sessions_root) = cache_root().map(|root| root.join("sessions")) {
        if let Ok(entries) = std::fs::read_dir(&sessions_root) {
            for entry in entries.flatten() {
                let path = entry.path();
                let Some(file_name) = entry.file_name().into_string().ok() else {
                    continue;
                };
                let Ok(runtime_id) = Uuid::parse_str(&file_name) else {
                    continue;
                };
                if referenced.contains(&file_name) {
                    continue;
                }
                let Ok(metadata) = std::fs::symlink_metadata(&path) else {
                    continue;
                };
                if !metadata.file_type().is_dir()
                    || metadata
                        .modified()
                        .ok()
                        .and_then(|modified| SystemTime::now().duration_since(modified).ok())
                        .is_none_or(|age| age.as_millis() < SESSION_IDLE_TTL_MS as u128)
                {
                    continue;
                }
                if has_session(state, &format!("{SESSION_PREFIX}{runtime_id}")).await {
                    continue;
                }
                let _ = std::fs::remove_dir_all(&path);
            }
        }
    }
    Ok(())
}

fn resolve_workdir(raw: Option<&str>) -> Result<PathBuf, ServerError> {
    let home = env::var_os("HOME").map(PathBuf::from).ok_or_else(|| {
        ServerError::message(
            "HOME is not set; start the MCP server from the host shell environment",
        )
    })?;
    let value = raw.unwrap_or("~");
    let path = if value == "~" {
        home.clone()
    } else if let Some(suffix) = value.strip_prefix("~/") {
        home.join(suffix)
    } else {
        PathBuf::from(value)
    };
    if !path.is_dir() {
        return Err(ServerError::message(format!(
            "working directory does not exist or is not a directory: {}",
            path.display()
        )));
    }
    Ok(path)
}

fn shell_quote(path: &std::path::Path) -> String {
    shell_quote_text(&path.to_string_lossy())
}

fn shell_quote_text(text: &str) -> String {
    format!("'{}'", text.replace('\'', "'\\''"))
}

fn one_shot_launch(script_path: &std::path::Path, marker: Uuid) -> String {
    let command = format!(
        "bash -- {} ; {}",
        shell_quote(script_path),
        marker_tail_command(marker)
    );
    format!("exec bash -c {}", shell_quote_text(&command))
}

fn marker_tail_command(marker: Uuid) -> String {
    format!("__shell_mcp_exit=$?; {}", print_marker_command(marker))
}

fn print_marker_command(marker: Uuid) -> String {
    format!("printf '\\n{MARKER_PREFIX}{marker}:%s\\n' \"$__shell_mcp_exit\"")
}

fn parse_exit_code(snapshot: &str, marker: Uuid) -> Option<i32> {
    let expected = format!("{MARKER_PREFIX}{marker}:");
    snapshot.lines().rev().find_map(|line| {
        line.trim_start()
            .strip_prefix(&expected)?
            .trim()
            .parse()
            .ok()
    })
}

fn strip_marker(output: &str, marker: Uuid) -> String {
    let marker_text = format!("{MARKER_PREFIX}{marker}");
    output
        .lines()
        // Also hide the shell's echoed launch line, which contains the marker
        // inside its quoted command and is not real command output.
        .filter(|line| !line.contains(&marker_text))
        .collect::<Vec<_>>()
        .join("\n")
}

/// Return the part of `current` that was not part of `previous`. Falls back to
/// a line-based diff when the plain prefix check fails. This covers edits to
/// the prompt line and tmux dropping old lines from bounded history.
fn snapshot_delta(previous: &str, current: &str) -> String {
    if let Some(delta) = current.strip_prefix(previous) {
        return delta.to_string();
    }
    let previous_lines: Vec<&str> = previous.lines().collect();
    let current_lines: Vec<&str> = current.lines().collect();
    let common_prefix = previous_lines
        .iter()
        .zip(&current_lines)
        .take_while(|(previous, current)| previous == current)
        .count();
    let history_overlap = suffix_prefix_overlap(&previous_lines, &current_lines);
    current_lines[common_prefix.max(history_overlap)..].join("\n")
}

fn suffix_prefix_overlap(previous: &[&str], current: &[&str]) -> usize {
    if current.is_empty() {
        return 0;
    }
    // KMP prefix lengths find the largest suffix of the old pane that is a
    // prefix of the new pane without quadratic work on long tmux histories.
    let mut prefix = vec![0; current.len()];
    for index in 1..current.len() {
        let mut matched = prefix[index - 1];
        while matched > 0 && current[index] != current[matched] {
            matched = prefix[matched - 1];
        }
        if current[index] == current[matched] {
            matched += 1;
        }
        prefix[index] = matched;
    }
    let mut matched = 0;
    for line in previous {
        while matched > 0 && (matched == current.len() || current[matched] != *line) {
            matched = prefix[matched - 1];
        }
        if matched < current.len() && current[matched] == *line {
            matched += 1;
        }
    }
    matched
}

fn limit_output(output: &str, max_chars: usize) -> String {
    if output.chars().count() <= max_chars {
        return output.to_string();
    }
    let keep = max_chars.saturating_sub(160);
    let head = keep / 2;
    let tail = keep.saturating_sub(head);
    let head_text: String = output.chars().take(head).collect();
    let tail_text: String = output
        .chars()
        .rev()
        .take(tail)
        .collect::<String>()
        .chars()
        .rev()
        .collect();
    format!("{head_text}\n... output truncated ...\n{tail_text}")
}

fn required_string(args: &Value, name: &str) -> Result<String, ServerError> {
    args.get(name)
        .and_then(Value::as_str)
        .map(ToOwned::to_owned)
        .ok_or_else(|| ServerError::message(format!("{name} is required")))
}

fn required_uuid(args: &Value, name: &str) -> Result<Uuid, ServerError> {
    let value = required_string(args, name)?;
    Uuid::parse_str(&value).map_err(|_| ServerError::message(format!("invalid {name}")))
}

fn bounded_u64(args: &Value, name: &str, default: u64, max: u64) -> u64 {
    args.get(name)
        .and_then(Value::as_u64)
        .unwrap_or(default)
        .min(max)
}

fn bounded_usize(args: &Value, name: &str, default: usize, max: usize) -> usize {
    args.get(name)
        .and_then(Value::as_u64)
        .map(|value| value as usize)
        .unwrap_or(default)
        .clamp(256, max)
}

fn bounded_u16(args: &Value, name: &str, default: u16, min: u16, max: u16) -> u16 {
    args.get(name)
        .and_then(Value::as_u64)
        .map(|value| value as u16)
        .unwrap_or(default)
        .clamp(min, max)
}

async fn authorize_request(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> Response {
    if let Err(response) = authorize(&state, request.headers()) {
        return *response;
    }
    next.run(request).await
}

fn authorize(state: &AppState, headers: &HeaderMap) -> Result<(), Box<Response>> {
    if let Some(origin) = headers
        .get(header::ORIGIN)
        .and_then(|value| value.to_str().ok())
    {
        let local_origin = origin == "http://127.0.0.1"
            || origin.starts_with("http://127.0.0.1:")
            || origin == "http://localhost"
            || origin.starts_with("http://localhost:");
        if !local_origin {
            return Err(Box::new(
                (
                    StatusCode::FORBIDDEN,
                    Json(json!({ "error": "invalid Origin" })),
                )
                    .into_response(),
            ));
        }
    }
    let Some(expected) = state.config.token.as_deref() else {
        return Ok(());
    };
    let bearer = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    if bearer != Some(expected) {
        Err(Box::new(
            (
                StatusCode::UNAUTHORIZED,
                [(header::WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"))],
                Json(json!({ "error": "invalid MCP token" })),
            )
                .into_response(),
        ))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{
        body::{to_bytes, Body},
        http::Request,
    };
    use tower::ServiceExt;

    fn sample_session(exit_code: Option<i32>, last_active_ms: u64) -> SessionState {
        SessionState {
            name: format!("{SESSION_PREFIX}{}", Uuid::new_v4()),
            timeout_ms: 60 * 60 * 1_000,
            last_active_ms,
            exit_code,
            timed_out: false,
            current_marker: Uuid::new_v4(),
            last_snapshot: String::new(),
            operation_lock: Arc::new(Mutex::new(())),
        }
    }

    #[test]
    fn current_command_marker_ignores_stale_history() {
        let old_marker = Uuid::new_v4();
        let current_marker = Uuid::new_v4();
        let snapshot = format!(
            "{MARKER_PREFIX}{old_marker}:0\ncommand output\n{MARKER_PREFIX}{current_marker}:7\n"
        );
        assert_eq!(parse_exit_code(&snapshot, current_marker), Some(7));
        assert_eq!(
            parse_exit_code(&format!("{MARKER_PREFIX}{old_marker}:0\n"), current_marker),
            None
        );
        assert!(marker_tail_command(current_marker).contains(&current_marker.to_string()));
    }

    #[test]
    fn echoed_launch_does_not_count_as_command_output() {
        let marker = Uuid::new_v4();
        let pane = format!(
            "$ exec bash -c 'printf {MARKER_PREFIX}{marker}'\nactual output\n{MARKER_PREFIX}{marker}:0\n"
        );
        assert_eq!(strip_marker(&pane, marker), "actual output");
    }

    #[test]
    fn one_shot_command_replaces_shell_and_keeps_marker_in_the_same_line() {
        let marker = Uuid::new_v4();
        let launch = one_shot_launch(std::path::Path::new("a'b.sh"), marker);
        assert!(launch.starts_with("exec bash -c "));
        assert!(launch.contains("b.sh"));
        assert!(!launch.contains("a'b.sh"));
        assert!(launch.contains(&format!("{MARKER_PREFIX}{marker}:")));
        assert!(!launch.contains('\n'));
    }

    #[test]
    fn pane_delta_handles_prompt_edits_and_history_rollover() {
        assert_eq!(
            snapshot_delta("first\nprompt$ ", "first\nprompt$ next\nresult"),
            "next\nresult"
        );
        assert_eq!(
            snapshot_delta("first\nprompt$ old", "first\nprompt$ next\nresult"),
            "prompt$ next\nresult"
        );
        assert_eq!(
            snapshot_delta("one\ntwo\nthree", "two\nthree\nfour"),
            "four"
        );
    }

    #[test]
    fn gc_only_reaps_completed_idle_sessions() {
        let now = SESSION_IDLE_TTL_MS + 1;
        assert!(!should_reap_session(&sample_session(None, 0), now));
        assert!(should_reap_session(&sample_session(Some(0), 0), now));
        assert!(!should_reap_session(&sample_session(Some(0), now), now));
    }

    #[test]
    fn restored_session_keeps_its_current_marker() {
        let session = sample_session(None, 123);
        let serialized = serde_json::to_string(&session).unwrap();
        let restored: SessionState = serde_json::from_str(&serialized).unwrap();
        assert_eq!(restored.current_marker, session.current_marker);
        assert_eq!(restored.last_active_ms, 123);
    }

    #[test]
    fn write_stdin_requires_session_id_and_reports_missing_id_briefly() {
        let schema = write_stdin_tool();
        assert_eq!(
            schema.input_schema.get("required"),
            Some(&json!(["session_id"]))
        );
        assert_eq!(
            required_uuid(&json!({}), "session_id").unwrap_err().0,
            "session_id is required"
        );
    }

    fn unavailable_tmux_state(exit_code: Option<i32>) -> (AppState, Uuid) {
        let session_id = Uuid::new_v4();
        let session = sample_session(exit_code, now_ms());
        let state = AppState {
            config: Arc::new(Config {
                token: None,
                tmux: "nonexistent-shell-mcp-test-tmux".into(),
                tmux_socket: "test".into(),
            }),
            sessions: Arc::new(Mutex::new(HashMap::from([(session_id, session)]))),
        };
        (state, session_id)
    }

    #[tokio::test]
    async fn controls_on_terminated_session_are_idempotent() {
        let (state, session_id) = unavailable_tmux_state(Some(137));
        let args = json!({
            "session_id": session_id.to_string(),
            "interrupt": true,
            "terminate": true,
            "chars": "",
            "yield_time_ms": 0,
        });
        let first: Value =
            serde_json::from_str(&write_stdin(&state, &args).await.unwrap()).unwrap();
        let second: Value =
            serde_json::from_str(&write_stdin(&state, &args).await.unwrap()).unwrap();
        let polled: Value = serde_json::from_str(
            &write_stdin(
                &state,
                &json!({ "session_id": session_id.to_string(), "chars": "", "yield_time_ms": 0 }),
            )
            .await
            .unwrap(),
        )
        .unwrap();
        remove_state_file(&session_id);

        assert_eq!(first, second);
        assert_eq!(first, polled);
        assert_eq!(first["status"], "completed");
        assert_eq!(first["exit_code"], 137);
    }

    #[tokio::test]
    async fn polling_disappeared_session_returns_completed() {
        let (state, session_id) = unavailable_tmux_state(None);
        let result: Value = serde_json::from_str(
            &write_stdin(
                &state,
                &json!({ "session_id": session_id.to_string(), "chars": "", "yield_time_ms": 0 }),
            )
            .await
            .unwrap(),
        )
        .unwrap();
        remove_state_file(&session_id);

        assert_eq!(result["status"], "completed");
        assert_eq!(result["exit_code"], 1);
    }

    #[tokio::test]
    async fn completed_session_rejects_new_command_input() {
        let (state, session_id) = unavailable_tmux_state(Some(0));
        let result = write_stdin(
            &state,
            &json!({ "session_id": session_id.to_string(), "chars": "echo unsafe" }),
        )
        .await;
        assert_eq!(
            result.unwrap_err().0,
            "Shell command has completed; use exec_command for a new command"
        );
        remove_state_file(&session_id);
    }

    #[tokio::test]
    async fn completed_session_does_not_wait_for_full_yield() {
        let (state, session_id) = unavailable_tmux_state(Some(0));
        let result = tokio::time::timeout(
            Duration::from_secs(2),
            wait_for_session_output(&state, session_id, DEFAULT_MAX_OUTPUT, MAX_YIELD_MS),
        )
        .await;
        remove_state_file(&session_id);
        assert!(matches!(
            result.unwrap().unwrap().status,
            SessionStatus::Completed(0)
        ));
    }

    fn test_router() -> Router {
        build_router(AppState {
            config: Arc::new(Config {
                token: None,
                tmux: "tmux".into(),
                tmux_socket: "test".into(),
            }),
            sessions: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    #[test]
    fn bearer_token_auth_is_client_independent() {
        let state = AppState {
            config: Arc::new(Config {
                token: Some("secret".into()),
                tmux: "tmux".into(),
                tmux_socket: "test".into(),
            }),
            sessions: Arc::new(Mutex::new(HashMap::new())),
        };
        let mut headers = HeaderMap::new();
        assert!(authorize(&state, &headers).is_err());
        headers.insert(header::AUTHORIZATION, "Bearer secret".parse().unwrap());
        assert!(authorize(&state, &headers).is_ok());
    }

    async fn send_request(router: Router, request: Request<Body>) -> (HeaderMap, Value) {
        let response = router.oneshot(request).await.unwrap();
        let status = response.status();
        let headers = response.headers().clone();
        let body = to_bytes(response.into_body(), 1024 * 1024).await.unwrap();
        let body_text = String::from_utf8(body.to_vec()).unwrap();
        let json_text = body_text
            .lines()
            .find_map(|line| line.strip_prefix("data: {"))
            .map(|line| format!("{{{line}"))
            .unwrap_or_else(|| body_text.clone());
        let value: Value = serde_json::from_str(&json_text)
            .unwrap_or_else(|_| panic!("status={status}, body={body_text}"));
        assert_eq!(status, StatusCode::OK, "{value}");
        (headers, value)
    }

    #[tokio::test]
    async fn modern_tool_call_needs_no_client_specific_header() {
        let router = test_router();
        let discover = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "server/discover",
            "params": { "_meta": {
                "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientInfo": { "name": "test", "version": "1.0" },
                "io.modelcontextprotocol/clientCapabilities": {}
            } }
        });
        let discover_request = Request::builder()
            .method("POST")
            .uri("/mcp")
            .header("host", "localhost")
            .header("content-type", "application/json")
            .header("accept", "application/json, text/event-stream")
            .header("mcp-protocol-version", "2026-07-28")
            .header("mcp-method", "server/discover")
            .body(Body::from(discover.to_string()))
            .unwrap();
        let (_, discover_result) = send_request(router.clone(), discover_request).await;
        assert!(discover_result["result"]["supportedVersions"]
            .as_array()
            .unwrap()
            .contains(&json!("2026-07-28")));

        let body = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "exec_command",
                "arguments": { "command": "" },
                "_meta": {
                    "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                    "io.modelcontextprotocol/clientInfo": { "name": "test", "version": "1.0" },
                    "io.modelcontextprotocol/clientCapabilities": {}
                }
            }
        });
        let request = Request::builder()
            .method("POST")
            .uri("/mcp")
            .header("host", "localhost")
            .header("content-type", "application/json")
            .header("accept", "application/json, text/event-stream")
            .header("mcp-protocol-version", "2026-07-28")
            .header("mcp-method", "tools/call")
            .header("mcp-name", "exec_command")
            .body(Body::from(body.to_string()))
            .unwrap();
        let (_, response) = send_request(router.clone(), request).await;
        assert_eq!(response["result"]["isError"], true);
        assert_eq!(
            response["result"]["content"][0]["text"],
            "command must not be empty"
        );

        let missing_session_id = json!({
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {
                "name": "write_stdin",
                "arguments": {},
                "_meta": {
                    "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                    "io.modelcontextprotocol/clientInfo": { "name": "test", "version": "1.0" },
                    "io.modelcontextprotocol/clientCapabilities": {}
                }
            }
        });
        let request = Request::builder()
            .method("POST")
            .uri("/mcp")
            .header("host", "localhost")
            .header("content-type", "application/json")
            .header("accept", "application/json, text/event-stream")
            .header("mcp-protocol-version", "2026-07-28")
            .header("mcp-method", "tools/call")
            .header("mcp-name", "write_stdin")
            .body(Body::from(missing_session_id.to_string()))
            .unwrap();
        let (_, response) = send_request(router, request).await;
        assert_eq!(response["result"]["isError"], true);
        assert_eq!(
            response["result"]["content"][0]["text"],
            "session_id is required"
        );
    }

    #[tokio::test]
    async fn legacy_initialize_still_uses_standard_mcp_session() {
        let body = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": { "name": "test", "version": "1.0" }
            }
        });
        let request = Request::builder()
            .method("POST")
            .uri("/mcp")
            .header("host", "localhost")
            .header("content-type", "application/json")
            .header("accept", "application/json, text/event-stream")
            .body(Body::from(body.to_string()))
            .unwrap();
        let router = test_router();
        let (headers, response) = send_request(router.clone(), request).await;
        assert_eq!(response["result"]["protocolVersion"], "2025-11-25");
        assert_eq!(response["result"]["serverInfo"]["name"], "shell-mcp");
        assert_eq!(response["result"]["serverInfo"]["version"], SERVER_VERSION);
        let mcp_session_id = headers.get("mcp-session-id").unwrap().to_str().unwrap();
        let initialized = Request::builder()
            .method("POST")
            .uri("/mcp")
            .header("host", "localhost")
            .header("content-type", "application/json")
            .header("accept", "application/json, text/event-stream")
            .header("mcp-session-id", mcp_session_id)
            .header("mcp-protocol-version", "2025-11-25")
            .body(Body::from(
                json!({ "jsonrpc": "2.0", "method": "notifications/initialized" }).to_string(),
            ))
            .unwrap();
        let initialized_response = router.clone().oneshot(initialized).await.unwrap();
        assert_eq!(initialized_response.status(), StatusCode::ACCEPTED);

        let call = Request::builder()
            .method("POST")
            .uri("/mcp")
            .header("host", "localhost")
            .header("content-type", "application/json")
            .header("accept", "application/json, text/event-stream")
            .header("mcp-session-id", mcp_session_id)
            .header("mcp-protocol-version", "2025-11-25")
            .body(Body::from(
                json!({
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/call",
                    "params": { "name": "exec_command", "arguments": { "command": "" } }
                })
                .to_string(),
            ))
            .unwrap();
        let (_, result) = send_request(router, call).await;
        assert_eq!(result["result"]["isError"], true);
        assert_eq!(
            result["result"]["content"][0]["text"],
            "command must not be empty"
        );
    }
}
