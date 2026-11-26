//! One door into the core: a JSON request in, a JSON answer out.
//!
//! A request is `{"m": "<method>", ...arguments, "cookies": "<WebView's>",
//! "rid": <number>}`. The answer is `{"ok": <value>}` or `{"err": {"kind":
//! "...", "msg": "..."}}`, where kind is `cloudflare` (open the WebView), `api`,
//! `net`, `bad` (the request itself was wrong) or `cancelled` ([`Core::cancel`]
//! was called with its rid while it ran). The JNI side passes strings and nothing else,
//! so the door stays two functions wide however much the core grows.

use crate::sync::{Unpoisoned, UnpoisonedRw};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};

use tokio::sync::oneshot;

use serde_json::{Value, json};

use crate::blacklist::Rules;
use crate::boorus::{Boorus, Site};
use crate::download::Downloads;
use crate::e621::{Client, Error};
use crate::model::{Coll, Post, Saved};
use crate::now_ms;
use crate::store::Files;

/// How many colours collections cycle through (the app's `COLL_COLORS`).
const COLL_COLORS: u32 = 6;

/// A watched query checked this recently is left alone.
const FRESH_FOR_MS: i64 = 5 * 60_000;

pub struct Core {
    /// Every call blocks its JNI thread on this runtime; the network work
    /// itself is async, so it can be dropped when cancelled.
    rt: tokio::runtime::Runtime,
    /// Requests in flight by rid, so `cancel` can stop them.
    running: Mutex<HashMap<u64, oneshot::Sender<()>>>,
    e621: Arc<Client>,
    boorus: Boorus,
    downloads: Downloads,
    files: Files,
    rules: RwLock<Rules>,
    saved: Mutex<Vec<Saved>>,
    colls: Mutex<Vec<Coll>>,
}

enum Fail {
    E621(Error),
    Bad(String),
    Cancelled,
}

impl From<Error> for Fail {
    fn from(e: Error) -> Self {
        Fail::E621(e)
    }
}

type Answer = Result<Value, Fail>;

fn bad(what: &str) -> Fail {
    Fail::Bad(format!("в запросе нет «{what}»"))
}

/// `["user", "key"]` from the app; anything else is no login.
fn login_pair(v: &Value) -> Option<(String, String)> {
    let a = v.as_array()?;
    let (u, k) = (a.first()?.as_str()?, a.get(1)?.as_str()?);
    (!u.is_empty() && !k.is_empty()).then(|| (u.to_owned(), k.to_owned()))
}

/// The booru a call is for; e621 when the app does not say.
fn site_arg(r: &Value) -> Result<Site, Fail> {
    match r.get("site").and_then(Value::as_str) {
        None | Some("") => Ok(Site::E621),
        Some(code) => Site::parse(code).ok_or_else(|| Fail::Bad(format!("нет такого сайта: {code}"))),
    }
}

fn strings(r: &Value, k: &str) -> Vec<String> {
    serde_json::from_value(r.get(k).cloned().unwrap_or_default()).unwrap_or_default()
}

fn str_arg<'a>(r: &'a Value, k: &str) -> Result<&'a str, Fail> {
    r.get(k).and_then(Value::as_str).ok_or_else(|| bad(k))
}

fn int_arg(r: &Value, k: &str) -> Result<i64, Fail> {
    r.get(k).and_then(Value::as_i64).ok_or_else(|| bad(k))
}

fn post_arg(r: &Value, k: &str) -> Result<Option<Post>, Fail> {
    match r.get(k) {
        None | Some(Value::Null) => Ok(None),
        Some(v) => serde_json::from_value(v.clone()).map(Some).map_err(|e| Fail::Bad(e.to_string())),
    }
}

fn to_json<T: serde::Serialize>(v: &T) -> Value {
    serde_json::to_value(v).unwrap_or(Value::Null)
}

impl Core {
    /// `dir` is where saved queries and collections live; `agent` the
    /// WebView's User-Agent; `proxy` the phone's HTTP proxy, if any.
    pub fn new(dir: &Path, agent: &str, proxy: Option<&str>) -> Self {
        let files = Files::new(dir);
        let e621 = Arc::new(Client::new(agent, proxy));
        Self {
            rt: tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .thread_name("booruhub-core")
                .enable_all()
                .build()
                .expect("a tokio runtime starts"),
            running: Mutex::new(HashMap::new()),
            boorus: Boorus::new(e621.clone()),
            e621,
            downloads: Downloads::default(),
            saved: Mutex::new(files.load("saved")),
            colls: Mutex::new(files.load("collections")),
            files,
            rules: RwLock::new(Rules::default()),
        }
    }

    /// Stops the request with this rid, if it still runs: its call answers
    /// `cancelled` at once and the HTTP request is dropped mid-flight.
    pub fn cancel(&self, rid: u64) {
        if let Some(stop) = self.running.guard().remove(&rid) {
            let _ = stop.send(());
        }
    }

    pub fn call(&self, request: &str) -> String {
        let answer = match serde_json::from_str::<Value>(request) {
            Ok(r) => {
                if let Some(c) = r.get("cookies").and_then(Value::as_str) {
                    self.e621.set_cookies(c);
                }
                // The app sends its login with every request, so the core
                // can never hold a stale one: null signs out.
                if let Some(login) = r.get("login") {
                    self.e621.set_auth(login_pair(login));
                }
                if let Some(logins) = r.get("logins") {
                    self.boorus.set_logins(logins);
                }
                let rid = r.get("rid").and_then(Value::as_u64);
                let stop = rid.map(|id| {
                    let (tx, rx) = oneshot::channel();
                    self.running.guard().insert(id, tx);
                    rx
                });
                let answer = self.rt.block_on(async {
                    let stopped = async {
                        match stop {
                            // Fires on cancel; a dropped sender only means it finished.
                            Some(rx) => {
                                if rx.await.is_err() {
                                    std::future::pending::<()>().await
                                }
                            }
                            None => std::future::pending::<()>().await,
                        }
                    };
                    tokio::select! {
                        a = self.dispatch(&r) => a,
                        _ = stopped => Err(Fail::Cancelled),
                    }
                });
                if let Some(id) = rid {
                    self.running.guard().remove(&id);
                }
                answer
            }
            Err(e) => Err(Fail::Bad(e.to_string())),
        };
        match answer {
            Ok(v) => json!({ "ok": v }),
            Err(Fail::E621(e)) => {
                let kind = match e {
                    Error::Cloudflare => "cloudflare",
                    Error::Api(_) => "api",
                    Error::Net(_) => "net",
                };
                json!({ "err": { "kind": kind, "msg": e.to_string() } })
            }
            Err(Fail::Bad(msg)) => json!({ "err": { "kind": "bad", "msg": msg } }),
            Err(Fail::Cancelled) => json!({ "err": { "kind": "cancelled", "msg": "отменено" } }),
        }
        .to_string()
    }

    async fn dispatch(&self, r: &Value) -> Answer {
        match str_arg(r, "m")? {
            // --- e621 ---------------------------------------------------------
            "auth" => {
                let pair = match (r.get("user").and_then(Value::as_str), r.get("key").and_then(Value::as_str)) {
                    (Some(u), Some(k)) if !u.is_empty() && !k.is_empty() => Some((u.to_owned(), k.to_owned())),
                    _ => None,
                };
                self.e621.set_auth(pair);
                Ok(Value::Null)
            }
            "check" => {
                self.boorus.check(site_arg(r)?, str_arg(r, "user")?, str_arg(r, "key")?).await?;
                Ok(Value::Null)
            }
            "posts" | "posts_mix" => {
                let page = r.get("page").and_then(Value::as_u64).unwrap_or(1) as u32;
                let limit = r.get("limit").and_then(Value::as_u64).unwrap_or(60) as u32;
                let tags = str_arg(r, "tags")?;
                let exclude = strings(r, "exclude");
                let posts = if str_arg(r, "m")? == "posts_mix" {
                    let sites: Vec<Site> = strings(r, "sites").iter().filter_map(|c| Site::parse(c)).collect();
                    self.boorus.mix(&sites, tags, &exclude, page, limit).await?
                } else {
                    self.boorus.posts(site_arg(r)?, tags, &exclude, page, limit).await?
                };
                Ok(self.marked(posts))
            }
            "favorite" => {
                let on = r.get("on").and_then(Value::as_bool).unwrap_or(true);
                self.boorus.favorite(site_arg(r)?, int_arg(r, "id")? as u64, on).await?;
                Ok(Value::Null)
            }
            "comments" => Ok(to_json(&self.boorus.comments(site_arg(r)?, int_arg(r, "post")? as u64).await?)),
            "avatars" => {
                let users: Vec<u64> = serde_json::from_value(r.get("users").cloned().unwrap_or_default()).map_err(|_| bad("users"))?;
                let mut found = self.e621.avatars(&users).await?;
                self.mark(found.values_mut());
                // JSON keys are strings: the app reads the ids back from them.
                Ok(Value::Object(found.into_iter().map(|(u, p)| (u.to_string(), to_json(&p))).collect()))
            }
            "autocomplete" => Ok(to_json(&self.boorus.autocomplete(site_arg(r)?, str_arg(r, "prefix")?).await?)),
            "categories" => {
                let names: Vec<String> = serde_json::from_value(r.get("names").cloned().unwrap_or_default()).map_err(|_| bad("names"))?;
                Ok(to_json(&self.boorus.categories(site_arg(r)?, &names).await?))
            }
            "account_blacklist" => Ok(to_json(&self.e621.account_blacklist(str_arg(r, "user")?).await?)),

            // --- around a post ----------------------------------------------------
            "family" => {
                let parent = r.get("parent").and_then(Value::as_u64).unwrap_or(0);
                let posts = self.boorus.family(site_arg(r)?, int_arg(r, "post")? as u64, parent).await?;
                Ok(self.marked(posts))
            }
            "pools" => {
                let ids: Vec<u64> = serde_json::from_value(r.get("ids").cloned().unwrap_or_default()).unwrap_or_default();
                Ok(to_json(&self.boorus.pools(site_arg(r)?, int_arg(r, "post")? as u64, &ids).await?))
            }
            "by_ids" => {
                let ids: Vec<u64> = serde_json::from_value(r.get("ids").cloned().unwrap_or_default()).map_err(|_| bad("ids"))?;
                let posts = self.boorus.by_ids(site_arg(r)?, &ids).await?;
                Ok(self.marked(posts))
            }
            "notes" => Ok(to_json(&self.boorus.notes(site_arg(r)?, int_arg(r, "post")? as u64).await?)),
            "wiki" => Ok(to_json(&self.boorus.wiki(site_arg(r)?, str_arg(r, "tag")?).await?)),
            "popular" => {
                let page = r.get("page").and_then(Value::as_u64).unwrap_or(1) as u32;
                let posts = self.boorus.popular(site_arg(r)?, r.get("scale").and_then(Value::as_str).unwrap_or("day"), page).await?;
                Ok(self.marked(posts))
            }
            "sets" => Ok(to_json(&self.e621.sets(str_arg(r, "user")?).await?)),
            "set_post" => {
                let on = r.get("on").and_then(Value::as_bool).unwrap_or(true);
                self.e621.set_post(int_arg(r, "set")? as u64, int_arg(r, "post")? as u64, on).await?;
                Ok(Value::Null)
            }

            // --- downloads ------------------------------------------------------
            "dl_start" => {
                let part = PathBuf::from(str_arg(r, "part")?);
                self.downloads.start(self.e621.clone(), int_arg(r, "id")? as u64, str_arg(r, "url")?.to_owned(), part);
                Ok(Value::Null)
            }
            "dl_pause" => {
                self.downloads.pause(int_arg(r, "id")? as u64);
                Ok(Value::Null)
            }
            "dl_remove" => {
                let part = r.get("part").and_then(Value::as_str).map(PathBuf::from);
                self.downloads.remove(int_arg(r, "id")? as u64, part);
                Ok(Value::Null)
            }
            "dl_status" => Ok(to_json(&self.downloads.status())),

            // --- the blacklist --------------------------------------------------
            "blacklist" => {
                let lines: Vec<String> = serde_json::from_value(r.get("lines").cloned().unwrap_or_default()).map_err(|_| bad("lines"))?;
                *self.rules.writer() = Rules::parse(&lines);
                Ok(Value::Null)
            }
            // Every post the app has on screen, marked again in one call.
            "blocked" => {
                let posts: Vec<Post> = serde_json::from_value(r.get("posts").cloned().unwrap_or_default()).map_err(|_| bad("posts"))?;
                let rules = self.rules.reader();
                Ok(posts.iter().map(|p| Value::Bool(rules.blocks(p))).collect())
            }

            // --- saved queries --------------------------------------------------
            "saved_list" => Ok(self.saved_json()),
            "saved_import" => {
                if !self.files.exists("saved") {
                    let list: Vec<Saved> = serde_json::from_value(r.get("list").cloned().unwrap_or_default()).unwrap_or_default();
                    self.write_saved(|s| *s = list);
                }
                Ok(self.saved_json())
            }
            "saved_replace" => {
                let list: Vec<Saved> = serde_json::from_value(r.get("list").cloned().unwrap_or_default()).map_err(|_| bad("list"))?;
                self.write_saved(|s| *s = list);
                Ok(self.saved_json())
            }
            "saved_add" => {
                let q = Saved {
                    id: now_ms(),
                    name: str_arg(r, "name")?.to_owned(),
                    query: str_arg(r, "query")?.to_owned(),
                    site: site_arg(r)?.code().to_owned(),
                    watch: true,
                    seen: 0,
                    fresh: 0,
                    checked: 0,
                };
                self.write_saved(|s| s.insert(0, q));
                Ok(self.saved_json())
            }
            "saved_remove" => {
                let id = int_arg(r, "id")?;
                self.write_saved(|s| s.retain(|q| q.id != id));
                Ok(self.saved_json())
            }
            "saved_watch" => {
                let id = int_arg(r, "id")?;
                let on = r.get("on").and_then(Value::as_bool).unwrap_or(true);
                self.write_saved(|s| s.iter_mut().filter(|q| q.id == id).for_each(|q| q.watch = on));
                Ok(self.saved_json())
            }
            "saved_check" => {
                let only = r.get("only").and_then(Value::as_i64);
                self.check_saved(only, r.get("rating").and_then(Value::as_str).unwrap_or("")).await;
                Ok(self.saved_json())
            }
            "saved_open" => {
                let id = int_arg(r, "id")?;
                let rating = r.get("rating").and_then(Value::as_str).unwrap_or("");
                let q = self.saved.guard().iter().find(|q| q.id == id).cloned().ok_or_else(|| bad("id"))?;
                let newest = self.newest(&q, rating).await?;
                if newest > 0 {
                    self.write_saved(|s| {
                        s.iter_mut().filter(|q| q.id == id).for_each(|q| {
                            q.seen = newest;
                            q.fresh = 0;
                            q.checked = now_ms();
                        })
                    });
                }
                Ok(self.saved_json())
            }

            // --- collections ----------------------------------------------------
            "colls_list" => Ok(self.colls_json()),
            "colls_import" => {
                if !self.files.exists("collections") {
                    let list: Vec<Coll> = serde_json::from_value(r.get("list").cloned().unwrap_or_default()).unwrap_or_default();
                    self.write_colls(|c| *c = list);
                }
                Ok(self.colls_json())
            }
            "colls_replace" => {
                let list: Vec<Coll> = serde_json::from_value(r.get("list").cloned().unwrap_or_default()).map_err(|_| bad("list"))?;
                self.write_colls(|c| *c = list);
                Ok(self.colls_json())
            }
            "coll_add" => {
                let first = post_arg(r, "post")?;
                let name = str_arg(r, "name")?.to_owned();
                self.write_colls(|c| {
                    let color = c.len() as u32 % COLL_COLORS;
                    c.push(Coll { id: now_ms(), name, color, updated: now_ms(), posts: first.into_iter().collect() });
                });
                Ok(self.colls_json())
            }
            "coll_remove" => {
                let id = int_arg(r, "id")?;
                self.write_colls(|c| c.retain(|x| x.id != id));
                Ok(self.colls_json())
            }
            "coll_toggle" => {
                let id = int_arg(r, "id")?;
                let mut p = post_arg(r, "post")?.ok_or_else(|| bad("post"))?;
                p.blocked = false;
                self.write_colls(|c| {
                    if let Some(x) = c.iter_mut().find(|x| x.id == id) {
                        let same = |q: &Post| q.id == p.id && q.site == p.site;
                        if x.posts.iter().any(same) {
                            x.posts.retain(|q| !same(q));
                        } else {
                            x.posts.insert(0, p);
                        }
                        x.updated = now_ms();
                    }
                });
                Ok(self.colls_json())
            }
            "coll_put" => {
                let id = int_arg(r, "id")?;
                let on = r.get("on").and_then(Value::as_bool).unwrap_or(true);
                let posts: Vec<Post> = serde_json::from_value(r.get("posts").cloned().unwrap_or_default()).map_err(|_| bad("posts"))?;
                self.write_colls(|c| {
                    if let Some(x) = c.iter_mut().find(|x| x.id == id) {
                        for mut p in posts.into_iter().rev() {
                            let same = |q: &Post| q.id == p.id && q.site == p.site;
                            let inside = x.posts.iter().any(same);
                            if on && !inside {
                                p.blocked = false;
                                x.posts.insert(0, p);
                            } else if !on && inside {
                                x.posts.retain(|q| !same(q));
                            }
                        }
                        x.updated = now_ms();
                    }
                });
                Ok(self.colls_json())
            }
            // Stands in for a slow network call in the tests.
            #[cfg(test)]
            "sleep" => {
                tokio::time::sleep(std::time::Duration::from_millis(int_arg(r, "ms")? as u64)).await;
                Ok(Value::Null)
            }
            other => Err(Fail::Bad(format!("нет такого метода: {other}"))),
        }
    }

    /// Puts the blacklist's mark on each post.
    fn mark<'a>(&self, posts: impl IntoIterator<Item = &'a mut Post>) {
        let rules = self.rules.reader();
        for p in posts {
            p.blocked = rules.blocks(p);
        }
    }

    /// Posts as the app gets them: the blacklist's mark on each.
    fn marked(&self, mut posts: Vec<Post>) -> Value {
        self.mark(&mut posts);
        to_json(&posts)
    }

    // --- saved queries ------------------------------------------------------------

    fn saved_json(&self) -> Value {
        to_json(&*self.saved.guard())
    }

    fn write_saved(&self, change: impl FnOnce(&mut Vec<Saved>)) {
        let mut s = self.saved.guard();
        change(&mut s);
        self.files.save("saved", &*s);
    }

    /// The query a watch asks: the saved one with the rating filter, newest
    /// first. A sort typed into it (`order:score`) is left out, or the "newest"
    /// post would be the best one and new posts would go uncounted.
    fn base(q: &Saved, rating: &str) -> String {
        q.query.split_whitespace().filter(|t| !t.to_ascii_lowercase().starts_with("order:")).chain(rating.split_whitespace()).collect::<Vec<_>>().join(" ")
    }

    fn site_of(q: &Saved) -> Site {
        Site::parse(&q.site).unwrap_or(Site::E621)
    }

    async fn newest(&self, q: &Saved, rating: &str) -> Result<u64, Error> {
        let posts = self.boorus.posts(Self::site_of(q), &Self::base(q, rating), &[], 1, 1).await?;
        Ok(posts.first().map_or(0, |p| p.id))
    }

    async fn check_one(&self, q: &Saved, rating: &str) -> Option<Saved> {
        let mut next = q.clone();
        if q.seen == 0 {
            next.seen = self.newest(q, rating).await.ok()?;
            next.fresh = 0;
        } else {
            next.fresh = match Self::site_of(q) {
                Site::E621 => {
                    let tags = format!("{} id:>{}", Self::base(q, rating), q.seen);
                    self.e621.posts(&tags, 1, 100).await.ok()?.len() as u32
                }
                // Danbooru counts id:> against its two tags: count the newest page here instead.
                site => {
                    let posts = self.boorus.posts(site, &Self::base(q, rating), &[], 1, 100).await.ok()?;
                    posts.iter().filter(|p| p.id > q.seen).count() as u32
                }
            };
        }
        next.checked = now_ms();
        Some(next)
    }

    /// Counts each watched query's posts newer than the last one seen (up to
    /// 100). Queries checked in the last few minutes are left alone unless
    /// asked for by id, and two run at a time: e621 asks clients to keep near
    /// two requests a second.
    async fn check_saved(&self, only: Option<i64>, rating: &str) {
        let now = now_ms();
        let due: Vec<Saved> =
            self.saved.lock().unwrap().iter().filter(|q| q.watch && only.map_or(now - q.checked > FRESH_FOR_MS, |id| q.id == id)).cloned().collect();
        for pair in due.chunks(2) {
            let results: Vec<Saved> = futures_util::future::join_all(pair.iter().map(|q| self.check_one(q, rating))).await.into_iter().flatten().collect();
            self.write_saved(|s| {
                for next in results {
                    if let Some(q) = s.iter_mut().find(|q| q.id == next.id) {
                        // Keep what the person changed meanwhile; take the counts.
                        q.seen = next.seen;
                        q.fresh = next.fresh;
                        q.checked = next.checked;
                    }
                }
            });
        }
    }

    // --- collections --------------------------------------------------------------

    fn colls_json(&self) -> Value {
        to_json(&*self.colls.guard())
    }

    fn write_colls(&self, change: impl FnOnce(&mut Vec<Coll>)) {
        let mut c = self.colls.guard();
        change(&mut c);
        self.files.save("collections", &*c);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn core() -> (Core, std::path::PathBuf) {
        // Tests run in parallel: each gets a folder of its own.
        static NEXT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
        let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!("booruhub-rpc-{}-{n}", std::process::id()));
        (Core::new(&dir, "test", None), dir)
    }

    fn ok(answer: String) -> Value {
        let v: Value = serde_json::from_str(&answer).unwrap();
        v.get("ok").cloned().unwrap_or_else(|| panic!("not ok: {answer}"))
    }

    #[test]
    fn a_watch_asks_newest_first() {
        let q = |query: &str| serde_json::from_value::<Saved>(json!({ "id": 1, "name": "q", "query": query })).unwrap();
        assert_eq!(Core::base(&q("fox order:score  solo"), "-rating:e"), "fox solo -rating:e");
        assert_eq!(Core::base(&q(""), ""), "");
    }

    #[test]
    fn collections_live_on_disk() {
        let (c, dir) = core();
        let post = json!({ "id": 7, "rating": "s", "tags": { "general": ["fox"] } });
        let list = ok(c.call(&json!({ "m": "coll_add", "name": "Foxes", "post": post }).to_string()));
        let id = list[0]["id"].as_i64().unwrap();
        assert_eq!(list[0]["posts"][0]["id"], 7);
        let list = ok(c.call(&json!({ "m": "coll_toggle", "id": id, "post": post }).to_string()));
        assert_eq!(list[0]["posts"].as_array().unwrap().len(), 0);

        // A second core over the same folder sees the same collection.
        let again = Core::new(&dir, "test", None);
        assert_eq!(ok(again.call(r#"{"m":"colls_list"}"#))[0]["name"], "Foxes");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn import_happens_once() {
        let (c, dir) = core();
        let one = json!([{ "id": 1, "name": "a", "query": "fox" }]);
        assert_eq!(ok(c.call(&json!({ "m": "saved_import", "list": one }).to_string()))[0]["query"], "fox");
        let two = json!([{ "id": 2, "name": "b", "query": "wolf" }]);
        assert_eq!(ok(c.call(&json!({ "m": "saved_import", "list": two }).to_string()))[0]["query"], "fox");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_cancelled_call_answers_at_once() {
        let (c, dir) = core();
        let started = std::time::Instant::now();
        let answer = std::thread::scope(|s| {
            let call = s.spawn(|| c.call(r#"{"m":"sleep","ms":10000,"rid":42}"#));
            std::thread::sleep(std::time::Duration::from_millis(100));
            c.cancel(42);
            call.join().unwrap()
        });
        let v: Value = serde_json::from_str(&answer).unwrap();
        assert_eq!(v["err"]["kind"], "cancelled");
        assert!(started.elapsed() < std::time::Duration::from_secs(2));
        // A call without a rid, or one not cancelled, is unaffected.
        assert!(ok(c.call(r#"{"m":"sleep","ms":10,"rid":43}"#)).is_null());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn errors_say_what_went_wrong() {
        let (c, dir) = core();
        let v: Value = serde_json::from_str(&c.call(r#"{"m":"nope"}"#)).unwrap();
        assert_eq!(v["err"]["kind"], "bad");
        let _ = std::fs::remove_dir_all(dir);
    }
}
