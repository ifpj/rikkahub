use axum::{
    extract::{Request, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    middleware::{self, Next},
    response::{
        sse::{Event, KeepAlive, Sse},
        IntoResponse, Response,
    },
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
use serde_json::{json, Value};
use std::{
    borrow::Cow, collections::HashMap, convert::Infallible, env, net::SocketAddr, path::PathBuf,
    sync::Arc, time::Duration,
};
use tokio::{process::Command, sync::Mutex, time::sleep};
use uuid::Uuid;

#[allow(dead_code)]
const PROTOCOL_VERSION: &str = "2024-11-05";
const SERVER_VERSION: &str = "0.1.0";
const DEFAULT_YIELD_MS: u64 = 1_000;
const MAX_YIELD_MS: u64 = 30_000;
const DEFAULT_TIMEOUT_MS: u64 = 30_000;
const MAX_TIMEOUT_MS: u64 = 24 * 60 * 60 * 1_000;
const DEFAULT_MAX_OUTPUT: usize = 12_000;
const MAX_OUTPUT: usize = 64_000;
const DEFAULT_ROWS: u16 = 24;
const DEFAULT_COLUMNS: u16 = 120;
const SESSION_PREFIX: &str = "rikkahub-";

#[derive(Debug, Parser, Clone)]
#[command(
    name = "rikkahub-shell-mcp",
    version,
    about = "Interactive HTTP shell MCP server"
)]
struct Args {
    /// Address to listen on. Keep this on localhost for security.
    #[arg(long, env = "RIKKAHUB_SHELL_MCP_BIND", default_value = "127.0.0.1")]
    bind: String,

    /// HTTP port used by the MCP endpoint.
    #[arg(long, env = "RIKKAHUB_SHELL_MCP_PORT", default_value_t = 38741)]
    port: u16,

    /// Bearer token required by RikkaHub. If omitted, the server accepts local requests without authentication.
    #[arg(long, env = "RIKKAHUB_SHELL_MCP_TOKEN")]
    token: Option<String>,

    /// tmux executable. Useful when Termux uses a non-standard installation.
    #[arg(long, env = "RIKKAHUB_SHELL_MCP_TMUX", default_value = "tmux")]
    tmux: String,

    /// Dedicated tmux socket name so RikkaHub sessions do not collide with user sessions.
    #[arg(
        long,
        env = "RIKKAHUB_SHELL_MCP_TMUX_SOCKET",
        default_value = "rikkahub"
    )]
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

#[derive(Debug, Clone)]
struct SessionState {
    name: String,
    owner: String,
    last_snapshot: String,
    timeout_ms: u64,
    exit_code: Option<i32>,
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
struct TermuxMcpServer {
    state: AppState,
}

impl ServerHandler for TermuxMcpServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
            .with_protocol_version(ProtocolVersion::V_2024_11_05)
            .with_server_info(Implementation::new("rikkahub-shell-mcp", SERVER_VERSION))
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
        context: RequestContext<rmcp::RoleServer>,
    ) -> Result<CallToolResponse, ErrorData> {
        let owner = client_session_id(&context).ok_or_else(|| {
            ErrorData::invalid_request(
                "MCP session identity is missing; reconnect the Streamable HTTP session",
                None,
            )
        })?;
        let args = request
            .arguments
            .map(Value::Object)
            .unwrap_or_else(|| json!({}));
        let result = match request.name.as_ref() {
            "exec_command" => execute_command(&self.state, &args, &owner).await,
            "write_stdin" => write_stdin(&self.state, &args, &owner).await,
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

fn client_session_id(context: &RequestContext<rmcp::RoleServer>) -> Option<String> {
    context
        .extensions
        .get::<axum::http::request::Parts>()
        .and_then(|parts| parts.headers.get("mcp-session-id"))
        .and_then(|value| value.to_str().ok())
        .map(ToOwned::to_owned)
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

    let state = AppState {
        config: Arc::new(Config {
            token: args.token,
            tmux: args.tmux,
            tmux_socket: args.tmux_socket,
        }),
        sessions: Arc::new(Mutex::new(HashMap::new())),
    };

    let mcp_state = state.clone();
    let mcp_service = StreamableHttpService::new(
        move || {
            Ok(TermuxMcpServer {
                state: mcp_state.clone(),
            })
        },
        Arc::new(LocalSessionManager::default()),
        StreamableHttpServerConfig::default()
            .with_legacy_session_mode(true)
            .with_json_response(true),
    );
    let router = Router::new()
        .route("/health", get(health))
        .nest_service("/mcp", mcp_service)
        .layer(middleware::from_fn_with_state(
            state.clone(),
            authorize_request,
        ))
        .with_state(state);
    let address = SocketAddr::new(bind, args.port);
    println!("rikkahub-shell-mcp listening on http://{address}/mcp");
    if let Some(token) = env::var_os("RIKKAHUB_SHELL_MCP_TOKEN") {
        if token.is_empty() {
            eprintln!("warning: RIKKAHUB_SHELL_MCP_TOKEN is empty");
        }
    }

    let listener = tokio::net::TcpListener::bind(address).await?;
    axum::serve(listener, router).await?;
    Ok(())
}

async fn health() -> Response {
    Json(json!({
        "ok": true,
        "server": "rikkahub-shell-mcp",
        "version": SERVER_VERSION,
    }))
    .into_response()
}

#[allow(dead_code)]
async fn sse_stream(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(response) = authorize(&state, &headers) {
        return *response;
    }
    let stream = futures_stream::unfold((), |state| async move {
        sleep(Duration::from_secs(15)).await;
        Some((
            Ok::<_, Infallible>(Event::default().comment("keep-alive")),
            state,
        ))
    });
    Sse::new(stream)
        .keep_alive(
            KeepAlive::new()
                .interval(Duration::from_secs(15))
                .text("keep-alive"),
        )
        .into_response()
}

#[allow(dead_code)]
async fn mcp_delete(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(response) = authorize(&state, &headers) {
        return *response;
    }
    StatusCode::NO_CONTENT.into_response()
}

#[allow(dead_code)]
async fn mcp_post(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(message): Json<Value>,
) -> Response {
    if let Err(response) = authorize(&state, &headers) {
        return *response;
    }

    if message.is_array() {
        return json_rpc_error(
            Value::Null,
            -32600,
            "Batch JSON-RPC messages are not supported by this local server",
        );
    }

    let id = message.get("id").cloned().unwrap_or(Value::Null);
    let method = message.get("method").and_then(Value::as_str).unwrap_or("");
    if id.is_null() {
        handle_notification(&state, method, &message).await;
        return StatusCode::ACCEPTED.into_response();
    }

    let result = match method {
        "initialize" => initialize_result(&message),
        "ping" => Ok(json!({})),
        "tools/list" => Ok(tools_list_result()),
        "tools/call" => handle_tool_call(&state, &message).await,
        _ => Err((-32601, format!("Method not found: {method}"))),
    };

    match result {
        Ok(value) => Json(json!({ "jsonrpc": "2.0", "id": id, "result": value })).into_response(),
        Err((code, message)) => json_rpc_error(id, code, &message),
    }
}

fn exec_command_tool() -> Tool {
    Tool::new(
        "exec_command",
        "Run a shell command in the host environment using a persistent interactive shell session. If the command is still running after yield_time_ms, use write_stdin with the returned session_id. Do not start a second command just to continue an existing session.",
        json!({
            "type": "object",
            "properties": {
                "command": { "type": "string", "description": "Shell command to run" },
                "workdir": { "type": "string", "description": "Working directory. Defaults to the Termux home directory." },
                "timeout_ms": { "type": "integer", "description": "Maximum session lifetime in milliseconds. Defaults to 30000." },
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
        "Continue an existing exec_command session. Use empty chars to poll output, chars to send input, interrupt for Ctrl+C, close_stdin for EOF, or terminate to stop the session.",
        json!({
            "type": "object",
            "properties": {
                "session_id": { "type": "string", "description": "Session ID returned by exec_command" },
                "chars": { "type": "string", "description": "Text to write to the process stdin" },
                "yield_time_ms": { "type": "integer", "description": "How long to wait for output after writing or polling. Defaults to 1000." },
                "close_stdin": { "type": "boolean", "description": "Send EOF after writing" },
                "interrupt": { "type": "boolean", "description": "Send Ctrl+C to the foreground process" },
                "terminate": { "type": "boolean", "description": "Terminate the tmux session" },
                "rows": { "type": "integer", "description": "New terminal height; columns must also be provided." },
                "columns": { "type": "integer", "description": "New terminal width; rows must also be provided." }
            },
            "required": ["session_id"]
        }).as_object().unwrap().clone(),
    )
}

#[allow(dead_code)]
async fn handle_notification(_state: &AppState, _method: &str, _message: &Value) {}

#[allow(dead_code)]
fn initialize_result(message: &Value) -> Result<Value, (i64, String)> {
    let requested = message
        .pointer("/params/protocolVersion")
        .and_then(Value::as_str)
        .unwrap_or(PROTOCOL_VERSION);
    let protocol_version = match requested {
        "2024-11-05" | "2025-03-26" | "2025-06-18" | "2025-11-25" => requested,
        _ => PROTOCOL_VERSION,
    };
    Ok(json!({
        "protocolVersion": protocol_version,
        "capabilities": {
            "tools": { "listChanged": false }
        },
        "serverInfo": {
            "name": "rikkahub-shell-mcp",
            "version": SERVER_VERSION
        }
    }))
}

#[allow(dead_code)]
fn tools_list_result() -> Value {
    json!({
        "tools": [
            {
                "name": "exec_command",
                "description": "Run a shell command in the host environment using a persistent interactive shell session. If the command is still running after yield_time_ms, use write_stdin with the returned session_id. Do not start a second command just to continue an existing session.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "command": { "type": "string", "description": "Shell command to run" },
                        "workdir": { "type": "string", "description": "Working directory. Defaults to the Termux home directory." },
                        "timeout_ms": { "type": "integer", "description": "Maximum session lifetime in milliseconds. Defaults to 30000." },
                        "yield_time_ms": { "type": "integer", "description": "How long to wait before returning output. Defaults to 1000." },
                        "max_output_chars": { "type": "integer", "description": "Maximum output characters returned to the model. Defaults to 12000." },
                        "rows": { "type": "integer", "description": "Initial terminal height. Defaults to 24." },
                        "columns": { "type": "integer", "description": "Initial terminal width. Defaults to 120." }
                    },
                    "required": ["command"]
                }
            },
            {
                "name": "write_stdin",
                "description": "Continue an existing exec_command session. Use empty chars to poll output, chars to send input, interrupt for Ctrl+C, close_stdin for EOF, or terminate to stop the session.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "session_id": { "type": "string", "description": "Session ID returned by exec_command" },
                        "chars": { "type": "string", "description": "Text to write to the process stdin" },
                        "yield_time_ms": { "type": "integer", "description": "How long to wait for output after writing or polling. Defaults to 1000." },
                        "close_stdin": { "type": "boolean", "description": "Send EOF after writing" },
                        "interrupt": { "type": "boolean", "description": "Send Ctrl+C to the foreground process" },
                        "terminate": { "type": "boolean", "description": "Terminate the tmux session" },
                        "rows": { "type": "integer", "description": "New terminal height; columns must also be provided." },
                        "columns": { "type": "integer", "description": "New terminal width; rows must also be provided." },
                    },
                    "required": ["session_id"]
                }
            }
        ]
    })
}

#[allow(dead_code)]
async fn handle_tool_call(state: &AppState, message: &Value) -> Result<Value, (i64, String)> {
    let name = message
        .pointer("/params/name")
        .and_then(Value::as_str)
        .ok_or((-32602, "tools/call requires params.name".to_string()))?;
    let args = message
        .pointer("/params/arguments")
        .cloned()
        .unwrap_or_else(|| json!({}));

    let result = match name {
        "exec_command" => execute_command(state, &args, "legacy").await,
        "write_stdin" => write_stdin(state, &args, "legacy").await,
        _ => Err(ServerError::message(format!("Unknown tool: {name}"))),
    };

    match result {
        Ok(text) => Ok(json!({
            "content": [{ "type": "text", "text": text }],
            "isError": false
        })),
        Err(error) => Ok(json!({
            "content": [{ "type": "text", "text": error.0 }],
            "isError": true
        })),
    }
}

async fn execute_command(
    state: &AppState,
    args: &Value,
    owner: &str,
) -> Result<String, ServerError> {
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
    let session_name = format!("{SESSION_PREFIX}{session_id}");
    let script_dir = session_dir(session_id)?;
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
            // it once so the persistent session and all later commands use
            // Bash while inheriting Termux's original environment.
            "exec bash".into(),
        ],
    )
    .await?;
    let launch = format!(
        "bash -- '{}' ; __rikkahub_exit=$?; printf '\\n__RIKKAHUB_EXIT__%s\\n' \"$__rikkahub_exit\"",
        script_path.to_string_lossy()
    );
    send_literal(state, &session_name, &launch).await?;
    send_key(state, &session_name, "Enter").await?;

    let session_state = SessionState {
        name: session_name.clone(),
        owner: owner.to_owned(),
        last_snapshot: String::new(),
        timeout_ms,
        exit_code: None,
    };
    state
        .sessions
        .lock()
        .await
        .insert(session_id, session_state);
    spawn_timeout_watcher(state.clone(), session_id);

    sleep(Duration::from_millis(yield_ms)).await;
    let output = poll_session(state, session_id, max_output).await?;
    Ok(format_session_result(session_id, output))
}

async fn write_stdin(state: &AppState, args: &Value, owner: &str) -> Result<String, ServerError> {
    let session_id = required_uuid(args, "session_id")?;
    let state_entry = ensure_session(state, session_id, Some(owner)).await?;
    let name = state_entry.name.clone();
    let max_output = bounded_usize(args, "max_output_chars", DEFAULT_MAX_OUTPUT, MAX_OUTPUT);
    let yield_ms = bounded_u64(args, "yield_time_ms", DEFAULT_YIELD_MS, MAX_YIELD_MS);
    let chars = args.get("chars").and_then(Value::as_str);

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
        send_text(state, &name, chars).await?;
    }
    if args
        .get("interrupt")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        send_key(state, &name, "C-c").await?;
    }
    if args
        .get("close_stdin")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        send_key(state, &name, "C-d").await?;
    }
    if args
        .get("terminate")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        kill_session(state, &name).await?;
        return Ok(format_session_result(
            session_id,
            PollResult {
                status: SessionStatus::Completed(137),
                output: String::new(),
            },
        ));
    }

    sleep(Duration::from_millis(yield_ms)).await;
    let output = poll_session(state, session_id, max_output).await?;
    Ok(format_session_result(session_id, output))
}

fn spawn_timeout_watcher(state: AppState, session_id: Uuid) {
    tokio::spawn(async move {
        let timeout_ms = state
            .sessions
            .lock()
            .await
            .get(&session_id)
            .map(|session| session.timeout_ms)
            .unwrap_or(DEFAULT_TIMEOUT_MS);
        sleep(Duration::from_millis(timeout_ms)).await;
        let session = state.sessions.lock().await.get(&session_id).cloned();
        let Some(session) = session else { return };
        if session.exit_code.is_none() && has_session(&state, &session.name).await {
            let _ = kill_session(&state, &session.name).await;
            if let Some(entry) = state.sessions.lock().await.get_mut(&session_id) {
                entry.exit_code = Some(124);
            }
        }
    });
}

async fn poll_session(
    state: &AppState,
    session_id: Uuid,
    max_output: usize,
) -> Result<PollResult, ServerError> {
    let session = ensure_session(state, session_id, None).await?;
    if !has_session(state, &session.name).await {
        return Ok(PollResult {
            status: SessionStatus::Completed(session.exit_code.unwrap_or(1)),
            output: String::new(),
        });
    }
    let snapshot = capture_pane(state, &session.name).await?;
    let delta = if snapshot.starts_with(&session.last_snapshot) {
        snapshot[session.last_snapshot.len()..].to_string()
    } else {
        snapshot.clone()
    };
    let marker = parse_exit_code(&snapshot);
    {
        let mut sessions = state.sessions.lock().await;
        if let Some(entry) = sessions.get_mut(&session_id) {
            entry.last_snapshot = snapshot;
            if marker.is_some() {
                entry.exit_code = marker;
            }
        }
    }
    let status = if let Some(exit_code) = marker {
        SessionStatus::Completed(exit_code)
    } else if has_session(state, &session.name).await {
        SessionStatus::Running
    } else {
        SessionStatus::Completed(1)
    };
    Ok(PollResult {
        status,
        output: limit_output(&strip_marker(&delta), max_output),
    })
}

#[derive(Debug)]
struct PollResult {
    status: SessionStatus,
    output: String,
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
    }
    serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string())
}

async fn ensure_session(
    state: &AppState,
    session_id: Uuid,
    owner: Option<&str>,
) -> Result<SessionState, ServerError> {
    let session = state
        .sessions
        .lock()
        .await
        .get(&session_id)
        .cloned()
        .ok_or_else(|| ServerError::message(format!("Shell session {session_id} is not known")))?;
    if owner.is_some_and(|owner| session.owner != owner) {
        return Err(ServerError::message(format!(
            "Shell session {session_id} belongs to another MCP client"
        )));
    }
    if !has_session(state, &session.name).await && session.exit_code.is_none() {
        return Err(ServerError::message(format!(
            "Shell session {session_id} is not running"
        )));
    }
    Ok(session)
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

fn session_dir(session_id: Uuid) -> Result<PathBuf, ServerError> {
    let home = env::var_os("HOME").map(PathBuf::from).ok_or_else(|| {
        ServerError::message(
            "HOME is not set; start the MCP server from the host shell environment",
        )
    })?;
    Ok(home
        .join(".cache")
        .join("rikkahub-shell-mcp")
        .join("sessions")
        .join(session_id.to_string()))
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

fn parse_exit_code(snapshot: &str) -> Option<i32> {
    snapshot
        .lines()
        .rev()
        .find_map(|line| line.strip_prefix("__RIKKAHUB_EXIT__")?.trim().parse().ok())
}

fn strip_marker(output: &str) -> String {
    output
        .lines()
        .filter(|line| !line.trim_start().starts_with("__RIKKAHUB_EXIT__"))
        .collect::<Vec<_>>()
        .join("\n")
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
    let supplied = bearer.or_else(|| {
        headers
            .get("x-rikkahub-token")
            .and_then(|v| v.to_str().ok())
    });
    if supplied != Some(expected) {
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

#[allow(dead_code)]
fn json_rpc_error(id: Value, code: i64, message: &str) -> Response {
    (
        StatusCode::OK,
        Json(json!({
            "jsonrpc": "2.0",
            "id": id,
            "error": { "code": code, "message": message }
        })),
    )
        .into_response()
}

mod futures_stream {
    pub use futures_util::stream::unfold;
}
