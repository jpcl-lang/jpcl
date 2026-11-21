// writer.zig
//! Turns values back into `.jp` source in one fixed, deterministic style.

const std = @import("std");
const Allocator = std.mem.Allocator;

const values = @import("values.zig");
const Value = values.Value;
const Object = values.Object;

pub const StringifyOptions = struct {
    /// Spaces per nesting level.
    indent: usize = 2,
    /// Column budget for keeping an array on one line.
    width: usize = 88,
    /// Sort keys by code point instead of keeping insertion order.
    sort_keys: bool = false,
    /// Escape non-ASCII characters instead of writing them literally.
    ensure_ascii: bool = false,
};

/// Serialise a document. The result ends with a newline; an empty object gives `""`.
/// The caller owns the returned slice.
pub fn stringify(gpa: Allocator, root: Object, options: StringifyOptions) Allocator.Error![]u8 {
    var arena: std.heap.ArenaAllocator = .init(gpa);
    defer arena.deinit();
    const writer: Writer = .{
        .arena = arena.allocator(),
        .options = options,
    };
    return gpa.dupe(u8, try writer.document(root));
}

const Writer = struct {
    arena: Allocator,
    options: StringifyOptions,

    const Error = Allocator.Error;

    fn document(self: Writer, root: Object) Error![]const u8 {
        var preamble: std.ArrayList(u8) = .empty;
        var blocks: std.ArrayList(u8) = .empty;

        for (try self.keys(root)) |key| {
            const value = root.get(key).?;
            if (value == .object) {
                if (blocks.items.len > 0) try blocks.appendSlice(self.arena, "\n\n");
                try blocks.print(self.arena, "[{s}]", .{try self.formatKey(key)});
                for (try self.keys(value.object)) |child| {
                    try blocks.append(self.arena, '\n');
                    try blocks.appendSlice(self.arena, try self.entry(child, value.object.get(child).?, 0));
                }
            } else {
                // A root scalar after a header would be read back into that section, so hoist it
                if (preamble.items.len > 0) try preamble.append(self.arena, '\n');
                try preamble.appendSlice(self.arena, try self.entry(key, value, 0));
            }
        }

        const separator = if (preamble.items.len > 0 and blocks.items.len > 0) "\n\n" else "";
        if (preamble.items.len == 0 and blocks.items.len == 0) return "";
        return std.mem.concat(self.arena, u8, &.{ preamble.items, separator, blocks.items, "\n" });
    }

    /// One `key: value` line, possibly spanning several lines.
    fn entry(self: Writer, key: []const u8, value: Value, level: usize) Error![]const u8 {
        const prefix = try std.mem.concat(self.arena, u8, &.{ try self.pad(level), try self.formatKey(key), ":" });
        if (value == .null) return prefix;
        const column = codePointLength(prefix) + 1;
        return std.mem.concat(self.arena, u8, &.{ prefix, " ", try self.render(value, level, column) });
    }

    fn pad(self: Writer, level: usize) Error![]const u8 {
        const spaces = try self.arena.alloc(u8, self.options.indent * level);
        @memset(spaces, ' ');
        return spaces;
    }

    fn formatKey(self: Writer, key: []const u8) Error![]const u8 {
        return if (isBareKey(key)) key else self.formatString(key);
    }

    /// A string literal with the minimum necessary escaping.
    fn formatString(self: Writer, value: []const u8) Error![]const u8 {
        var out: std.ArrayList(u8) = .empty;
        try out.append(self.arena, '"');
        var view = std.unicode.Utf8View.initUnchecked(value).iterator();
        while (view.nextCodepointSlice()) |slice| {
            const code = std.unicode.utf8Decode(slice) catch unreachable;
            if (simpleEscape(code)) |escaped| {
                try out.appendSlice(self.arena, escaped);
            } else if (code < 0x20 or code == 0x7f or (self.options.ensure_ascii and code > 0x7e)) {
                try appendUnicodeEscape(&out, self.arena, code);
            } else {
                try out.appendSlice(self.arena, slice);
            }
        }
        try out.append(self.arena, '"');
        return out.items;
    }

    /// Render any value; `column` is where it starts on the current line.
    fn render(self: Writer, value: Value, level: usize, column: usize) Error![]const u8 {
        return switch (value) {
            .null => "null",
            .bool => |flag| if (flag) "true" else "false",
            .string => |text| self.formatString(text),
            .integer => |integer| std.fmt.allocPrint(self.arena, "{d}", .{integer}),
            .float => |float| formatNumber(self.arena, float),
            .object => |object| self.renderObject(object, level),
            .array => |array| self.renderArray(array.items, level, column),
        };
    }

    fn renderObject(self: Writer, object: Object, level: usize) Error![]const u8 {
        if (object.count() == 0) return "{}";
        var out: std.ArrayList(u8) = .empty;
        try out.appendSlice(self.arena, "{\n");
        for (try self.keys(object), 0..) |key, i| {
            if (i > 0) try out.appendSlice(self.arena, ",\n");
            try out.appendSlice(self.arena, try self.entry(key, object.get(key).?, level + 1));
        }
        try out.print(self.arena, "\n{s}}}", .{try self.pad(level)});
        return out.items;
    }

    /// `[...]`, inline when it fits on the line.
    fn renderArray(self: Writer, items: []const Value, level: usize, column: usize) Error![]const u8 {
        if (items.len == 0) return "[]";
        const parts = try self.arena.alloc([]const u8, items.len);
        for (items, parts) |item, *part| part.* = try self.render(item, level + 1, column);

        const joined = try std.mem.join(self.arena, ", ", parts);
        const inline_text = try std.mem.concat(self.arena, u8, &.{ "[", joined, "]" });
        const fits = column + codePointLength(inline_text) <= self.options.width;
        if (std.mem.indexOfScalar(u8, inline_text, '\n') == null and fits) return inline_text;

        const inner = try self.pad(level + 1);
        var out: std.ArrayList(u8) = .empty;
        try out.appendSlice(self.arena, "[\n");
        for (parts, 0..) |part, i| {
            if (i > 0) try out.appendSlice(self.arena, ",\n");
            try out.appendSlice(self.arena, inner);
            try out.appendSlice(self.arena, part);
        }
        try out.print(self.arena, "\n{s}]", .{try self.pad(level)});
        return out.items;
    }

    /// Keys in output order.
    fn keys(self: Writer, object: Object) Error![]const []const u8 {
        const list = try self.arena.dupe([]const u8, object.keys());
        if (self.options.sort_keys) {
            // UTF-8 byte order is code point order
            std.mem.sort([]const u8, list, {}, lessThan);
        }
        return list;
    }
};

fn lessThan(_: void, left: []const u8, right: []const u8) bool {
    return std.mem.lessThan(u8, left, right);
}

/// Keys matching `[A-Za-z0-9_][A-Za-z0-9_-]*` are written without quotes.
fn isBareKey(key: []const u8) bool {
    if (key.len == 0 or key[0] == '-') return false;
    for (key) |char| {
        if (!std.ascii.isAlphanumeric(char) and char != '_' and char != '-') return false;
    }
    return true;
}

fn simpleEscape(code: u21) ?[]const u8 {
    return switch (code) {
        '\\' => "\\\\",
        '"' => "\\\"",
        '\n' => "\\n",
        '\r' => "\\r",
        '\t' => "\\t",
        0x08 => "\\b",
        0x0c => "\\f",
        else => null,
    };
}

/// `\uXXXX`, or a UTF-16 surrogate pair for astral characters.
pub fn appendUnicodeEscape(out: *std.ArrayList(u8), gpa: Allocator, code: u21) Allocator.Error!void {
    if (code > 0xffff) {
        const offset = code - 0x10000;
        try out.print(gpa, "\\u{x:0>4}\\u{x:0>4}", .{ 0xd800 + (offset >> 10), 0xdc00 + (offset & 0x3ff) });
    } else {
        try out.print(gpa, "\\u{x:0>4}", .{code});
    }
}

pub fn codePointLength(text: []const u8) usize {
    return std.unicode.utf8CountCodepoints(text) catch text.len;
}

/// A number, including the non-finite literals `nan`, `inf` and `-inf`.
fn formatNumber(gpa: Allocator, value: f64) Allocator.Error![]const u8 {
    if (std.math.isNan(value)) return "nan";
    if (std.math.isInf(value)) return if (value > 0) "inf" else "-inf";
    return formatFinite(gpa, value);
}

/// Render a finite float exactly as jpcl-py and jpcl-lang do, so all writers emit
/// the same bytes: a whole number below 1e16 as an integer, anything else as the
/// shortest round-tripping digits, in scientific notation when the exponent is
/// below -4 or at least 16 (`1e-05`, `1e+16`).
pub fn formatFinite(gpa: Allocator, value: f64) Allocator.Error![]const u8 {
    if (value == @trunc(value) and @abs(value) < 1e16) {
        return std.fmt.allocPrint(gpa, "{d}", .{@as(i64, @intFromFloat(value))});
    }

    // `{e}` gives the shortest unique digits, as `d.ddde±x`
    var buffer: [64]u8 = undefined;
    const scientific = std.fmt.bufPrint(&buffer, "{e}", .{@abs(value)}) catch unreachable;
    const e_at = std.mem.indexOfScalar(u8, scientific, 'e').?;
    const exponent = std.fmt.parseInt(i32, scientific[e_at + 1 ..], 10) catch unreachable;
    var digit_buffer: [32]u8 = undefined;
    var count: usize = 0;
    for (scientific[0..e_at]) |char| {
        if (char == '.') continue;
        digit_buffer[count] = char;
        count += 1;
    }
    const digits = digit_buffer[0..count];
    const sign = if (value < 0) "-" else "";

    if (exponent < -4 or exponent >= 16) {
        const exponent_sign = if (exponent < 0) "-" else "+";
        const magnitude: u32 = @abs(exponent);
        if (digits.len > 1) {
            return std.fmt.allocPrint(gpa, "{s}{c}.{s}e{s}{d:0>2}", .{ sign, digits[0], digits[1..], exponent_sign, magnitude });
        }
        return std.fmt.allocPrint(gpa, "{s}{c}e{s}{d:0>2}", .{ sign, digits[0], exponent_sign, magnitude });
    }
    if (exponent < 0) {
        const zeros = try gpa.alloc(u8, @intCast(-exponent - 1));
        defer gpa.free(zeros);
        @memset(zeros, '0');
        return std.fmt.allocPrint(gpa, "{s}0.{s}{s}", .{ sign, zeros, digits });
    }
    const point: usize = @intCast(exponent + 1);
    return std.fmt.allocPrint(gpa, "{s}{s}.{s}", .{ sign, digits[0..point], digits[point..] });
}

// -- tests -----------------------------------------------------------------

const testing = std.testing;
const parse = @import("parser.zig").parse;

fn expectFinite(expected: []const u8, value: f64) !void {
    const text = try formatFinite(testing.allocator, value);
    defer testing.allocator.free(text);
    try testing.expectEqualStrings(expected, text);
}

test "float formatting matches the other writers" {
    try expectFinite("3", 3.0);
    try expectFinite("0", -0.0);
    try expectFinite("0.1", 0.1);
    try expectFinite("123.456", 123.456);
    try expectFinite("1e-05", 1e-5);
    try expectFinite("0.0001", 1e-4);
    try expectFinite("-2.5e-07", -2.5e-7);
    try expectFinite("1e+16", 1e16);
    try expectFinite("1.5e+300", 1.5e300);
    try expectFinite("1234567890123456.8", 1234567890123456.8);
}

fn expectRoundTrip(source: []const u8, expected: []const u8, options: StringifyOptions) !void {
    const parsed = try parse(testing.allocator, source, .{});
    defer parsed.deinit();
    const text = try stringify(testing.allocator, parsed.value, options);
    defer testing.allocator.free(text);
    try testing.expectEqualStrings(expected, text);
}

test "document layout" {
    try expectRoundTrip(
        \\[b]
        \\x: 1
        \\version: 2
        \\[SERVER_ID]
        \\config: {disabled_channels:, disabled_users: [9892, 82082, 8209], e: {}}
        \\"needs quotes": "tab\t é"
        \\
    ,
        \\[b]
        \\x: 1
        \\version: 2
        \\
        \\[SERVER_ID]
        \\config: {
        \\  disabled_channels:,
        \\  disabled_users: [9892, 82082, 8209],
        \\  e: {}
        \\}
        \\"needs quotes": "tab\t é"
        \\
    , .{});
}

test "root scalars are hoisted above sections" {
    try expectRoundTrip("a: [null, 1.0, nan, -inf]\n[s]\nk:\n", "a: [null, 1, nan, -inf]\n\n[s]\nk:\n", .{});
    try expectRoundTrip("", "", .{});
}

test "arrays wrap when they outgrow the width" {
    try expectRoundTrip("[s]\nids: [111111111111111111, 222222222222222222]\n",
        \\[s]
        \\ids: [
        \\  111111111111111111,
        \\  222222222222222222
        \\]
        \\
    , .{ .width = 30 });
}

test "sort keys and ensure ascii" {
    try expectRoundTrip("b: \"é😀\"\na: 1\n", "a: 1\nb: \"\\u00e9\\ud83d\\ude00\"\n", .{
        .sort_keys = true,
        .ensure_ascii = true,
    });
}
