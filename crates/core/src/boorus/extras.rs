//! What a post leads to beyond its own page: its family, its pools, the notes
//! on it, what its tags mean, and each site's popular page.

use futures_util::future::join;

use super::*;
use crate::model::{Note, Pool, Wiki};

fn ids_list(ids: &[u64]) -> String {
    ids.iter().map(u64::to_string).collect::<Vec<_>>().join(",")
}

impl Boorus {
    /// Posts by id, in the order given (a pool's); ones gone are left out.
    pub async fn by_ids(&self, site: Site, ids: &[u64]) -> Result<Vec<Post>> {
        let mut found: HashMap<u64, Post> = HashMap::new();
        for chunk in ids.chunks(100) {
            for p in self.posts(site, &format!("id:{}", ids_list(chunk)), &[], 1, 100).await? {
                found.insert(p.id, p);
            }
        }
        Ok(ids.iter().filter_map(|i| found.remove(i)).collect())
    }

    /// The parent and all its children (the post among them), parent first.
    pub async fn family(&self, site: Site, post: u64, parent: u64) -> Result<Vec<Post>> {
        let root = if parent > 0 { parent } else { post };
        let (head, kids) = join(self.posts(site, &format!("id:{root}"), &[], 1, 1), self.posts(site, &format!("parent:{root}"), &[], 1, 100)).await;
        // The parent may be gone; its children are still a family.
        let mut out = head.unwrap_or_default();
        let mut kids = kids?;
        kids.sort_by_key(|p| p.id);
        for k in kids {
            if !out.iter().any(|p| p.id == k.id) {
                out.push(k);
            }
        }
        Ok(out)
    }

    /// The pools a post is in: e621 names them in the post (`ids`), Danbooru
    /// is asked; the old API has none.
    pub async fn pools(&self, site: Site, post: u64, ids: &[u64]) -> Result<Vec<Pool>> {
        let body = match site {
            Site::E621 if ids.is_empty() => return Ok(Vec::new()),
            Site::E621 => self.e621.get("/pools.json", &[("search[id]", ids_list(ids))]).await?,
            Site::Danbooru => {
                let q = [("search[post_ids_include_all]", post.to_string()), ("only", "id,name,post_ids".into())];
                self.run(site, self.get(site, "/pools.json", &q)).await?
            }
            _ => return Ok(Vec::new()),
        };
        let list = serde_json::from_str::<Value>(&body).map(|v| crate::parse::array_in(v, "pools")).unwrap_or_default();
        Ok(list
            .iter()
            .filter_map(|o| {
                Some(Pool {
                    id: num(o, "id")? as u64,
                    name: text(o, "name").replace('_', " "),
                    posts: o.get("post_ids").and_then(Value::as_array).map(|a| a.iter().filter_map(Value::as_u64).collect()).unwrap_or_default(),
                })
            })
            .collect())
    }

    /// The notes on a post's picture.
    pub async fn notes(&self, site: Site, post: u64) -> Result<Vec<Note>> {
        let note = |x: Option<i64>, y: Option<i64>, w: Option<i64>, h: Option<i64>, body: &str| {
            let body = unescape(&strip_tags(&body.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")));
            Some(Note { x: x?, y: y?, w: w?, h: h?, body: body.trim().to_owned() }).filter(|n| !n.body.is_empty())
        };
        match site {
            Site::E621 | Site::Danbooru => {
                let q = [("search[post_id]", post.to_string()), ("search[is_active]", "true".into()), ("limit", "200".into())];
                let body = if site == Site::E621 { self.e621.get("/notes.json", &q).await? } else { self.run(site, self.get(site, "/notes.json", &q)).await? };
                let list = serde_json::from_str::<Value>(&body).map(|v| crate::parse::array_in(v, "notes")).unwrap_or_default();
                Ok(list
                    .iter()
                    .filter(|o| o.get("is_active").and_then(Value::as_bool).unwrap_or(true))
                    .filter_map(|o| note(num(o, "x"), num(o, "y"), num(o, "width"), num(o, "height"), &text(o, "body")))
                    .collect())
            }
            Site::Gelbooru | Site::Rule34 => {
                let q = [("page", "dapi".to_owned()), ("s", "note".into()), ("q", "index".into()), ("post_id", post.to_string())];
                let body = self.run(site, self.get(site, "/index.php", &q)).await?;
                let n = |a: &HashMap<String, String>, k: &str| a.get(k).and_then(|v| v.trim().parse::<f64>().ok()).map(|v| v as i64);
                Ok(xml_items(&body, "note")
                    .iter()
                    .filter(|a| a.get("is_active").is_none_or(|v| v == "true"))
                    .filter_map(|a| note(n(a, "x"), n(a, "y"), n(a, "width"), n(a, "height"), a.get("body").map(String::as_str).unwrap_or("")))
                    .collect())
            }
        }
    }

    /// What a tag means: its wiki page, made plain, and the tags seen with it.
    pub async fn wiki(&self, site: Site, tag: &str) -> Result<Wiki> {
        let tag = tag.trim().to_lowercase();
        let (raw, related, count) = match site {
            Site::E621 => {
                let (page, info) =
                    join(self.e621.get("/wiki_pages.json", &[("search[title]", tag.clone())]), self.e621.get("/tags.json", &[("search[name]", tag.clone())]))
                        .await;
                let page = serde_json::from_str::<Value>(&page?).map(|v| crate::parse::array_in(v, "wiki_pages")).unwrap_or_default();
                let info = info.ok().and_then(|b| serde_json::from_str::<Value>(&b).ok()).map(|v| crate::parse::array_in(v, "tags")).unwrap_or_default();
                let info = info.first();
                // "femboy 300 male 291 …": a name, then its weight.
                let related = info
                    .map(|o| text(o, "related_tags"))
                    .unwrap_or_default()
                    .split_whitespace()
                    .step_by(2)
                    // A tag never counted answers "[]".
                    .filter(|t| *t != tag && !t.starts_with('['))
                    .take(24)
                    .map(str::to_owned)
                    .collect();
                (page.first().map(|o| text(o, "body")).unwrap_or_default(), related, info.and_then(|o| num(o, "post_count")).unwrap_or(0))
            }
            Site::Danbooru => {
                let (page, info) = join(
                    self.run(site, self.get(site, "/wiki_pages.json", &[("search[title]", tag.clone()), ("only", "title,body".into())])),
                    self.run(site, self.get(site, "/related_tag.json", &[("query", tag.clone()), ("limit", "25".into())])),
                )
                .await;
                let page = serde_json::from_str::<Value>(&page?).map(|v| crate::parse::array_in(v, "wiki_pages")).unwrap_or_default();
                let info = info.ok().and_then(|b| serde_json::from_str::<Value>(&b).ok()).unwrap_or(Value::Null);
                let related = info
                    .get("related_tags")
                    .and_then(Value::as_array)
                    .map(|a| a.iter().filter_map(|r| r.pointer("/tag/name").and_then(Value::as_str)).filter(|t| *t != tag).map(str::to_owned).collect())
                    .unwrap_or_default();
                let count = info.pointer("/tag/post_count").and_then(Value::as_i64).unwrap_or(0);
                (page.first().map(|o| text(o, "body")).unwrap_or_default(), related, count)
            }
            _ => return Err(Error::Api(format!("У {} нет вики в API", site.name()))),
        };
        let (body, links) = dtext(&raw);
        Ok(Wiki { title: tag, body, links, related, count })
    }

    /// The site's most liked posts of the day, week or month.
    pub async fn popular(&self, site: Site, scale: &str, page: u32) -> Result<Vec<Post>> {
        let scale = if matches!(scale, "week" | "month") { scale } else { "day" };
        match site {
            // e621's popular page is one page.
            Site::E621 if page > 1 => Ok(Vec::new()),
            Site::E621 => {
                let body = self.e621.get("/popular.json", &[("scale", scale.into())]).await?;
                crate::parse::posts(&body).ok_or_else(|| garbled("e621"))
            }
            Site::Danbooru => {
                let q = [("scale", scale.to_owned()), ("page", page.to_string())];
                let body = self.run(site, self.get(site, "/explore/posts/popular.json", &q)).await?;
                Ok(listed(site, &body, "posts")?.iter().filter_map(danbooru_post).collect())
            }
            _ => Err(Error::Api(format!("У {} нет раздела «Популярное»", site.name()))),
        }
    }
}

/// DText (e621's and Danbooru's wiki markup) as plain text, and the tags it
/// links to: `[[tag|words]]` reads "words", `h4.` and `[b]` go, `* ` is a bullet.
pub(crate) fn dtext(src: &str) -> (String, Vec<String>) {
    let mut links: Vec<String> = Vec::new();
    let mut link = |t: &str| {
        let t = t.trim().to_lowercase().replace(' ', "_");
        if !t.is_empty() && !t.contains("://") && !links.contains(&t) {
            links.push(t);
        }
    };
    let mut lines: Vec<String> = Vec::new();
    for line in src.replace("\r\n", "\n").lines() {
        let mut l = line.trim();
        // Headings: "h4. See also" → "See also".
        if l.len() > 3 && l.starts_with('h') && l.as_bytes()[1].is_ascii_digit() && l[2..].starts_with(". ") {
            l = &l[4..];
        }
        let bullet = l.starts_with('*') && l.trim_start_matches('*').starts_with(' ');
        if bullet {
            l = l.trim_start_matches('*').trim_start();
        }
        let mut out = String::new();
        let mut rest = l;
        while !rest.is_empty() {
            if let Some(after) = rest.strip_prefix("[[")
                && let Some(end) = after.find("]]")
            {
                let inner = &after[..end];
                let (target, words) = inner.split_once('|').unwrap_or((inner, inner));
                link(target);
                out.push_str(&words.replace('_', " "));
                rest = &after[end + 2..];
            } else if let Some(after) = rest.strip_prefix("{{")
                && let Some(end) = after.find("}}")
            {
                let inner = &after[..end];
                if !inner.contains(' ') {
                    link(inner);
                }
                out.push_str(&inner.replace('_', " "));
                rest = &after[end + 2..];
            } else if let Some(after) = rest.strip_prefix("thumb #") {
                // A picture of a post: nothing to read.
                rest = after.trim_start_matches(|c: char| c.is_ascii_digit());
            } else if let Some(after) = rest.strip_prefix('"')
                && let Some(end) = after.find("\":")
                && end < 200
            {
                // "words":https://… or "words":[/path]
                out.push_str(&after[..end]);
                let url = &after[end + 2..];
                rest = match url.strip_prefix('[') {
                    Some(u) => u.find(']').map_or("", |e| &u[e + 1..]),
                    None => url.find(char::is_whitespace).map_or("", |e| &url[e..]),
                };
            } else if let Some(after) = rest.strip_prefix('[')
                && let Some(end) = after.find(']')
                && end < 40
                && after[..end].trim_start_matches('/').starts_with(|c: char| c.is_ascii_alphabetic())
                && !after[..end].split('=').next().unwrap_or("").contains(' ')
            {
                // [b], [/spoiler], [section=Title]: the markup goes, a title stays.
                if let Some((_, title)) = after[..end].split_once('=') {
                    out.push_str(title);
                }
                rest = &after[end + 1..];
            } else {
                let c = rest.chars().next().unwrap();
                out.push(c);
                rest = &rest[c.len_utf8()..];
            }
        }
        let out = out.trim();
        if out.is_empty() {
            if lines.last().is_some_and(|l| !l.is_empty()) {
                lines.push(String::new());
            }
        } else {
            lines.push(if bullet { format!("• {out}") } else { out.to_owned() });
        }
    }
    while lines.last().is_some_and(String::is_empty) {
        lines.pop();
    }
    (lines.join("\n"), links)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wiki_text_reads_plain() {
        let (body, links) = dtext(
            "thumb #2443148 thumb #676384\n\nA [[male]]-bodied character, see [[male_(lore)|canonically male]].\n\nh4. See also:\n* [[crossdressing]]\n[b]Note[/b] \"site\":https://e621.net here {{femboy rating:s}}\n[section=More]x[/section]",
        );
        assert_eq!(body, "A male-bodied character, see canonically male.\n\nSee also:\n• crossdressing\nNote site here femboy rating:s\nMorex");
        assert_eq!(links, ["male", "male_(lore)", "crossdressing"]);
    }
}
