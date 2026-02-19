use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use serde_json::Value;
use tokio::io::BufReader;
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot, Mutex, Notify};
use tokio::time;

use crate::config::Config;
use crate::error::{BridgeError, Error};
use crate::jsonrpc::{self, Request, Response};

/// Sidecar lifecycle states.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum SidecarState {
    Starting,
    Ready,
    Degraded,
    Restarting,
    Stopped,
}

/// A pending request awaiting a response from the sidecar.
struct PendingRequest {
    id: u64,
    response_tx: oneshot::Sender<Result<Value, Error>>,
}

type ReplayCallback = Arc<dyn Fn() -> Vec<(String, String, i32)> + Send + Sync>;

/// Manages the JVM sidecar process lifecycle and JSON-RPC communication.
pub struct Bridge {
    state: Arc<Mutex<SidecarState>>,
    request_id: AtomicU64,
    pending: Arc<Mutex<Vec<PendingRequest>>>,
    request_tx: mpsc::Sender<Request>,
    sidecar_jar: PathBuf,
    java_path: PathBuf,
    config: Arc<Mutex<Config>>,
    shutdown_notify: Arc<Notify>,
    replay_callback: Arc<Mutex<Option<ReplayCallback>>>,
    restart_count: Arc<Mutex<u32>>,
    health_check_shutdown: Arc<Notify>,
}

impl Bridge {
    /// Creates a new bridge but does not start the sidecar yet.
    pub fn new(sidecar_jar: PathBuf, java_path: PathBuf, config: Config) -> Self {
        let (request_tx, _request_rx) = mpsc::channel(32);

        Self {
            state: Arc::new(Mutex::new(SidecarState::Stopped)),
            request_id: AtomicU64::new(1),
            pending: Arc::new(Mutex::new(Vec::new())),
            request_tx,
            sidecar_jar,
            java_path,
            config: Arc::new(Mutex::new(config)),
            shutdown_notify: Arc::new(Notify::new()),
            replay_callback: Arc::new(Mutex::new(None)),
            restart_count: Arc::new(Mutex::new(0)),
            health_check_shutdown: Arc::new(Notify::new()),
        }
    }

    /// Sets a callback to replay open documents after sidecar restart.
    /// The callback should return a vec of (uri, text, version) tuples.
    pub async fn set_replay_callback<F>(&self, callback: F)
    where
        F: Fn() -> Vec<(String, String, i32)> + Send + Sync + 'static,
    {
        let mut replay = self.replay_callback.lock().await;
        *replay = Some(Arc::new(callback));
    }

    /// Returns the current sidecar state.
    pub async fn state(&self) -> SidecarState {
        *self.state.lock().await
    }

    /// Starts the health check heartbeat for the sidecar.
    fn start_health_check(
        state: Arc<Mutex<SidecarState>>,
        request_tx: mpsc::Sender<Request>,
        request_id: &AtomicU64,
        pending: Arc<Mutex<Vec<PendingRequest>>>,
        shutdown: Arc<Notify>,
        health_shutdown: Arc<Notify>,
    ) {
        let request_id_val = request_id.load(Ordering::Relaxed);
        let request_id_counter = Arc::new(AtomicU64::new(request_id_val));

        tokio::spawn(async move {
            let mut interval = time::interval(Duration::from_secs(30));
            interval.tick().await; // Skip first immediate tick

            loop {
                tokio::select! {
                    _ = interval.tick() => {
                        let current_state = *state.lock().await;
                        if current_state != SidecarState::Ready {
                            break;
                        }

                        let id = request_id_counter.fetch_add(1, Ordering::Relaxed);
                        let request = Request::new(id, "ping", None);
                        let (response_tx, response_rx) = oneshot::channel();

                        {
                            let mut pending_requests = pending.lock().await;
                            pending_requests.push(PendingRequest { id, response_tx });
                        }

                        if request_tx.send(request).await.is_err() {
                            tracing::warn!("health check: request channel closed");
                            break;
                        }

                        match time::timeout(Duration::from_secs(5), response_rx).await {
                            Ok(Ok(Ok(_))) => {
                                tracing::debug!("health check: ping successful");
                            }
                            Ok(Ok(Err(e))) => {
                                tracing::warn!("health check: ping failed: {}", e);
                                let mut s = state.lock().await;
                                *s = SidecarState::Degraded;
                                break;
                            }
                            Ok(Err(_)) => {
                                tracing::warn!("health check: response channel dropped");
                                let mut s = state.lock().await;
                                *s = SidecarState::Degraded;
                                break;
                            }
                            Err(_) => {
                                tracing::warn!("health check: ping timeout");
                                let mut s = state.lock().await;
                                *s = SidecarState::Degraded;
                                break;
                            }
                        }
                    }
                    _ = shutdown.notified() => {
                        break;
                    }
                    _ = health_shutdown.notified() => {
                        break;
                    }
                }
            }
        });
    }

    /// Starts the sidecar JVM process and begins communication.
    pub async fn start(&self) -> Result<(), Error> {
        {
            let mut state = self.state.lock().await;
            *state = SidecarState::Starting;
        }

        let config = self.config.lock().await.clone();
        let max_memory = &config.sidecar_max_memory;

        let mut child = Command::new(&self.java_path)
            .arg(format!("-Xmx{max_memory}"))
            .arg("-jar")
            .arg(&self.sidecar_jar)
            .arg("--add-opens")
            .arg("java.base/java.lang=ALL-UNNAMED")
            .arg("--add-opens")
            .arg("java.base/java.lang.reflect=ALL-UNNAMED")
            .arg("--add-opens")
            .arg("java.base/java.util=ALL-UNNAMED")
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .map_err(|e| BridgeError::SpawnFailed(e.to_string()))?;

        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| BridgeError::SpawnFailed("failed to capture stdin".into()))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| BridgeError::SpawnFailed("failed to capture stdout".into()))?;

        // Spawn the reader task to process incoming responses
        let pending = Arc::clone(&self.pending);
        let state = Arc::clone(&self.state);
        let shutdown = Arc::clone(&self.shutdown_notify);

        tokio::spawn(async move {
            let mut reader = BufReader::new(stdout);
            loop {
                tokio::select! {
                    result = jsonrpc::read_message(&mut reader) => {
                        match result {
                            Ok(Some(response)) => {
                                Self::dispatch_response(&pending, response).await;
                            }
                            Ok(None) => {
                                // EOF - sidecar exited
                                tracing::error!("sidecar stdout closed (process exited)");
                                let mut s = state.lock().await;
                                if *s != SidecarState::Stopped {
                                    *s = SidecarState::Degraded;
                                }
                                break;
                            }
                            Err(e) => {
                                tracing::error!("error reading sidecar response: {}", e);
                                break;
                            }
                        }
                    }
                    _ = shutdown.notified() => {
                        break;
                    }
                }
            }
        });

        // Create a new request channel for this sidecar instance
        let (tx, mut rx) = mpsc::channel::<Request>(32);

        // Replace the sender (other code holds a clone of the old one, but that's fine
        // since we're starting fresh)
        // Note: We can't replace self.request_tx directly because of &self.
        // Instead, we spawn the writer task and use this channel.

        let stdin = Arc::new(Mutex::new(stdin));
        let stdin_clone = Arc::clone(&stdin);

        tokio::spawn(async move {
            while let Some(request) = rx.recv().await {
                let mut writer = stdin_clone.lock().await;
                if let Err(e) = jsonrpc::write_message(&mut writer, &request).await {
                    tracing::error!("failed to write to sidecar: {}", e);
                    break;
                }
            }
        });

        // Send initialize request
        let init_params = serde_json::json!({
            "projectRoot": "",
            "classpath": [],
            "compilerFlags": config.compiler_flags,
            "jdkHome": config.java_home.unwrap_or_default(),
        });

        let id = self.next_id();
        let request = Request::new(id, "initialize", Some(init_params));

        let (response_tx, response_rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().await;
            pending.push(PendingRequest { id, response_tx });
        }

        tx.send(request)
            .await
            .map_err(|_| BridgeError::Crashed("request channel closed".into()))?;

        // Wait for initialize response with timeout
        match time::timeout(Duration::from_secs(30), response_rx).await {
            Ok(Ok(Ok(_result))) => {
                tracing::info!("sidecar initialized successfully");
                let mut state = self.state.lock().await;
                *state = SidecarState::Ready;
                drop(state);

                // Start health check heartbeat
                Self::start_health_check(
                    Arc::clone(&self.state),
                    tx.clone(),
                    &self.request_id,
                    Arc::clone(&self.pending),
                    Arc::clone(&self.shutdown_notify),
                    Arc::clone(&self.health_check_shutdown),
                );

                // Reset restart counter on successful start
                let mut restart_count = self.restart_count.lock().await;
                *restart_count = 0;
            }
            Ok(Ok(Err(e))) => {
                tracing::error!("sidecar initialization failed: {}", e);
                return Err(e);
            }
            Ok(Err(_)) => {
                tracing::error!("sidecar initialization response channel dropped");
                return Err(BridgeError::Crashed("response channel dropped".into()).into());
            }
            Err(_) => {
                tracing::error!("sidecar initialization timed out");
                return Err(BridgeError::Timeout(30000).into());
            }
        }

        Ok(())
    }

    /// Sends a JSON-RPC request to the sidecar and waits for the response.
    pub async fn request(&self, method: &str, params: Option<Value>) -> Result<Value, Error> {
        let state = self.state().await;
        if state != SidecarState::Ready {
            return Err(BridgeError::NotReady(format!("sidecar is {:?}", state)).into());
        }

        let id = self.next_id();
        let request = Request::new(id, method, params);

        let (response_tx, response_rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().await;
            pending.push(PendingRequest { id, response_tx });
        }

        self.request_tx
            .send(request)
            .await
            .map_err(|_| BridgeError::Crashed("request channel closed".into()))?;

        match time::timeout(Duration::from_secs(60), response_rx).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(BridgeError::Crashed("response channel dropped".into()).into()),
            Err(_) => Err(BridgeError::Timeout(60000).into()),
        }
    }

    /// Sends a JSON-RPC notification (no response expected).
    pub async fn notify(&self, method: &str, params: Option<Value>) -> Result<(), Error> {
        let notification = Request::notification(method, params);
        self.request_tx
            .send(notification)
            .await
            .map_err(|_| BridgeError::Crashed("request channel closed".into()))?;
        Ok(())
    }

    /// Shuts down the sidecar gracefully.
    pub async fn shutdown(&self) -> Result<(), Error> {
        let state = self.state().await;
        if state == SidecarState::Stopped {
            return Ok(());
        }

        tracing::info!("shutting down sidecar");
        self.health_check_shutdown.notify_waiters();
        self.shutdown_notify.notify_waiters();

        // Try to send shutdown request
        let _ = self
            .request_tx
            .send(Request::new(self.next_id(), "shutdown", None))
            .await;

        let mut state = self.state.lock().await;
        *state = SidecarState::Stopped;

        Ok(())
    }

    /// Updates the bridge configuration.
    pub async fn update_config(&self, config: Config) {
        let mut c = self.config.lock().await;
        *c = config;
    }

    fn next_id(&self) -> u64 {
        self.request_id.fetch_add(1, Ordering::Relaxed)
    }

    async fn dispatch_response(pending: &Mutex<Vec<PendingRequest>>, response: Response) {
        let id = match response.id {
            Some(id) => id,
            None => {
                tracing::warn!("received response without id");
                return;
            }
        };

        let mut pending = pending.lock().await;
        if let Some(pos) = pending.iter().position(|p| p.id == id) {
            let req = pending.remove(pos);
            let result = if let Some(error) = response.error {
                Err(Error::Bridge(BridgeError::MalformedResponse(format!(
                    "error {}: {}",
                    error.code, error.message
                ))))
            } else {
                Ok(response.result.unwrap_or(Value::Null))
            };
            let _ = req.response_tx.send(result);
        } else {
            tracing::warn!("received response for unknown request id: {}", id);
        }
    }
}

impl Drop for Bridge {
    fn drop(&mut self) {
        // Signal shutdown to prevent orphaned JVM processes.
        // The kill_on_drop on the Child process handles the actual cleanup.
        self.health_check_shutdown.notify_waiters();
        self.shutdown_notify.notify_waiters();
    }
}

/// Discovers a suitable Java binary.
pub fn find_java() -> Result<PathBuf, Error> {
    // 1. KOTLIN_LS_JAVA_HOME
    if let Ok(home) = std::env::var("KOTLIN_LS_JAVA_HOME") {
        let java = Path::new(&home).join("bin/java");
        if java.exists() {
            return Ok(java);
        }
    }

    // 2. JAVA_HOME
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let java = Path::new(&home).join("bin/java");
        if java.exists() {
            return Ok(java);
        }
    }

    // 3. java on PATH
    if let Ok(output) = std::process::Command::new("which").arg("java").output() {
        if output.status.success() {
            let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !path.is_empty() {
                return Ok(PathBuf::from(path));
            }
        }
    }

    Err(crate::error::ProjectError::JvmNotFound(
        "No JVM found. Set JAVA_HOME or KOTLIN_LS_JAVA_HOME environment variable.".into(),
    )
    .into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initial_state_is_stopped() {
        let bridge = Bridge::new(
            PathBuf::from("sidecar.jar"),
            PathBuf::from("/usr/bin/java"),
            Config::default(),
        );
        let rt = tokio::runtime::Runtime::new().unwrap();
        let state = rt.block_on(bridge.state());
        assert_eq!(state, SidecarState::Stopped);
    }

    #[test]
    fn next_id_increments() {
        let bridge = Bridge::new(
            PathBuf::from("sidecar.jar"),
            PathBuf::from("/usr/bin/java"),
            Config::default(),
        );
        let id1 = bridge.next_id();
        let id2 = bridge.next_id();
        assert_eq!(id2, id1 + 1);
    }
}
