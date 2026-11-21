// values.zig
//! The value types a `.jp` document parses to.

const std = @import("std");

/// A section or `{...}` object. Keys keep their insertion order.
pub const Object = std.StringArrayHashMapUnmanaged(Value);

/// An `[...]` array.
pub const Array = std.ArrayList(Value);

/// Any value a `.jp` document can hold. An empty value (`key:`) parses to `.null`.
pub const Value = union(enum) {
    null,
    bool: bool,
    integer: i128,
    float: f64,
    string: []const u8,
    array: Array,
    object: Object,
};
