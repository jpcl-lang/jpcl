// scanner.rs
//! Lexical scanning for `.jp`: whitespace, comments, strings and bare tokens.

use std::fmt::Write as _;

use crate::error::DecodeError;
use crate::value::Value;

// Starts a comment that runs to the end of the line
pub const LINE_COMMENT: &str = "~~";

// Opens a block comment, which may span lines
pub const BLOCK_OPEN: &str = "~*";

// Closes a block comment
pub const BLOCK_CLOSE: &str = "*~";

pub type Result<T> = std::result::Result<T, DecodeError>;

fn is_inline_space(byte: u8) -> bool {
    matches!(byte, b' ' | b'\t' | 0x0b | 0x0c)
}

fn is_line_break(byte: u8) -> bool {
    byte == b'\r' || byte == b'\n'
}

#[derive(Clone, Copy)]
pub enum Terminators {
    Value,
    Key,
    Header,
}

impl Terminators {
    fn ends(self, byte: u8) -> bool {
        match self {
            Terminators::Value => matches!(byte, b',' | b'}' | b']' | b'\r' | b'\n'),
            // A bare key may contain spaces but not brackets or quotes
            Terminators::Key => matches!(
                byte,
                b':' | b',' | b'{' | b'}' | b'[' | b']' | b'"' | b'\'' | b'\r' | b'\n'
            ),
            Terminators::Header => matches!(byte, b'.' | b']' | b'\r' | b'\n'),
        }
    }
}

/// Drop any leading UTF-8 byte order marks.
pub fn strip_bom(text: &str) -> &str {
    text.trim_start_matches('\u{feff}')
}

/// A cursor over the source text with lexical helpers.
pub struct Scanner<'a> {
    pub text: &'a str,
    pub pos: usize,
}

impl<'a> Scanner<'a> {
    pub fn new(text: &'a str) -> Self {
        Scanner {
            text: strip_bom(text),
            pos: 0,
        }
    }

    fn bytes(&self) -> &'a [u8] {
        self.text.as_bytes()
    }

    // -- cursor ----------------------------------------------------------

    pub fn eof(&self) -> bool {
        self.pos >= self.text.len()
    }

    pub fn peek(&self) -> Option<u8> {
        self.bytes().get(self.pos).copied()
    }

    pub fn starts_with(&self, needle: &str) -> bool {
        self.text[self.pos..].starts_with(needle)
    }

    pub fn error_at(&self, pos: usize, message: String) -> DecodeError {
        DecodeError::new(message, self.text, pos)
    }

    pub fn error_here(&self, message: String) -> DecodeError {
        self.error_at(self.pos, message)
    }

    /// Describe the current character for an error message.
    pub fn describe_here(&self) -> String {
        match self.text[self.pos..].chars().next() {
            None => "end of file".into(),
            Some('\r' | '\n') => "end of line".into(),
            Some(char) => repr(char.encode_utf8(&mut [0; 4])),
        }
    }

    // -- trivia ----------------------------------------------------------

    /// Skip spaces, tabs and comments, but never a line break. A block comment
    /// that spans lines counts as a line break, so the cursor stops on its `~*`.
    pub fn skip_inline(&mut self) -> Result<()> {
        self.skip(false)
    }

    /// Skip all whitespace (line breaks included) and comments.
    pub fn skip_ignorable(&mut self) -> Result<()> {
        self.skip(true)
    }

    fn skip(&mut self, newlines: bool) -> Result<()> {
        let text = self.text;
        let bytes = self.bytes();
        let mut i = self.pos;

        while i < bytes.len() {
            let byte = bytes[i];

            if is_inline_space(byte) || (newlines && is_line_break(byte)) {
                i += 1;
            } else if text[i..].starts_with(LINE_COMMENT) {
                while i < bytes.len() && !is_line_break(bytes[i]) {
                    i += 1;
                }
            } else if text[i..].starts_with(BLOCK_OPEN) {
                let Some(close) = text[i + BLOCK_OPEN.len()..].find(BLOCK_CLOSE) else {
                    return Err(self.error_at(i, "unterminated block comment: missing '*~'".into()));
                };
                let after = i + BLOCK_OPEN.len() + close + BLOCK_CLOSE.len();
                let crosses_line = text[i..after].contains(['\r', '\n']);

                if crosses_line && !newlines {
                    break;
                }

                i = after;
            } else {
                break;
            }
        }

        self.pos = i;
        Ok(())
    }

    /// At a line break, the end of the file, or a block comment that spans lines.
    pub fn at_line_end(&self) -> bool {
        match self.peek() {
            None => true,
            Some(byte) => is_line_break(byte) || self.starts_with(BLOCK_OPEN),
        }
    }

    // -- literals --------------------------------------------------------

    /// Read a quoted string at the cursor and return its decoded value.
    pub fn scan_string(&mut self) -> Result<String> {
        let text = self.text;
        let bytes = self.bytes();
        let start = self.pos;
        let quote = bytes[start];
        let mut out = String::new();
        let mut i = start + 1;
        let mut literal_from = i;

        loop {
            if i >= bytes.len() {
                return Err(self.error_at(start, "unterminated string literal".into()));
            }

            let byte = bytes[i];

            if byte == quote {
                self.pos = i + 1;
                out.push_str(&text[literal_from..i]);
                return Ok(out);
            }

            if is_line_break(byte) {
                return Err(self.error_at(
                    start,
                    "unterminated string literal (use \\n for a line break)".into(),
                    ));
            }

            if byte != b'\\' {
                i += 1;
                continue;
            }

            out.push_str(&text[literal_from..i]);
            let escape_at = i;
            i += 1;

            if i >= bytes.len() {
                return Err(self.error_at(escape_at, "unterminated escape sequence".into()));
            }

            let marker = bytes[i];

            if let Some(decoded) = simple_escape(marker) {
                out.push(decoded);
                i += 1;
            } else if matches!(marker, b'x' | b'u' | b'U') {
                i = self.scan_hex_escape(&mut out, i, escape_at)?;
            } else if is_line_break(marker) {
                // Backslash-newline continues the string, eating the indent
                i += 1;

                if marker == b'\r' && bytes.get(i) == Some(&b'\n') {
                    i += 1;
                }

                while i < bytes.len() && is_inline_space(bytes[i]) {
                    i += 1;
                }
            } else {
                let shown = text[i..].chars().next().unwrap();
                return Err(
                    self.error_at(escape_at, format!("invalid escape sequence '\\{shown}'"))
                );
            }

            literal_from = i;
        }
    }

    /// Decode `\xNN`, `\uNNNN` or `\UNNNNNNNN` whose marker is at `marker_at`.
    fn scan_hex_escape(
        &self,
        out: &mut String,
        marker_at: usize,
        escape_at: usize,
    ) -> Result<usize> {
        let text = self.text;
        let marker = text.as_bytes()[marker_at] as char;
        let width = match marker {
            'x' => 2,
            'u' => 4,
            _ => 8,
        };
        let digits = hex_digits(text, marker_at + 1, width).ok_or_else(|| {
            self.error_at(
                escape_at,
                format!("'\\{marker}' escape needs {width} hex digits"),
            )
            })?;
        let mut code = u32::from_str_radix(digits, 16).unwrap();
        let mut i = marker_at + 1 + width;

        // Recombine a UTF-16 surrogate pair, as JSON encoders emit them
        if marker == 'u' && (0xd800..=0xdbff).contains(&code) && text[i..].starts_with("\\u") {
            if let Some(low) =
            hex_digits(text, i + 2, 4).map(|low| u32::from_str_radix(low, 16).unwrap())
            {
                if (0xdc00..=0xdfff).contains(&low) {
                    code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
                    i += 6;
                }
            }
        }

        if (0xd800..=0xdfff).contains(&code) {
            return Err(self.error_at(
                escape_at,
                format!("'\\{marker}{digits}' is an unpaired surrogate"),
                ));
        }

        let Some(char) = char::from_u32(code) else {
            return Err(self.error_at(
                escape_at,
                format!("'\\{marker}{digits}' is outside the Unicode range"),
                ));
        };
        out.push(char);
        Ok(i)
    }

    /// Consume up to (not including) a terminator or a comment.
    pub fn scan_until(&mut self, kind: Terminators) -> &'a str {
        let text = self.text;
        let bytes = self.bytes();
        let mut i = self.pos;

        while i < bytes.len() && !kind.ends(bytes[i]) && !is_comment_at(text, i) {
            i += 1;
        }

        let raw = &text[self.pos..i];
        self.pos = i;
        raw
    }
}

fn is_comment_at(text: &str, i: usize) -> bool {
    let rest = &text.as_bytes()[i..];
    rest.starts_with(LINE_COMMENT.as_bytes()) || rest.starts_with(BLOCK_OPEN.as_bytes())
}

/// `width` hex digits starting at `at`, or None.
fn hex_digits(text: &str, at: usize, width: usize) -> Option<&str> {
    let digits = text.get(at..at + width)?;
    digits
    .bytes()
    .all(|byte| byte.is_ascii_hexdigit())
    .then_some(digits)
}

fn simple_escape(marker: u8) -> Option<char> {
    Some(match marker {
        b'"' | b'\'' | b'\\' | b'/' => marker as char,
        b'0' => '\0',
        b'a' => '\x07',
        b'b' => '\x08',
        b'e' => '\x1b',
        b'f' => '\x0c',
        b'n' => '\n',
        b'r' => '\r',
        b't' => '\t',
        b'v' => '\x0b',
        _ => return None,
        })
}

/// A string shown the way Python's `repr()` does, for error messages.
pub fn repr(value: &str) -> String {
    let quote = if value.contains('\'') && !value.contains('"') {
        '"'
    } else {
        '\''
    };
    let mut out = String::new();
    out.push(quote);

    for char in value.chars() {
        match char {
            _ if char == quote || char == '\\' => {
                out.push('\\');
                out.push(char);
            }
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\0'..='\x1f' | '\x7f' => {
                let _ = write!(out, "\\x{:02x}", char as u32);
            }
            _ => out.push(char),
        }
    }

    out.push(quote);
    out
}

// -- bare tokens -----------------------------------------------------------

/// Unicode whitespace, as Python's `str.isspace` defines it.
fn is_unicode_space(char: char) -> bool {
    matches!(
        char,
        '\t'..='\r' | '\x1c'..='\x20' | '\u{85}' | '\u{a0}' | '\u{1680}' | '\u{2000}'..='\u{200a}'
            | '\u{2028}' | '\u{2029}' | '\u{202f}' | '\u{205f}' | '\u{3000}'
    )
}

/// Trim Unicode whitespace from both ends, as `str.strip()` and `String.trim()` do.
pub fn trim_space(text: &str) -> &str {
    text.trim_matches(is_unicode_space)
}

const KEYWORDS: [(&str, Value); 14] = [
    ("true", Value::Bool(true)),
    ("false", Value::Bool(false)),
    ("null", Value::Null),
    ("none", Value::Null),
    ("nil", Value::Null),
    ("nan", Value::Float(f64::NAN)),
    ("inf", Value::Float(f64::INFINITY)),
    ("infinity", Value::Float(f64::INFINITY)),
    ("+inf", Value::Float(f64::INFINITY)),
    ("+infinity", Value::Float(f64::INFINITY)),
    ("-inf", Value::Float(f64::NEG_INFINITY)),
    ("-infinity", Value::Float(f64::NEG_INFINITY)),
    ("+nan", Value::Float(f64::NAN)),
    ("-nan", Value::Float(f64::NAN)),
];

/// Interpret an unquoted token: keyword, then number, then plain string.
/// An empty or whitespace-only token means "no value" and yields null.
pub fn interpret_bare(text: &str) -> Value {
    let token = trim_space(text);

    if token.is_empty() {
        return Value::Null;
    }

    for (word, value) in KEYWORDS {
        if token.eq_ignore_ascii_case(word) {
            return value;
        }
    }

    parse_number(token).unwrap_or_else(|| Value::String(token.to_string()))
}

/// Match `D+(_D+)*` at `i` and return the end, or None.
fn grouped_digits(bytes: &[u8], mut i: usize) -> Option<usize> {
    loop {
        let run_start = i;

        while i < bytes.len() && bytes[i].is_ascii_digit() {
            i += 1;
        }

        if i == run_start {
            return None;
        }

        if i + 1 < bytes.len() && bytes[i] == b'_' && bytes[i + 1].is_ascii_digit() {
            i += 1;
            continue;
        }

        return Some(i);
    }
}

/// Match `(_?D)+` for a radix body, which must use up the whole text.
fn radix_digits(base: u32, body: &[u8]) -> bool {
    if body.is_empty() {
        return false;
    }

    let mut i = 0;

    while i < body.len() {
        if body[i] == b'_' {
            i += 1;
        }

        if i >= body.len() || !(body[i] as char).is_digit(base) {
            return false;
        }

        i += 1;
    }

    true
}

fn is_float(bytes: &[u8]) -> bool {
    let mut i = usize::from(matches!(bytes.first(), Some(b'+' | b'-')));

    if bytes.get(i) == Some(&b'.') {
        let Some(end) = grouped_digits(bytes, i + 1) else {
            return false;
        };
        i = end;
    } else {
        let Some(end) = grouped_digits(bytes, i) else {
            return false;
        };
        i = end;

        if bytes.get(i) == Some(&b'.') {
            i += 1;

            if let Some(end) = grouped_digits(bytes, i) {
                i = end;
            }
        }
    }

    if matches!(bytes.get(i), Some(b'e' | b'E')) {
        i += 1;

        if matches!(bytes.get(i), Some(b'+' | b'-')) {
            i += 1;
        }

        let Some(end) = grouped_digits(bytes, i) else {
            return false;
        };
        i = end;
    }

    i == bytes.len()
}

/// Numbers with `_` separators, a sign and `0x`/`0o`/`0b` prefixes, as in TOML and Python.
fn parse_number(text: &str) -> Option<Value> {
    let bytes = text.as_bytes();
    let signed = matches!(bytes[0], b'+' | b'-');
    let body = &bytes[usize::from(signed)..];

    if body.len() > 2 && body[0] == b'0' {
        let base = match body[1] {
            b'x' | b'X' => Some(16),
            b'o' | b'O' => Some(8),
            b'b' | b'B' => Some(2),
            _ => None,
        };

        if let Some(base) = base {
            if !radix_digits(base, &body[2..]) {
                return None;
            }

            let digits = text[usize::from(signed) + 2..].replace('_', "");
            // Beyond i128 a radix literal stays a plain string, as in the Go and Zig ports
            let magnitude = i128::from_str_radix(&digits, base).ok()?;
            return Some(Value::Integer(if bytes[0] == b'-' {
                -magnitude
                } else {
                    magnitude
                }));
        }
    }

    let clean = text.replace('_', "");

    if grouped_digits(bytes, usize::from(signed)) == Some(bytes.len()) {
        // Beyond i128, fall back to a float, as the Go and Zig ports do
        if let Ok(integer) = clean.parse::<i128>() {
            return Some(Value::Integer(integer));
        }

        return clean.parse::<f64>().ok().map(Value::Float);
    }

    if is_float(bytes) {
        return clean.parse::<f64>().ok().map(Value::Float);
    }

    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn string(text: &str) -> Value {
        Value::String(text.into())
    }

    #[test]
    fn bare_tokens() {
        assert_eq!(interpret_bare("   "), Value::Null);
        assert_eq!(interpret_bare("True"), Value::Bool(true));
        assert_eq!(interpret_bare("None"), Value::Null);
        assert_eq!(interpret_bare("1_000"), Value::Integer(1000));
        assert_eq!(interpret_bare("-0xff"), Value::Integer(-255));
        assert_eq!(interpret_bare("0o755"), Value::Integer(493));
        assert_eq!(interpret_bare("0b_101"), Value::Integer(5));
        assert_eq!(interpret_bare(".5"), Value::Float(0.5));
        assert_eq!(interpret_bare("5."), Value::Float(5.0));
        assert_eq!(interpret_bare("1e-7"), Value::Float(1e-7));
        assert!(matches!(interpret_bare("-NaN"), Value::Float(float) if float.is_nan()));
        assert_eq!(interpret_bare("-Infinity"), Value::Float(f64::NEG_INFINITY));
        assert_eq!(interpret_bare("12abc"), string("12abc"));
        assert_eq!(interpret_bare("1__0"), string("1__0"));
        assert_eq!(interpret_bare("0x"), string("0x"));
        assert_eq!(interpret_bare("0xf_"), string("0xf_"));
        assert_eq!(interpret_bare("#8496AD"), string("#8496AD"));
        assert_eq!(
            interpret_bare("111111111111111111"),
            Value::Integer(111111111111111111)
        );
    }

    #[test]
    fn trim_matches_python() {
        assert_eq!(trim_space("\u{3000} \tx y\u{a0}\u{85}\n"), "x y");
        assert_eq!(trim_space("é"), "é");
        assert_eq!(trim_space(" \u{2028} "), "");
    }

    #[test]
    fn repr_matches_python() {
        assert_eq!(repr("prefix"), "'prefix'");
        assert_eq!(repr("it's"), "\"it's\"");
        assert_eq!(repr("a\tb\x01"), "'a\\tb\\x01'");
    }
}
