// value.rs
//! The value types a `.jp` document parses to.

use std::collections::HashMap;

/// Any value a `.jp` document can hold. An empty value (`key:`) parses to `Null`.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Integer(i128),
    Float(f64),
    String(String),
    Array(Vec<Value>),
    Object(Object),
}

impl Value {
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::String(text) => Some(text),
            _ => None,
        }
    }

    pub fn as_object(&self) -> Option<&Object> {
        match self {
            Value::Object(object) => Some(object),
            _ => None,
        }
    }
}

/// A section or `{...}` object. Keys keep their insertion order.
#[derive(Debug, Clone, Default)]
pub struct Object {
    entries: Vec<(String, Value)>,
    index: HashMap<String, usize>,
}

impl Object {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn contains_key(&self, key: &str) -> bool {
        self.index.contains_key(key)
    }

    pub fn get(&self, key: &str) -> Option<&Value> {
        self.index.get(key).map(|&at| &self.entries[at].1)
    }

    pub fn get_mut(&mut self, key: &str) -> Option<&mut Value> {
        let at = *self.index.get(key)?;
        Some(&mut self.entries[at].1)
    }

    /// Insert or replace. A replaced key keeps its original position.
    pub fn insert(&mut self, key: impl Into<String>, value: Value) -> Option<Value> {
        let key = key.into();
        if let Some(&at) = self.index.get(&key) {
            return Some(std::mem::replace(&mut self.entries[at].1, value));
        }
        self.index.insert(key.clone(), self.entries.len());
        self.entries.push((key, value));
        None
    }

    pub fn keys(&self) -> impl Iterator<Item = &str> {
        self.entries.iter().map(|(key, _)| key.as_str())
    }

    pub fn iter(&self) -> impl Iterator<Item = (&str, &Value)> {
        self.entries
            .iter()
            .map(|(key, value)| (key.as_str(), value))
    }
}

// Equal when the same keys map to equal values, in the same order
impl PartialEq for Object {
    fn eq(&self, other: &Self) -> bool {
        self.entries == other.entries
    }
}

impl FromIterator<(String, Value)> for Object {
    fn from_iter<T: IntoIterator<Item = (String, Value)>>(items: T) -> Self {
        let mut object = Object::new();
        for (key, value) in items {
            object.insert(key, value);
        }
        object
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn insert_keeps_order_and_position() {
        let mut object = Object::new();
        object.insert("b", Value::Integer(1));
        object.insert("a", Value::Integer(2));
        object.insert("b", Value::Integer(3));
        assert_eq!(object.keys().collect::<Vec<_>>(), ["b", "a"]);
        assert_eq!(object.get("b"), Some(&Value::Integer(3)));
    }
}
