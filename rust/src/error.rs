// error.rs
//! Errors for parsing, writing and file access.

use std::fmt;
use std::io;
use std::path::PathBuf;

/// A malformed `.jp` document. `Display` prints `file:line:col: message` and a caret excerpt.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodeError {
    /// The message without the location prefix or source excerpt.
    pub message: String,
    /// Zero-based byte offset of the error inside the parsed text.
    pub pos: usize,
    /// One-based line number.
    pub line: usize,
    /// One-based column, counted in code points.
    pub col: usize,
    /// Name of the file the text came from, if known.
    pub filename: Option<String>,
    // The offending line, or None when the document was empty
    excerpt: Option<String>,
}

impl DecodeError {
    pub(crate) fn new(message: String, text: &str, pos: usize) -> Self {
        let pos = pos.min(text.len());
        let before = &text[..pos];
        let line_start = before.rfind('\n').map_or(0, |at| at + 1);
        let line_end = text[pos..].find('\n').map_or(text.len(), |at| pos + at);
        DecodeError {
            message,
            pos,
            line: before.matches('\n').count() + 1,
            col: before[line_start..].chars().count() + 1,
            filename: None,
            excerpt: (!text.is_empty()).then(|| {
                text[line_start..line_end]
                .trim_end_matches('\r')
                .to_string()
                }),
        }
    }

    /// Name the file the text came from, for the message prefix.
    pub fn with_filename(mut self, filename: impl Into<String>) -> Self {
        self.filename = Some(filename.into());
        self
    }
}

impl fmt::Display for DecodeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let filename = self.filename.as_deref().unwrap_or("<string>");
        write!(
            formatter,
            "{filename}:{}:{}: {}",
            self.line, self.col, self.message
        )?;
        let Some(source) = &self.excerpt else {
            return Ok(());
        };
        // Keep tabs in the caret prefix so the marker stays aligned
        let prefix: String = source
        .chars()
        .take(self.col - 1)
        .map(|char| if char == '\t' { '\t' } else { ' ' })
        .collect();
        write!(formatter, "\n    {source}\n    {prefix}^")
    }
}

impl std::error::Error for DecodeError {}

/// Anything `load`, `dump` or `parse` can fail with.
#[derive(Debug)]
pub enum Error {
    Decode(DecodeError),
    Io { path: PathBuf, source: io::Error },
}

impl fmt::Display for Error {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Decode(error) => error.fmt(formatter),
            Error::Io { path, source } => {
                write!(formatter, "{}: {}", path.display(), describe_io(source))
            }
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Decode(error) => Some(error),
            Error::Io { source, .. } => Some(source),
        }
    }
}

impl From<DecodeError> for Error {
    fn from(error: DecodeError) -> Self {
        Error::Decode(error)
    }
}

/// A short, lowercase reason, matching the Zig port's wording.
pub(crate) fn describe_io(error: &io::Error) -> String {
    match error.kind() {
        io::ErrorKind::NotFound => "no such file or directory".into(),
        io::ErrorKind::PermissionDenied => "permission denied".into(),
        io::ErrorKind::IsADirectory => "is a directory".into(),
        io::ErrorKind::InvalidData => "invalid UTF-8".into(),
        _ => error.to_string(),
    }
}
