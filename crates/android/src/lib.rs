//! The calls the app makes into the core (`app.booruhub.Core`).
//!
//! `init` once, with the app's folder, the WebView's User-Agent and the
//! phone's proxy; then `call` with a JSON request for everything else
//! (booruhub-core's rpc.rs), and `cancel` from another thread to stop a call
//! by its rid. Strings in, strings out: nothing else crosses.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::OnceLock;

use booruhub_core::Core;
use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jlong, jstring};

static CORE: OnceLock<Core> = OnceLock::new();

fn string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    if value.is_null() {
        return None;
    }
    env.get_string(value).ok().map(Into::into)
}

/// Answers whether the core is ready. A second call keeps the first core.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_booruhub_Core_init(
    mut env: JNIEnv,
    _this: JObject,
    dir: JString,
    agent: JString,
    proxy: JString,
) -> jboolean {
    let (Some(dir), Some(agent)) = (string(&mut env, &dir), string(&mut env, &agent)) else {
        return 0;
    };
    let proxy = string(&mut env, &proxy);
    let made = catch_unwind(AssertUnwindSafe(|| {
        CORE.get_or_init(|| Core::new(Path::new(&dir), &agent, proxy.as_deref()));
    }));
    made.is_ok() as jboolean
}

/// One JSON request in, one JSON answer out. A panic in the core becomes an
/// error answer instead of taking the app down with it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_booruhub_Core_call(mut env: JNIEnv, _this: JObject, request: JString) -> jstring {
    let answer = match (CORE.get(), string(&mut env, &request)) {
        (None, _) => r#"{"err":{"kind":"bad","msg":"ядро не запущено"}}"#.to_owned(),
        (_, None) => r#"{"err":{"kind":"bad","msg":"пустой запрос"}}"#.to_owned(),
        (Some(core), Some(req)) => catch_unwind(AssertUnwindSafe(|| core.call(&req)))
            .unwrap_or_else(|_| r#"{"err":{"kind":"bad","msg":"ядро упало на этом запросе"}}"#.to_owned()),
    };
    env.new_string(answer).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Stops the call with this rid; that call answers `cancelled` right away.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_booruhub_Core_cancel(_env: JNIEnv, _this: JObject, rid: jlong) {
    if let Some(core) = CORE.get() {
        let _ = catch_unwind(AssertUnwindSafe(|| core.cancel(rid as u64)));
    }
}
