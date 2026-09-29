mod pty;
mod session;
mod terminal_text;

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
use serde_json::{json, Value};
use std::{borrow::Cow, net::SocketAddr, sync::Arc};

const SERVER_VERSION: &str = env!("CARGO_PKG_VERSION");
const RAW_STDOUT_HEADER: &str = "x-shell-mcp-raw-stdout";

#[derive(Debug, Parser)]
#[command(
    name = "shell-mcp",
    version,
    about = "Interactive HTTP PTY shell MCP server"
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
}

#[derive(Clone)]
struct AppState {
    token: Option<String>,
    sessions: session::SessionManager,
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
        context: RequestContext<rmcp::RoleServer>,
    ) -> Result<CallToolResponse, ErrorData> {
        let include_raw = context
            .extensions
            .get::<axum::http::request::Parts>()
            .and_then(|parts| parts.headers.get(RAW_STDOUT_HEADER))
            .is_some_and(|value| value == "1");
        let args = request
            .arguments
            .map(Value::Object)
            .unwrap_or_else(|| json!({}));
        let result = match request.name.as_ref() {
            "exec_command" => {
                self.state
                    .sessions
                    .execute_command_with_raw(&args, include_raw)
                    .await
            }
            "write_stdin" => {
                self.state
                    .sessions
                    .write_stdin_with_raw(&args, include_raw)
                    .await
            }
            name => {
                return Err(ErrorData::invalid_params(
                    format!("Unknown tool: {name}"),
                    None,
                ))
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
    let state = AppState {
        token: args.token,
        sessions: session::SessionManager::default(),
    };
    let gc = state.sessions.start_gc();
    let router = build_router(state.clone());
    let address = SocketAddr::new(bind, args.port);
    let listener = tokio::net::TcpListener::bind(address).await?;
    println!("shell-mcp listening on http://{address}/mcp");
    let result = axum::serve(listener, router)
        .with_graceful_shutdown(shutdown_signal())
        .await;
    gc.abort();
    state.sessions.shutdown().await;
    result?;
    Ok(())
}

#[cfg(unix)]
async fn shutdown_signal() {
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("cannot listen for SIGTERM");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {},
        _ = terminate.recv() => {},
    }
}

#[cfg(not(unix))]
async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
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
    Json(json!({ "ok": true, "server": "shell-mcp", "version": SERVER_VERSION })).into_response()
}

fn exec_command_tool() -> Tool {
    Tool::new(
        "exec_command",
        "Run one Bash command in an interactive PTY. If it is still running after yield_time_ms, continue it with write_stdin and the returned session_id. Once completed, use exec_command for a new command.",
        json!({
            "type": "object",
            "properties": {
                "command": { "type": "string", "description": "Shell command to run" },
                "workdir": { "type": "string", "description": "Working directory. Defaults to the host user's home directory." },
                "timeout_ms": { "type": "integer", "description": "Idle timeout in milliseconds. Renewed by each poll or input; defaults to 600000." },
                "yield_time_ms": { "type": "integer", "description": "Maximum time to wait for output or completion. Defaults to 1000." },
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
        "Continue a running exec_command session. Empty chars polls output; chars sends input, interrupt sends Ctrl+C, close_stdin sends EOF, and terminate stops the command. A completed session only supports polling.",
        json!({
            "type": "object",
            "properties": {
                "session_id": { "type": "string", "description": "Session ID returned by exec_command" },
                "chars": { "type": "string", "description": "Text to write to the running command's stdin. Use exec_command for a new command after completion." },
                "yield_time_ms": { "type": "integer", "description": "Maximum time to wait for output or completion after writing or polling. Defaults to 1000." },
                "max_output_chars": { "type": "integer", "description": "Maximum output characters returned to the model. Defaults to 12000." },
                "close_stdin": { "type": "boolean", "description": "Send EOF (Ctrl+D) after writing" },
                "interrupt": { "type": "boolean", "description": "Send Ctrl+C to the process group" },
                "terminate": { "type": "boolean", "description": "Terminate the command. Already-completed commands keep their original exit code." },
                "rows": { "type": "integer", "description": "New terminal height; columns must also be provided." },
                "columns": { "type": "integer", "description": "New terminal width; rows must also be provided." }
            },
            "required": ["session_id"]
        }).as_object().unwrap().clone(),
    )
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
    let Some(expected) = state.token.as_deref() else {
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

    fn test_router() -> Router {
        build_router(AppState {
            token: None,
            sessions: session::SessionManager::default(),
        })
    }

    #[test]
    fn write_stdin_schema_requires_session_id() {
        assert_eq!(
            write_stdin_tool().input_schema.get("required"),
            Some(&json!(["session_id"]))
        );
    }

    #[test]
    fn bearer_token_auth_is_client_independent() {
        let state = AppState {
            token: Some("secret".into()),
            sessions: session::SessionManager::default(),
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
            "jsonrpc": "2.0", "id": 1, "method": "server/discover",
            "params": { "_meta": {
                "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientInfo": { "name": "test", "version": "1.0" },
                "io.modelcontextprotocol/clientCapabilities": {}
            } }
        });
        let request = Request::builder()
            .method("POST")
            .uri("/mcp")
            .header("host", "localhost")
            .header("content-type", "application/json")
            .header("accept", "application/json, text/event-stream")
            .header("mcp-protocol-version", "2026-07-28")
            .header("mcp-method", "server/discover")
            .body(Body::from(discover.to_string()))
            .unwrap();
        let (_, discover_result) = send_request(router.clone(), request).await;
        assert!(discover_result["result"]["supportedVersions"]
            .as_array()
            .unwrap()
            .contains(&json!("2026-07-28")));

        for (name, arguments, expected_error) in [
            (
                "exec_command",
                json!({ "command": "" }),
                "command must not be empty",
            ),
            ("write_stdin", json!({}), "session_id is required"),
        ] {
            let body = json!({
                "jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": {
                    "name": name, "arguments": arguments,
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
                .header("mcp-name", name)
                .body(Body::from(body.to_string()))
                .unwrap();
            let (_, response) = send_request(router.clone(), request).await;
            assert_eq!(response["result"]["isError"], true);
            assert_eq!(response["result"]["content"][0]["text"], expected_error);
        }
    }

    #[tokio::test]
    async fn raw_stdout_header_controls_extra_field_on_tool_calls() {
        let sessions = session::SessionManager::default();
        let raw_id = sessions
            .insert_completed_test_session(0, b"\x1b[31mred\x1b[0m\n")
            .await;
        let plain_id = sessions
            .insert_completed_test_session(0, b"\x1b[32mgreen\x1b[0m\n")
            .await;
        let router = build_router(AppState {
            token: None,
            sessions,
        });

        for (id, include_raw, expected) in [(raw_id, true, "red\n"), (plain_id, false, "green\n")] {
            let body = json!({
                "jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {
                    "name": "write_stdin", "arguments": { "session_id": id.to_string() },
                    "_meta": {
                        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                        "io.modelcontextprotocol/clientInfo": { "name": "test", "version": "1.0" },
                        "io.modelcontextprotocol/clientCapabilities": {}
                    }
                }
            });
            let mut request = Request::builder()
                .method("POST")
                .uri("/mcp")
                .header("host", "localhost")
                .header("content-type", "application/json")
                .header("accept", "application/json, text/event-stream")
                .header("mcp-protocol-version", "2026-07-28")
                .header("mcp-method", "tools/call")
                .header("mcp-name", "write_stdin");
            if include_raw {
                request = request.header(RAW_STDOUT_HEADER, "1");
            }
            let (_, response) = send_request(
                router.clone(),
                request.body(Body::from(body.to_string())).unwrap(),
            )
            .await;
            let text = response["result"]["content"][0]["text"]
                .as_str()
                .expect("text tool result");
            let output: Value = serde_json::from_str(text).unwrap();
            assert_eq!(output["stdout"], expected);
            assert_eq!(output.get("raw_stdout").is_some(), include_raw);
        }
    }

    #[tokio::test]
    async fn legacy_initialize_still_uses_standard_mcp_session() {
        let router = test_router();
        let body = json!({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25", "capabilities": {},
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
        let (headers, response) = send_request(router.clone(), request).await;
        assert_eq!(response["result"]["protocolVersion"], "2025-11-25");
        assert_eq!(response["result"]["serverInfo"]["name"], "shell-mcp");
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
        assert_eq!(
            router.clone().oneshot(initialized).await.unwrap().status(),
            StatusCode::ACCEPTED
        );
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
                    "jsonrpc": "2.0", "id": 2, "method": "tools/call",
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
