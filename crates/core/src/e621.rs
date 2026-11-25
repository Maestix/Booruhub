//! The few calls of e621's API the app needs. https://e621.net/help/api
//!
//! Cloudflare ties `cf_clearance` to the cookie *and* the User-Agent that
//! solved the challenge in the app's WebView, so the client sends that agent,
//! and the app hands over the WebView's cookies with every call.

use std::collections::HashMap;
use std::sync::RwLock;
use std::time::Duration;

use reqwest::{Client as Http, RequestBuilder};
use reqwest::{Method, StatusCode, header};
use serde_json::Value;

use crate::model::{Comment, Post, PostSet, TagHint};
use crate::parse;

pub const BASE: &str = "https://e621.net";

#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// Cloudflare wants a browser to prove itself: the app opens its WebView.
    #[error("Cloudflare просит проверку")]
    Cloudflare,
    /// e621 answered, and said no.
    #[error("{0}")]
    Api(String),
    /// No answer at all.
    #[error("{0}")]
    Net(String),
}

impl From<reqwest::Error> for Error {
    fn from(e: reqwest::Error) -> Self {
        let text = format!("{e:?}");
        Error::Net(if text.contains("dns error") || text.contains("failed to lookup") {
            "Нет сети: e621.net не находится".into()
        } else if e.is_timeout() || e.is_connect() {
            "e621 не отвечает — проверьте сеть".into()
        } else {
            e.to_string()
        })
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// Cloudflare's "prove you are a browser" page instead of an answer.
pub(crate) fn is_challenge(resp: &reqwest::Response) -> bool {
    matches!(resp.status().as_u16(), 403 | 429 | 503) && {
        let h = resp.headers();
        let get = |k: &str| h.get(k).and_then(|v| v.to_str().ok()).unwrap_or("").to_ascii_lowercase();
        get("cf-mitigated") == "challenge" || (get("server").contains("cloudflare") && get("content-type").contains("text/html"))
    }
}

pub struct Client {
    http: Http,
    agent: String,
    auth: RwLock<Option<(String, String)>>,
    cookies: RwLock<String>,
}

impl Client {
    /// `proxy` is the phone's HTTP proxy ("host:port") when one is set.
    pub fn new(agent: &str, proxy: Option<&str>) -> Self {
        let mut b = Http::builder()
            .connect_timeout(Duration::from_secs(8))
            // Files may take minutes; what must not happen is silence.
            .read_timeout(Duration::from_secs(30))
            .gzip(true)
            .brotli(true);
        if let Some(p) = proxy.filter(|p| !p.is_empty())
            && let Ok(p) = reqwest::Proxy::all(format!("http://{p}"))
        {
            b = b.proxy(p);
        }
        Self {
            http: b.build().expect("an HTTP client with rustls builds"),
            agent: agent.to_owned(),
            auth: RwLock::new(None),
            cookies: RwLock::new(String::new()),
        }
    }

    pub fn set_auth(&self, auth: Option<(String, String)>) {
        *self.auth.write().unwrap() = auth;
    }

    pub fn set_cookies(&self, cookies: &str) {
        *self.cookies.write().unwrap() = cookies.to_owned();
    }

    fn request(&self, method: Method, path: &str, auth: Option<&(String, String)>) -> RequestBuilder {
        let mut r = self
            .http
            .request(method, format!("{BASE}{path}"))
            // An API answer, unlike a file, has no reason to take long.
            .timeout(Duration::from_secs(40))
            .header(header::USER_AGENT, &self.agent)
            .header(header::ACCEPT, "application/json");
        let cookies = self.cookies.read().unwrap();
        if !cookies.is_empty() {
            r = r.header(header::COOKIE, cookies.as_str());
        }
        let own = self.auth.read().unwrap().clone();
        if let Some((user, key)) = auth.cloned().or(own) {
            r = r.basic_auth(user, Some(key));
        }
        r
    }

    /// The HTTP client the other boorus share.
    pub(crate) fn http(&self) -> &Http {
        &self.http
    }

    /// A file, from byte `from` on when a ".part" already holds the rest.
    /// e621's own servers get the WebView's agent and cookies; another
    /// booru's CDN gets the app's plain agent, since a browser's agent
    /// without that site's Cloudflare cookie is what Cloudflare stops.
    pub fn file(&self, url: &str, from: u64) -> RequestBuilder {
        let host = reqwest::Url::parse(url).ok().and_then(|u| u.host_str().map(str::to_owned)).unwrap_or_default();
        let ours = host == "e621.net" || host.ends_with(".e621.net");
        let mut r = self.http.get(url).header(header::USER_AGENT, if ours { self.agent.as_str() } else { crate::boorus::APP_AGENT });
        // Gelbooru's CDN sends a file only to a request linked from its site.
        if let Some(referer) = crate::boorus::referer_for(&host) {
            r = r.header(header::REFERER, referer);
        }
        let cookies = self.cookies.read().unwrap();
        if ours && !cookies.is_empty() {
            r = r.header(header::COOKIE, cookies.as_str());
        }
        if from > 0 {
            r = r.header(header::RANGE, format!("bytes={from}-"));
        }
        r
    }

    async fn run(&self, r: RequestBuilder) -> Result<String> {
        let resp = r.send().await?;
        let status = resp.status();
        if is_challenge(&resp) {
            return Err(Error::Cloudflare);
        }
        let body = resp.text().await?;
        if status == StatusCode::UNAUTHORIZED {
            return Err(Error::Api("e621 не принял логин или API-ключ".into()));
        }
        if status == StatusCode::FORBIDDEN && body.contains("InvalidAuthenticityToken") {
            return Err(Error::Api("e621 не увидел вход — войдите в аккаунт заново".into()));
        }
        if !status.is_success() {
            let reason = serde_json::from_str::<Value>(&body)
                .ok()
                .and_then(|v| v.get("message").or(v.get("reason")).and_then(Value::as_str).map(str::to_owned))
                .filter(|r| !r.is_empty());
            return Err(Error::Api(match reason {
                Some(r) => format!("e621 ответил {}: {r}", status.as_u16()),
                None => format!("e621 ответил {}", status.as_u16()),
            }));
        }
        Ok(body)
    }

    pub(crate) async fn get(&self, path: &str, query: &[(&str, String)]) -> Result<String> {
        self.run(self.request(Method::GET, path, None).query(query)).await
    }

    pub async fn posts(&self, tags: &str, page: u32, limit: u32) -> Result<Vec<Post>> {
        let body = self.get("/posts.json", &[("tags", tags.trim().into()), ("page", page.to_string()), ("limit", limit.to_string())]).await?;
        Ok(parse::posts(&body))
    }

    /// Fails unless e621 accepts the pair: a signed request with a wrong key gets 401.
    pub async fn check(&self, user: &str, key: &str) -> Result<()> {
        let pair = (user.to_owned(), key.to_owned());
        self.run(self.request(Method::GET, "/posts.json", Some(&pair)).query(&[("limit", "1")])).await.map(drop)
    }

    pub async fn favorite(&self, id: u64, on: bool) -> Result<()> {
        let r = if on {
            self.request(Method::POST, "/favorites.json", None).form(&[("post_id", id.to_string())])
        } else {
            self.request(Method::DELETE, &format!("/favorites/{id}.json"), None)
        };
        self.run(r).await.map(drop)
    }

    /// A post's comments, oldest first; hidden ones left out.
    pub async fn comments(&self, post: u64) -> Result<Vec<Comment>> {
        // e621 answers newest first, a page at a time: read pages until one
        // comes back short, so the start of a long thread is there too.
        const PAGE: usize = 320;
        let mut list = Vec::new();
        for page in 1..=10 {
            let body = self
                .get(
                    "/comments.json",
                    &[
                        ("group_by", "comment".into()),
                        ("search[post_id]", post.to_string()),
                        ("limit", PAGE.to_string()),
                        ("page", page.to_string()),
                    ],
                )
                .await?;
            let got = serde_json::from_str::<Value>(&body).map(|v| parse::array_in(v, "comments")).unwrap_or_default();
            let short = got.len() < PAGE;
            list.extend(got);
            if short {
                break;
            }
        }
        let mut out: Vec<Comment> = list
            .iter()
            .filter(|o| !o.get("is_hidden").and_then(Value::as_bool).unwrap_or(false))
            .filter_map(|o| {
                Some(Comment {
                    id: o.get("id")?.as_u64()?,
                    user: o.get("creator_id").and_then(Value::as_u64).unwrap_or(0),
                    author: o.get("creator_name").and_then(Value::as_str).unwrap_or("?").to_owned(),
                    body: o.get("body").and_then(Value::as_str).unwrap_or_default().to_owned(),
                    score: o.get("score").and_then(Value::as_i64).unwrap_or(0),
                    at: o.get("created_at").and_then(Value::as_str).unwrap_or_default().to_owned(),
                    avatar: None,
                })
            })
            .collect();
        out.sort_by_key(|c| c.id);
        // A comment posted between two pages shifts the rest by one.
        out.dedup_by_key(|c| c.id);
        Ok(out)
    }

    /// The posts people picked as their avatars, by account id; accounts
    /// without one, or whose post is gone, are left out.
    pub async fn avatars(&self, users: &[u64]) -> Result<HashMap<u64, Post>> {
        let mut wants: HashMap<u64, Vec<u64>> = HashMap::new();
        let mut ids: Vec<u64> = users.iter().copied().filter(|&u| u > 0).collect();
        ids.sort_unstable();
        ids.dedup();
        for chunk in ids.chunks(100) {
            let list = chunk.iter().map(u64::to_string).collect::<Vec<_>>().join(",");
            let body = self.get("/users.json", &[("search[id]", list), ("limit", "100".into())]).await?;
            for u in serde_json::from_str::<Value>(&body).map(|v| parse::array_in(v, "users")).unwrap_or_default() {
                if let (Some(user), Some(post)) = (u.get("id").and_then(Value::as_u64), u.get("avatar_id").and_then(Value::as_u64)) {
                    wants.entry(post).or_default().push(user);
                }
            }
        }
        let posts: Vec<u64> = wants.keys().copied().collect();
        let mut out = HashMap::new();
        for chunk in posts.chunks(100) {
            let list = chunk.iter().map(u64::to_string).collect::<Vec<_>>().join(",");
            for p in self.posts(&format!("id:{list}"), 1, 100).await? {
                for &user in wants.get(&p.id).into_iter().flatten() {
                    out.insert(user, p.clone());
                }
            }
        }
        Ok(out)
    }

    pub async fn autocomplete(&self, prefix: &str) -> Result<Vec<TagHint>> {
        let body = self.get("/tags/autocomplete.json", &[("search[name_matches]", prefix.into()), ("expiry", "7".into())]).await?;
        let list = serde_json::from_str::<Value>(&body).map(|v| parse::array_in(v, "tags")).unwrap_or_default();
        Ok(list
            .iter()
            .map(|o| TagHint {
                name: o.get("name").and_then(Value::as_str).unwrap_or_default().into(),
                count: o.get("post_count").and_then(Value::as_i64).unwrap_or(0),
                category: o.get("category").and_then(Value::as_i64).unwrap_or(0),
            })
            .collect())
    }

    /// Categories for tags: the new posts.json gives them flat.
    pub async fn categories(&self, names: &[String]) -> Result<HashMap<String, i64>> {
        let mut out = HashMap::new();
        for chunk in names.chunks(100) {
            let body = self.get("/tags.json", &[("search[name]", chunk.join(",")), ("limit", "320".into())]).await?;
            if let Ok(v) = serde_json::from_str::<Value>(&body) {
                for o in parse::array_in(v, "tags") {
                    if let Some(name) = o.get("name").and_then(Value::as_str) {
                        out.insert(name.to_owned(), o.get("category").and_then(Value::as_i64).unwrap_or(0));
                    }
                }
            }
        }
        Ok(out)
    }

    /// An account's sets, newest first.
    pub async fn sets(&self, user: &str) -> Result<Vec<PostSet>> {
        let body = self.get("/post_sets.json", &[("search[creator_name]", user.into()), ("limit", "100".into())]).await?;
        let list = serde_json::from_str::<Value>(&body).map(|v| parse::array_in(v, "post_sets")).unwrap_or_default();
        Ok(list
            .iter()
            .filter_map(|o| {
                Some(PostSet {
                    id: o.get("id")?.as_u64()?,
                    name: o.get("name").and_then(Value::as_str).unwrap_or_default().replace('_', " "),
                    short: o.get("shortname").and_then(Value::as_str).unwrap_or_default().to_owned(),
                    posts: o.get("post_ids").and_then(Value::as_array).map(|a| a.iter().filter_map(Value::as_u64).collect()).unwrap_or_default(),
                })
            })
            .collect())
    }

    /// Puts a post into a set, or takes it out.
    pub async fn set_post(&self, set: u64, post: u64, on: bool) -> Result<()> {
        let path = format!("/post_sets/{set}/{}.json", if on { "add_posts" } else { "remove_posts" });
        self.run(self.request(Method::POST, &path, None).form(&[("post_ids[]", post.to_string())])).await.map(drop)
    }

    /// The signed-in account's blacklist, one rule per line, as the site keeps it.
    pub async fn account_blacklist(&self, user: &str) -> Result<Vec<String>> {
        let body = self.get("/users.json", &[("search[name_matches]", user.into()), ("limit", "1".into())]).await?;
        let found = serde_json::from_str::<Value>(&body).map(|v| parse::array_in(v, "users")).unwrap_or_default();
        let id = found
            .first()
            .and_then(|u| u.get("id"))
            .and_then(Value::as_u64)
            .ok_or_else(|| Error::Api(format!("e621 не нашёл пользователя {user}")))?;
        let me: Value = serde_json::from_str(&self.get(&format!("/users/{id}.json"), &[]).await?).unwrap_or(Value::Null);
        Ok(me
            .get("blacklisted_tags")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .lines()
            .map(str::trim)
            .filter(|l| !l.is_empty())
            .map(str::to_owned)
            .collect())
    }
}
