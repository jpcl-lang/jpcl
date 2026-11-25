// json.rs
//! JSON in and out for the CLI: big integers keep every digit, and key order is kept.

use std::fmt::Write as _;

use crate::value::{Object, Value};
use crate::writer::format_finite;

/// Render a value as indented JSON, as `jpcl to-json` does in every port.
pub fn to_json(value: &Value, indent: usize) -> String {
    let mut out = String::new();
    render(&mut out, value, indent, 0);
    out
}

fn render(out: &mut String, value: &Value, indent: usize, level: usize) {
    match value {
        Value::Null => out.push_str("null"),
        Value::Bool(flag) => out.push_str(if *flag { "true" } else { "false" }),
        Value::Integer(integer) => {
            let _ = write!(out, "{integer}");
        }
        Value::Float(float) if float.is_nan() => out.push_str("NaN"),
        Value::Float(float) if float.is_infinite() => {
            out.push_str(if *float > 0.0 {
                "Infinity"
            } else {
                "-Infinity"
            });
        }
        Value::Float(float) => out.push_str(&format_finite(*float)),
        Value::String(text) => quote(out, text),
        Value::Array(items) if items.is_empty() => out.push_str("[]"),
        Value::Array(items) => {
            out.push('[');

            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }

                newline(out, indent * (level + 1));
                render(out, item, indent, level + 1);
            }

            newline(out, indent * level);
            out.push(']');
        }
        Value::Object(object) if object.is_empty() => out.push_str("{}"),
        Value::Object(object) => {
            out.push('{');

            for (i, (key, child)) in object.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }

                newline(out, indent * (level + 1));
                quote(out, key);
                out.push_str(": ");
                render(out, child, indent, level + 1);
            }

            newline(out, indent * level);
            out.push('}');
        }
    }
}

fn newline(out: &mut String, spaces: usize) {
    out.push('\n');
    out.extend(std::iter::repeat_n(' ', spaces));
}

/// A JSON string literal, escaped as `JSON.stringify` does.
fn quote(out: &mut String, text: &str) {
    out.push('"');

    for char in text.chars() {
        match char {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\x08' => out.push_str("\\b"),
            '\x0c' => out.push_str("\\f"),
            '\0'..='\x1f' => {
                let _ = write!(out, "\\u{:04x}", char as u32);
            }
            _ => out.push(char),
        }
    }

    out.push('"');
}

/// Parse JSON text. Duplicate keys keep the last value, as `JSON.parse` does.
pub fn from_json(text: &str) -> Result<Value, String> {
    let mut reader = Reader {
        bytes: text.as_bytes(),
        text,
        pos: 0,
        depth: 0,
    };
    reader.skip_space();
    let value = reader.value()?;
    reader.skip_space();

    if reader.pos < reader.bytes.len() {
        return Err(reader.error("unexpected text after the value"));
    }

    Ok(value)
}

struct Reader<'a> {
    bytes: &'a [u8],
    text: &'a str,
    pos: usize,
    depth: usize,
}

impl Reader<'_> {
    fn error(&self, message: &str) -> String {
        format!("invalid JSON at byte {}: {message}", self.pos)
    }

    fn skip_space(&mut self) {
        while matches!(self.bytes.get(self.pos), Some(b' ' | b'\t' | b'\n' | b'\r')) {
            self.pos += 1;
        }
    }

    fn eat(&mut self, byte: u8) -> bool {
        let found = self.bytes.get(self.pos) == Some(&byte);

        if found {
            self.pos += 1;
        }

        found
    }

    fn value(&mut self) -> Result<Value, String> {
        match self.bytes.get(self.pos) {
            Some(b'{') => self.container(true),
            Some(b'[') => self.container(false),
            Some(b'"') => Ok(Value::String(self.string()?)),
            Some(b'-' | b'0'..=b'9') => self.number(),
            _ => {
                for (word, value) in [
                    ("true", Value::Bool(true)),
                    ("false", Value::Bool(false)),
                    ("null", Value::Null),
                ] {
                    if self.text[self.pos..].starts_with(word) {
                        self.pos += word.len();
                        return Ok(value);
                    }
                }

                Err(self.error("expected a value"))
            }
        }
    }

    fn container(&mut self, is_object: bool) -> Result<Value, String> {
        self.depth += 1;

        if self.depth > crate::MAX_DEPTH {
            return Err(self.error("nesting too deep"));
        }

        self.pos += 1;
        let close = if is_object { b'}' } else { b']' };
        let mut object = Object::new();
        let mut items = Vec::new();

        self.skip_space();

        if !self.eat(close) {
            loop {
                self.skip_space();

                if is_object {
                    if self.bytes.get(self.pos) != Some(&b'"') {
                        return Err(self.error("expected a string key"));
                    }

                    let key = self.string()?;
                    self.skip_space();

                    if !self.eat(b':') {
                        return Err(self.error("expected ':'"));
                    }

                    self.skip_space();
                    let value = self.value()?;
                    object.insert(key, value);
                } else {
                    items.push(self.value()?);
                }

                self.skip_space();

                if self.eat(close) {
                    break;
                }

                if !self.eat(b',') {
                    return Err(self.error("expected ',' or a closing bracket"));
                }
            }
        }

        self.depth -= 1;
        Ok(if is_object {
            Value::Object(object)
        } else {
            Value::Array(items)
        })
    }

    fn string(&mut self) -> Result<String, String> {
        self.pos += 1;
        let mut out = String::new();

        loop {
            let start = self.pos;

            while self.pos < self.bytes.len()
                && !matches!(self.bytes[self.pos], b'"' | b'\\' | 0..=0x1f)
            {
                self.pos += 1;
            }

            out.push_str(&self.text[start..self.pos]);

            match self.bytes.get(self.pos) {
                Some(b'"') => {
                    self.pos += 1;
                    return Ok(out);
                }
                Some(b'\\') => {
                    self.pos += 1;
                    let marker = self.bytes.get(self.pos).copied();
                    self.pos += 1;
                    out.push(match marker {
                        Some(b'"') => '"',
                        Some(b'\\') => '\\',
                        Some(b'/') => '/',
                        Some(b'b') => '\x08',
                        Some(b'f') => '\x0c',
                        Some(b'n') => '\n',
                        Some(b'r') => '\r',
                        Some(b't') => '\t',
                        Some(b'u') => self.unicode_escape()?,
                        _ => return Err(self.error("invalid escape")),
                    });
                }
                _ => return Err(self.error("unterminated string")),
            }
        }
    }

    fn hex4(&mut self) -> Result<u32, String> {
        let digits = self
            .text
            .get(self.pos..self.pos + 4)
            .ok_or_else(|| self.error("short \\u escape"))?;
        let code = u32::from_str_radix(digits, 16).map_err(|_| self.error("invalid \\u escape"))?;
        self.pos += 4;
        Ok(code)
    }

    fn unicode_escape(&mut self) -> Result<char, String> {
        let high = self.hex4()?;

        if (0xd800..=0xdbff).contains(&high) && self.text[self.pos..].starts_with("\\u") {
            self.pos += 2;
            let low = self.hex4()?;

            if (0xdc00..=0xdfff).contains(&low) {
                return Ok(
                    char::from_u32(0x10000 + ((high - 0xd800) << 10) + (low - 0xdc00)).unwrap(),
                );
            }

            return Err(self.error("unpaired surrogate"));
        }

        char::from_u32(high).ok_or_else(|| self.error("unpaired surrogate"))
    }

    fn number(&mut self) -> Result<Value, String> {
        let start = self.pos;
        self.eat(b'-');
        let digits_start = self.pos;

        while matches!(self.bytes.get(self.pos), Some(b'0'..=b'9')) {
            self.pos += 1;
        }

        let integer_digits = self.pos - digits_start;

        if integer_digits == 0 || (integer_digits > 1 && self.bytes[digits_start] == b'0') {
            return Err(self.error("invalid number"));
        }

        let mut is_integer = true;

        if self.eat(b'.') {
            is_integer = false;
            let fraction_start = self.pos;

            while matches!(self.bytes.get(self.pos), Some(b'0'..=b'9')) {
                self.pos += 1;
            }

            if self.pos == fraction_start {
                return Err(self.error("invalid number"));
            }
        }

        if matches!(self.bytes.get(self.pos), Some(b'e' | b'E')) {
            is_integer = false;
            self.pos += 1;

            if matches!(self.bytes.get(self.pos), Some(b'+' | b'-')) {
                self.pos += 1;
            }

            let exponent_start = self.pos;

            while matches!(self.bytes.get(self.pos), Some(b'0'..=b'9')) {
                self.pos += 1;
            }

            if self.pos == exponent_start {
                return Err(self.error("invalid number"));
            }
        }

        let text = &self.text[start..self.pos];

        if is_integer {
            if let Ok(integer) = text.parse::<i128>() {
                return Ok(Value::Integer(integer));
            }
        }

        text.parse::<f64>()
            .map(Value::Float)
            .map_err(|_| self.error("invalid number"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::{ParseOptions, parse};

    #[test]
    fn json_output() {
        let root = parse(
            "id: 111111111111111111\nn: [nan, -inf, 0.5]\ne: {}\ns: \"a\\\"b\\u0001\"\n",
            &ParseOptions::default(),
        )
        .unwrap();
        let expected = concat!(
            "{\n  \"id\": 111111111111111111,\n  \"n\": [\n    NaN,\n    -Infinity,\n    0.5\n  ],\n",
            "  \"e\": {},\n  \"s\": \"a\\\"b\\u0001\"\n}",
        );
        assert_eq!(to_json(&Value::Object(root), 2), expected);
    }

    #[test]
    fn json_input() {
        let value = from_json(r#" {"z": 1, "s": {"id": 123456789012345678901234, "f": -1.5e3, "u": "é😀"}, "a": [true, null]} "#)
        .unwrap();
        let Value::Object(root) = value else {
            panic!("expected an object")
        };
        assert_eq!(root.keys().collect::<Vec<_>>(), ["z", "s", "a"]);
        let section = root.get("s").unwrap().as_object().unwrap();
        assert_eq!(
            section.get("id"),
            Some(&Value::Integer(123456789012345678901234))
        );
        assert_eq!(section.get("f"), Some(&Value::Float(-1500.0)));
        assert_eq!(section.get("u").unwrap().as_str(), Some("é😀"));
        assert!(from_json("{\"a\": 01}").is_err());
        assert!(from_json("[1,]").is_err());
        assert!(from_json("{} x").is_err());
    }
}
