//! e621's answers, in both shapes.
//!
//! posts.json used to answer `{"posts": [...]}` with `file`/`sample`/`preview`
//! and tags by category. By November 2026 it answers a bare array with
//! `files.{meta,original,preview,sample,video}`, `stats`, and the tags flat.
//! Both are read; the app never sees the difference.

use serde_json::{Map, Value};

use crate::model::{FLAT, Post, TAG_ORDER};

/// The array of `key` in an answer that is either the array itself or wraps it.
pub fn array_in(value: Value, key: &str) -> Vec<Value> {
    match value {
        Value::Array(a) => a,
        Value::Object(mut o) => match o.remove(key) {
            Some(Value::Array(a)) => a,
            _ => Vec::new(),
        },
        _ => Vec::new(),
    }
}

/// None when the answer is not JSON at all (a maintenance page, say), so
/// that reads as a failure rather than as "nothing found".
pub fn posts(text: &str) -> Option<Vec<Post>> {
    if text.trim().is_empty() {
        return Some(Vec::new());
    }
    let value = serde_json::from_str::<Value>(text).ok()?;
    Some(array_in(value, "posts").into_iter().filter_map(post).collect())
}

/// A string field, or None when missing, null or blank.
fn s(o: &Value, key: &str) -> Option<String> {
    o.get(key).and_then(Value::as_str).filter(|v| !v.trim().is_empty()).map(str::to_owned)
}

fn int(o: &Value, key: &str) -> i64 {
    o.get(key).and_then(Value::as_i64).unwrap_or(0)
}

/// A width, height or size: 0 when missing or nonsense (negative).
fn whole<T: TryFrom<i64> + Default>(o: &Value, key: &str) -> T {
    T::try_from(int(o, key)).unwrap_or_default()
}

// Each post is taken by value: its tag lists move into the Post instead of
// being copied (a post carries ~100 tags, a page 60 posts).
fn post(mut o: Value) -> Option<Post> {
    let tags = o.get_mut("tags").map(Value::take);
    if o.get("files").is_some() { new_shape(&o, tags) } else { old_shape(&o, tags) }
}

fn grouped(tags: Option<Value>) -> Map<String, Value> {
    let Some(Value::Object(mut by_cat)) = tags else { return Map::new() };
    let mut out = Map::new();
    for cat in TAG_ORDER {
        if let Some(Value::Array(a)) = by_cat.remove(cat)
            && !a.is_empty()
        {
            out.insert(cat.into(), Value::Array(a));
        }
    }
    out
}

fn new_shape(o: &Value, tags: Option<Value>) -> Option<Post> {
    let files = o.get("files")?;
    let null = Value::Null;
    let meta = files.get("meta").unwrap_or(&null);
    let original = files.get("original").unwrap_or(&null);
    let stats = o.get("stats").unwrap_or(&null);
    let picture = |v: Option<&Value>| v.and_then(|v| s(v, "webp").or_else(|| s(v, "jpg")).or_else(|| s(v, "url")));
    let has_sample = meta.get("has_sample").and_then(Value::as_bool).unwrap_or(true);

    // A phone plays the 720p mp4 sample best; then a smaller one; then the mp4
    // variant; then the file itself.
    let video = files.get("video").filter(|v| v.get("has").and_then(Value::as_bool).unwrap_or(true)).and_then(|v| {
        let samples = v.get("samples");
        samples
            .and_then(|x| x.get("720p"))
            .and_then(|x| s(x, "url"))
            .or_else(|| samples.and_then(|x| x.get("480p")).and_then(|x| s(x, "url")))
            .or_else(|| v.get("variants").and_then(|x| x.get("mp4")).and_then(|x| s(x, "url")))
            .or_else(|| v.get("original").and_then(|x| s(x, "url")))
    });

    let tags = match tags {
        Some(flat @ Value::Array(_)) => Map::from_iter([(FLAT.to_owned(), flat)]),
        other => grouped(other),
    };

    Some(Post {
        id: o.get("id")?.as_u64()?,
        site: crate::model::e621(),
        preview: picture(files.get("preview")),
        sample: if has_sample { picture(files.get("sample")) } else { None },
        file: s(original, "url"),
        video,
        ext: s(meta, "ext").unwrap_or_default(),
        w: whole(original, "width"),
        h: whole(original, "height"),
        size: whole(meta, "size"),
        rating: s(o, "rating").unwrap_or_else(|| "s".into()),
        score: stats.get("score").map(|v| int(v, "total")).unwrap_or(0),
        favs: int(stats, "fav_count"),
        fav: stats.get("is_favorited").and_then(Value::as_bool).unwrap_or(false),
        comments: int(stats, "comment_count"),
        about: s(o, "description").unwrap_or_default(),
        tags,
        blocked: false,
        ..family(o)
    })
}

fn old_shape(o: &Value, tags: Option<Value>) -> Option<Post> {
    let null = Value::Null;
    let file = o.get("file").unwrap_or(&null);
    let sample = o.get("sample").filter(|v| v.get("has").and_then(Value::as_bool).unwrap_or(true));
    Some(Post {
        id: o.get("id")?.as_u64()?,
        site: crate::model::e621(),
        preview: o.get("preview").and_then(|v| s(v, "url")),
        sample: sample.and_then(|v| s(v, "url")),
        file: s(file, "url"),
        video: s(file, "url").filter(|_| matches!(s(file, "ext").as_deref(), Some("webm" | "mp4"))),
        ext: s(file, "ext").unwrap_or_default(),
        w: whole(file, "width"),
        h: whole(file, "height"),
        size: whole(file, "size"),
        rating: s(o, "rating").unwrap_or_else(|| "s".into()),
        score: o.get("score").map(|v| int(v, "total")).unwrap_or(0),
        favs: int(o, "fav_count"),
        fav: o.get("is_favorited").and_then(Value::as_bool).unwrap_or(false),
        comments: int(o, "comment_count"),
        about: s(o, "description").unwrap_or_default(),
        tags: grouped(tags),
        blocked: false,
        ..family(o)
    })
}

/// Parent, children, notes and pools, the same in both shapes; the rest of
/// the post is filled in by the caller.
fn family(o: &Value) -> Post {
    let rel = o.get("relationships");
    let has = |k: &str| o.pointer(&format!("/has/{k}")).and_then(Value::as_bool).unwrap_or(false);
    Post {
        parent: rel.and_then(|r| r.get("parent_id")).and_then(Value::as_u64).unwrap_or(0),
        children: has("children")
            || rel.and_then(|r| r.get("children")).and_then(Value::as_array).is_some_and(|c| !c.is_empty())
            || o.get("has_children").and_then(Value::as_bool).unwrap_or(false),
        notes: has("notes") || o.get("has_notes").and_then(Value::as_bool).unwrap_or(false),
        pools: o.get("pools").and_then(Value::as_array).map(|a| a.iter().filter_map(Value::as_u64).collect()).unwrap_or_default(),
        ..Post::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NEW: &str = r#"[{"id":6874802,"files":{"meta":{"ext":"webm","size":19472226,"has_sample":true},
        "original":{"width":1080,"height":1080,"url":"https://s/a.webm"},
        "preview":{"jpg":"https://s/p.jpg","webp":"https://s/p.webp"},
        "sample":{"jpg":"https://s/s.jpg","webp":"https://s/s.webp"},
        "video":{"has":true,"variants":{"mp4":{"url":"https://s/alt.mp4"}},
                 "samples":{"480p":{"url":"https://s/480.mp4"},"720p":{"url":"https://s/720.mp4"}}}},
        "stats":{"score":{"total":18},"fav_count":24,"is_favorited":false},
        "rating":"e","tags":["fox","solo"]}]"#;

    const OLD: &str = r#"{"posts":[{"id":1,"file":{"url":"https://s/f.png","ext":"png","width":10,"height":20,"size":5},
        "sample":{"has":true,"url":"https://s/s.jpg"},"preview":{"url":"https://s/p.jpg"},
        "score":{"total":3},"fav_count":4,"rating":"s",
        "tags":{"general":["a","b"],"artist":["x"],"meta":[]}}]}"#;

    #[test]
    fn reads_the_2026_shape() {
        let p = &posts(NEW).unwrap()[0];
        assert_eq!(p.id, 6874802);
        assert_eq!(p.video.as_deref(), Some("https://s/720.mp4"));
        assert_eq!(p.sample.as_deref(), Some("https://s/s.webp"));
        assert_eq!((p.w, p.h, p.size, p.score, p.favs), (1080, 1080, 19472226, 18, 24));
        assert_eq!(p.all_tags().collect::<Vec<_>>(), ["fox", "solo"]);
        assert!(p.tags.contains_key(FLAT));
    }

    #[test]
    fn reads_the_old_shape_with_categories_in_order() {
        let p = &posts(OLD).unwrap()[0];
        assert_eq!(p.file.as_deref(), Some("https://s/f.png"));
        assert_eq!(p.tags.keys().collect::<Vec<_>>(), ["artist", "general"]);
    }
}
