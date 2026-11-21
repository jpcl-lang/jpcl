// json.zig
//! Renders values as indented JSON, the way `jpcl to-json` does in every port.

const std = @import("std");
const Allocator = std.mem.Allocator;

const Value = @import("values.zig").Value;
const writer_zig = @import("writer.zig");

/// The caller owns the returned slice.
pub fn toJson(gpa: Allocator, value: Value, indent: usize) Allocator.Error![]u8 {
    var arena: std.heap.ArenaAllocator = .init(gpa);
    defer arena.deinit();
    var out: std.ArrayList(u8) = .empty;
    try render(arena.allocator(), &out, value, indent, 0);
    return gpa.dupe(u8, out.items);
}

fn render(arena: Allocator, out: *std.ArrayList(u8), value: Value, indent: usize, level: usize) Allocator.Error!void {
    switch (value) {
        .null => try out.appendSlice(arena, "null"),
        .bool => |flag| try out.appendSlice(arena, if (flag) "true" else "false"),
        .integer => |integer| try out.print(arena, "{d}", .{integer}),
        .float => |float| {
            if (std.math.isNan(float)) {
                try out.appendSlice(arena, "NaN");
            } else if (std.math.isInf(float)) {
                try out.appendSlice(arena, if (float > 0) "Infinity" else "-Infinity");
            } else {
                try out.appendSlice(arena, try writer_zig.formatFinite(arena, float));
            }
        },
        .string => |text| try quote(arena, out, text),
        .array => |array| {
            if (array.items.len == 0) return out.appendSlice(arena, "[]");
            try out.append(arena, '[');
            for (array.items, 0..) |item, i| {
                if (i > 0) try out.append(arena, ',');
                try newline(arena, out, indent * (level + 1));
                try render(arena, out, item, indent, level + 1);
            }
            try newline(arena, out, indent * level);
            try out.append(arena, ']');
        },
        .object => |object| {
            if (object.count() == 0) return out.appendSlice(arena, "{}");
            try out.append(arena, '{');
            var entries = object.iterator();
            var first = true;
            while (entries.next()) |entry| {
                if (!first) try out.append(arena, ',');
                first = false;
                try newline(arena, out, indent * (level + 1));
                try quote(arena, out, entry.key_ptr.*);
                try out.appendSlice(arena, ": ");
                try render(arena, out, entry.value_ptr.*, indent, level + 1);
            }
            try newline(arena, out, indent * level);
            try out.append(arena, '}');
        },
    }
}

fn newline(arena: Allocator, out: *std.ArrayList(u8), spaces: usize) Allocator.Error!void {
    try out.append(arena, '\n');
    try out.appendNTimes(arena, ' ', spaces);
}

/// A JSON string literal, escaped as `JSON.stringify` does.
fn quote(arena: Allocator, out: *std.ArrayList(u8), text: []const u8) Allocator.Error!void {
    try out.append(arena, '"');
    for (text) |char| {
        switch (char) {
            '"' => try out.appendSlice(arena, "\\\""),
            '\\' => try out.appendSlice(arena, "\\\\"),
            '\n' => try out.appendSlice(arena, "\\n"),
            '\r' => try out.appendSlice(arena, "\\r"),
            '\t' => try out.appendSlice(arena, "\\t"),
            0x08 => try out.appendSlice(arena, "\\b"),
            0x0c => try out.appendSlice(arena, "\\f"),
            0...0x07, 0x0b, 0x0e...0x1f => try out.print(arena, "\\u{x:0>4}", .{char}),
            else => try out.append(arena, char),
        }
    }
    try out.append(arena, '"');
}

test "json output" {
    const parse = @import("parser.zig").parse;
    const parsed = try parse(std.testing.allocator, "id: 111111111111111111\nn: [nan, -inf, 0.5]\ne: {}\ns: \"a\\\"b\\u0001\"\n", .{});
    defer parsed.deinit();
    const text = try toJson(std.testing.allocator, .{ .object = parsed.value }, 2);
    defer std.testing.allocator.free(text);
    try std.testing.expectEqualStrings(
        \\{
        \\  "id": 111111111111111111,
        \\  "n": [
        \\    NaN,
        \\    -Infinity,
        \\    0.5
        \\  ],
        \\  "e": {},
        \\  "s": "a\"b\u0001"
        \\}
    , text);
}
