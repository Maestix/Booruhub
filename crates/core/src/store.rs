//! Saved queries and collections, kept as JSON files in the app's folder.
//! Writes go to a temporary file first and replace the old one in one rename,
//! so a crash mid-write never leaves half a file.

use std::fs;
use std::path::{Path, PathBuf};

use serde::Serialize;
use serde::de::DeserializeOwned;

pub struct Files {
    dir: PathBuf,
}

impl Files {
    pub fn new(dir: &Path) -> Self {
        let _ = fs::create_dir_all(dir);
        Self { dir: dir.to_owned() }
    }

    fn path(&self, name: &str) -> PathBuf {
        self.dir.join(format!("{name}.json"))
    }

    pub fn exists(&self, name: &str) -> bool {
        self.path(name).exists()
    }

    pub fn load<T: DeserializeOwned + Default>(&self, name: &str) -> T {
        fs::read(self.path(name)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
    }

    pub fn save<T: Serialize>(&self, name: &str, value: &T) {
        let Ok(bytes) = serde_json::to_vec(value) else { return };
        let tmp = self.dir.join(format!("{name}.json.tmp"));
        if fs::write(&tmp, bytes).is_ok() {
            let _ = fs::rename(&tmp, self.path(name));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saves_and_loads() {
        let dir = std::env::temp_dir().join(format!("booruhub-store-{}", std::process::id()));
        let f = Files::new(&dir);
        assert!(!f.exists("x"));
        f.save("x", &vec![1, 2, 3]);
        assert_eq!(f.load::<Vec<i32>>("x"), vec![1, 2, 3]);
        let _ = fs::remove_dir_all(dir);
    }
}
