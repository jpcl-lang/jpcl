// lib.rs
//! jpcl: a hybrid JSON/TOML configuration language (`.jp` files).

mod error;
mod json;
mod parser;
mod scanner;
mod value;
mod writer;

use std::fs;
use std::io::Write as _;
use std::path::Path;

pub use error::{DecodeError, Error};
pub use json::{from_json, to_json};
pub use parser::{DuplicateKeys, MAX_DEPTH, ParseOptions, parse};
pub use value::{Object, Value};
pub use writer::{StringifyOptions, stringify};

/// The version of the `.jp` format and of this crate.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Conventional file extension.
pub const SUFFIX: &str = ".jp";

/// Parse raw bytes, reporting invalid UTF-8 as a `DecodeError` at the first bad byte.
pub fn parse_bytes(bytes: &[u8], options: &ParseOptions) -> Result<Object, DecodeError> {
    match std::str::from_utf8(bytes) {
        Ok(text) => parse(text, options),
        Err(error) => {
            let text = String::from_utf8_lossy(bytes);
            let start = scanner::strip_bom(&text).as_ptr() as usize - text.as_ptr() as usize;
            Err(DecodeError::new(
                "invalid UTF-8".into(),
                &text[start..],
                error.valid_up_to() - start,
            ))
        }
    }
}

/// Read and parse a `.jp` file.
pub fn load(path: impl AsRef<Path>, options: &ParseOptions) -> Result<Object, Error> {
    let path = path.as_ref();
    let bytes = fs::read(path).map_err(|source| Error::Io {
        path: path.to_path_buf(),
        source,
    })?;
    parse_bytes(&bytes, options)
        .map_err(|error| error.with_filename(path.display().to_string()).into())
}

/// Serialise `root` and write it to `path` atomically, creating missing parent folders.
pub fn dump(
    root: &Object,
    path: impl AsRef<Path>,
    options: &StringifyOptions,
) -> Result<(), Error> {
    let path = path.as_ref();
    write_atomic(path, stringify(root, options).as_bytes()).map_err(|source| Error::Io {
        path: path.to_path_buf(),
        source,
    })
}

/// Write to a temporary file beside `path`, then rename it over `path`, so readers never see half a file.
pub fn write_atomic(path: &Path, data: &[u8]) -> std::io::Result<()> {
    let folder = match path.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent,
        _ => Path::new("."),
    };
    fs::create_dir_all(folder)?;
    let name = path.file_name().unwrap_or_default().to_string_lossy();
    let temporary = folder.join(format!(".{name}.{}.tmp", std::process::id()));

    let result = (|| {
        let mut file = fs::File::create(&temporary)?;
        file.write_all(data)?;
        file.sync_all()?;
        fs::rename(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

/// The value at a dotted path such as `SERVER_ID.config.disabled_users`, or None if
/// any part is missing. An empty value (`key:`) is present and returns `Value::Null`.
pub fn get_path<'a>(root: &'a Object, path: &str) -> Option<&'a Value> {
    let mut parts = path.split('.');
    let mut node = root.get(parts.next()?)?;
    for part in parts {
        node = node.as_object()?.get(part)?;
    }
    Some(node)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn readme_example() {
        let root = parse("[SERVER_ID]\nprefix: \"!\"\n", &ParseOptions::default()).unwrap();
        let prefix = get_path(&root, "SERVER_ID.prefix").and_then(Value::as_str);
        assert_eq!(prefix, Some("!"));
    }

    #[test]
    fn version_matches_the_other_ports() {
        assert_eq!(VERSION, "2.0.0");
    }

    #[test]
    fn get_path_walks_objects_only() {
        let root = parse("[a]\nb: {c: [1]}\nempty:\n", &ParseOptions::default()).unwrap();
        assert!(matches!(get_path(&root, "a.b.c"), Some(Value::Array(items)) if items.len() == 1));
        assert_eq!(get_path(&root, "a.empty"), Some(&Value::Null));
        assert_eq!(get_path(&root, "a.b.c.d"), None);
        assert_eq!(get_path(&root, "missing"), None);
    }

    #[test]
    fn invalid_utf8_is_a_decode_error() {
        let error = parse_bytes(b"a: \xff\n", &ParseOptions::default()).unwrap_err();
        assert_eq!(
            (error.message.as_str(), error.line, error.col),
            ("invalid UTF-8", 1, 4)
        );
    }

    #[test]
    fn load_and_dump_round_trip() {
        let folder = std::env::temp_dir().join(format!("jpcl-test-{}", std::process::id()));
        let path = folder.join("nested").join("out.jp");
        let mut root = Object::new();
        root.insert("version", Value::Integer(2));

        dump(&root, &path, &StringifyOptions::default()).unwrap();
        let loaded = load(&path, &ParseOptions::default()).unwrap();
        fs::remove_dir_all(&folder).unwrap();
        assert_eq!(loaded, root);
    }

    #[test]
    fn load_reports_the_filename() {
        let missing = load("definitely-missing.jp", &ParseOptions::default()).unwrap_err();
        assert_eq!(
            missing.to_string(),
            "definitely-missing.jp: no such file or directory"
        );
    }
}
