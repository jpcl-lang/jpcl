// lib.rs
//! jpcl: a hybrid JSON/TOML configuration language (`.jp` files).

/// The version of the `.jp` format and of this crate.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Conventional file extension.
pub const SUFFIX: &str = ".jp";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_matches_the_other_ports() {
        assert_eq!(VERSION, "2.0.0");
    }
}
