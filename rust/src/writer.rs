// writer.rs
//! Turns values back into `.jp` source in one fixed, deterministic style.

use std::fmt::Write as _;

use crate::value::{Object, Value};

#[derive(Debug, Clone)]
pub struct StringifyOptions {
    /// Spaces per nesting level.
    pub indent: usize,
    /// Column budget for keeping an array on one line.
    pub width: usize,
    /// Sort keys by code point instead of keeping insertion order.
    pub sort_keys: bool,
    /// Escape non-ASCII characters instead of writing them literally.
    pub ensure_ascii: bool,
}

impl Default for StringifyOptions {
    fn default() -> Self {
        StringifyOptions {
            indent: 2,
            width: 88,
            sort_keys: false,
            ensure_ascii: false,
        }
    }
}

/// Serialise a document. The result ends with a newline; an empty object gives `""`.
pub fn stringify(root: &Object, options: &StringifyOptions) -> String {
    Writer { options }.document(root)
}

struct Writer<'a> {
    options: &'a StringifyOptions,
}

impl Writer<'_> {
    fn document(&self, root: &Object) -> String {
        let mut preamble: Vec<String> = Vec::new();
        let mut blocks: Vec<String> = Vec::new();

        for (key, value) in self.items(root) {
            if let Value::Object(section) = value {
                let mut block = format!("[{}]", self.format_key(key));
                for (child, child_value) in self.items(section) {
                    block.push('\n');
                    block.push_str(&self.entry(child, child_value, 0));
                }
                blocks.push(block);
            } else {
                // A root scalar after a header would be read back into that section, so hoist it
                preamble.push(self.entry(key, value, 0));
            }
        }

        if !preamble.is_empty() {
            blocks.insert(0, preamble.join("\n"));
        }
        if blocks.is_empty() {
            return String::new();
        }
        blocks.join("\n\n") + "\n"
    }

    /// One `key: value` line, possibly spanning several lines.
    fn entry(&self, key: &str, value: &Value, level: usize) -> String {
        let prefix = format!("{}{}:", self.pad(level), self.format_key(key));
        if *value == Value::Null {
            return prefix;
        }
        let column = prefix.chars().count() + 1;
        format!("{prefix} {}", self.render(value, level, column))
    }

    fn pad(&self, level: usize) -> String {
        " ".repeat(self.options.indent * level)
    }

    fn format_key(&self, key: &str) -> String {
        if is_bare_key(key) {
            key.to_string()
        } else {
            self.format_string(key)
        }
    }

    /// A string literal with the minimum necessary escaping.
    fn format_string(&self, value: &str) -> String {
        let mut out = String::from('"');
        for char in value.chars() {
            match char {
                '\\' => out.push_str("\\\\"),
                '"' => out.push_str("\\\""),
                '\n' => out.push_str("\\n"),
                '\r' => out.push_str("\\r"),
                '\t' => out.push_str("\\t"),
                '\x08' => out.push_str("\\b"),
                '\x0c' => out.push_str("\\f"),
                _ if char < ' '
                    || char == '\x7f'
                    || (self.options.ensure_ascii && char > '\x7e') =>
                {
                    push_unicode_escape(&mut out, char);
                }
                _ => out.push(char),
            }
        }
        out.push('"');
        out
    }

    /// Render any value; `column` is where it starts on the current line.
    fn render(&self, value: &Value, level: usize, column: usize) -> String {
        match value {
            Value::Null => "null".into(),
            Value::Bool(flag) => flag.to_string(),
            Value::String(text) => self.format_string(text),
            Value::Integer(integer) => integer.to_string(),
            Value::Float(float) => format_number(*float),
            Value::Object(object) => self.render_object(object, level),
            Value::Array(items) => self.render_array(items, level, column),
        }
    }

    fn render_object(&self, object: &Object, level: usize) -> String {
        if object.is_empty() {
            return "{}".into();
        }
        let body: Vec<String> = self
            .items(object)
            .into_iter()
            .map(|(key, value)| self.entry(key, value, level + 1))
            .collect();
        format!("{{\n{}\n{}}}", body.join(",\n"), self.pad(level))
    }

    /// `[...]`, inline when it fits on the line.
    fn render_array(&self, items: &[Value], level: usize, column: usize) -> String {
        if items.is_empty() {
            return "[]".into();
        }
        let parts: Vec<String> = items
            .iter()
            .map(|item| self.render(item, level + 1, column))
            .collect();
        let inline = format!("[{}]", parts.join(", "));
        if !inline.contains('\n') && column + inline.chars().count() <= self.options.width {
            return inline;
        }
        let inner = self.pad(level + 1);
        let body: Vec<String> = parts.iter().map(|part| format!("{inner}{part}")).collect();
        format!("[\n{}\n{}]", body.join(",\n"), self.pad(level))
    }

    /// Entries in output order.
    fn items<'o>(&self, object: &'o Object) -> Vec<(&'o str, &'o Value)> {
        let mut items: Vec<_> = object.iter().collect();
        if self.options.sort_keys {
            // UTF-8 byte order is code point order
            items.sort_by(|left, right| left.0.cmp(right.0));
        }
        items
    }
}

/// Keys matching `[A-Za-z0-9_][A-Za-z0-9_-]*` are written without quotes.
fn is_bare_key(key: &str) -> bool {
    !key.is_empty()
        && !key.starts_with('-')
        && key
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}

/// `\uXXXX`, or a UTF-16 surrogate pair for astral characters.
pub(crate) fn push_unicode_escape(out: &mut String, char: char) {
    let mut units = [0; 2];
    for unit in char.encode_utf16(&mut units) {
        let _ = write!(out, "\\u{unit:04x}");
    }
}

/// A number, including the non-finite literals `nan`, `inf` and `-inf`.
fn format_number(value: f64) -> String {
    if value.is_nan() {
        return "nan".into();
    }
    if value.is_infinite() {
        return if value > 0.0 { "inf" } else { "-inf" }.into();
    }
    format_finite(value)
}

/// Render a finite float exactly as jpcl-py and jpcl-lang do, so all writers emit
/// the same bytes: a whole number below 1e16 as an integer, anything else as the
/// shortest round-tripping digits, in scientific notation when the exponent is
/// below -4 or at least 16 (`1e-05`, `1e+16`).
pub fn format_finite(value: f64) -> String {
    if value == value.trunc() && value.abs() < 1e16 {
        return (value as i64).to_string();
    }

    // `{:e}` gives the shortest unique digits, as `d.ddde±x`
    let scientific = format!("{:e}", value.abs());
    let (mantissa, exponent) = scientific.split_once('e').unwrap();
    let exponent: i32 = exponent.parse().unwrap();
    let digits: String = mantissa.chars().filter(|&char| char != '.').collect();
    let sign = if value < 0.0 { "-" } else { "" };

    if !(-4..16).contains(&exponent) {
        let fraction = if digits.len() > 1 {
            format!(".{}", &digits[1..])
        } else {
            String::new()
        };
        let exponent_sign = if exponent < 0 { '-' } else { '+' };
        return format!(
            "{sign}{}{fraction}e{exponent_sign}{:02}",
            &digits[..1],
            exponent.abs()
        );
    }
    if exponent < 0 {
        let zeros = "0".repeat((-exponent - 1) as usize);
        return format!("{sign}0.{zeros}{digits}");
    }
    let point = (exponent + 1) as usize;
    format!("{sign}{}.{}", &digits[..point], &digits[point..])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::{ParseOptions, parse};

    #[test]
    fn float_formatting_matches_the_other_writers() {
        let cases = [
            ("3", 3.0),
            ("0", -0.0),
            ("0.1", 0.1),
            ("123.456", 123.456),
            ("1e-05", 1e-5),
            ("0.0001", 1e-4),
            ("-2.5e-07", -2.5e-7),
            ("1e+16", 1e16),
            ("1.5e+300", 1.5e300),
            ("1234567890123456.8", 1234567890123456.8),
        ];
        for (expected, value) in cases {
            assert_eq!(format_finite(value), expected);
        }
    }

    fn round_trip(source: &str, options: &StringifyOptions) -> String {
        stringify(&parse(source, &ParseOptions::default()).unwrap(), options)
    }

    #[test]
    fn document_layout() {
        let source = concat!(
            "[b]\nx: 1\nversion: 2\n[SERVER_ID]\n",
            "config: {disabled_channels:, disabled_users: [9892, 82082, 8209], e: {}}\n",
            "\"needs quotes\": \"tab\\t é\"\n",
        );
        let expected = concat!(
            "[b]\nx: 1\nversion: 2\n\n[SERVER_ID]\nconfig: {\n",
            "  disabled_channels:,\n  disabled_users: [9892, 82082, 8209],\n  e: {}\n}\n",
            "\"needs quotes\": \"tab\\t é\"\n",
        );
        assert_eq!(round_trip(source, &StringifyOptions::default()), expected);
    }

    #[test]
    fn root_scalars_are_hoisted_above_sections() {
        let text = round_trip(
            "a: [null, 1.0, nan, -inf]\n[s]\nk:\n",
            &StringifyOptions::default(),
        );
        assert_eq!(text, "a: [null, 1, nan, -inf]\n\n[s]\nk:\n");
        assert_eq!(round_trip("", &StringifyOptions::default()), "");
    }

    #[test]
    fn arrays_wrap_when_they_outgrow_the_width() {
        let options = StringifyOptions {
            width: 30,
            ..StringifyOptions::default()
        };
        let text = round_trip(
            "[s]\nids: [111111111111111111, 222222222222222222]\n",
            &options,
        );
        assert_eq!(
            text,
            "[s]\nids: [\n  111111111111111111,\n  222222222222222222\n]\n"
        );
    }

    #[test]
    fn sort_keys_and_ensure_ascii() {
        let options = StringifyOptions {
            sort_keys: true,
            ensure_ascii: true,
            ..StringifyOptions::default()
        };
        let text = round_trip("b: \"é😀\"\na: 1\n", &options);
        assert_eq!(text, "a: 1\nb: \"\\u00e9\\ud83d\\ude00\"\n");
    }
}
