//! BooruHub's core, shared by the Android app (through `crates/android`) and
//! anything else that wants e621 without a phone.
//!
//! The app talks to it through [`Core::call`]: one JSON request in, one JSON
//! answer out (`rpc.rs`). Everything that is not Android's own business lives
//! here — e621's API and both shapes of its answers, the blacklist, saved
//! queries with their new-post counts, and collections on disk. The screens,
//! the WebView that passes Cloudflare, pictures, video and the gallery stay in
//! Kotlin.

mod blacklist;
mod boorus;
mod download;
mod e621;
mod model;
mod parse;
mod rpc;
mod store;

pub use blacklist::Rules;
pub use boorus::{Boorus, Site};
pub use e621::{Client, Error};
pub use model::{Coll, Post, Saved, TagHint};
pub use rpc::Core;

/// Milliseconds since the epoch, as the app's own clocks count.
pub(crate) fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
