//! Locks that outlive a panic. The app calls the core through `catch_unwind`
//! (crates/android), so a panic answers one request with an error and the
//! core carries on; with `lock().unwrap()` a panic while a lock was held would
//! poison it, and every later call touching it would panic too — the core
//! dead until the app restarts. What these locks guard (lists, a cookie
//! string, the blacklist) stays usable after a panic, so the poison is set
//! aside.

use std::sync::{Mutex, MutexGuard, PoisonError, RwLock, RwLockReadGuard, RwLockWriteGuard};

pub(crate) trait Unpoisoned<T> {
    fn guard(&self) -> MutexGuard<'_, T>;
}

impl<T> Unpoisoned<T> for Mutex<T> {
    fn guard(&self) -> MutexGuard<'_, T> {
        self.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

pub(crate) trait UnpoisonedRw<T> {
    fn reader(&self) -> RwLockReadGuard<'_, T>;
    fn writer(&self) -> RwLockWriteGuard<'_, T>;
}

impl<T> UnpoisonedRw<T> for RwLock<T> {
    fn reader(&self) -> RwLockReadGuard<'_, T> {
        self.read().unwrap_or_else(PoisonError::into_inner)
    }

    fn writer(&self) -> RwLockWriteGuard<'_, T> {
        self.write().unwrap_or_else(PoisonError::into_inner)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_panic_under_a_lock_leaves_it_usable() {
        let m = std::sync::Arc::new(Mutex::new(1));
        let held = m.clone();
        let _ = std::thread::spawn(move || {
            let _g = held.guard();
            panic!("while holding it");
        })
        .join();
        assert!(m.is_poisoned());
        *m.guard() += 1;
        assert_eq!(*m.guard(), 2);
    }
}
