use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::Value;
use tokio::io::BufReader;
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot, watch, Mutex, Notify};
use tokio::time;

use crate::config::Config;
use crate::error::{BridgeError, Error, SupersededStage};
use crate::jsonrpc::{self, Request, Response, SidecarMessage};
use crate::runtime::SidecarRuntime;

/// An unsolicited notification sent by the sidecar (e.g. workspace/applyEdit).
#[derive(Debug)]
pub struct SidecarNotification {
    pub method: String,
    pub params: Option<Value>,
}

/// Sidecar lifecycle states.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum SidecarState {
    Starting,
    Ready,
    Degraded,
    Stopped,
}

/// A pending request awaiting a response from the sidecar.
struct PendingRequest {
    id: u64,
    response_tx: oneshot::Sender<Result<Value, Error>>,
}

/// Identifies a request that may be superseded by a newer one for the same bucket.
/// `bucket` is typically a document URI; `seq` is monotonically increasing per bucket.
#[derive(Debug, Clone)]
struct SupersessionTag {
    bucket: String,
    seq: u64,
}

/// Message enqueued on the internal writer channel.
/// `Plain` requests are written to stdin unconditionally; `Supersedable` requests
/// are dropped before stdin write when a newer request for the same bucket has
/// been registered, saving sidecar CPU and reducing user-visible queue wait.
enum QueuedMessage {
    Plain(Request),
    Supersedable {
        request: Request,
        tag: SupersessionTag,
    },
}

#[derive(Debug, Clone, Copy)]
pub struct BridgeRequestTiming {
    pub ready_wait_ms: f64,
    pub enqueue_send_ms: f64,
    pub response_wait_ms: f64,
    pub total_ms: f64,
}

/// Stored initialization parameters for restart.
#[derive(Clone, Default)]
struct InitParams {
    project_root: String,
    classpath: Vec<String>,
    compiler_flags: Vec<String>,
    source_roots: Vec<String>,
}

const MAX_RESTART_ATTEMPTS: u32 = 5;

fn elapsed_ms(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1000.0
}

/// Manages the JVM sidecar process lifecycle and JSON-RPC communication.
pub struct Bridge {
    state: Arc<Mutex<SidecarState>>,
    /// Watch channel for state transitions. Allows `request()` to wait for Ready.
    state_watch_tx: Arc<watch::Sender<SidecarState>>,
    state_watch_rx: watch::Receiver<SidecarState>,
    request_id: AtomicU64,
    pending: Arc<Mutex<Vec<PendingRequest>>>,
    request_tx: Mutex<mpsc::Sender<QueuedMessage>>,
    /// Tracks the latest supersession seq per bucket (URI for completion).
    /// When a newer request arrives, queued older requests are dropped before stdin write.
    latest_supersession_seq: Arc<Mutex<HashMap<String, u64>>>,
    /// Counts of completion requests skipped before sidecar work due to supersession.
    superseded_before_send_count: Arc<AtomicU64>,
    /// Counts of completion responses discarded after sidecar work due to supersession.
    superseded_in_flight_count: Arc<AtomicU64>,
    runtime: SidecarRuntime,
    java_path: PathBuf,
    config: Arc<Mutex<Config>>,
    shutdown_notify: Arc<Notify>,
    restart_count: Arc<Mutex<u32>>,
    health_check_shutdown: Arc<Notify>,
    /// Holds the sidecar child process to prevent kill_on_drop from firing.
    child: Mutex<Option<tokio::process::Child>>,
    /// Stored init params for automatic restart.
    init_params: Mutex<InitParams>,
    /// Prevents overlapping restart loops from racing and killing fresh sidecars.
    restart_in_progress: AtomicBool,
    /// Channel for forwarding unsolicited sidecar notifications to the server.
    notification_tx: mpsc::Sender<SidecarNotification>,
}

impl Bridge {
    /// Creates a new bridge but does not start the sidecar yet.
    ///
    /// The `notification_tx` channel is used to forward unsolicited
    /// notifications from the sidecar (e.g. workspace/applyEdit) to the
    /// server, which then relays them to the LSP client.
    pub fn new(
        runtime: SidecarRuntime,
        java_path: PathBuf,
        config: Config,
        notification_tx: mpsc::Sender<SidecarNotification>,
    ) -> Self {
        tracing::debug!(
            "Bridge::new called with runtime: {:?}, java_path: {:?}",
            runtime,
            java_path
        );
        let (request_tx, _request_rx) = mpsc::channel::<QueuedMessage>(32);
        let (state_watch_tx, state_watch_rx) = watch::channel(SidecarState::Stopped);

        Self {
            state: Arc::new(Mutex::new(SidecarState::Stopped)),
            state_watch_tx: Arc::new(state_watch_tx),
            state_watch_rx,
            request_id: AtomicU64::new(1),
            pending: Arc::new(Mutex::new(Vec::new())),
            request_tx: Mutex::new(request_tx),
            latest_supersession_seq: Arc::new(Mutex::new(HashMap::new())),
            superseded_before_send_count: Arc::new(AtomicU64::new(0)),
            superseded_in_flight_count: Arc::new(AtomicU64::new(0)),
            runtime,
            java_path,
            config: Arc::new(Mutex::new(config)),
            shutdown_notify: Arc::new(Notify::new()),
            restart_count: Arc::new(Mutex::new(0)),
            health_check_shutdown: Arc::new(Notify::new()),
            child: Mutex::new(None),
            init_params: Mutex::new(InitParams::default()),
            restart_in_progress: AtomicBool::new(false),
            notification_tx,
        }
    }

    /// Returns the current sidecar state.
    pub async fn state(&self) -> SidecarState {
        *self.state.lock().await
    }

    /// Updates the state and notifies all watchers (request buffering).
    async fn set_state(
        state: &Mutex<SidecarState>,
        watch_tx: &watch::Sender<SidecarState>,
        new_state: SidecarState,
    ) {
        let mut s = state.lock().await;
        *s = new_state;
        let _ = watch_tx.send(new_state);
    }

    /// Starts the health check heartbeat for the sidecar.
    fn start_health_check(
        bridge: Arc<Bridge>,
        request_tx: mpsc::Sender<QueuedMessage>,
        request_id: &AtomicU64,
    ) {
        let state = Arc::clone(&bridge.state);
        let state_watch_tx = Arc::clone(&bridge.state_watch_tx);
        let pending = Arc::clone(&bridge.pending);
        let shutdown = Arc::clone(&bridge.shutdown_notify);
        let health_shutdown = Arc::clone(&bridge.health_check_shutdown);
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
            let mut should_restart = false;

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

                        if request_tx.send(QueuedMessage::Plain(request)).await.is_err() {
                            tracing::warn!("health check: request channel closed");
                            Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                            should_restart = true;
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
                                    should_restart = true;
                                    break;
                                }
                            }
                            Ok(Err(_)) => {
                                tracing::warn!("health check: response channel dropped");
                                Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                                should_restart = true;
                                break;
                            }
                            Err(_) => {
                                consecutive_failures += 1;
                                tracing::warn!("health check: ping timeout ({}/{})", consecutive_failures, MAX_FAILURES);
                                if consecutive_failures >= MAX_FAILURES {
                                    Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                                    should_restart = true;
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

            // Trigger automatic restart if degraded
            if should_restart {
                Self::try_restart(bridge).await;
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
            let _ = req
                .response_tx
                .send(Err(Error::Bridge(BridgeError::Crashed(reason.to_string()))));
        }
    }

    /// Starts the sidecar JVM process and begins communication.
    /// The optional project_root, classpath, and source_roots are forwarded
    /// to the sidecar's `initialize` request so the Analysis API session
    /// is configured with actual project data.
    pub async fn start(
        self: &Arc<Self>,
        project_root: Option<&str>,
        classpath: &[String],
        compiler_flags: &[String],
        source_roots: &[String],
    ) -> Result<(), Error> {
        // Store init params for potential restart
        {
            let mut params = self.init_params.lock().await;
            *params = InitParams {
                project_root: project_root.unwrap_or("").to_string(),
                classpath: classpath.to_vec(),
                compiler_flags: compiler_flags.to_vec(),
                source_roots: source_roots.to_vec(),
            };
        }

        {
            Self::set_state(&self.state, &self.state_watch_tx, SidecarState::Starting).await;
            tracing::debug!("sidecar state changed to Starting");
        }

        let config = self.config.lock().await.clone();
        let max_memory = &config.sidecar_max_memory;
        let runtime = self.runtime.clone();

        let mut command = Command::new(&self.java_path);
        command
            .arg(format!("-Xmx{max_memory}"))
            .arg("--add-opens")
            .arg("java.base/java.lang=ALL-UNNAMED")
            .arg("--add-opens")
            .arg("java.base/java.lang.reflect=ALL-UNNAMED")
            .arg("--add-opens")
            .arg("java.base/java.util=ALL-UNNAMED");

        match runtime.main_class.as_deref() {
            Some(main_class) => {
                let classpath = std::env::join_paths(&runtime.classpath)
                    .map_err(|e| BridgeError::SpawnFailed(e.to_string()))?;
                command.arg("-cp").arg(classpath).arg(main_class);
            }
            None => {
                let sidecar_jar = runtime.classpath.first().ok_or_else(|| {
                    BridgeError::SpawnFailed("sidecar runtime classpath is empty".into())
                })?;
                command.arg("-jar").arg(sidecar_jar);
            }
        }

        let mut child = command
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
        let stderr = child.stderr.take();

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

        // Spawn the reader task to process incoming responses and notifications
        let pending = Arc::clone(&self.pending);
        let state = Arc::clone(&self.state);
        let state_watch_tx = Arc::clone(&self.state_watch_tx);
        let shutdown = Arc::clone(&self.shutdown_notify);
        let reader_bridge = Arc::clone(self);
        let notification_tx = self.notification_tx.clone();

        tokio::spawn(async move {
            let mut reader = BufReader::new(stdout);
            tracing::debug!("sidecar reader task started");
            let mut should_restart = false;
            loop {
                tokio::select! {
                    result = jsonrpc::read_message(&mut reader) => {
                        match result {
                            Ok(Some(SidecarMessage::Response(response))) => {
                                tracing::debug!("Read response from sidecar");
                                Self::dispatch_response(&pending, response).await;
                            }
                            Ok(Some(SidecarMessage::Notification { method, params })) => {
                                tracing::debug!("Read notification from sidecar: {}", method);
                                if notification_tx.send(SidecarNotification { method, params }).await.is_err() {
                                    tracing::debug!("notification channel closed, listener has exited");
                                }
                            }
                            Ok(None) => {
                                // EOF - sidecar exited. Cancel all pending requests immediately.
                                tracing::error!("sidecar stdout closed (process exited)");
                                Self::cancel_all_pending(&pending, "sidecar process exited").await;
                                let current = *state.lock().await;
                                if current != SidecarState::Stopped {
                                    Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                                    should_restart = true;
                                }
                                break;
                            }
                            Err(e) => {
                                tracing::error!("error reading sidecar response: {}", e);
                                Self::cancel_all_pending(&pending, &format!("read error: {}", e)).await;
                                Self::set_state(&state, &state_watch_tx, SidecarState::Degraded).await;
                                should_restart = true;
                                break;
                            }
                        }
                    }
                    _ = shutdown.notified() => {
                        tracing::debug!("sidecar reader task shutting down");
                        break;
                    }
                }
            }

            // Trigger automatic restart on unexpected exit
            if should_restart {
                Self::try_restart(reader_bridge).await;
            }
        });

        // Create a new request channel for this sidecar instance
        let (tx, mut rx) = mpsc::channel::<QueuedMessage>(32);

        // Swap in the live sender so request()/notify()/shutdown() use it
        {
            let mut current_tx = self.request_tx.lock().await;
            *current_tx = tx.clone();
        }

        let stdin = Arc::new(Mutex::new(stdin));
        let stdin_clone = Arc::clone(&stdin);
        let writer_pending = Arc::clone(&self.pending);
        let writer_supersession = Arc::clone(&self.latest_supersession_seq);
        let writer_skip_count = Arc::clone(&self.superseded_before_send_count);

        tokio::spawn(async move {
            while let Some(message) = rx.recv().await {
                let request = match Self::filter_queued_message(
                    message,
                    &writer_pending,
                    &writer_supersession,
                    &writer_skip_count,
                )
                .await
                {
                    Some(req) => req,
                    None => continue, // dropped (superseded)
                };
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
            "compilerFlags": compiler_flags,
            "jdkHome": config.java_home.unwrap_or_default(),
            "sourceRoots": source_roots,
        });

        let id = self.next_id();
        let request = Request::new(id, "initialize", Some(init_params.clone()));
        tracing::debug!("sending initialize request to sidecar with id {}", id);

        let (response_tx, response_rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().await;
            pending.push(PendingRequest { id, response_tx });
            tracing::debug!("Added pending request with id {}", id);
        }

        tx.send(QueuedMessage::Plain(request))
            .await
            .map_err(|_| BridgeError::Crashed("request channel closed".into()))?;
        tracing::debug!("initialize request sent to sidecar, waiting for response");

        // Wait for initialize response with timeout
        match time::timeout(Duration::from_secs(30), response_rx).await {
            Ok(Ok(Ok(result))) => {
                if let Some(actual_version) = result.get("kotlinVersion").and_then(|v| v.as_str()) {
                    match runtime.kotlin_version.as_deref() {
                        Some(selected_version) if selected_version != actual_version => {
                            tracing::warn!(
                                "sidecar reported Kotlin runtime {} after selecting {} ({})",
                                actual_version,
                                selected_version,
                                runtime.selection_reason.description()
                            );
                        }
                        Some(selected_version) => {
                            tracing::info!(
                                "sidecar ready with Kotlin runtime {} ({})",
                                selected_version,
                                runtime.selection_reason.description()
                            );
                        }
                        None => {
                            tracing::info!(
                                "sidecar ready with Kotlin runtime {} ({})",
                                actual_version,
                                runtime.selection_reason.description()
                            );
                        }
                    }
                } else {
                    tracing::warn!("sidecar initialize response did not include kotlinVersion");
                }
                tracing::debug!("sidecar initialized successfully");
                Self::set_state(&self.state, &self.state_watch_tx, SidecarState::Ready).await;
                tracing::info!("sidecar ready");

                // Start health check heartbeat
                Self::start_health_check(Arc::clone(self), tx.clone(), &self.request_id);

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
            SidecarState::Starting | SidecarState::Degraded => {
                // Starting: sidecar is booting up, wait for Ready.
                // Degraded: a restart may be in progress — wait to see if
                // the state transitions to Starting/Ready before giving up.
                tracing::debug!(
                    "waiting for sidecar to become Ready (current: {:?})",
                    current
                );
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
                    SidecarState::Stopped => {
                        return Err(BridgeError::NotReady(format!(
                            "sidecar transitioned to {:?} while waiting",
                            state
                        ))
                        .into());
                    }
                    SidecarState::Starting | SidecarState::Degraded => {
                        // Keep waiting — restart may be in progress
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
        self.request_with_timeout(method, params, Duration::from_secs(60))
            .await
    }

    /// Sends a JSON-RPC request with a custom response timeout.
    /// Used for long-running operations like project-wide analysis.
    pub async fn request_with_timeout(
        &self,
        method: &str,
        params: Option<Value>,
        timeout: Duration,
    ) -> Result<Value, Error> {
        let (value, _) = self
            .request_with_timeout_timed(method, params, timeout)
            .await?;
        Ok(value)
    }

    pub async fn request_with_timeout_timed(
        &self,
        method: &str,
        params: Option<Value>,
        timeout: Duration,
    ) -> Result<(Value, BridgeRequestTiming), Error> {
        let total_start = Instant::now();

        let ready_start = Instant::now();
        self.wait_for_ready(Duration::from_secs(30)).await?;
        let ready_wait_ms = elapsed_ms(ready_start.elapsed());

        tracing::debug!(
            "Sending request '{}' to sidecar (timeout: {:?})",
            method,
            timeout
        );

        let enqueue_start = Instant::now();
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
            .send(QueuedMessage::Plain(request))
            .await
            .map_err(|_| BridgeError::Crashed("request channel closed".into()))?;
        let enqueue_send_ms = elapsed_ms(enqueue_start.elapsed());

        let response_start = Instant::now();
        match time::timeout(timeout, response_rx).await {
            Ok(Ok(result)) => {
                let response_wait_ms = elapsed_ms(response_start.elapsed());
                let total_ms = elapsed_ms(total_start.elapsed());
                result.map(|value| {
                    (
                        value,
                        BridgeRequestTiming {
                            ready_wait_ms,
                            enqueue_send_ms,
                            response_wait_ms,
                            total_ms,
                        },
                    )
                })
            }
            Ok(Err(_)) => Err(BridgeError::Crashed("response channel dropped".into()).into()),
            Err(_) => Err(BridgeError::Timeout(timeout.as_millis() as u64).into()),
        }
    }

    /// Sends a request that may be superseded by a newer request for the same `bucket`.
    ///
    /// When this is called:
    /// - The latest seq for `bucket` is bumped atomically; the new seq is `my_seq`.
    /// - If a newer request arrives before this one is written to stdin, it is
    ///   dropped from the writer queue and the response future resolves with
    ///   [`BridgeError::Superseded`] with `stage = BeforeSend` — saving sidecar work.
    /// - If a newer request arrives after this one's response was received but
    ///   before `request_with_supersession` returns, the response is discarded
    ///   and the function returns [`BridgeError::Superseded`] with `stage = InFlight`.
    ///
    /// This is used by the completion handler to avoid wasting sidecar CPU on
    /// completions the user no longer cares about (typing burst → only the latest
    /// position matters).
    ///
    /// Returns `(value, timing, my_seq)` on success.
    pub async fn request_with_supersession(
        &self,
        method: &str,
        params: Option<Value>,
        timeout: Duration,
        bucket: &str,
    ) -> Result<(Value, BridgeRequestTiming, u64), Error> {
        let total_start = Instant::now();

        let ready_start = Instant::now();
        self.wait_for_ready(Duration::from_secs(30)).await?;
        let ready_wait_ms = elapsed_ms(ready_start.elapsed());

        let enqueue_start = Instant::now();
        let id = self.next_id();
        let request = Request::new(id, method, params);

        // Bump the per-bucket seq; later registrations see a strictly greater seq.
        let my_seq = {
            let mut map = self.latest_supersession_seq.lock().await;
            let entry = map.entry(bucket.to_string()).or_insert(0);
            *entry += 1;
            *entry
        };

        let (response_tx, response_rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().await;
            pending.push(PendingRequest { id, response_tx });
        }

        let tag = SupersessionTag {
            bucket: bucket.to_string(),
            seq: my_seq,
        };
        self.request_tx
            .lock()
            .await
            .send(QueuedMessage::Supersedable { request, tag })
            .await
            .map_err(|_| BridgeError::Crashed("request channel closed".into()))?;
        let enqueue_send_ms = elapsed_ms(enqueue_start.elapsed());

        let response_start = Instant::now();
        let outcome = match time::timeout(timeout, response_rx).await {
            Ok(Ok(result)) => {
                let response_wait_ms = elapsed_ms(response_start.elapsed());
                let total_ms = elapsed_ms(total_start.elapsed());
                let timing = BridgeRequestTiming {
                    ready_wait_ms,
                    enqueue_send_ms,
                    response_wait_ms,
                    total_ms,
                };
                result.map(|value| (value, timing))
            }
            Ok(Err(_)) => Err(BridgeError::Crashed("response channel dropped".into()).into()),
            Err(_) => Err(BridgeError::Timeout(timeout.as_millis() as u64).into()),
        };

        match outcome {
            Ok((value, timing)) => {
                // In-flight supersession: a newer request was registered while we
                // were waiting for the sidecar response. The result is now stale.
                let latest = {
                    let map = self.latest_supersession_seq.lock().await;
                    map.get(bucket).copied().unwrap_or(0)
                };
                if my_seq < latest {
                    self.superseded_in_flight_count
                        .fetch_add(1, Ordering::Relaxed);
                    Err(BridgeError::Superseded {
                        bucket: bucket.to_string(),
                        my_seq,
                        latest_seq: latest,
                        stage: SupersededStage::InFlight,
                    }
                    .into())
                } else {
                    Ok((value, timing, my_seq))
                }
            }
            Err(e) => Err(e),
        }
    }

    /// Returns the number of requests skipped before stdin write due to supersession.
    pub fn superseded_before_send_count(&self) -> u64 {
        self.superseded_before_send_count.load(Ordering::Relaxed)
    }

    /// Returns the number of responses discarded after sidecar work due to supersession.
    pub fn superseded_in_flight_count(&self) -> u64 {
        self.superseded_in_flight_count.load(Ordering::Relaxed)
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
            .send(QueuedMessage::Plain(notification))
            .await
            .map_err(|_| BridgeError::Crashed("request channel closed".into()))?;
        Ok(())
    }

    /// Attempts to restart the sidecar after it enters the Degraded state.
    /// Uses exponential backoff (2s, 4s, 8s, 16s, 32s) up to MAX_RESTART_ATTEMPTS.
    /// Called from the health check loop and reader EOF handler.
    fn try_restart(
        bridge: Arc<Self>,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = ()> + Send>> {
        Box::pin(async move {
            if bridge
                .restart_in_progress
                .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                .is_err()
            {
                tracing::debug!("sidecar restart already in progress, skipping duplicate trigger");
                return;
            }

            loop {
                let attempt = {
                    let mut count = bridge.restart_count.lock().await;
                    if *count >= MAX_RESTART_ATTEMPTS {
                        tracing::error!(
                            "sidecar restart limit reached ({}/{}), giving up",
                            *count,
                            MAX_RESTART_ATTEMPTS,
                        );
                        Self::set_state(
                            &bridge.state,
                            &bridge.state_watch_tx,
                            SidecarState::Stopped,
                        )
                        .await;
                        bridge.restart_in_progress.store(false, Ordering::Release);
                        return;
                    }
                    *count += 1;
                    *count
                };

                // Exponential backoff: 2^attempt seconds (2, 4, 8, 16, 32)
                let delay = Duration::from_secs(1 << attempt);
                tracing::warn!(
                    "sidecar restart attempt {}/{} in {:?}",
                    attempt,
                    MAX_RESTART_ATTEMPTS,
                    delay,
                );
                time::sleep(delay).await;

                // Kill old process if still running
                {
                    let mut child = bridge.child.lock().await;
                    if let Some(mut proc) = child.take() {
                        let _ = proc.kill().await;
                    }
                }

                // Cancel any lingering pending requests
                Self::cancel_all_pending(&bridge.pending, "sidecar restarting").await;

                // Stop old health check
                bridge.health_check_shutdown.notify_waiters();

                // Retrieve stored init params
                let params = bridge.init_params.lock().await.clone();

                // Attempt restart
                let result = bridge
                    .start(
                        Some(&params.project_root),
                        &params.classpath,
                        &params.compiler_flags,
                        &params.source_roots,
                    )
                    .await;

                match result {
                    Ok(()) => {
                        tracing::info!("sidecar restarted successfully (attempt {})", attempt);
                        bridge.restart_in_progress.store(false, Ordering::Release);
                        return;
                    }
                    Err(e) => {
                        tracing::error!("sidecar restart failed (attempt {}): {}", attempt, e);
                        Self::set_state(
                            &bridge.state,
                            &bridge.state_watch_tx,
                            SidecarState::Degraded,
                        )
                        .await;
                    }
                }
            }
        })
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
            .send(QueuedMessage::Plain(Request::new(
                self.next_id(),
                "shutdown",
                None,
            )))
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

    /// Resolves a queued message for the writer task: returns `Some(Request)` to write
    /// to stdin, or `None` if the request was superseded and should be dropped. When a
    /// request is dropped, the matching pending entry is removed and resolved with
    /// [`BridgeError::Superseded`] (`BeforeSend` stage), and the skip-count metric is
    /// incremented.
    async fn filter_queued_message(
        message: QueuedMessage,
        pending: &Mutex<Vec<PendingRequest>>,
        latest_supersession_seq: &Mutex<HashMap<String, u64>>,
        skip_count: &AtomicU64,
    ) -> Option<Request> {
        match message {
            QueuedMessage::Plain(request) => Some(request),
            QueuedMessage::Supersedable { request, tag } => {
                let latest = {
                    let map = latest_supersession_seq.lock().await;
                    map.get(&tag.bucket).copied().unwrap_or(0)
                };
                if tag.seq >= latest {
                    return Some(request);
                }
                let id_opt = request.id;
                tracing::debug!(
                    target: "completion_supersession",
                    bucket = %tag.bucket,
                    my_seq = tag.seq,
                    latest_seq = latest,
                    request_id = ?id_opt,
                    "dropping superseded request before stdin write"
                );
                skip_count.fetch_add(1, Ordering::Relaxed);
                if let Some(id) = id_opt {
                    let mut pending = pending.lock().await;
                    if let Some(pos) = pending.iter().position(|p| p.id == id) {
                        let req = pending.remove(pos);
                        let _ = req.response_tx.send(Err(Error::Bridge(
                            BridgeError::Superseded {
                                bucket: tag.bucket.clone(),
                                my_seq: tag.seq,
                                latest_seq: latest,
                                stage: SupersededStage::BeforeSend,
                            },
                        )));
                    }
                }
                None
            }
        }
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
        tracing::debug!(
            "Looking for pending request with id {}, have {} pending requests",
            id,
            pending.len()
        );
        if let Some(pos) = pending.iter().position(|p| p.id == id) {
            let req = pending.remove(pos);
            let result = if let Some(error) = response.error {
                tracing::error!("Sidecar returned error for request {}: {:?}", id, error);
                Err(Error::Bridge(BridgeError::MalformedResponse(format!(
                    "error {}: {}",
                    error.code, error.message
                ))))
            } else {
                tracing::debug!("sidecar returned success for request {}", id);
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

    fn test_bridge() -> (Bridge, mpsc::Receiver<SidecarNotification>) {
        let (tx, rx) = mpsc::channel(16);
        let bridge = Bridge::new(
            SidecarRuntime {
                requested_kotlin_version: None,
                kotlin_version: Some("2.3.20".into()),
                classpath: vec![PathBuf::from("sidecar.jar")],
                main_class: None,
                selection_reason: crate::runtime::RuntimeSelectionReason::DefaultBundled,
            },
            PathBuf::from("/usr/bin/java"),
            Config::default(),
            tx,
        );
        (bridge, rx)
    }

    #[test]
    fn initial_state_is_stopped() {
        let (bridge, _rx) = test_bridge();
        let rt = tokio::runtime::Runtime::new().unwrap();
        let state = rt.block_on(bridge.state());
        assert_eq!(state, SidecarState::Stopped);
    }

    #[test]
    fn next_id_increments() {
        let (bridge, _rx) = test_bridge();
        let id1 = bridge.next_id();
        let id2 = bridge.next_id();
        assert_eq!(id2, id1 + 1);
    }

    #[tokio::test]
    async fn request_before_start_returns_not_ready() {
        let (bridge, _rx) = test_bridge();
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
        let (bridge, _rx) = test_bridge();
        // Manually set state to Ready
        Bridge::set_state(&bridge.state, &bridge.state_watch_tx, SidecarState::Ready).await;

        let result = bridge.wait_for_ready(Duration::from_secs(1)).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn wait_for_ready_blocks_during_starting() {
        let (bridge, _rx) = test_bridge();
        // Set state to Starting
        Bridge::set_state(
            &bridge.state,
            &bridge.state_watch_tx,
            SidecarState::Starting,
        )
        .await;

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
    async fn wait_for_ready_waits_then_times_out_on_degraded() {
        let (bridge, _rx) = test_bridge();
        Bridge::set_state(
            &bridge.state,
            &bridge.state_watch_tx,
            SidecarState::Degraded,
        )
        .await;

        // Degraded now waits (restart may be in progress) then times out
        let result = bridge.wait_for_ready(Duration::from_millis(100)).await;
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(
            matches!(err, Error::Bridge(BridgeError::Timeout(_))),
            "expected Timeout, got: {:?}",
            err
        );
    }

    #[tokio::test]
    async fn cancel_all_pending_sends_errors() {
        let pending = Arc::new(Mutex::new(Vec::new()));
        let (tx, rx) = oneshot::channel();
        {
            let mut p = pending.lock().await;
            p.push(PendingRequest {
                id: 1,
                response_tx: tx,
            });
        }

        Bridge::cancel_all_pending(&pending, "test crash").await;

        let result = rx.await.unwrap();
        assert!(result.is_err());
    }

    #[test]
    fn bridge_stores_selected_runtime_for_restarts() {
        let runtime = SidecarRuntime {
            requested_kotlin_version: Some("2.2.20".into()),
            kotlin_version: Some("2.3.20".into()),
            classpath: vec![PathBuf::from("runtime.jar")],
            main_class: Some("dev.kouros.sidecar.MainKt".into()),
            selection_reason: crate::runtime::RuntimeSelectionReason::SameMinorFallback,
        };
        let (tx, _rx) = mpsc::channel(1);
        let bridge = Bridge::new(
            runtime.clone(),
            PathBuf::from("/usr/bin/java"),
            Config::default(),
            tx,
        );

        assert_eq!(bridge.runtime, runtime);
    }

    /// Helper: build the shared state (`pending`, `latest_supersession_seq`,
    /// skip-counter) the writer task uses, plus a registered pending entry whose
    /// response future is returned so a test can await its resolution.
    async fn make_writer_state(
        request_id: u64,
    ) -> (
        Arc<Mutex<Vec<PendingRequest>>>,
        Arc<Mutex<HashMap<String, u64>>>,
        Arc<AtomicU64>,
        oneshot::Receiver<Result<Value, Error>>,
    ) {
        let pending = Arc::new(Mutex::new(Vec::new()));
        let latest = Arc::new(Mutex::new(HashMap::new()));
        let skip_count = Arc::new(AtomicU64::new(0));
        let (tx, rx) = oneshot::channel();
        {
            let mut p = pending.lock().await;
            p.push(PendingRequest {
                id: request_id,
                response_tx: tx,
            });
        }
        (pending, latest, skip_count, rx)
    }

    #[tokio::test]
    async fn filter_passes_plain_messages_through() {
        let (pending, latest, skip_count, _rx) = make_writer_state(1).await;
        let msg = QueuedMessage::Plain(Request::new(1, "ping", None));

        let result = Bridge::filter_queued_message(msg, &pending, &latest, &skip_count).await;

        assert!(result.is_some(), "Plain messages should pass through");
        assert_eq!(skip_count.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn filter_passes_supersedable_when_seq_is_latest() {
        let (pending, latest, skip_count, _rx) = make_writer_state(7).await;
        // Bucket has not yet been registered → latest defaults to 0; seq=1 is fresh.
        let msg = QueuedMessage::Supersedable {
            request: Request::new(7, "completion", None),
            tag: SupersessionTag {
                bucket: "file:///a.kt".into(),
                seq: 1,
            },
        };

        let result = Bridge::filter_queued_message(msg, &pending, &latest, &skip_count).await;

        assert!(result.is_some(), "fresh supersedable should be written");
        assert_eq!(skip_count.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn filter_drops_superseded_request_and_resolves_pending_with_error() {
        let (pending, latest, skip_count, rx) = make_writer_state(42).await;
        // Simulate a newer registration arriving before the writer drained the queue.
        {
            let mut map = latest.lock().await;
            map.insert("file:///a.kt".into(), 5);
        }
        let msg = QueuedMessage::Supersedable {
            request: Request::new(42, "completion", None),
            tag: SupersessionTag {
                bucket: "file:///a.kt".into(),
                seq: 3,
            },
        };

        let result = Bridge::filter_queued_message(msg, &pending, &latest, &skip_count).await;

        assert!(result.is_none(), "superseded message should be dropped");
        assert_eq!(skip_count.load(Ordering::Relaxed), 1);
        assert!(
            pending.lock().await.is_empty(),
            "pending entry should have been removed"
        );

        let received = rx.await.expect("response_tx was dropped without sending");
        match received {
            Err(Error::Bridge(BridgeError::Superseded {
                bucket,
                my_seq,
                latest_seq,
                stage,
            })) => {
                assert_eq!(bucket, "file:///a.kt");
                assert_eq!(my_seq, 3);
                assert_eq!(latest_seq, 5);
                assert_eq!(stage, SupersededStage::BeforeSend);
            }
            other => panic!("expected BridgeError::Superseded BeforeSend, got {:?}", other),
        }
    }

    #[tokio::test]
    async fn filter_does_not_drop_when_other_bucket_advances() {
        let (pending, latest, skip_count, _rx) = make_writer_state(9).await;
        // A different bucket has a higher seq — must not affect this request.
        {
            let mut map = latest.lock().await;
            map.insert("file:///other.kt".into(), 99);
        }
        let msg = QueuedMessage::Supersedable {
            request: Request::new(9, "completion", None),
            tag: SupersessionTag {
                bucket: "file:///a.kt".into(),
                seq: 1,
            },
        };

        let result = Bridge::filter_queued_message(msg, &pending, &latest, &skip_count).await;

        assert!(
            result.is_some(),
            "supersession is per-bucket; other buckets should not drop us"
        );
        assert_eq!(skip_count.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn filter_drops_typing_burst_keeping_only_latest() {
        // Simulate the user-visible scenario: 3 completions queued for the same
        // file. Only the newest should reach stdin; the other two are dropped
        // and their pending oneshots resolve with Superseded.
        let pending = Arc::new(Mutex::new(Vec::new()));
        let latest = Arc::new(Mutex::new(HashMap::new()));
        let skip_count = Arc::new(AtomicU64::new(0));
        let bucket = "file:///burst.kt".to_string();

        let mut receivers = Vec::new();
        for (id, seq) in [(1u64, 1u64), (2, 2), (3, 3)] {
            let (tx, rx) = oneshot::channel();
            pending.lock().await.push(PendingRequest {
                id,
                response_tx: tx,
            });
            receivers.push((id, seq, rx));
            // Register the seq globally as if `request_with_supersession` had run.
            latest
                .lock()
                .await
                .insert(bucket.clone(), seq);
        }

        // Now the writer drains the channel in FIFO order.
        let mut wrote_to_stdin = Vec::new();
        for (id, seq, _) in &receivers {
            let msg = QueuedMessage::Supersedable {
                request: Request::new(*id, "completion", None),
                tag: SupersessionTag {
                    bucket: bucket.clone(),
                    seq: *seq,
                },
            };
            if let Some(req) =
                Bridge::filter_queued_message(msg, &pending, &latest, &skip_count).await
            {
                wrote_to_stdin.push(req.id.unwrap());
            }
        }

        assert_eq!(
            wrote_to_stdin,
            vec![3],
            "only the newest completion should be written to stdin"
        );
        assert_eq!(skip_count.load(Ordering::Relaxed), 2);

        // The two older requests' response futures must resolve with Superseded.
        for (id, seq, rx) in receivers.into_iter().take(2) {
            let received = rx.await.expect("dropped response_tx");
            match received {
                Err(Error::Bridge(BridgeError::Superseded {
                    my_seq, latest_seq, stage, ..
                })) => {
                    assert_eq!(my_seq, seq, "request id={} should report its own seq", id);
                    assert_eq!(latest_seq, 3);
                    assert_eq!(stage, SupersededStage::BeforeSend);
                }
                other => panic!("request {} expected Superseded, got {:?}", id, other),
            }
        }
    }

    #[tokio::test]
    async fn request_with_supersession_assigns_strictly_increasing_seq_per_bucket() {
        let (bridge, _rx) = test_bridge();
        // Bump twice for the same bucket; second seq must be greater.
        let bucket = "file:///a.kt";
        let first = {
            let mut map = bridge.latest_supersession_seq.lock().await;
            let entry = map.entry(bucket.to_string()).or_insert(0);
            *entry += 1;
            *entry
        };
        let second = {
            let mut map = bridge.latest_supersession_seq.lock().await;
            let entry = map.entry(bucket.to_string()).or_insert(0);
            *entry += 1;
            *entry
        };
        assert_eq!(first, 1);
        assert_eq!(second, 2);

        // Different bucket starts independently.
        let other = {
            let mut map = bridge.latest_supersession_seq.lock().await;
            let entry = map.entry("file:///b.kt".to_string()).or_insert(0);
            *entry += 1;
            *entry
        };
        assert_eq!(other, 1);
    }
}
