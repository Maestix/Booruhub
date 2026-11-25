//! The download queue: two files at a time, each growing in a ".part" file the
//! app names, resumed with a Range request after a pause. The app polls
//! [`Downloads::status`] while something runs and, once a file is done, moves
//! it into the phone's gallery (that part is Android's).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use serde::Serialize;
use tokio::io::AsyncWriteExt;
use tokio::sync::Semaphore;
use tokio::task::AbortHandle;

use crate::e621::{Client, Error};

/// How many files download at once.
const PARALLEL: usize = 2;

#[derive(Debug, Clone, Serialize)]
pub struct Status {
    pub id: u64,
    /// queued, running, paused, done or failed.
    pub state: &'static str,
    pub done: u64,
    pub total: u64,
    /// Bytes a second, over the last half second.
    pub speed: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

struct Job {
    status: Arc<Mutex<Status>>,
    task: Option<AbortHandle>,
}

pub struct Downloads {
    jobs: Mutex<HashMap<u64, Job>>,
    slots: Arc<Semaphore>,
}

impl Default for Downloads {
    fn default() -> Self {
        Self { jobs: Mutex::new(HashMap::new()), slots: Arc::new(Semaphore::new(PARALLEL)) }
    }
}

impl Downloads {
    /// Starts (or resumes) a file. Must be called inside the core's runtime.
    pub fn start(&self, client: Arc<Client>, id: u64, url: String, part: PathBuf) {
        let mut jobs = self.jobs.lock().unwrap();
        if let Some(job) = jobs.get(&id)
            && matches!(job.status.lock().unwrap().state, "queued" | "running" | "done")
        {
            return;
        }
        let status = Arc::new(Mutex::new(Status { id, state: "queued", done: 0, total: 0, speed: 0, error: None }));
        let slots = self.slots.clone();
        let st = status.clone();
        let task = tokio::spawn(async move {
            let Ok(_slot) = slots.acquire_owned().await else { return };
            st.lock().unwrap().state = "running";
            let result = fetch(&client, &st, &url, &part).await;
            let mut s = st.lock().unwrap();
            s.speed = 0;
            match result {
                Ok(()) => s.state = "done",
                Err(e) => {
                    s.state = "failed";
                    s.error = Some(match e {
                        Error::Api(m) | Error::Net(m) => m,
                        Error::Cloudflare => e.to_string(),
                    });
                }
            }
        });
        jobs.insert(id, Job { status, task: Some(task.abort_handle()) });
    }

    /// Stops a file where it is; its ".part" stays for a resume.
    pub fn pause(&self, id: u64) {
        if let Some(job) = self.jobs.lock().unwrap().get_mut(&id) {
            if let Some(t) = job.task.take() {
                t.abort();
            }
            let mut s = job.status.lock().unwrap();
            if matches!(s.state, "queued" | "running") {
                s.state = "paused";
                s.speed = 0;
            }
        }
    }

    /// Forgets a file; with `delete`, its ".part" goes too.
    pub fn remove(&self, id: u64, part: Option<PathBuf>) {
        if let Some(job) = self.jobs.lock().unwrap().remove(&id)
            && let Some(t) = job.task
        {
            t.abort();
        }
        if let Some(p) = part {
            let _ = std::fs::remove_file(p);
        }
    }

    pub fn status(&self) -> Vec<Status> {
        self.jobs.lock().unwrap().values().map(|j| j.status.lock().unwrap().clone()).collect()
    }
}

async fn fetch(client: &Client, st: &Mutex<Status>, url: &str, part: &PathBuf) -> Result<(), Error> {
    if let Some(dir) = part.parent() {
        let _ = tokio::fs::create_dir_all(dir).await;
    }
    let have = tokio::fs::metadata(part).await.map(|m| m.len()).unwrap_or(0);
    let mut resp = client.file(url, have).send().await?;
    if crate::e621::is_challenge(&resp) {
        return Err(Error::Cloudflare);
    }
    let code = resp.status().as_u16();
    if code == 429 {
        return Err(Error::Api("ошибка 429 · лимит запросов".into()));
    }
    if !resp.status().is_success() {
        return Err(Error::Api(format!("ошибка {code}")));
    }
    // 206: the server picked up where the ".part" ends; 200: it sends it all.
    let resumed = code == 206;
    let start = if resumed { have } else { 0 };
    {
        let mut s = st.lock().unwrap();
        s.done = start;
        s.total = start + resp.content_length().unwrap_or(0);
    }
    let mut out = tokio::fs::OpenOptions::new()
        .create(true)
        .write(true)
        .append(resumed)
        .truncate(!resumed)
        .open(part)
        .await
        .map_err(|e| Error::Net(format!("файл: {e}")))?;
    let mut window = (Instant::now(), 0u64);
    while let Some(chunk) = resp.chunk().await? {
        out.write_all(&chunk).await.map_err(|e| Error::Net(format!("запись: {e}")))?;
        let mut s = st.lock().unwrap();
        s.done += chunk.len() as u64;
        window.1 += chunk.len() as u64;
        let dt = window.0.elapsed();
        if dt.as_millis() >= 500 {
            s.speed = (window.1 as f64 / dt.as_secs_f64()) as u64;
            window = (Instant::now(), 0);
        }
    }
    out.flush().await.map_err(|e| Error::Net(format!("запись: {e}")))?;
    let mut s = st.lock().unwrap();
    if s.total < s.done {
        s.total = s.done;
    }
    Ok(())
}
