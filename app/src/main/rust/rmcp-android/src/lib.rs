use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine;
use http::header::{HeaderName, HeaderValue};
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jlong, jstring};
use jni::{JNIEnv, JavaVM};
use once_cell::sync::Lazy;
use rmcp::model::{
    CallToolRequestParams, ClientCapabilities, ClientConfig, Implementation, ProtocolVersion,
};
use rmcp::service::RunningService;
use rmcp::transport::StreamableHttpClientTransport;
use rmcp::transport::streamable_http_client::StreamableHttpClientTransportConfig;
use rmcp::{ClientLifecycleMode, ClientServiceExt, RoleClient};
use serde_json::{Map, Value};
use tokio::runtime::{Builder, Runtime};
use tokio::sync::oneshot;

type Client = RunningService<RoleClient, ClientConfig>;
static RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("RMCP runtime")
});
static CLIENTS: Lazy<Mutex<HashMap<i64, Arc<Client>>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static CALLS: Lazy<Mutex<HashMap<i64, oneshot::Sender<()>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));
static NEXT_ID: AtomicI64 = AtomicI64::new(1);
static NEXT_CALL_ID: AtomicI64 = AtomicI64::new(1);

fn string(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|s| s.into())
        .map_err(|error| error.to_string())
}

fn fail(env: &mut JNIEnv, error: impl std::fmt::Display) {
    let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
}

fn catch_panic<T>(work: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    catch_unwind(AssertUnwindSafe(work)).map_err(|panic| {
        panic
            .downcast_ref::<String>()
            .cloned()
            .or_else(|| panic.downcast_ref::<&str>().map(|value| value.to_string()))
            .unwrap_or_else(|| "RMCP native panic".to_string())
    })?
}

fn client(id: jlong) -> Result<Arc<Client>, String> {
    CLIENTS
        .lock()
        .map_err(|e| e.to_string())?
        .get(&id)
        .cloned()
        .ok_or_else(|| format!("MCP client {id} is closed"))
}

fn new_java_string(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            fail(env, e);
            std::ptr::null_mut()
        }
    }
}

fn notify_closed(vm: &JavaVM, callback: &GlobalRef) {
    let Ok(mut env) = vm.attach_current_thread() else {
        return;
    };
    if let Err(error) = env.call_method(callback.as_obj(), "onClosed", "()V", &[]) {
        eprintln!("Cannot notify MCP connection closure: {error}");
        let _ = env.exception_clear();
    }
}

fn notify_call(vm: &JavaVM, callback: &GlobalRef, outcome: Result<String, String>) {
    let Ok(mut env) = vm.attach_current_thread() else {
        return;
    };
    let (result, error) = match outcome {
        Ok(result) => (Some(result), None),
        Err(error) => (None, Some(error)),
    };
    let result = result
        .and_then(|value| env.new_string(value).ok())
        .map(JObject::from)
        .unwrap_or(JObject::null());
    let error = error
        .and_then(|value| env.new_string(value).ok())
        .map(JObject::from)
        .unwrap_or(JObject::null());
    if let Err(failure) = env.call_method(
        callback.as_obj(),
        "onComplete",
        "(Ljava/lang/String;Ljava/lang/String;)V",
        &[JValue::Object(&result), JValue::Object(&error)],
    ) {
        eprintln!("Cannot notify MCP tool completion: {failure}");
        let _ = env.exception_clear();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_me_rerere_rikkahub_data_ai_mcp_RmcpNative_connect(
    mut env: JNIEnv,
    _: JClass,
    url: JString,
    name: JString,
    headers_json: JString,
    certs_json: JString,
    on_closed: JObject,
) -> jlong {
    let result = catch_panic(|| -> Result<i64, String> {
        let vm = env.get_java_vm().map_err(|e| e.to_string())?;
        let on_closed = env.new_global_ref(on_closed).map_err(|e| e.to_string())?;
        let url = string(&mut env, url)?;
        let name = string(&mut env, name)?;
        let headers_json = string(&mut env, headers_json)?;
        let certs_json = string(&mut env, certs_json)?;
        let pairs: Vec<(String, String)> =
            serde_json::from_str(&headers_json).map_err(|e| e.to_string())?;
        let mut headers = HashMap::new();
        for (key, value) in pairs {
            headers.insert(
                HeaderName::try_from(key).map_err(|e| e.to_string())?,
                HeaderValue::try_from(value).map_err(|e| e.to_string())?,
            );
        }
        let encoded_certs: Vec<String> =
            serde_json::from_str(&certs_json).map_err(|e| e.to_string())?;
        let certs = encoded_certs
            .into_iter()
            .map(|encoded| {
                let der = base64::engine::general_purpose::STANDARD
                    .decode(encoded)
                    .map_err(|e| e.to_string())?;
                reqwest::Certificate::from_der(&der).map_err(|e| e.to_string())
            })
            .collect::<Result<Vec<_>, _>>()?;
        let http_client = reqwest::Client::builder()
            .tls_certs_only(certs)
            .connect_timeout(Duration::from_secs(20))
            .read_timeout(Duration::from_secs(600))
            .build()
            .map_err(|e| e.to_string())?;
        let config = ClientConfig::new(
            ClientCapabilities::default(),
            Implementation::new(name, "1.0"),
        )
        .with_protocol_version(ProtocolVersion::LATEST_WITH_INITIALIZE);
        let (running, transport_closed) = RUNTIME.block_on(async {
            // WorkerTransport::spawn requires an active Tokio runtime.
            let transport = StreamableHttpClientTransport::with_client(
                http_client,
                StreamableHttpClientTransportConfig::with_uri(url)
                    .custom_headers(headers)
                    .reinit_on_expired_session(true),
            );
            let transport_closed = transport.cancel_token();
            let running = tokio::time::timeout(
                Duration::from_secs(35),
                config.serve_with_lifecycle(
                    transport,
                    ClientLifecycleMode::Auto {
                        preferred_versions: vec![ProtocolVersion::V_2026_07_28],
                        legacy_version: Some(ProtocolVersion::LATEST_WITH_INITIALIZE),
                    },
                ),
            )
            .await;
            (running, transport_closed)
        });
        let running = running
            .map_err(|e| e.to_string())?
            .map_err(|e| e.to_string())?;
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        CLIENTS
            .lock()
            .map_err(|e| e.to_string())?
            .insert(id, Arc::new(running));
        RUNTIME.spawn(async move {
            transport_closed.cancelled().await;
            let still_registered = CLIENTS
                .lock()
                .is_ok_and(|clients| clients.contains_key(&id));
            if still_registered {
                notify_closed(&vm, &on_closed);
            }
        });
        Ok(id)
    });
    match result {
        Ok(id) => id,
        Err(e) => {
            fail(&mut env, e);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_me_rerere_rikkahub_data_ai_mcp_RmcpNative_listTools(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
) -> jstring {
    let result = catch_panic(|| -> Result<String, String> {
        let client = client(id)?;
        let tools = RUNTIME
            .block_on(async {
                tokio::time::timeout(Duration::from_secs(30), client.list_all_tools()).await
            })
            .map_err(|e| e.to_string())?
            .map_err(|e| e.to_string())?;
        serde_json::to_string(&tools).map_err(|e| e.to_string())
    });
    match result {
        Ok(json) => new_java_string(&mut env, json),
        Err(e) => {
            fail(&mut env, e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_me_rerere_rikkahub_data_ai_mcp_RmcpNative_protocolVersion(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
) -> jstring {
    match client(id) {
        Ok(client) => {
            let version = client
                .peer_info()
                .map(|info| info.protocol_version.to_string())
                .unwrap_or_else(|| "unknown".to_string());
            new_java_string(&mut env, version)
        }
        Err(e) => {
            fail(&mut env, e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_me_rerere_rikkahub_data_ai_mcp_RmcpNative_beginCall(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    name: JString,
    arguments_json: JString,
    on_complete: JObject,
) -> jlong {
    let result = catch_panic(|| -> Result<i64, String> {
        let vm = env.get_java_vm().map_err(|e| e.to_string())?;
        let on_complete = env.new_global_ref(on_complete).map_err(|e| e.to_string())?;
        let client = client(id)?;
        let name = string(&mut env, name)?;
        let arguments_json = string(&mut env, arguments_json)?;
        let args: Map<String, Value> =
            serde_json::from_str(&arguments_json).map_err(|e| e.to_string())?;
        let params = CallToolRequestParams::new(name).with_arguments(args);
        let call_id = NEXT_CALL_ID.fetch_add(1, Ordering::Relaxed);
        let (cancel, cancelled) = oneshot::channel();
        CALLS
            .lock()
            .map_err(|e| e.to_string())?
            .insert(call_id, cancel);
        RUNTIME.spawn(async move {
            let outcome = tokio::select! {
                _ = cancelled => return,
                outcome = tokio::time::timeout(Duration::from_secs(120), client.call_tool(params)) => outcome,
            };
            let outcome = outcome
                .map_err(|e| e.to_string())
                .and_then(|value| value.map_err(|e| e.to_string()))
                .and_then(|value| serde_json::to_string(&value).map_err(|e| e.to_string()));
            let pending = CALLS.lock().is_ok_and(|mut calls| calls.remove(&call_id).is_some());
            if pending {
                notify_call(&vm, &on_complete, outcome);
            }
        });
        Ok(call_id)
    });
    match result {
        Ok(id) => id,
        Err(e) => {
            fail(&mut env, e);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_me_rerere_rikkahub_data_ai_mcp_RmcpNative_cancelCall(
    _: JNIEnv,
    _: JClass,
    call_id: jlong,
) {
    if let Ok(mut calls) = CALLS.lock() {
        if let Some(cancel) = calls.remove(&call_id) {
            let _ = cancel.send(());
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_me_rerere_rikkahub_data_ai_mcp_RmcpNative_close(
    _: JNIEnv,
    _: JClass,
    id: jlong,
) {
    if let Ok(mut clients) = CLIENTS.lock() {
        if let Some(client) = clients.remove(&id) {
            client.cancellation_token().cancel();
        }
    }
}

#[cfg(test)]
mod tests {
    use rmcp::model::ProtocolVersion;

    #[test]
    fn pinned_sdk_exposes_all_expected_protocol_revisions() {
        let versions: Vec<_> = ProtocolVersion::KNOWN_VERSIONS
            .iter()
            .map(ProtocolVersion::as_str)
            .collect();
        assert_eq!(
            versions,
            [
                "2024-11-05",
                "2025-03-26",
                "2025-06-18",
                "2025-11-25",
                "2026-07-28",
            ]
        );
        assert_eq!(
            ProtocolVersion::LATEST_WITH_INITIALIZE.as_str(),
            "2025-11-25"
        );
        assert_eq!(ProtocolVersion::LATEST.as_str(), "2026-07-28");
    }
}
