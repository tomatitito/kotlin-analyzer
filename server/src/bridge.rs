use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use serde_json::Value;
use tokio::io::BufReader;
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot, watch, Mutex, Notify};
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
    /// Watch channel for state transitions. Allows `request()` to wait for Ready.
    state_watch_tx: Arc<watch::Sender<SidecarState>>,
    state_watch_rx: watch::Receiver<SidecarState>,
    request_id: AtomicU64,
    pending: Arc<Mutex<Vec<PendingRequest>>>,
    request_tx: Mutex<mpsc::Sender<Request>>,
    sidecar_jar: PathBuf,
    java_path: PathBuf,
    config: Arc<Mutex<Config>>,
    shutdown_notify: Arc<Notify>,
    replay_callback: Arc<Mutex<Option<ReplayCallback>>>,
    restart_count: Arc<Mutex<u32>>,
    health_check_shutdown: Arc<Notify>,
    /// Holds the sidecar child process to prevent kill_on_drop from firing.
    child: Mutex<Option<tokio::process::Child>>,
}

impl Bridge {
    /// Creates a new bridge but does not start the sidecar yet.
    pub fn new(sidecar_jar: PathBuf, java_path: PathBuf, config: Config) -> Self {
        tracing::info!("Bridge::new called with sidecar_jar: {:?}, java_path: {:?}", sidecar_jar, java_path);
        let (request_tx, _request_rx) = mpsc::channel(32);
        let (state_watch_tx, state_watch_rx) = watch::channel(SidecarState::Stopped);

        Self {
            state: Arc::new(Mutex::new(SidecarState::Stopped)),
            state_watch_tx: Arc::new(state_watch_tx),
            state_watch_rx,
            request_id: AtomicU64::new(1),
            pending: Arc::new(Mutex::new(Vec::new())),
            request_tx: Mutex::new(request_tx),
            sidecar_jar,
            java_path,
            config: Arc::new(Mutex::new(config)),
            shutdown_notify: Arc::new(Notify::new()),
            replay_callback: Arc::new(Mutex::new(None)),
            restart_count: Arc::new(Mutex::new(0)),
            health_check_shutdown: Arc::new(Notify::new()),
            child: Mutex::new(None),
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

    /// Updates the state and notifies all watchers (request buffering).
    async fn set_state(state: &Mutex<SidecarState>, watch_tx: &watch::Sender<SidecarState>, new_state: SidecarState) {
        let mut s = state.lock().await;
        *s = new_state;
        let _ = watch_tx.send(new_state);
    }

    /// Starts the health check heartbeat for the sidecar.
    fn start_health_check(
        state: Arc<Mutex<SidecarState>>,
        state_watch_tx: Arc<watch::Sender<SidecarState>>,
        request_tx: mpsc::Sender<Request>,
        request_id: &AtomicU64,
        pending: Arc<Mutex<Vec<PendingRequest>>>,
        shutdown: Arc<Notify>,
        health_shutdown: Arc<Notify>,
    ) {
        let request_id_val = request_id.load(Ordering::Relaxed);
        let request_id_counter = Arc::new(AtomicU64::new(request_id_val));

        tokio::spawn(async move {
            // Wait 60s before first health check to allow initial analysis to complete.
            // The first analysis with many source files can be slow as the FIR tree
            // is built for all modules.
            time::sleep(Duration::from_secs(60)).await;

            let mut interval = time::interval(Duration::from_secs(30));
            interval.tick().await; // Skip first immediate tick

            let mut consecutive_failures: u32 = 0;
            const MAX_FAILURES: u32 = 3;

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
                            Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                            break;
                        }

                        // Use a generous timeout - the sidecar may be busy analyzing
                        match time::timeout(Duration::from_secs(30), response_rx).await {
                            Ok(Ok(Ok(_))) => {
                                tracing::debug!("health check: ping successful");
                                consecutive_failures = 0;
                            }
                            Ok(Ok(Err(e))) => {
                                consecutive_failures += 1;
                                tracing::warn!("health check: ping failed ({}/{}): {}", consecutive_failures, MAX_FAILURES, e);
                                if consecutive_failures >= MAX_FAILURES {
                                    Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                                    break;
                                }
                            }
                            Ok(Err(_)) => {
                                tracing::warn!("health check: response channel dropped");
                                Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                                break;
                            }
                            Err(_) => {
                                consecutive_failures += 1;
                                tracing::warn!("health check: ping timeout ({}/{})", consecutive_failures, MAX_FAILURES);
                                if consecutive_failures >= MAX_FAILURES {
                                    Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                                    break;
                                }
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

    /// Cancel all pending requests with an error (used on sidecar crash).
    async fn cancel_all_pending(pending: &Mutex<Vec<PendingRequest>>, reason: &str) {
        let mut reqs = pending.lock().await;
        let count = reqs.len();
        if count > 0 {
            tracing::warn!("cancelling {} pending request(s): {}", count, reason);
        }
        for req in reqs.drain(..) {
            let _ = req.response_tx.send(Err(
                Error::Bridge(BridgeError::Crashed(reason.to_string()))
            ));
        }
    }

    /// Starts the sidecar JVM process and begins communication.
    /// The optional project_root, classpath, and source_roots are forwarded
    /// to the sidecar's `initialize` request so the Analysis API session
    /// is configured with actual project data.
    pub async fn start(
        &self,
        project_root: Option<&str>,
        classpath: &[String],
        source_roots: &[String],
    ) -> Result<(), Error> {
        {
            Self::set_state(&self.state, &self.state_watch_tx, SidecarState::Starting).await;
            tracing::info!("Sidecar state changed to Starting");
        }

        let config = self.config.lock().await.clone();
        let max_memory = &config.sidecar_max_memory;

        let mut child = Command::new(&self.java_path)
            .arg(format!("-Xmx{max_memory}"))
            .arg("--add-opens")
            .arg("java.base/java.lang=ALL-UNNAMED")
            .arg("--add-opens")
            .arg("java.base/java.lang.reflect=ALL-UNNAMED")
            .arg("--add-opens")
            .arg("java.base/java.util=ALL-UNNAMED")
            .arg("-jar")
            .arg(&self.sidecar_jar)
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
        let stderr = child
            .stderr
            .take();

        // Store the child process handle to prevent kill_on_drop from firing
        {
            let mut child_slot = self.child.lock().await;
            *child_slot = Some(child);
        }

        // Forward sidecar stderr to our tracing output
        if let Some(stderr) = stderr {
            tokio::spawn(async move {
                use tokio::io::AsyncBufReadExt;
                let reader = BufReader::new(stderr);
                let mut lines = reader.lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    tracing::info!(target: "sidecar", "{}", line);
                }
            });
        }

        // Spawn the reader task to process incoming responses
        let pending = Arc::clone(&self.pending);
        let state = Arc::clone(&self.state);
        let state_watch_tx = Arc::clone(&self.state_watch_tx);
        let shutdown = Arc::clone(&self.shutdown_notify);

        tokio::spawn(async move {
            let mut reader = BufReader::new(stdout);
            tracing::info!("Sidecar reader task started, waiting for messages from sidecar...");
            loop {
                tokio::select! {
                    result = jsonrpc::read_message(&mut reader) => {
                        match result {
                            Ok(Some(response)) => {
                                tracing::debug!("Read message from sidecar");
                                Self::dispatch_response(&pending, response).await;
                            }
                            Ok(None) => {
                                // EOF - sidecar exited. Cancel all pending requests immediately.
                                tracing::error!("sidecar stdout closed (process exited)");
                                Self::cancel_all_pending(&pending, "sidecar process exited").await;
                                let current = *state.lock().await;
                                if current != SidecarState::Stopped {
                                    Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                                }
                                break;
                            }
                            Err(e) => {
                                tracing::error!("error reading sidecar response: {}", e);
                                Self::cancel_all_pending(&pending, &format!("read error: {}", e)).await;
                                break;
                            }
                        }
                    }
                    _ = shutdown.notified() => {
                        tracing::info!("Sidecar reader task shutting down");
                        break;
                    }
                }
            }
        });

        // Create a new request channel for this sidecar instance
        let (tx, mut rx) = mpsc::channel::<Request>(32);

        // Swap in the live sender so request()/notify()/shutdown() use it
        {
            let mut current_tx = self.request_tx.lock().await;
            *current_tx = tx.clone();
        }

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

        // Send initialize request with project configuration
        let init_params = serde_json::json!({
            "projectRoot": project_root.unwrap_or(""),
            "classpath": classpath,
            "compilerFlags": config.compiler_flags,
            "jdkHome": config.java_home.unwrap_or_default(),
            "sourceRoots": source_roots,
        });

        let id = self.next_id();
        let request = Request::new(id, "initialize", Some(init_params.clone()));
        tracing::info!("Sending initialize request to sidecar with id {} and params: {:?}", id, init_params);

        let (response_tx, response_rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().await;
            pending.push(PendingRequest { id, response_tx });
            tracing::debug!("Added pending request with id {}", id);
        }

        tx.send(request)
            .await
            .map_err(|_| BridgeError::Crashed("request channel closed".into()))?;
        tracing::info!("Initialize request sent to sidecar, waiting for response...");

        // Wait for initialize response with timeout
        match time::timeout(Duration::from_secs(30), response_rx).await {
            Ok(Ok(Ok(result))) => {
                tracing::info!("sidecar initialized successfully with result: {:?}", result);
                Self::set_state(&self.state, &self.state_watch_tx, SidecarState::Ready).await;
                tracing::info!("Sidecar state changed to Ready");

                // Start health check heartbeat
                Self::start_health_check(
                    Arc::clone(&self.state),
                    Arc::clone(&self.state_watch_tx),
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
                Self::set_state(&self.state, &self.state_watch_tx, SidecarState::Stopped).await;
                return Err(e);
            }
            Ok(Err(_)) => {
                tracing::error!("sidecar initialization response channel dropped");
                Self::set_state(&self.state, &self.state_watch_tx, SidecarState::Stopped).await;
                return Err(BridgeError::Crashed("response channel dropped".into()).into());
            }
            Err(_) => {
                tracing::error!("sidecar initialization timed out");
                Self::set_state(&self.state, &self.state_watch_tx, SidecarState::Stopped).await;
                return Err(BridgeError::Timeout(30000).into());
            }
        }

        Ok(())
    }

    /// Waits for the sidecar to reach the `Ready` state.
    /// Returns immediately if already Ready. Returns an error if the state
    /// transitions to Stopped or Degraded, or if the timeout expires.
    async fn wait_for_ready(&self, timeout: Duration) -> Result<(), Error> {
        let mut rx = self.state_watch_rx.clone();

        // Check current state first
        let current = *rx.borrow();
        match current {
            SidecarState::Ready => return Ok(()),
            SidecarState::Stopped => {
                return Err(BridgeError::NotReady("sidecar is Stopped".into()).into());
            }
            SidecarState::Degraded => {
                return Err(BridgeError::NotReady("sidecar is Degraded".into()).into());
            }
            SidecarState::Starting | SidecarState::Restarting => {
                tracing::info!("waiting for sidecar to become Ready (current: {:?})", current);
            }
        }

        // Wait for state change with timeout
        match time::timeout(timeout, async {
            loop {
                if rx.changed().await.is_err() {
                    return Err(BridgeError::Crashed("state watch channel closed".into()).into());
                }
                let state = *rx.borrow();
                match state {
                    SidecarState::Ready => return Ok(()),
                    SidecarState::Stopped | SidecarState::Degraded => {
                        return Err(BridgeError::NotReady(
                            format!("sidecar transitioned to {:?} while waiting", state),
                        ).into());
                    }
                    SidecarState::Starting | SidecarState::Restarting => {
                        // Keep waiting
                        continue;
                    }
                }
            }
        })
        .await
        {
            Ok(result) => result,
            Err(_) => {
                tracing::warn!("timed out waiting for sidecar to become Ready");
                Err(BridgeError::Timeout(timeout.as_millis() as u64).into())
            }
        }
    }

    /// Sends a JSON-RPC request to the sidecar and waits for the response.
    /// If the sidecar is still starting, waits up to 30 seconds for it to
    /// become Ready before sending the request.
    pub async fn request(&self, method: &str, params: Option<Value>) -> Result<Value, Error> {
        // Wait for sidecar to be ready (buffers during startup)
        self.wait_for_ready(Duration::from_secs(30)).await?;

        tracing::debug!("Sending request '{}' to sidecar", method);

        let id = self.next_id();
        let request = Request::new(id, method, params);

        let (response_tx, response_rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().await;
            pending.push(PendingRequest { id, response_tx });
        }

        self.request_tx
            .lock()
            .await
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
    /// Waits for sidecar readiness before sending.
    pub async fn notify(&self, method: &str, params: Option<Value>) -> Result<(), Error> {
        // Wait for sidecar to be ready for notifications too
        self.wait_for_ready(Duration::from_secs(30)).await?;

        let notification = Request::notification(method, params);
        self.request_tx
            .lock()
            .await
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

        // Cancel all pending requests
        Self::cancel_all_pending(&self.pending, "server shutting down").await;

        // Try to send shutdown request
        let _ = self
            .request_tx
            .lock()
            .await
            .send(Request::new(self.next_id(), "shutdown", None))
            .await;

        Self::set_state(&self.state, &self.state_watch_tx, SidecarState::Stopped).await;

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
        tracing::debug!("Received response from sidecar: {:?}", response);
        let id = match response.id {
            Some(id) => id,
            None => {
                tracing::warn!("received response without id");
                return;
            }
        };

        let mut pending = pending.lock().await;
        tracing::debug!("Looking for pending request with id {}, have {} pending requests", id, pending.len());
        if let Some(pos) = pending.iter().position(|p| p.id == id) {
            let req = pending.remove(pos);
            let result = if let Some(error) = response.error {
                tracing::error!("Sidecar returned error for request {}: {:?}", id, error);
                Err(Error::Bridge(BridgeError::MalformedResponse(format!(
                    "error {}: {}",
                    error.code, error.message
                ))))
            } else {
                tracing::info!("Sidecar returned success for request {}", id);
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

    #[tokio::test]
    async fn request_before_start_returns_not_ready() {
        let bridge = Bridge::new(
            PathBuf::from("sidecar.jar"),
            PathBuf::from("/usr/bin/java"),
            Config::default(),
        );
        // State is Stopped, so wait_for_ready should return NotReady immediately
        let result = bridge.request("hover", None).await;
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(
            matches!(err, Error::Bridge(BridgeError::NotReady(_))),
            "expected NotReady, got: {:?}",
            err
        );
    }

    #[tokio::test]
    async fn wait_for_ready_returns_immediately_when_ready() {
        let bridge = Bridge::new(
            PathBuf::from("sidecar.jar"),
            PathBuf::from("/usr/bin/java"),
            Config::default(),
        );
        // Manually set state to Ready
        Bridge::set_state(&bridge.state, &bridge.state_watch_tx, SidecarState::Ready).await;

        let result = bridge.wait_for_ready(Duration::from_secs(1)).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn wait_for_ready_blocks_during_starting() {
        let bridge = Bridge::new(
            PathBuf::from("sidecar.jar"),
            PathBuf::from("/usr/bin/java"),
            Config::default(),
        );
        // Set state to Starting
        Bridge::set_state(&bridge.state, &bridge.state_watch_tx, SidecarState::Starting).await;

        let state_ref = Arc::clone(&bridge.state);
        let watch_tx = Arc::clone(&bridge.state_watch_tx);

        // Transition to Ready after a short delay
        tokio::spawn(async move {
            time::sleep(Duration::from_millis(100)).await;
            Bridge::set_state(&state_ref, &watch_tx, SidecarState::Ready).await;
        });

        let result = bridge.wait_for_ready(Duration::from_secs(5)).await;
        assert!(result.is_ok(), "expected Ok, got: {:?}", result);
    }

    #[tokio::test]
    async fn wait_for_ready_returns_error_on_degraded() {
        let bridge = Bridge::new(
            PathBuf::from("sidecar.jar"),
            PathBuf::from("/usr/bin/java"),
            Config::default(),
        );
        Bridge::set_state(&bridge.state, &bridge.state_watch_tx, SidecarState::Degraded).await;

        let result = bridge.wait_for_ready(Duration::from_secs(1)).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn cancel_all_pending_sends_errors() {
        let pending = Arc::new(Mutex::new(Vec::new()));
        let (tx, rx) = oneshot::channel();
        {
            let mut p = pending.lock().await;
            p.push(PendingRequest { id: 1, response_tx: tx });
        }

        Bridge::cancel_all_pending(&pending, "test crash").await;

        let result = rx.await.unwrap();
        assert!(result.is_err());
    }
}
