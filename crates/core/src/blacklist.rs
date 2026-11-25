//! e621's blacklist rules: each line is a set of terms that must all hold;
//! "-tag" must be absent, "rating:x" matches the rating, "*" is a wildcard.

use std::collections::HashSet;

use crate::model::Post;

enum Term {
    Tag(String),
    Wild(Vec<String>),
    Rating(char),
}

struct Rule(Vec<(bool, Term)>);

/// Rules parsed once, when the list changes, and matched against every post
/// the core hands out.
#[derive(Default)]
pub struct Rules(Vec<Rule>);

impl Rules {
    pub fn parse<S: AsRef<str>>(lines: &[S]) -> Self {
        Self(
            lines
                .iter()
                .map(|l| {
                    Rule(
                        l.as_ref()
                            .split_whitespace()
                            .map(|t| {
                                let (neg, t) = t.strip_prefix('-').map_or((false, t), |r| (true, r));
                                let t = t.to_lowercase();
                                let term = if let Some(r) = t.strip_prefix("rating:") {
                                    Term::Rating(r.chars().next().unwrap_or('s'))
                                } else if t.contains('*') {
                                    Term::Wild(t.split('*').map(str::to_owned).collect())
                                } else {
                                    Term::Tag(t)
                                };
                                (neg, term)
                            })
                            .collect(),
                    )
                })
                .filter(|r| !r.0.is_empty())
                .collect(),
        )
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    pub fn blocks(&self, p: &Post) -> bool {
        if self.0.is_empty() {
            return false;
        }
        let tags: HashSet<&str> = p.all_tags().collect();
        let rating = p.rating.chars().next().unwrap_or('s');
        self.0.iter().any(|rule| {
            rule.0.iter().all(|(neg, term)| {
                let hit = match term {
                    Term::Tag(t) => tags.contains(t.as_str()),
                    Term::Rating(r) => *r == rating,
                    Term::Wild(parts) => tags.iter().any(|t| wild(parts, t)),
                };
                hit != *neg
            })
        })
    }
}

/// `parts` are a mask split at its "*": the first must start the tag, the
/// last must end it, and the rest must appear in order between.
fn wild(parts: &[String], tag: &str) -> bool {
    let (Some(first), Some(last)) = (parts.first(), parts.last()) else { return false };
    if !tag.starts_with(first.as_str()) || tag.len() < first.len() + last.len() || !tag.ends_with(last.as_str()) {
        return false;
    }
    let mut rest = &tag[first.len()..tag.len() - last.len()];
    for mid in &parts[1..parts.len() - 1] {
        match rest.find(mid.as_str()) {
            Some(i) => rest = &rest[i + mid.len()..],
            None => return false,
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn post(rating: &str, tags: &[&str]) -> Post {
        serde_json::from_value(serde_json::json!({
            "id": 1, "rating": rating, "tags": { "general": tags }
        }))
        .unwrap()
    }

    #[test]
    fn rules_match_as_on_the_site() {
        let r = Rules::parse(&["gore", "fox -solo", "blood*", "rating:e feral", "*_tail"]);
        assert!(r.blocks(&post("s", &["gore"])));
        assert!(r.blocks(&post("s", &["fox", "duo"])));
        assert!(!r.blocks(&post("s", &["fox", "solo"])));
        assert!(r.blocks(&post("s", &["bloody"])));
        assert!(r.blocks(&post("e", &["feral"])));
        assert!(!r.blocks(&post("s", &["feral"])));
        assert!(r.blocks(&post("s", &["fox_tail", "solo"])));
        assert!(!r.blocks(&post("s", &["cat"])));
    }
}
