//! Danbooru, Gelbooru and Rule34 next to e621: one set of calls for all four.
//!
//! The app speaks e621's tag language (`rating:s`, `order:hot`, `type:webm`,
//! `date:week`…); [`translate`] turns a query into each site's own. Their
//! answers become the same [`Post`] e621's do, with `site` saying where it is
//! from, ratings folded into e621's three (s, q, e) and tag categories into
//! e621's numbers.
//!
//! - Danbooru: open to all, two tags a search without Gold (ratings free);
//!   the login is a name and an API key, sent as HTTP basic auth.
//! - Gelbooru and Rule34: the old Gelbooru API, which by 2026 answers only
//!   with `user_id` and `api_key` in the query. No favourites through it.

use std::collections::{BTreeSet, HashMap};
use std::sync::{Arc, RwLock};
use std::time::Duration;

use reqwest::{Method, RequestBuilder, header};
use serde_json::{Map, Value};

use crate::e621::{Client, Error, Result, is_challenge};
use crate::model::{Comment, FLAT, Post, TagHint};

mod extras;

/// The agent for every site but e621: a browser's agent without that site's
/// Cloudflare cookie is what Cloudflare stops (Danbooru's CDN answers it 403).
/// How long the mixed feed waits for the other sites once one has answered.
const MIX_GRACE_SECS: u64 = 6;

pub const APP_AGENT: &str = concat!("booruhub/", env!("CARGO_PKG_VERSION"), " (Android)");

/// The site page a booru's file servers expect to be linked from: without
/// it, Gelbooru's CDN redirects to its gallery page instead of the file.
pub fn referer_for(host: &str) -> Option<&'static str> {
    if host == "gelbooru.com" || host.ends_with(".gelbooru.com") {
        Some("https://gelbooru.com/")
    } else if host == "rule34.xxx" || host.ends_with(".rule34.xxx") {
        Some("https://rule34.xxx/")
    } else if host.ends_with("donmai.us") {
        Some("https://danbooru.donmai.us/")
    } else {
        None
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Site {
    E621,
    Danbooru,
    Gelbooru,
    Rule34,
}

impl Site {
    pub fn parse(code: &str) -> Option<Site> {
        Some(match code {
            "e621" => Site::E621,
            "danbooru" => Site::Danbooru,
            "gelbooru" => Site::Gelbooru,
            "rule34" => Site::Rule34,
            _ => return None,
        })
    }

    pub fn code(self) -> &'static str {
        match self {
            Site::E621 => "e621",
            Site::Danbooru => "danbooru",
            Site::Gelbooru => "gelbooru",
            Site::Rule34 => "rule34",
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Site::E621 => "e621",
            Site::Danbooru => "Danbooru",
            Site::Gelbooru => "Gelbooru",
            Site::Rule34 => "Rule34",
        }
    }

    fn api(self) -> &'static str {
        match self {
            Site::E621 => crate::e621::BASE,
            Site::Danbooru => "https://danbooru.donmai.us",
            Site::Gelbooru => "https://gelbooru.com",
            Site::Rule34 => "https://api.rule34.xxx",
        }
    }

    /// The old Gelbooru API: Gelbooru itself and Rule34.
    fn gel(self) -> bool {
        matches!(self, Site::Gelbooru | Site::Rule34)
    }
}

pub struct Boorus {
    pub e621: Arc<Client>,
    /// Danbooru: (name, API key). Gelbooru, Rule34: (user id, API key).
    logins: RwLock<HashMap<Site, (String, String)>>,
}

type Pair = (String, String);

impl Boorus {
    pub fn new(e621: Arc<Client>) -> Self {
        Self { e621, logins: RwLock::new(HashMap::new()) }
    }

    /// `{"danbooru": ["name", "key"], ...}` from the app; a site left out is signed out.
    pub fn set_logins(&self, v: &Value) {
        let mut map = HashMap::new();
        if let Some(o) = v.as_object() {
            for (code, pair) in o {
                let (Some(site), Some(a)) = (Site::parse(code), pair.as_array()) else { continue };
                if let (Some(u), Some(k)) = (a.first().and_then(Value::as_str), a.get(1).and_then(Value::as_str))
                    && !u.trim().is_empty()
                    && !k.trim().is_empty()
                {
                    map.insert(site, (u.trim().to_owned(), k.trim().to_owned()));
                }
            }
        }
        *self.logins.write().unwrap() = map;
    }

    fn login(&self, site: Site) -> Option<Pair> {
        self.logins.read().unwrap().get(&site).cloned()
    }

    fn request(&self, site: Site, method: Method, url: &str, auth: Option<Pair>) -> RequestBuilder {
        let mut r = self
            .e621
            .http()
            .request(method, url)
            .timeout(Duration::from_secs(40))
            .header(header::USER_AGENT, APP_AGENT)
            .header(header::ACCEPT, "application/json");
        match (site, auth.or_else(|| self.login(site))) {
            (Site::Danbooru, Some((u, k))) => r = r.basic_auth(u, Some(k)),
            (s, Some((id, key))) if s.gel() => r = r.query(&[("user_id", id), ("api_key", key)]),
            _ => {}
        }
        r
    }

    fn get(&self, site: Site, path: &str, query: &[(&str, String)]) -> RequestBuilder {
        self.request(site, Method::GET, &format!("{}{path}", site.api()), None).query(query)
    }

    async fn run(&self, site: Site, r: RequestBuilder) -> Result<String> {
        let resp = r.send().await?;
        let status = resp.status();
        if is_challenge(&resp) {
            return Err(Error::Api(format!("{} просит проверку Cloudflare — попробуйте позже", site.name())));
        }
        let body = resp.text().await?;
        // The Gelbooru API says so with a 200 and a JSON string.
        let refused = body.trim_start().starts_with("\"Missing authentication") || matches!(status.as_u16(), 401 | 403);
        if refused {
            return Err(Error::Api(if site.gel() && self.login(site).is_none() {
                format!("{} пускает в API только с ключом — добавьте его в настройках", site.name())
            } else {
                format!("{} не принял логин или API-ключ", site.name())
            }));
        }
        if !status.is_success() {
            let reason = serde_json::from_str::<Value>(&body)
                .ok()
                .and_then(|v| v.get("message").or(v.get("reason")).and_then(Value::as_str).map(str::to_owned))
                .filter(|r| !r.is_empty());
            return Err(Error::Api(match reason {
                Some(r) if r.contains("more than 2 tags") => "Danbooru без Gold ищет не больше двух тегов".into(),
                Some(r) => format!("{} ответил {}: {r}", site.name(), status.as_u16()),
                None => format!("{} ответил {}", site.name(), status.as_u16()),
            }));
        }
        Ok(body)
    }

    // --- posts ------------------------------------------------------------------

    /// One page of a query. `exclude` are the blacklist's plain tags, added as
    /// minus-tags where the site has room for them (not on Danbooru).
    pub async fn posts(&self, site: Site, tags: &str, exclude: &[String], page: u32, limit: u32) -> Result<Vec<Post>> {
        let q = translate(site, tags, exclude, self.login(site).is_some());
        match site {
            Site::E621 => self.e621.posts(&q, page, limit).await,
            Site::Danbooru => {
                let body = self
                    .run(site, self.get(site, "/posts.json", &[
                        ("page", if q.contains("random:") { "1".into() } else { page.to_string() }),
                        ("tags", q),
                        ("limit", limit.min(200).to_string()),
                    ]))
                    .await?;
                let list = serde_json::from_str::<Value>(&body).map(|v| crate::parse::array_in(v, "posts")).unwrap_or_default();
                Ok(list.iter().filter_map(danbooru_post).collect())
            }
            Site::Gelbooru | Site::Rule34 => {
                let query = [
                    ("page", "dapi".to_owned()),
                    ("s", "post".into()),
                    ("q", "index".into()),
                    ("json", "1".into()),
                    ("tags", q),
                    ("pid", page.saturating_sub(1).to_string()),
                    ("limit", limit.min(100).to_string()),
                ];
                let body = self.run(site, self.get(site, "/index.php", &query)).await?;
                // Gelbooru wraps the list in {"post": [...]}; Rule34 sends it bare,
                // or nothing at all when nothing matches.
                let list = serde_json::from_str::<Value>(&body).map(|v| crate::parse::array_in(v, "post")).unwrap_or_default();
                Ok(list.iter().filter_map(|o| gel_post(site, o)).collect())
            }
        }
    }

    /// Several sites' pages at once, taken in turn. A site that fails is left
    /// out; only when all fail is it an error.
    pub async fn mix(&self, sites: &[Site], tags: &str, exclude: &[String], page: u32, limit: u32) -> Result<Vec<Post>> {
        if sites.is_empty() {
            return Ok(Vec::new());
        }
        let each = (limit / sites.len() as u32).max(20);
        // All sites at once. Once one has answered, the rest get a few seconds
        // more: a site that hangs is left out of this page instead of holding
        // the whole feed until its timeout.
        let mut pending: futures_util::stream::FuturesUnordered<_> = sites
            .iter()
            .enumerate()
            .map(|(i, &s)| async move { (i, self.posts(s, tags, exclude, page, each).await) })
            .collect();
        let mut done = Vec::new();
        let mut deadline: Option<tokio::time::Instant> = None;
        loop {
            let next = match deadline {
                None => futures_util::StreamExt::next(&mut pending).await,
                Some(d) => match tokio::time::timeout_at(d, futures_util::StreamExt::next(&mut pending)).await {
                    Ok(x) => x,
                    Err(_) => break,
                },
            };
            let Some((i, r)) = next else { break };
            if r.is_ok() && deadline.is_none() {
                deadline = Some(tokio::time::Instant::now() + Duration::from_secs(MIX_GRACE_SECS));
            }
            done.push((i, r));
        }
        // In the sources' own order, whoever came first.
        done.sort_by_key(|(i, _)| *i);
        let mut lists = Vec::new();
        let mut first_err = None;
        for (_, r) in done {
            match r {
                Ok(l) => lists.push(l.into_iter()),
                Err(e) => {
                    first_err.get_or_insert(e);
                }
            }
        }
        if lists.is_empty()
            && let Some(e) = first_err
        {
            return Err(e);
        }
        let mut out = Vec::new();
        loop {
            let before = out.len();
            for l in &mut lists {
                out.extend(l.next());
            }
            if out.len() == before {
                break;
            }
        }
        Ok(out)
    }

    // --- the rest -----------------------------------------------------------------

    pub async fn comments(&self, site: Site, post: u64) -> Result<Vec<Comment>> {
        let mut out: Vec<Comment> = match site {
            Site::E621 => return self.e621.comments(post).await,
            Site::Danbooru => {
                let query = [
                    ("group_by", "comment".to_owned()),
                    ("search[post_id]", post.to_string()),
                    ("limit", "1000".into()),
                    ("only", "id,body,score,created_at,creator_id,is_deleted,creator[name]".into()),
                ];
                let body = self.run(site, self.get(site, "/comments.json", &query)).await?;
                let list = serde_json::from_str::<Value>(&body).map(|v| crate::parse::array_in(v, "comments")).unwrap_or_default();
                list.iter()
                    .filter(|o| !o.get("is_deleted").and_then(Value::as_bool).unwrap_or(false))
                    .filter_map(|o| {
                        Some(Comment {
                            id: num(o, "id")? as u64,
                            author: o.pointer("/creator/name").and_then(Value::as_str).unwrap_or("?").to_owned(),
                            user: num(o, "creator_id").unwrap_or(0) as u64,
                            body: text(o, "body"),
                            score: num(o, "score").unwrap_or(0),
                            at: text(o, "created_at"),
                            avatar: None,
                        })
                    })
                    .collect()
            }
            // Gelbooru's comment API answers "Disabled due to abuse": its
            // comments are read off the post's own page.
            Site::Gelbooru => {
                let query = [("page", "post".to_owned()), ("s", "view".into()), ("id", post.to_string())];
                let html = self.run(site, self.get(site, "/index.php", &query)).await?;
                gel_comments_html(&html)
            }
            Site::Rule34 => {
                let query = [("page", "dapi".to_owned()), ("s", "comment".into()), ("q", "index".into()), ("post_id", post.to_string())];
                let body = self.run(site, self.get(site, "/index.php", &query)).await?;
                xml_items(&body, "comment")
                    .into_iter()
                    .filter_map(|a| {
                        Some(Comment {
                            id: a.get("id")?.parse().ok()?,
                            author: a.get("creator").cloned().unwrap_or_else(|| "?".into()),
                            user: a.get("creator_id").and_then(|v| v.parse().ok()).unwrap_or(0),
                            body: a.get("body").cloned().unwrap_or_default(),
                            score: 0,
                            at: iso(a.get("created_at").map(String::as_str).unwrap_or_default()),
                            avatar: None,
                        })
                    })
                    .collect()
            }
        };
        out.sort_by_key(|c| c.id);
        out.dedup_by_key(|c| c.id);
        Ok(out)
    }

    pub async fn autocomplete(&self, site: Site, prefix: &str) -> Result<Vec<TagHint>> {
        match site {
            Site::E621 => self.e621.autocomplete(prefix).await,
            Site::Danbooru => {
                let query = [("search[query]", prefix.to_owned()), ("search[type]", "tag_query".into()), ("limit", "10".into())];
                let body = self.run(site, self.get(site, "/autocomplete.json", &query)).await?;
                Ok(hints(&body, |o| {
                    Some(TagHint { name: text(o, "value"), count: num(o, "post_count").unwrap_or(0), category: danbooru_cat(num(o, "category").unwrap_or(0)) })
                }))
            }
            Site::Gelbooru => {
                let query = [("page", "autocomplete2".to_owned()), ("term", prefix.into()), ("type", "tag_query".into()), ("limit", "10".into())];
                let body = self.run(site, self.get(site, "/index.php", &query)).await?;
                Ok(hints(&body, |o| {
                    Some(TagHint { name: text(o, "value"), count: num(o, "post_count").unwrap_or(0), category: named_cat(&text(o, "category")) })
                }))
            }
            Site::Rule34 => {
                // Rule34's hints live on a host of their own, open to all.
                let r = self.request(site, Method::GET, "https://ac.rule34.xxx/autocomplete.php", None).query(&[("q", prefix)]);
                let body = self.run(site, r).await?;
                Ok(hints(&body, |o| {
                    // "cat_ears (211728)"
                    let label = text(o, "label");
                    let count = label.rsplit_once('(').and_then(|(_, n)| n.trim_end_matches(')').trim().parse().ok()).unwrap_or(0);
                    Some(TagHint { name: text(o, "value"), count, category: named_cat(&text(o, "type")) })
                }))
            }
        }
    }

    /// Categories for tags, in e621's numbers.
    pub async fn categories(&self, site: Site, names: &[String]) -> Result<HashMap<String, i64>> {
        let mut out = HashMap::new();
        match site {
            Site::E621 => return self.e621.categories(names).await,
            Site::Danbooru => {
                for chunk in names.chunks(100) {
                    let query = [("search[name_comma]", chunk.join(",")), ("limit", "1000".into()), ("only", "name,category".into())];
                    let body = self.run(site, self.get(site, "/tags.json", &query)).await?;
                    for o in serde_json::from_str::<Value>(&body).map(|v| crate::parse::array_in(v, "tags")).unwrap_or_default() {
                        out.insert(text(&o, "name"), danbooru_cat(num(&o, "category").unwrap_or(0)));
                    }
                }
            }
            Site::Gelbooru | Site::Rule34 => {
                for chunk in names.chunks(100) {
                    let query = [
                        ("page", "dapi".to_owned()),
                        ("s", "tag".into()),
                        ("q", "index".into()),
                        ("json", "1".into()),
                        ("names", chunk.join(" ")),
                        ("limit", "100".into()),
                    ];
                    let body = self.run(site, self.get(site, "/index.php", &query)).await?;
                    match serde_json::from_str::<Value>(&body) {
                        Ok(v) => {
                            for o in crate::parse::array_in(v, "tag") {
                                out.insert(unescape(&text(&o, "name")), gel_cat(num(&o, "type").unwrap_or(0)));
                            }
                        }
                        // Rule34 may answer tags in XML whatever is asked.
                        Err(_) => {
                            for a in xml_items(&body, "tag") {
                                if let Some(name) = a.get("name") {
                                    out.insert(name.clone(), gel_cat(a.get("type").and_then(|t| t.parse().ok()).unwrap_or(0)));
                                }
                            }
                        }
                    }
                }
            }
        }
        Ok(out)
    }

    pub async fn favorite(&self, site: Site, id: u64, on: bool) -> Result<()> {
        match site {
            Site::E621 => self.e621.favorite(id, on).await,
            Site::Danbooru => {
                if self.login(site).is_none() {
                    return Err(Error::Api("Избранное Danbooru — после входа в настройках".into()));
                }
                let base = site.api();
                let r = if on {
                    self.request(site, Method::POST, &format!("{base}/favorites.json"), None).form(&[("post_id", id.to_string())])
                } else {
                    self.request(site, Method::DELETE, &format!("{base}/favorites/{id}.json"), None)
                };
                self.run(site, r).await.map(drop)
            }
            _ => Err(Error::Api(format!("{} не даёт ставить избранное через API — только на сайте", site.name()))),
        }
    }

    /// Fails unless the site accepts the pair.
    pub async fn check(&self, site: Site, user: &str, key: &str) -> Result<()> {
        let pair = Some((user.trim().to_owned(), key.trim().to_owned()));
        match site {
            Site::E621 => self.e621.check(user, key).await,
            Site::Danbooru => {
                let body = self.run(site, self.request(site, Method::GET, &format!("{}/profile.json", site.api()), pair)).await?;
                // A wrong key answers 401; an anonymous profile has no id.
                match serde_json::from_str::<Value>(&body).ok().and_then(|v| v.get("id").cloned()) {
                    Some(_) => Ok(()),
                    None => Err(Error::Api("Danbooru не принял логин или API-ключ".into())),
                }
            }
            Site::Gelbooru | Site::Rule34 => {
                let query = [("page", "dapi"), ("s", "post"), ("q", "index"), ("json", "1"), ("limit", "1")];
                let r = self.request(site, Method::GET, &format!("{}/index.php", site.api()), pair).query(&query);
                self.run(site, r).await.map(drop)
            }
        }
    }
}

// --- the query, in each site's words ---------------------------------------------

/// Tags Danbooru does not count against its limit of two.
fn free_on_danbooru(t: &str) -> bool {
    let t = t.trim_start_matches('-');
    ["rating:", "status:", "limit:"].iter().any(|p| t.starts_with(p))
}

/// e621's query as `site` understands it; e621 itself only gets the minus-tags.
///
/// `~a ~b` ("any of") is Gelbooru's `{a ~ b}` and Rule34's `( a ~ b )`;
/// e621 and Danbooru take it as it is.
pub(crate) fn translate(site: Site, tags: &str, exclude: &[String], logged_in: bool) -> String {
    let minus = exclude.iter().filter(|e| !e.is_empty()).map(|e| format!("-{e}"));
    let words = tags.split_whitespace();
    if site == Site::E621 {
        return words.map(str::to_owned).chain(minus).collect::<Vec<_>>().join(" ");
    }
    let mut out: Vec<String> = Vec::new();
    let mut order = None;
    let mut any: Vec<&str> = Vec::new();
    // File types gather into one tag each way, so Danbooru counts them once.
    let (mut want, mut skip) = (BTreeSet::new(), BTreeSet::new());
    for t in words {
        if site.gel() {
            if let Some(b) = t.strip_prefix('~').filter(|b| !b.is_empty()) {
                any.push(b);
                continue;
            }
        }
        let (neg, body) = match t.strip_prefix('-') {
            Some(b) => (true, b),
            None => (false, t),
        };
        let lower = body.to_ascii_lowercase();
        let sign = if neg { "-" } else { "" };
        if let Some(r) = lower.strip_prefix("rating:") {
            out.extend(rating(site, neg, r).unwrap_or_else(|| vec![t.to_owned()]));
        } else if let Some(o) = lower.strip_prefix("order:") {
            if !neg {
                order = sort(site, o);
            }
        } else if let Some(kind) = lower.strip_prefix("type:") {
            (if neg { &mut skip } else { &mut want }).insert(kind.to_owned());
        } else if let Some(when) = lower.strip_prefix("date:") {
            // Only Danbooru can say "this week".
            if site == Site::Danbooru {
                let age = match when {
                    "day" => "1d",
                    "week" => "1w",
                    "month" => "1mo",
                    "year" => "1y",
                    _ => continue,
                };
                out.push(format!("{sign}age:<{age}"));
            }
        } else if lower.starts_with("ratio:") {
            if site == Site::Danbooru {
                out.push(t.to_owned());
            }
        } else {
            out.push(t.to_owned());
        }
    }
    match any.as_slice() {
        [] => {}
        [one] => out.push((*one).to_owned()),
        many if site == Site::Gelbooru => out.push(format!("{{{}}}", many.join(" ~ "))),
        many => out.push(format!("( {} )", many.join(" ~ "))),
    }
    for (set, sign) in [(&want, ""), (&skip, "-")] {
        if set.is_empty() {
            continue;
        }
        if site == Site::Danbooru {
            out.push(format!("{sign}filetype:{}", set.iter().cloned().collect::<Vec<_>>().join(",")));
        } else {
            let mut tags = BTreeSet::new();
            for kind in set {
                match kind.as_str() {
                    "webm" | "mp4" => tags.insert("video"),
                    "gif" => tags.insert(if site == Site::Gelbooru { "animated_gif" } else { "gif" }),
                    _ => false,
                };
            }
            out.extend(tags.into_iter().map(|t| format!("{sign}{t}")));
        }
    }
    if site != Site::Danbooru {
        out.extend(minus);
    }
    if let Some(o) = order {
        // Gelbooru sorts its whole site in 15 seconds or more; a bound on the
        // score narrows it to a second, and the top of the list stays the same.
        if site == Site::Gelbooru && !out.iter().any(|t| !t.starts_with('-') && !t.contains(':')) {
            match o.as_str() {
                "sort:score" => out.push("score:>100".into()),
                "sort:random" => out.push("score:>5".into()),
                _ => {}
            }
        }
        // Without Gold, a sort on top of two tags is one tag too many: the tags win.
        let counted = out.iter().filter(|t| !free_on_danbooru(t)).count();
        if site != Site::Danbooru || logged_in || counted < 2 {
            out.push(o);
        }
    }
    out.join(" ")
}

/// e621's rating letters in `site`'s words; None leaves the tag as typed.
fn rating(site: Site, neg: bool, r: &str) -> Option<Vec<String>> {
    let letter = match r {
        "s" | "safe" => 's',
        "q" | "questionable" => 'q',
        "e" | "explicit" => 'e',
        _ => return None,
    };
    let sign = if neg { "-" } else { "" };
    Some(match (site, letter) {
        // e621's safe is Danbooru's general and sensitive.
        (Site::Danbooru, 's') => vec![format!("{sign}rating:g,s")],
        (Site::Danbooru, l) => vec![format!("{sign}rating:{l}")],
        // Gelbooru has no lists: safe is "neither of the other two".
        (Site::Gelbooru, 's') if !neg => vec!["-rating:questionable".into(), "-rating:explicit".into()],
        (Site::Gelbooru, 's') => vec!["-rating:general".into(), "-rating:sensitive".into()],
        (_, 's') => vec![format!("{sign}rating:safe")],
        (_, 'q') => vec![format!("{sign}rating:questionable")],
        _ => vec![format!("{sign}rating:explicit")],
    })
}

/// e621's sort tabs in `site`'s words.
fn sort(site: Site, o: &str) -> Option<String> {
    match site {
        Site::E621 => Some(format!("order:{o}")),
        Site::Danbooru => Some(match o {
            "hot" => "order:rank".into(),
            // order:random times out on Danbooru's database by 2026; random:N
            // draws N posts afresh on every ask (and has no second page).
            "random" => "random:200".into(),
            other => format!("order:{other}"),
        }),
        Site::Gelbooru | Site::Rule34 => match o {
            "hot" | "score" => Some("sort:score".into()),
            "random" => Some("sort:random".into()),
            "comment_bumped" | "id" | "id_desc" => None,
            other => Some(format!("sort:{other}")),
        },
    }
}

// --- the answers -----------------------------------------------------------------

/// A string field, or "" when missing; the old API sends numbers as strings.
fn text(o: &Value, key: &str) -> String {
    match o.get(key) {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    }
}

fn opt(o: &Value, key: &str) -> Option<String> {
    Some(text(o, key)).filter(|s| !s.trim().is_empty())
}

fn num(o: &Value, key: &str) -> Option<i64> {
    match o.get(key)? {
        Value::Number(n) => n.as_i64(),
        Value::String(s) => s.trim().parse().ok(),
        Value::Bool(b) => Some(*b as i64),
        _ => None,
    }
}

/// A yes/no the old API may send as a bool, "true" or 1.
fn truthy(o: &Value, key: &str) -> bool {
    match o.get(key) {
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) => s == "true" || s == "1",
        Some(Value::Number(n)) => n.as_i64().unwrap_or(0) != 0,
        _ => false,
    }
}

/// e621's three ratings from any site's.
fn fold_rating(r: &str) -> String {
    match r {
        "q" | "questionable" => "q",
        "e" | "explicit" => "e",
        _ => "s",
    }
    .into()
}

fn ext_of(url: &str) -> String {
    let path = url.split(['?', '#']).next().unwrap_or("");
    path.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).filter(|e| e.len() <= 5).unwrap_or_default()
}

fn danbooru_post(o: &Value) -> Option<Post> {
    let id = num(o, "id")? as u64;
    let file = opt(o, "file_url");
    let large = opt(o, "large_file_url");
    let variants = o.pointer("/media_asset/variants").and_then(Value::as_array);
    let variant = |kind: &str| {
        variants
            .and_then(|v| v.iter().find(|x| x.get("type").and_then(Value::as_str) == Some(kind)))
            .and_then(|x| opt(x, "url"))
    };
    let ext = text(o, "file_ext");
    let (video, ext) = match ext.as_str() {
        "mp4" | "webm" => (file.clone(), ext),
        // An ugoira's zip is frames; Danbooru's sample of it is a webm.
        "zip" => match large.clone().filter(|u| u.ends_with(".webm")) {
            Some(u) => (Some(u), "webm".into()),
            None => (None, ext),
        },
        _ => (None, ext),
    };
    let sample = if video.is_some() { variant("720x720") } else { large.or_else(|| variant("720x720")) };
    let mut tags = Map::new();
    for (cat, key) in [
        ("artist", "tag_string_artist"),
        ("copyright", "tag_string_copyright"),
        ("character", "tag_string_character"),
        ("general", "tag_string_general"),
        ("meta", "tag_string_meta"),
    ] {
        let list: Vec<Value> = text(o, key).split_whitespace().map(|t| Value::String(t.to_owned())).collect();
        if !list.is_empty() {
            tags.insert(cat.into(), Value::Array(list));
        }
    }
    Some(Post {
        id,
        site: Site::Danbooru.code().into(),
        preview: variant("360x360").or_else(|| opt(o, "preview_file_url")),
        sample,
        file,
        video,
        ext,
        w: num(o, "image_width").unwrap_or(0) as u32,
        h: num(o, "image_height").unwrap_or(0) as u32,
        size: num(o, "file_size").unwrap_or(0) as u64,
        rating: fold_rating(&text(o, "rating")),
        score: num(o, "score").unwrap_or(0),
        favs: num(o, "fav_count").unwrap_or(0),
        fav: false,
        // Danbooru does not count a post's comments in its answer.
        comments: -1,
        about: String::new(),
        tags,
        blocked: false,
        parent: num(o, "parent_id").unwrap_or(0).max(0) as u64,
        children: truthy(o, "has_children"),
        notes: opt(o, "last_noted_at").is_some(),
        pools: Vec::new(),
    })
}

fn gel_post(site: Site, o: &Value) -> Option<Post> {
    let id = num(o, "id")? as u64;
    let file = opt(o, "file_url");
    let ext = file.as_deref().map(ext_of).filter(|e| !e.is_empty()).unwrap_or_else(|| ext_of(&text(o, "image")));
    let video = file.clone().filter(|_| matches!(ext.as_str(), "mp4" | "webm"));
    let sample = opt(o, "sample_url").filter(|s| Some(s) != file.as_ref() && video.is_none());
    let tags: Vec<Value> = unescape(&text(o, "tags")).split_whitespace().map(|t| Value::String(t.to_owned())).collect();
    Some(Post {
        id,
        site: site.code().into(),
        preview: opt(o, "preview_url"),
        sample,
        file,
        video,
        ext,
        w: num(o, "width").unwrap_or(0) as u32,
        h: num(o, "height").unwrap_or(0) as u32,
        size: 0,
        rating: fold_rating(&text(o, "rating")),
        score: num(o, "score").unwrap_or(0),
        favs: 0,
        fav: false,
        comments: num(o, "comment_count").unwrap_or(-1),
        about: String::new(),
        tags: Map::from_iter([(FLAT.to_owned(), Value::Array(tags))]),
        blocked: false,
        parent: num(o, "parent_id").unwrap_or(0).max(0) as u64,
        children: truthy(o, "has_children"),
        notes: truthy(o, "has_notes"),
        pools: Vec::new(),
    })
}

fn hints(body: &str, read: impl Fn(&Value) -> Option<TagHint>) -> Vec<TagHint> {
    let list = serde_json::from_str::<Value>(body).map(|v| crate::parse::array_in(v, "tags")).unwrap_or_default();
    list.iter().filter_map(read).filter(|h| !h.name.is_empty()).collect()
}

/// e621 numbers its categories 0 general, 1 artist, 3 copyright, 4 character,
/// 5 species, 6 invalid, 7 meta, 8 lore. Danbooru's 5 is meta.
fn danbooru_cat(c: i64) -> i64 {
    match c {
        1 | 3 | 4 => c,
        5 => 7,
        _ => 0,
    }
}

/// The old API: 5 metadata, 6 deprecated.
fn gel_cat(c: i64) -> i64 {
    match c {
        1 | 3 | 4 | 6 => c,
        5 => 7,
        _ => 0,
    }
}

fn named_cat(name: &str) -> i64 {
    match name {
        "artist" => 1,
        "copyright" => 3,
        "character" => 4,
        "metadata" | "meta" => 7,
        "deprecated" => 6,
        _ => 0,
    }
}

/// "2024-05-01 13:22" (the old API's comments) as ISO 8601; anything else as it came.
fn iso(at: &str) -> String {
    let at = at.trim();
    let b = at.as_bytes();
    if b.len() >= 16 && b[4] == b'-' && b[10] == b' ' {
        let time = if b.len() >= 19 { at[11..19].to_owned() } else { format!("{}:00", &at[11..16]) };
        return format!("{}T{time}+00:00", &at[..10]);
    }
    at.to_owned()
}

/// HTML's entities, as the old API escapes tags and comment bodies.
fn unescape(s: &str) -> String {
    if !s.contains('&') {
        return s.to_owned();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        rest = &rest[i..];
        let Some(end) = rest[..rest.len().min(12)].find(';') else {
            out.push('&');
            rest = &rest[1..];
            continue;
        };
        let name = &rest[1..end];
        let ch = match name {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            _ => name
                .strip_prefix("#x")
                .or_else(|| name.strip_prefix("#X"))
                .and_then(|h| u32::from_str_radix(h, 16).ok())
                .or_else(|| name.strip_prefix('#').and_then(|d| d.parse().ok()))
                .and_then(char::from_u32),
        };
        match ch {
            Some(c) => {
                out.push(c);
                rest = &rest[end + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

fn between<'a>(s: &'a str, from: &str, to: &str) -> Option<&'a str> {
    let start = s.find(from)? + from.len();
    let len = s[start..].find(to)?;
    Some(&s[start..start + len])
}

fn strip_tags(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut inside = false;
    for c in s.chars() {
        match c {
            '<' => inside = true,
            '>' if inside => inside = false,
            c if !inside => out.push(c),
            _ => {}
        }
    }
    out
}

/// Comments as Gelbooru's post page shows them: a block per comment with
/// the author's avatar, name, time, number, text and points.
fn gel_comments_html(html: &str) -> Vec<Comment> {
    let mut out = Vec::new();
    for part in html.split("<div class=\"commentAvatar\">").skip(1) {
        let avatar = between(part, "url('", "')")
            .filter(|u| !u.contains("honkonymous"))
            .map(|u| if u.starts_with("http") { u.to_owned() } else { format!("https://gelbooru.com/{}", u.trim_start_matches('/')) });
        let Some(b) = part.find("<div class=\"commentBody").map(|i| &part[i..]) else { continue };
        let Some(id) = between(b, "&raquo; #", "<").and_then(|v| v.trim().parse().ok()) else { continue };
        let Some(start) = b.find("&raquo; #").and_then(|k| b[k..].find("<br /><br />").map(|j| k + j + "<br /><br />".len())) else { continue };
        let end = b[start..].find("<span style=\"font-size: .9em;\">").map_or(b.len(), |e| start + e);
        let text = b[start..end].replace("<br />", "\n").replace("<br>", "\n");
        out.push(Comment {
            id,
            author: between(b, "<b>", "</b>").map(|a| unescape(&strip_tags(a))).unwrap_or_else(|| "?".into()),
            user: between(b, "s=profile&amp;id=", "\"").and_then(|v| v.parse().ok()).unwrap_or(0),
            body: unescape(&strip_tags(&text)).trim().to_owned(),
            score: between(b, "<span id=\"sc", "</span>").and_then(|s| s.split('>').nth(1)).and_then(|v| v.trim().parse().ok()).unwrap_or(0),
            at: iso(between(b, "commented at ", " &raquo;").unwrap_or_default()),
            avatar,
        });
    }
    out
}

/// The attributes of every `<tag .../>` in an XML answer: all the old API's
/// comments and tags carry are attributes.
fn xml_items(body: &str, tag: &str) -> Vec<HashMap<String, String>> {
    let open = format!("<{tag} ");
    let mut out = Vec::new();
    let mut rest = body;
    while let Some(i) = rest.find(&open) {
        rest = &rest[i + open.len()..];
        let mut attrs = HashMap::new();
        loop {
            rest = rest.trim_start();
            if rest.is_empty() || rest.starts_with('>') || rest.starts_with("/>") {
                break;
            }
            let Some(eq) = rest.find('=') else { break };
            let name = rest[..eq].trim().to_owned();
            rest = rest[eq + 1..].trim_start();
            let Some(q) = rest.chars().next().filter(|c| *c == '"' || *c == '\'') else { break };
            rest = &rest[1..];
            let Some(end) = rest.find(q) else { break };
            attrs.insert(name, unescape(&rest[..end]));
            rest = &rest[end + 1..];
        }
        out.push(attrs);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn queries_speak_each_sites_language() {
        let none: [String; 0] = [];
        let ex = ["gore".to_owned()];
        assert_eq!(translate(Site::E621, "fox order:hot", &ex, false), "fox order:hot -gore");
        assert_eq!(translate(Site::Danbooru, "cat order:hot rating:s", &ex, false), "cat rating:g,s order:rank");
        // Two tags already: the sort gives way, the ratings stay.
        assert_eq!(translate(Site::Danbooru, "cat dog order:hot -rating:e", &none, false), "cat dog -rating:e");
        assert_eq!(translate(Site::Danbooru, "cat dog order:hot", &none, true), "cat dog order:rank");
        assert_eq!(translate(Site::Gelbooru, "cat rating:s order:random", &ex, false), "cat -rating:questionable -rating:explicit -gore sort:random");
        // Nothing to narrow a whole-site sort by: a score bound does.
        assert_eq!(translate(Site::Gelbooru, "order:hot rating:s", &none, false), "-rating:questionable -rating:explicit score:>100 sort:score");
        assert_eq!(translate(Site::Gelbooru, "order:random", &none, false), "score:>5 sort:random");
        assert_eq!(translate(Site::Rule34, "-rating:e order:comment_bumped date:week", &none, false), "-rating:explicit");
        assert_eq!(translate(Site::Danbooru, "-type:webm -type:mp4 -type:gif date:week", &none, false), "age:<1w -filetype:gif,mp4,webm");
        assert_eq!(translate(Site::Gelbooru, "type:webm type:gif", &none, false), "animated_gif video");
        // "Any of" in each site's words.
        assert_eq!(translate(Site::E621, "+femboy ~a ~b", &none, false), "+femboy ~a ~b");
        assert_eq!(translate(Site::Gelbooru, "cat ~a ~b ~c", &none, false), "cat {a ~ b ~ c}");
        assert_eq!(translate(Site::Rule34, "~a ~b rating:e", &none, false), "rating:explicit ( a ~ b )");
        assert_eq!(translate(Site::Rule34, "~a", &none, false), "a");
        assert_eq!(translate(Site::Danbooru, "x ~a ~b", &none, true), "x ~a ~b");
    }

    #[test]
    fn reads_danbooru_posts() {
        let v: Value = serde_json::from_str(
            r#"{"id":5,"file_ext":"jpg","rating":"g","score":3,"fav_count":2,"image_width":10,"image_height":20,
            "file_url":"https://cdn/o.jpg","large_file_url":"https://cdn/l.jpg",
            "tag_string_artist":"bob","tag_string_general":"cat smile","tag_string_meta":"",
            "media_asset":{"variants":[{"type":"360x360","url":"https://cdn/p.jpg"}]}}"#,
        )
        .unwrap();
        let p = danbooru_post(&v).unwrap();
        assert_eq!((p.site.as_str(), p.rating.as_str(), p.preview.as_deref(), p.sample.as_deref()), ("danbooru", "s", Some("https://cdn/p.jpg"), Some("https://cdn/l.jpg")));
        assert_eq!(p.tags.keys().collect::<Vec<_>>(), ["artist", "general"]);
    }

    #[test]
    fn reads_the_old_api() {
        let v: Value = serde_json::from_str(
            r#"{"id":"9","file_url":"https://img/a.mp4","sample_url":"https://img/s.jpg","preview_url":"https://img/p.jpg",
            "width":"100","height":50,"rating":"explicit","score":"4","tags":"cat tom_&amp;_jerry"}"#,
        )
        .unwrap();
        let p = gel_post(Site::Rule34, &v).unwrap();
        assert_eq!((p.id, p.ext.as_str(), p.rating.as_str(), p.w), (9, "mp4", "e", 100));
        assert_eq!(p.video.as_deref(), Some("https://img/a.mp4"));
        assert_eq!(p.all_tags().collect::<Vec<_>>(), ["cat", "tom_&_jerry"]);

        let xml = r#"<?xml version="1.0"?><comments type="array"><comment created_at="2024-05-01 13:22" post_id="1" body="hi &quot;there&quot;" creator="ann" id="7" creator_id="3"/></comments>"#;
        let c = &xml_items(xml, "comment")[0];
        assert_eq!((c["body"].as_str(), c["creator"].as_str()), ("hi \"there\"", "ann"));
        assert_eq!(iso("2024-05-01 13:22"), "2024-05-01T13:22:00+00:00");

        let page = r##"<h2>User Comments:</h2><br /><div>
	<div class="commentAvatar">
		<div class="profileAvatar" style="background: #fff url('user_avatars/avatar_458503.jpg') no-repeat center; "></div>
	</div>
	<div class="commentBody ">
	<a href="index.php?page=account&amp;s=profile&amp;id=458503"><b>Pride_Queen</b></a> commented at 2020-09-01 23:05:46 &raquo; #2561522<br /><br />Line one<br />Komekko&#039;s<br /><br />
		<span style="font-size: .9em;">
			<span class="info"><a href="#"><img src="x.svg" /></a> <span id="sc2561522">208</span>  Points</span>
		</span></div></div>"##;
        let c = &gel_comments_html(page)[0];
        assert_eq!((c.id, c.user, c.score, c.author.as_str()), (2561522, 458503, 208, "Pride_Queen"));
        assert_eq!(c.body, "Line one\nKomekko's");
        assert_eq!(c.at, "2020-09-01T23:05:46+00:00");
        assert_eq!(c.avatar.as_deref(), Some("https://gelbooru.com/user_avatars/avatar_458503.jpg"));
    }
}
