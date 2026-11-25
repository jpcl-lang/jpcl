// parser.rs
//! Recursive-descent parser for `.jp`; `[` opens a section only at the top level.

use std::collections::HashSet;

use crate::error::DecodeError;
use crate::scanner::{Result, Scanner, Terminators, interpret_bare, repr, trim_space};
use crate::value::{Object, Value};

/// How deep `{` / `[` nesting may go, so hostile input fails cleanly instead of overflowing the stack.
pub const MAX_DEPTH: usize = 200;

/// What to do when a key appears twice in the same mapping.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum DuplicateKeys {
    #[default]
    Error,
    First,
    Last,
}

#[derive(Debug, Clone, Default)]
pub struct ParseOptions {
    pub duplicate_keys: DuplicateKeys,
}

/// Parse `text` as a `.jp` document.
pub fn parse(text: &str, options: &ParseOptions) -> std::result::Result<Object, DecodeError> {
    Parser {
        scanner: Scanner::new(text),
        duplicate_keys: options.duplicate_keys,
        depth: 0,
        defined: HashSet::new(),
    }
    .document()
}

struct Parser<'a> {
    scanner: Scanner<'a>,
    duplicate_keys: DuplicateKeys,
    depth: usize,
    // Section paths opened by an explicit header, to catch `[a]` twice
    defined: HashSet<Vec<String>>,
}

struct Entry {
    key: String,
    value: Value,
    key_pos: usize,
}

impl Parser<'_> {
    fn document(mut self) -> Result<Object> {
        let mut root = Object::new();
        // The open section, as a path from the root
        let mut current: Vec<String> = Vec::new();

        loop {
            self.scanner.skip_ignorable()?;

            if self.scanner.eof() {
                return Ok(root);
            }

            if self.scanner.peek() == Some(b'[') {
                current = self.header(&mut root)?;
                continue;
            }

            let entry = self.entry()?;
            let target = section_mut(&mut root, &current);
            self.store(target, entry)?;
            self.separator(None)?;
        }
    }

    // -- sections --------------------------------------------------------

    /// Parse `[a.b.c]`, open its section, and return its path.
    fn header(&mut self, root: &mut Object) -> Result<Vec<String>> {
        let start = self.scanner.pos;
        self.scanner.pos += 1;

        let mut path: Vec<String> = Vec::new();
        let mut pending_dot = false;

        loop {
            let scanner = &mut self.scanner;
            scanner.skip_inline()?;

            if scanner.at_line_end() {
                return Err(scanner.error_at(start, "unterminated section header".into()));
            }

            let byte = scanner.peek().unwrap();

            if byte == b']' {
                if path.is_empty() {
                    return Err(scanner.error_at(start, "section header cannot be empty".into()));
                }

                if pending_dot {
                    return Err(scanner.error_here("section header segment cannot be empty".into()));
                }

                scanner.pos += 1;
                break;
            }

            if byte == b'"' || byte == b'\'' {
                path.push(scanner.scan_string()?);
            } else {
                let segment = trim_space(scanner.scan_until(Terminators::Header));

                if segment.is_empty() {
                    return Err(scanner.error_here("section header segment cannot be empty".into()));
                }

                path.push(segment.to_string());
            }

            scanner.skip_inline()?;

            match scanner.peek() {
                Some(b'.') => {
                    scanner.pos += 1;
                    pending_dot = true;
                    continue;
                }
                Some(b']') => {
                    scanner.pos += 1;
                    break;
                }
                _ => {}
            }

            if scanner.at_line_end() {
                return Err(scanner.error_at(start, "unterminated section header".into()));
            }

            let found = scanner.describe_here();
            return Err(scanner.error_here(format!(
                "expected '.' or ']' in section header, found {found}"
            )));
        }

        self.scanner.skip_inline()?;

        if !self.scanner.at_line_end() {
            let found = self.scanner.describe_here();
            return Err(self
                .scanner
                .error_here(format!("unexpected {found} after section header")));
        }

        self.open_section(root, &path, start)?;
        Ok(path)
    }

    /// Create or reuse the nested object addressed by `path`.
    fn open_section(&mut self, root: &mut Object, path: &[String], start: usize) -> Result<()> {
        let fresh = self.defined.insert(path.to_vec());

        if !fresh && self.duplicate_keys == DuplicateKeys::Error {
            let message = format!("section '[{}]' is defined twice", path.join("."));
            return Err(self.scanner.error_at(start, message));
        }

        let mut node = root;

        for (depth, part) in path.iter().enumerate() {
            if !node.contains_key(part) {
                node.insert(part.clone(), Value::Object(Object::new()));
            }

            node = match node.get_mut(part) {
                Some(Value::Object(child)) => child,
                _ => {
                    let message = format!(
                        "cannot open section '[{}]': '{}' is already a non-section value",
                        path.join("."),
                        path[..=depth].join("."),
                    );
                    return Err(self.scanner.error_at(start, message));
                }
            };
        }

        Ok(())
    }

    // -- entries ---------------------------------------------------------

    fn entry(&mut self) -> Result<Entry> {
        self.scanner.skip_inline()?;
        let key_pos = self.scanner.pos;
        let key = self.key()?;

        self.scanner.skip_inline()?;

        if self.scanner.peek() != Some(b':') {
            let found = self.scanner.describe_here();
            let message = format!("expected ':' after key {}, found {found}", repr(&key));
            return Err(self.scanner.error_here(message));
        }

        self.scanner.pos += 1;
        Ok(Entry {
            key,
            value: self.optional_value()?,
            key_pos,
        })
    }

    fn key(&mut self) -> Result<String> {
        let scanner = &mut self.scanner;

        match scanner.peek() {
            None => return Err(scanner.error_here("expected a key, found end of file".into())),
            Some(b'"' | b'\'') => return scanner.scan_string(),
            Some(b'}' | b']') => {
                let found = scanner.describe_here();
                return Err(scanner.error_here(format!("unexpected {found}")));
            }
            Some(_) => {}
        }

        let start = scanner.pos;
        let key = trim_space(scanner.scan_until(Terminators::Key));

        if key.is_empty() {
            let found = scanner.describe_here();
            return Err(scanner.error_at(start, format!("expected a key, found {found}")));
        }

        Ok(key.to_string())
    }

    /// The value after a `:`, or null when it is empty.
    fn optional_value(&mut self) -> Result<Value> {
        self.scanner.skip_inline()?;

        if self.scanner.peek() == Some(b',') || self.scanner.at_line_end() {
            return Ok(Value::Null);
        }

        self.value()
    }

    fn value(&mut self) -> Result<Value> {
        Ok(match self.scanner.peek().unwrap() {
            b'{' => Value::Object(self.object()?),
            b'[' => Value::Array(self.array()?),
            b'"' | b'\'' => Value::String(self.scanner.scan_string()?),
            _ => interpret_bare(self.scanner.scan_until(Terminators::Value)),
        })
    }

    // -- containers ------------------------------------------------------

    fn enter(&mut self, start: usize) -> Result<()> {
        self.depth += 1;

        if self.depth > MAX_DEPTH {
            return Err(self
                .scanner
                .error_at(start, format!("nesting deeper than {MAX_DEPTH} levels")));
        }

        Ok(())
    }

    fn object(&mut self) -> Result<Object> {
        let start = self.scanner.pos;
        self.enter(start)?;
        self.scanner.pos += 1;
        let mut result = Object::new();

        loop {
            self.scanner.skip_ignorable()?;

            match self.scanner.peek() {
                None => {
                    return Err(self
                        .scanner
                        .error_at(start, "unterminated object: missing '}'".into()));
                }
                Some(b'}') => {
                    self.scanner.pos += 1;
                    self.depth -= 1;
                    return Ok(result);
                }
                // Tolerate stray or repeated separators
                Some(b',') => {
                    self.scanner.pos += 1;
                    continue;
                }
                Some(_) => {}
            }

            let entry = self.entry()?;
            self.store(&mut result, entry)?;
            self.separator(Some(b'}'))?;
        }
    }

    fn array(&mut self) -> Result<Vec<Value>> {
        let start = self.scanner.pos;
        self.enter(start)?;
        self.scanner.pos += 1;
        let mut items = Vec::new();

        loop {
            self.scanner.skip_ignorable()?;

            match self.scanner.peek() {
                None => {
                    return Err(self
                        .scanner
                        .error_at(start, "unterminated array: missing ']'".into()));
                }
                Some(b']') => {
                    self.scanner.pos += 1;
                    self.depth -= 1;
                    return Ok(items);
                }
                Some(b',') => {
                    self.scanner.pos += 1;
                    continue;
                }
                Some(b'}') => {
                    return Err(self
                        .scanner
                        .error_at(start, "unterminated array: found '}' before ']'".into()));
                }
                Some(_) => {}
            }

            items.push(self.value()?);
            self.separator(Some(b']'))?;
        }
    }

    // -- separators and storage ------------------------------------------

    /// Require a `,`, a line break, or the closing bracket after a value.
    fn separator(&mut self, closing: Option<u8>) -> Result<()> {
        let scanner = &mut self.scanner;
        scanner.skip_inline()?;

        if scanner.at_line_end() {
            return Ok(());
        }

        let byte = scanner.peek().unwrap();

        if byte == b',' {
            scanner.pos += 1;
            return Ok(());
        }

        let found = scanner.describe_here();

        if let Some(close) = closing {
            if byte == close {
                return Ok(());
            }

            let message = format!(
                "expected ',', a line break or '{}' after value, found {found}",
                close as char
            );
            return Err(scanner.error_here(message));
        }
        // A section header always starts its own line, so the previous entry never ended
        if byte == b'[' {
            return Err(scanner.error_here("expected a line break before a section header".into()));
        }

        Err(scanner.error_here(format!(
            "expected ',' or a line break after value, found {found}"
        )))
    }

    /// Insert an entry, applying the duplicate-key policy.
    fn store(&self, target: &mut Object, entry: Entry) -> Result<()> {
        if target.contains_key(&entry.key) {
            match self.duplicate_keys {
                DuplicateKeys::Error => {
                    let message = format!("duplicate key {}", repr(&entry.key));
                    return Err(self.scanner.error_at(entry.key_pos, message));
                }
                DuplicateKeys::First => return Ok(()),
                DuplicateKeys::Last => {}
            }
        }

        target.insert(entry.key, entry.value);
        Ok(())
    }
}

/// The section object at `path`, which `open_section` has already created.
fn section_mut<'a>(root: &'a mut Object, path: &[String]) -> &'a mut Object {
    let mut node = root;

    for part in path {
        node = match node.get_mut(part) {
            Some(Value::Object(child)) => child,
            _ => unreachable!("open_section creates every part of the path"),
        };
    }

    node
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_default(text: &str) -> Object {
        parse(text, &ParseOptions::default()).unwrap()
    }

    fn expect_error(text: &str, message: &str, line: usize, col: usize) {
        let error = parse(text, &ParseOptions::default()).unwrap_err();
        assert_eq!(error.message, message, "for {text:?}");
        assert_eq!((error.line, error.col), (line, col), "for {text:?}");
    }

    fn at<'a>(root: &'a Object, path: &[&str]) -> &'a Value {
        let mut node = root.get(path[0]).unwrap();

        for part in &path[1..] {
            node = node.as_object().unwrap().get(part).unwrap();
        }

        node
    }

    #[test]
    fn sections_nested_objects_and_empty_values() {
        let root = parse_default(concat!(
            "~~ leading comment\n",
            "version: 2\n",
            "\n",
            "[SERVER_ID]\n",
            "config: {\n",
            "  disabled_channels:,\n",
            "  disabled_users: [9892, 82082, 8209]  ~~ trailing\n",
            "}\n",
            "\n",
            "[guild.limits]\n",
            "rate: 30 ~* inline *~\n",
            "~* a block\n",
            "   comment *~\n",
            "colour: #8496AD\n",
            "price: $5 $$ not a comment\n",
        ));
        assert_eq!(root.get("version"), Some(&Value::Integer(2)));
        assert_eq!(
            at(&root, &["SERVER_ID", "config", "disabled_channels"]),
            &Value::Null
        );
        assert!(
            matches!(at(&root, &["SERVER_ID", "config", "disabled_users"]), Value::Array(items) if items.len() == 3)
        );
        assert_eq!(at(&root, &["guild", "limits", "rate"]), &Value::Integer(30));
        assert_eq!(
            at(&root, &["guild", "limits", "colour"]).as_str(),
            Some("#8496AD")
        );
        assert_eq!(
            at(&root, &["guild", "limits", "price"]).as_str(),
            Some("$5 $$ not a comment")
        );
    }

    #[test]
    fn strings_and_escapes() {
        let root = parse_default(concat!(
            "a: \"tab\\there \\u00e9 \\U0001F600 \\ud83d\\ude00\"\n",
            "c: \"one \\\n    two\"\n",
            "url: http://example.com:8080\n",
        ));
        assert_eq!(root.get("a").unwrap().as_str(), Some("tab\there é 😀 😀"));
        assert_eq!(root.get("c").unwrap().as_str(), Some("one two"));
        assert_eq!(
            root.get("url").unwrap().as_str(),
            Some("http://example.com:8080")
        );
    }

    #[test]
    fn duplicate_keys() {
        expect_error("a: 1\na: 2\n", "duplicate key 'a'", 2, 1);
        expect_error("[s]\n[s]\n", "section '[s]' is defined twice", 2, 1);
        let first = parse(
            "a: 1\na: 2\n",
            &ParseOptions {
                duplicate_keys: DuplicateKeys::First,
            },
        )
        .unwrap();
        assert_eq!(first.get("a"), Some(&Value::Integer(1)));
        let last = parse(
            "a: 1\na: 2\n",
            &ParseOptions {
                duplicate_keys: DuplicateKeys::Last,
            },
        )
        .unwrap();
        assert_eq!(last.get("a"), Some(&Value::Integer(2)));
    }

    #[test]
    fn syntax_errors_point_at_the_right_place() {
        expect_error(
            "[SERVER_ID]\nprefix \"!\"\n",
            "expected ':' after key 'prefix', found '\"'",
            2,
            8,
        );
        expect_error("a: {\n  b: 1\n", "unterminated object: missing '}'", 1, 4);
        expect_error("a: [}\n", "unterminated array: found '}' before ']'", 1, 4);
        expect_error(
            "a: \"x\n",
            "unterminated string literal (use \\n for a line break)",
            1,
            4,
        );
        expect_error("a: \"\\q\"\n", "invalid escape sequence '\\q'", 1, 5);
        expect_error(
            "a: \"x\" b\n",
            "expected ',' or a line break after value, found 'b'",
            1,
            8,
        );
        expect_error(
            "a: \"x\" [s]\n",
            "expected a line break before a section header",
            1,
            8,
        );
        expect_error("[]\n", "section header cannot be empty", 1, 1);
        expect_error("[a.]\n", "section header segment cannot be empty", 1, 4);
        expect_error("[a\n", "unterminated section header", 1, 1);
        expect_error(
            "a: 1\n[a]\n",
            "cannot open section '[a]': 'a' is already a non-section value",
            2,
            1,
        );
        expect_error(
            "~* open\n",
            "unterminated block comment: missing '*~'",
            1,
            1,
        );
        expect_error(
            "é: \"\\uD800\"\n",
            "'\\uD800' is an unpaired surrogate",
            1,
            5,
        );
    }

    #[test]
    fn nesting_limit() {
        let deep = format!("a: {}", "[".repeat(MAX_DEPTH + 1));
        expect_error(&deep, "nesting deeper than 200 levels", 1, 4 + MAX_DEPTH);
    }

    #[test]
    fn byte_order_mark_and_crlf() {
        let root = parse_default("\u{feff}[a]\r\nx: 1\r\n");
        assert_eq!(at(&root, &["a", "x"]), &Value::Integer(1));
    }

    #[test]
    fn display_renders_a_caret() {
        let error = parse("[SERVER_ID]\n\tprefix \"!\"\n", &ParseOptions::default())
            .unwrap_err()
            .with_filename("servers.jp");
        assert_eq!(
            error.to_string(),
            "servers.jp:2:9: expected ':' after key 'prefix', found '\"'\n    \tprefix \"!\"\n    \t       ^",
        );
    }
}
