//! What the core hands the app, in the shape the app stores (Store.kt's
//! `postFromJson` reads exactly these names).

use serde::{Deserialize, Serialize};

/// A post, whichever shape e621 sent it in.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Post {
    pub id: u64,
    /// Which booru the post is from (`Site::code`); ids repeat across sites.
    #[serde(default = "e621")]
    pub site: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub preview: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sample: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub file: Option<String>,
    /// What a phone plays best: the 720p mp4 sample, else a smaller one, else the file.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub video: Option<String>,
    #[serde(default)]
    pub ext: String,
    #[serde(default)]
    pub w: u32,
    #[serde(default)]
    pub h: u32,
    #[serde(default)]
    pub size: u64,
    #[serde(default = "safe")]
    pub rating: String,
    #[serde(default)]
    pub score: i64,
    #[serde(default)]
    pub favs: i64,
    #[serde(default)]
    pub fav: bool,
    /// How many comments the post has.
    #[serde(default)]
    pub comments: i64,
    /// The uploader's description, DText as the site keeps it.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub about: String,
    /// Tags by category, in e621's order; flat answers keep them under [`FLAT`].
    #[serde(default)]
    pub tags: serde_json::Map<String, serde_json::Value>,
    /// Set by the blacklist on the way out; never stored as truth.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub blocked: bool,
    /// The post this one is a version or a page of; 0 for none.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub parent: u64,
    /// Other posts name this one their parent.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub children: bool,
    /// Notes (translations, captions) sit on the picture.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub notes: bool,
    /// e621's pools the post is in (other sites are asked by post).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub pools: Vec<u64>,
}

fn is_zero(v: &u64) -> bool {
    *v == 0
}

pub(crate) fn e621() -> String {
    "e621".into()
}

fn safe() -> String {
    "s".into()
}

/// The key flat tags wait under for their categories (the app's `E621.FLAT`).
pub const FLAT: &str = "_flat";

/// The categories in the order the app shows them.
pub const TAG_ORDER: [&str; 8] = ["artist", "copyright", "character", "species", "general", "lore", "meta", "invalid"];

impl Post {
    /// Every tag, whatever category it sits under.
    pub fn all_tags(&self) -> impl Iterator<Item = &str> {
        self.tags.values().filter_map(|v| v.as_array()).flatten().filter_map(|t| t.as_str())
    }
}

/// One comment under a post, oldest first in a list.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Comment {
    pub id: u64,
    pub author: String,
    /// The author's account id, for [`crate::e621::Client::avatars`].
    #[serde(default)]
    pub user: u64,
    /// DText: quotes, bold, spoilers and links stay as e621 wrote them.
    pub body: String,
    pub score: i64,
    /// ISO 8601.
    pub at: String,
    /// A picture of the author, where the site has one that is not a post.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub avatar: Option<String>,
}

/// A note on a picture, in the file's own pixels.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Note {
    pub x: i64,
    pub y: i64,
    pub w: i64,
    pub h: i64,
    pub body: String,
}

/// A pool: a comic or a series, its posts in reading order.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Pool {
    pub id: u64,
    pub name: String,
    pub posts: Vec<u64>,
}

/// What a tag means, as the site's wiki says, and the tags seen with it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Wiki {
    pub title: String,
    /// DText made plain: links as their words, markup gone.
    pub body: String,
    /// The tags the page links to, in order.
    pub links: Vec<String>,
    pub related: Vec<String>,
    pub count: i64,
}

/// One of an e621 account's sets.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PostSet {
    pub id: u64,
    pub name: String,
    pub short: String,
    pub posts: Vec<u64>,
}

/// An autocomplete answer.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TagHint {
    pub name: String,
    pub count: i64,
    pub category: i64,
}

/// A saved query; with `watch` on, the core counts posts newer than `seen`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Saved {
    pub id: i64,
    pub name: String,
    pub query: String,
    /// The booru the query runs on.
    #[serde(default = "e621")]
    pub site: String,
    #[serde(default = "yes")]
    pub watch: bool,
    #[serde(default)]
    pub seen: u64,
    #[serde(default)]
    pub fresh: u32,
    #[serde(default)]
    pub checked: i64,
}

fn yes() -> bool {
    true
}

/// A collection kept on the phone: the posts themselves, so it opens offline.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Coll {
    pub id: i64,
    pub name: String,
    #[serde(default)]
    pub color: u32,
    #[serde(default)]
    pub updated: i64,
    #[serde(default)]
    pub posts: Vec<Post>,
}
