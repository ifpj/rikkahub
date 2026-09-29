use crate::{pty, ServerError};
use serde_json::{json, Value};
use std::{collections::HashMap, env, path::PathBuf, sync::Arc};
use tokio::{
    sync::{Mutex, Notify},
    task::JoinHandle,
    time::{sleep, sleep_until, timeout, timeout_at, Duration, Instant},
};
use uuid::Uuid;

const DEFAULT_YIELD_MS: u64 = 1_000;
const MAX_YIELD_MS: u64 = 30_000;
const DEFAULT_TIMEOUT_MS: u64 = 10 * 60 * 1_000;
const MAX_TIMEOUT_MS: u64 = 24 * 60 * 60 * 1_000;
const SESSION_IDLE_TTL: Duration = Duration::from_secs(30 * 60);
const GC_INTERVAL: Duration = Duration::from_secs(10 * 60);
const DEFAULT_MAX_OUTPUT: usize = 12_000;
const MAX_OUTPUT: usize = 64_000;
const MAX_CAPTURE_BYTES: usize = 256 * 1024;
const DEFAULT_ROWS: u16 = 24;
const DEFAULT_COLUMNS: u16 = 120;

#[derive(Clone, Default)]
pub struct SessionManager {
    sessions: Arc<Mutex<HashMap<Uuid, Arc<Session>>>>,
}

struct Session {
    state: Mutex<SessionState>,
    operation: Mutex<()>,
    changed: Notify,
    activity: Notify,
}

struct SessionState {
    pty: Option<Arc<pty::PtyProcess>>,
    output: Vec<u8>,
    output_start: u64,
    cursor: u64,
    timeout: Duration,
    last_active: Instant,
    exit_code: Option<i32>,
    timed_out: bool,
}

struct PollResult {
    status: SessionStatus,
    output: String,
    truncated: bool,
    timed_out: bool,
}

#[derive(Debug, PartialEq, Eq)]
enum SessionStatus {
    Running,
    Completed(i32),
}

impl SessionManager {
    pub fn start_gc(&self) -> JoinHandle<()> {
        let manager = self.clone();
        tokio::spawn(async move {
            loop {
                sleep(GC_INTERVAL).await;
                manager.gc_once().await;
            }
        })
    }

    pub async fn shutdown(&self) {
        let sessions: Vec<_> = self.sessions.lock().await.values().cloned().collect();
        for session in sessions {
            let state = session.state.lock().await;
            if state.exit_code.is_none() {
                if let Some(pty) = state.pty.as_ref() {
                    let _ = pty.terminate();
                }
            }
        }
    }

    pub async fn execute_command(&self, args: &Value) -> Result<String, ServerError> {
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
        let (pty, mut child) =
            pty::spawn(&command, &cwd, rows, columns).map_err(ServerError::message)?;
        let pty = Arc::new(pty);
        let session_id = Uuid::new_v4();
        let session = Arc::new(Session {
            state: Mutex::new(SessionState {
                pty: Some(pty.clone()),
                output: Vec::new(),
                output_start: 0,
                cursor: 0,
                timeout: Duration::from_millis(timeout_ms),
                last_active: Instant::now(),
                exit_code: None,
                timed_out: false,
            }),
            operation: Mutex::new(()),
            changed: Notify::new(),
            activity: Notify::new(),
        });
        self.sessions
            .lock()
            .await
            .insert(session_id, session.clone());

        let read_session = session.clone();
        let read_pty = pty.clone();
        let mut reader = tokio::spawn(async move {
            loop {
                match read_pty.read_chunk().await {
                    Ok(Some(chunk)) => read_session.append_output(&chunk).await,
                    Ok(None) => break,
                    Err(error) => {
                        eprintln!("PTY read failed for {session_id}: {error}");
                        break;
                    }
                }
            }
        });
        let wait_session = session.clone();
        tokio::spawn(async move {
            let status = child.wait().await;
            // The last bytes can still be buffered in the PTY after wait() returns.
            if timeout(Duration::from_millis(250), &mut reader)
                .await
                .is_err()
            {
                reader.abort();
                let _ = reader.await;
            }
            let code = match status {
                Ok(status) => exit_code(status),
                Err(error) => {
                    eprintln!("Cannot wait for shell command {session_id}: {error}");
                    1
                }
            };
            wait_session.finish(code).await;
        });
        let idle_session = session.clone();
        tokio::spawn(async move { watch_idle(idle_session).await });

        let result = wait_for_output(&session, max_output, yield_ms).await;
        Ok(format_session_result(session_id, result))
    }

    pub async fn write_stdin(&self, args: &Value) -> Result<String, ServerError> {
        let session_id = required_uuid(args, "session_id")?;
        let session = self
            .sessions
            .lock()
            .await
            .get(&session_id)
            .cloned()
            .ok_or_else(|| {
                ServerError::message(format!("Shell session {session_id} is not known"))
            })?;
        let _operation = session.operation.lock().await;
        let max_output = bounded_usize(args, "max_output_chars", DEFAULT_MAX_OUTPUT, MAX_OUTPUT);
        let yield_ms = bounded_u64(args, "yield_time_ms", DEFAULT_YIELD_MS, MAX_YIELD_MS);
        let chars = args.get("chars").and_then(Value::as_str);
        let pty = {
            let state = session.state.lock().await;
            if state.exit_code.is_some() {
                if chars.is_some_and(|text| !text.is_empty()) {
                    return Err(completed_input_error());
                }
                None
            } else {
                Some(
                    state
                        .pty
                        .clone()
                        .ok_or_else(|| ServerError::message("Shell PTY is unavailable"))?,
                )
            }
        };
        let Some(pty) = pty else {
            return Ok(format_session_result(
                session_id,
                session.poll(max_output).await,
            ));
        };

        if args.get("rows").is_some() || args.get("columns").is_some() {
            let rows = resize_dimension(args, "rows")?;
            let columns = resize_dimension(args, "columns")?;
            pty.resize(rows, columns).map_err(ServerError::message)?;
        }
        if let Some(chars) = chars {
            if let Err(error) = pty.write_all(chars.as_bytes()).await {
                return self
                    .handle_late_input(&session, session_id, max_output, error)
                    .await;
            }
        }
        if args.get("interrupt").and_then(Value::as_bool) == Some(true) {
            pty.interrupt().map_err(ServerError::message)?;
        }
        if args.get("close_stdin").and_then(Value::as_bool) == Some(true) {
            if let Err(error) = pty.write_all(b"\x04\x04").await {
                return self
                    .handle_late_input(&session, session_id, max_output, error)
                    .await;
            }
        }
        if args.get("terminate").and_then(Value::as_bool) == Some(true) {
            pty.terminate().map_err(ServerError::message)?;
            let result = wait_for_completion(&session, max_output, 2_000).await;
            return Ok(format_session_result(session_id, result));
        }
        let result = wait_for_output(&session, max_output, yield_ms).await;
        Ok(format_session_result(session_id, result))
    }

    async fn handle_late_input(
        &self,
        session: &Arc<Session>,
        session_id: Uuid,
        max_output: usize,
        error: String,
    ) -> Result<String, ServerError> {
        let result = wait_for_output(session, max_output, 100).await;
        if result.status != SessionStatus::Running {
            return Err(completed_input_error());
        }
        Err(ServerError::message(format!(
            "Cannot write to shell session {session_id}: {error}"
        )))
    }

    async fn gc_once(&self) {
        let now = Instant::now();
        let sessions: Vec<_> = self
            .sessions
            .lock()
            .await
            .iter()
            .map(|(id, session)| (*id, session.clone()))
            .collect();
        for (id, session) in sessions {
            let state = session.state.lock().await;
            if state.exit_code.is_some()
                && now.duration_since(state.last_active) >= SESSION_IDLE_TTL
            {
                drop(state);
                self.sessions.lock().await.remove(&id);
            }
        }
    }
}

impl Session {
    async fn append_output(&self, bytes: &[u8]) {
        let mut state = self.state.lock().await;
        state.output.extend_from_slice(bytes);
        if state.output.len() > MAX_CAPTURE_BYTES {
            let extra = state.output.len() - MAX_CAPTURE_BYTES;
            state.output.drain(..extra);
            state.output_start += extra as u64;
        }
        drop(state);
        self.changed.notify_waiters();
    }

    async fn finish(&self, code: i32) {
        let mut state = self.state.lock().await;
        if state.exit_code.is_none() {
            state.exit_code = Some(if state.timed_out { 124 } else { code });
        }
        state.pty = None;
        drop(state);
        self.changed.notify_waiters();
        self.activity.notify_one();
    }

    async fn poll(&self, max_output: usize) -> PollResult {
        let mut state = self.state.lock().await;
        let lost = state.cursor < state.output_start;
        let start = state.cursor.max(state.output_start);
        let offset = (start - state.output_start) as usize;
        let bytes = &state.output[offset..];
        let available = if state.exit_code.is_some() {
            bytes.len()
        } else {
            match std::str::from_utf8(bytes) {
                Ok(_) => bytes.len(),
                Err(error) if error.error_len().is_none() => error.valid_up_to(),
                Err(_) => bytes.len(),
            }
        };
        let text = String::from_utf8_lossy(&bytes[..available]);
        let (mut output, limited) = limit_output(&text, max_output);
        if lost {
            output.insert_str(0, "[earlier terminal output discarded]\n");
        }
        state.cursor = start + available as u64;
        state.last_active = Instant::now();
        let result = PollResult {
            status: state
                .exit_code
                .map(SessionStatus::Completed)
                .unwrap_or(SessionStatus::Running),
            output,
            truncated: lost || limited,
            timed_out: state.timed_out,
        };
        let running = result.status == SessionStatus::Running;
        drop(state);
        if running {
            self.activity.notify_one();
        }
        result
    }
}

async fn wait_for_output(session: &Arc<Session>, max_output: usize, yield_ms: u64) -> PollResult {
    let deadline = Instant::now() + Duration::from_millis(yield_ms);
    loop {
        // Register before polling so output arriving between the check and wait is not missed.
        let notification = session.changed.notified();
        tokio::pin!(notification);
        notification.as_mut().enable();
        let result = session.poll(max_output).await;
        if result.status != SessionStatus::Running
            || !result.output.is_empty()
            || Instant::now() >= deadline
        {
            return result;
        }
        if timeout_at(deadline, notification).await.is_err() {
            return session.poll(max_output).await;
        }
    }
}

async fn wait_for_completion(
    session: &Arc<Session>,
    max_output: usize,
    wait_ms: u64,
) -> PollResult {
    let deadline = Instant::now() + Duration::from_millis(wait_ms);
    loop {
        let notification = session.changed.notified();
        tokio::pin!(notification);
        notification.as_mut().enable();
        if session.state.lock().await.exit_code.is_some() || Instant::now() >= deadline {
            return session.poll(max_output).await;
        }
        if timeout_at(deadline, notification).await.is_err() {
            return session.poll(max_output).await;
        }
    }
}

async fn watch_idle(session: Arc<Session>) {
    loop {
        let notification = session.activity.notified();
        tokio::pin!(notification);
        notification.as_mut().enable();
        let deadline = {
            let mut state = session.state.lock().await;
            if state.exit_code.is_some() {
                return;
            }
            let deadline = state.last_active + state.timeout;
            if Instant::now() >= deadline {
                if let Some(pty) = state.pty.as_ref() {
                    match pty.terminate() {
                        Ok(true) => {
                            state.timed_out = true;
                        }
                        Ok(false) => {} // wait() will report the natural exit status.
                        Err(error) => eprintln!("Cannot time out shell command: {error}"),
                    }
                }
                return;
            }
            deadline
        };
        tokio::select! {
            _ = sleep_until(deadline) => {},
            _ = notification => {},
        }
    }
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
    if result.truncated {
        value["truncated"] = json!(true);
    }
    serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string())
}

fn exit_code(status: std::process::ExitStatus) -> i32 {
    if let Some(code) = status.code() {
        return code;
    }
    #[cfg(unix)]
    {
        use std::os::unix::process::ExitStatusExt;
        if let Some(signal) = status.signal() {
            return 128 + signal;
        }
    }
    1
}

fn limit_output(output: &str, max_chars: usize) -> (String, bool) {
    let chars: Vec<char> = output.chars().collect();
    if chars.len() <= max_chars {
        return (output.to_string(), false);
    }
    let head = max_chars / 2;
    let tail = max_chars - head;
    let mut result: String = chars[..head].iter().collect();
    result.push_str("\n[terminal output truncated]\n");
    result.extend(chars[chars.len() - tail..].iter());
    (result, true)
}

fn completed_input_error() -> ServerError {
    ServerError::message("Shell command has completed; use exec_command for a new command")
}

fn resolve_workdir(raw: Option<&str>) -> Result<PathBuf, ServerError> {
    let home = env::var_os("HOME").map(PathBuf::from).ok_or_else(|| {
        ServerError::message(
            "HOME is not set; start the MCP server from the host shell environment",
        )
    })?;
    let value = raw.unwrap_or("~");
    let path = if value == "~" {
        home
    } else if let Some(suffix) = value.strip_prefix("~/") {
        home.join(suffix)
    } else {
        PathBuf::from(value)
    };
    let canonical = path.canonicalize().map_err(|error| {
        ServerError::message(format!(
            "cannot resolve working directory {}: {error}",
            path.display()
        ))
    })?;
    if !canonical.is_dir() {
        return Err(ServerError::message(format!(
            "working directory is not a directory: {}",
            canonical.display()
        )));
    }
    Ok(canonical)
}

fn required_string(args: &Value, name: &str) -> Result<String, ServerError> {
    args.get(name)
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| ServerError::message(format!("{name} is required")))
}

fn required_uuid(args: &Value, name: &str) -> Result<Uuid, ServerError> {
    let value = required_string(args, name)?;
    Uuid::parse_str(&value).map_err(|_| ServerError::message(format!("{name} must be a UUID")))
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
        .clamp(1, max)
}

fn bounded_u16(args: &Value, name: &str, default: u16, min: u16, max: u16) -> u16 {
    args.get(name)
        .and_then(Value::as_u64)
        .unwrap_or(default as u64)
        .clamp(min as u64, max as u64) as u16
}

fn resize_dimension(args: &Value, name: &str) -> Result<u16, ServerError> {
    let value = args
        .get(name)
        .and_then(Value::as_u64)
        .ok_or_else(|| ServerError::message("rows and columns must be provided together"))?;
    if !(1..=500).contains(&value) {
        return Err(ServerError::message(
            "terminal rows and columns must be between 1 and 500",
        ));
    }
    Ok(value as u16)
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn completed_session(manager: &SessionManager, code: i32) -> Uuid {
        let id = Uuid::new_v4();
        manager.sessions.lock().await.insert(
            id,
            Arc::new(Session {
                state: Mutex::new(SessionState {
                    pty: None,
                    output: Vec::new(),
                    output_start: 0,
                    cursor: 0,
                    timeout: Duration::from_secs(10),
                    last_active: Instant::now(),
                    exit_code: Some(code),
                    timed_out: false,
                }),
                operation: Mutex::new(()),
                changed: Notify::new(),
                activity: Notify::new(),
            }),
        );
        id
    }

    #[tokio::test]
    async fn completed_controls_are_idempotent_and_new_input_is_rejected() {
        let manager = SessionManager::default();
        let id = completed_session(&manager, 137).await;
        let args = json!({"session_id": id.to_string(), "interrupt": true, "terminate": true, "chars": ""});
        let first = manager.write_stdin(&args).await.unwrap();
        assert_eq!(first, manager.write_stdin(&args).await.unwrap());
        assert_eq!(
            serde_json::from_str::<Value>(&first).unwrap()["exit_code"],
            137
        );
        assert_eq!(
            manager
                .write_stdin(&json!({"session_id": id.to_string(), "chars": "echo unsafe"}))
                .await
                .unwrap_err()
                .0,
            completed_input_error().0
        );
    }

    #[tokio::test]
    async fn completed_sessions_are_reaped_from_memory() {
        let manager = SessionManager::default();
        let id = completed_session(&manager, 0).await;
        let session = manager.sessions.lock().await.get(&id).unwrap().clone();
        session.state.lock().await.last_active =
            Instant::now() - SESSION_IDLE_TTL - Duration::from_secs(1);
        manager.gc_once().await;
        assert!(!manager.sessions.lock().await.contains_key(&id));
    }

    #[test]
    fn required_session_id_and_output_limit() {
        assert_eq!(
            required_uuid(&json!({}), "session_id").unwrap_err().0,
            "session_id is required"
        );
        let (output, truncated) = limit_output("abcdefghij", 4);
        assert!(truncated);
        assert!(output.starts_with("ab"));
        assert!(output.ends_with("ij"));
    }

    #[tokio::test]
    async fn split_utf8_output_is_not_consumed_early() {
        let session = Session {
            state: Mutex::new(SessionState {
                pty: None,
                output: Vec::new(),
                output_start: 0,
                cursor: 0,
                timeout: Duration::from_secs(10),
                last_active: Instant::now(),
                exit_code: None,
                timed_out: false,
            }),
            operation: Mutex::new(()),
            changed: Notify::new(),
            activity: Notify::new(),
        };
        let bytes = "你好".as_bytes();
        session.append_output(&bytes[..2]).await;
        assert_eq!(session.poll(DEFAULT_MAX_OUTPUT).await.output, "");
        session.append_output(&bytes[2..]).await;
        assert_eq!(session.poll(DEFAULT_MAX_OUTPUT).await.output, "你好");
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn pty_command_streams_and_accepts_input() {
        let manager = SessionManager::default();
        let started: Value = serde_json::from_str(
            &manager
                .execute_command(&json!({
                    "command": "printf ready; read answer; printf ' got:%s' \"$answer\"",
                    "yield_time_ms": 1000,
                }))
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(started["status"], "running");
        assert!(started["stdout"].as_str().unwrap().contains("ready"));
        let id = started["session_id"].as_str().unwrap();
        let finished: Value = serde_json::from_str(
            &manager
                .write_stdin(&json!({
                    "session_id": id,
                    "chars": "hello\n",
                    "yield_time_ms": 1000,
                }))
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(finished["status"], "completed");
        assert_eq!(finished["exit_code"], 0);
        assert!(finished["stdout"].as_str().unwrap().contains("got:hello"));
    }
}
