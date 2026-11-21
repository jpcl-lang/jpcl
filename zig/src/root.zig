// root.zig
//! jpcl: a hybrid JSON/TOML configuration language (`.jp` files).

const std = @import("std");
const Allocator = std.mem.Allocator;
const Io = std.Io;

const parser = @import("parser.zig");
const scanner = @import("scanner.zig");
const values = @import("values.zig");
const writer = @import("writer.zig");

pub const Value = values.Value;
pub const Object = values.Object;
pub const Array = values.Array;

pub const Diagnostic = scanner.Diagnostic;
pub const DuplicateKeys = parser.DuplicateKeys;
pub const ParseOptions = parser.ParseOptions;
pub const Parsed = parser.Parsed;
pub const StringifyOptions = writer.StringifyOptions;
pub const max_depth = parser.max_depth;

pub const parse = parser.parse;
pub const parseLeaky = parser.parseLeaky;
pub const stringify = writer.stringify;
pub const toJson = @import("json.zig").toJson;

pub const version = "2.0.0";

/// Conventional file extension.
pub const suffix = ".jp";

/// Largest file `load` will read.
pub const max_file_size = 64 * 1024 * 1024;

pub const LoadError = parser.Error || Io.Dir.ReadFileAllocError;

/// Read and parse a `.jp` file. The returned `Parsed` owns everything, including the file text.
pub fn load(gpa: Allocator, io: Io, path: []const u8, options: ParseOptions) LoadError!Parsed {
    const arena = try gpa.create(std.heap.ArenaAllocator);
    errdefer gpa.destroy(arena);
    arena.* = .init(gpa);
    errdefer arena.deinit();

    const text = try Io.Dir.cwd().readFileAlloc(io, path, arena.allocator(), .limited(max_file_size));
    return .{
        .arena = arena,
        .value = try parseLeaky(arena.allocator(), text, options),
    };
}

/// Serialise `root` and write it to `path` atomically, creating missing parent folders.
pub fn dump(gpa: Allocator, io: Io, root: Object, path: []const u8, options: StringifyOptions) !void {
    const text = try stringify(gpa, root, options);
    defer gpa.free(text);
    try writeAtomic(io, path, text);
}

/// Write `data` to a temporary file, then rename it over `path`, so readers never see half a file.
pub fn writeAtomic(io: Io, path: []const u8, data: []const u8) !void {
    var file = try Io.Dir.cwd().createFileAtomic(io, path, .{
        .make_path = true,
        .replace = true,
    });
    defer file.deinit(io);
    try file.file.writeStreamingAll(io, data);
    try file.replace(io);
}

/// The value at a dotted path such as `SERVER_ID.config.disabled_users`, or null if
/// any part is missing. An empty value (`key:`) is present and returns `.null`.
pub fn getPath(root: Object, path: []const u8) ?Value {
    var node: Value = .{ .object = root };
    var parts = std.mem.splitScalar(u8, path, '.');
    while (parts.next()) |part| {
        if (node != .object) return null;
        node = node.object.get(part) orelse return null;
    }
    return node;
}

test {
    std.testing.refAllDecls(@This());
    _ = @import("scanner.zig");
    _ = @import("parser.zig");
    _ = @import("writer.zig");
    _ = @import("json.zig");
}

test "getPath" {
    const parsed = try parse(std.testing.allocator, "[a]\nb: {c: [1]}\nempty:\n", .{});
    defer parsed.deinit();
    try std.testing.expectEqual(@as(usize, 1), getPath(parsed.value, "a.b.c").?.array.items.len);
    try std.testing.expectEqual(Value.null, getPath(parsed.value, "a.empty").?);
    try std.testing.expectEqual(@as(?Value, null), getPath(parsed.value, "a.b.c.d"));
    try std.testing.expectEqual(@as(?Value, null), getPath(parsed.value, "missing"));
}

test "load and dump round trip" {
    const io = std.testing.io;
    const gpa = std.testing.allocator;
    var tmp = std.testing.tmpDir(.{});
    defer tmp.cleanup();

    var root: Object = .empty;
    defer root.deinit(gpa);
    try root.put(gpa, "version", .{ .integer = 2 });

    const dir_path = try tmp.dir.realPathFileAlloc(io, ".", gpa);
    defer gpa.free(dir_path);
    const path = try std.fs.path.join(gpa, &.{ dir_path, "nested", "out.jp" });
    defer gpa.free(path);

    try dump(gpa, io, root, path, .{});
    const parsed = try load(gpa, io, path, .{});
    defer parsed.deinit();
    try std.testing.expectEqual(@as(i128, 2), parsed.value.get("version").?.integer);
}
