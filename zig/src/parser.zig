// parser.zig
//! Recursive-descent parser for `.jp`; `[` opens a section only at the top level.

const std = @import("std");
const Allocator = std.mem.Allocator;

const scanner_zig = @import("scanner.zig");
const Scanner = scanner_zig.Scanner;
const Diagnostic = scanner_zig.Diagnostic;
pub const Error = scanner_zig.Error;
const repr = scanner_zig.repr;
const interpretBare = scanner_zig.interpretBare;

const values = @import("values.zig");
const Value = values.Value;
const Object = values.Object;
const Array = values.Array;

/// How deep `{` / `[` nesting may go, so hostile input fails cleanly instead of overflowing the stack.
pub const max_depth = 200;

/// What to do when a key appears twice in the same mapping.
pub const DuplicateKeys = enum { @"error", first, last };

pub const ParseOptions = struct {
    duplicate_keys: DuplicateKeys = .@"error",
    /// Filled in when parsing fails with `error.SyntaxError`.
    diagnostic: ?*Diagnostic = null,
};

/// A parsed document. Everything it holds lives in `arena`; call `deinit` to free it.
pub const Parsed = struct {
    arena: *std.heap.ArenaAllocator,
    value: Object,

    pub fn deinit(self: Parsed) void {
        const gpa = self.arena.child_allocator;
        self.arena.deinit();
        gpa.destroy(self.arena);
    }
};

/// Parse `text` as a `.jp` document.
pub fn parse(gpa: Allocator, text: []const u8, options: ParseOptions) Error!Parsed {
    const arena = try gpa.create(std.heap.ArenaAllocator);
    errdefer gpa.destroy(arena);
    arena.* = .init(gpa);
    errdefer arena.deinit();

    const value = try parseLeaky(arena.allocator(), text, options);
    return .{
        .arena = arena,
        .value = value,
    };
}

/// Like `parse`, but allocates into `arena` and never frees; for callers that own an arena already.
pub fn parseLeaky(arena: Allocator, text: []const u8, options: ParseOptions) Error!Object {
    var parser: Parser = .{
        .scanner = .init(text, arena, options.diagnostic),
        .duplicate_keys = options.duplicate_keys,
    };
    const invalid_at = firstInvalidUtf8(parser.scanner.text);
    if (invalid_at < parser.scanner.text.len) return parser.scanner.fail(invalid_at, "invalid UTF-8", .{});
    return parser.document();
}

fn firstInvalidUtf8(text: []const u8) usize {
    var i: usize = 0;
    while (i < text.len) {
        const length = std.unicode.utf8ByteSequenceLength(text[i]) catch return i;
        if (i + length > text.len) return i;
        _ = std.unicode.utf8Decode(text[i .. i + length]) catch return i;
        i += length;
    }
    return i;
}

const Parser = struct {
    scanner: Scanner,
    duplicate_keys: DuplicateKeys,
    depth: usize = 0,
    // Section paths opened by an explicit header, to catch `[a]` twice
    defined: std.StringHashMapUnmanaged(void) = .empty,

    const Entry = struct {
        key: []const u8,
        value: Value,
        key_pos: usize,
    };

    fn document(self: *Parser) Error!Object {
        const scanner = &self.scanner;
        var root: Object = .empty;
        var current: *Object = &root;

        while (true) {
            try scanner.skipIgnorable();
            if (scanner.eof()) return root;
            if (scanner.peek() == '[') {
                current = try self.header(&root);
                continue;
            }
            const entry = try self.parseEntry();
            try self.store(current, entry);
            try self.separator(null);
        }
    }

    // -- sections --------------------------------------------------------

    /// Parse `[a.b.c]` and return the object its entries belong to.
    fn header(self: *Parser, root: *Object) Error!*Object {
        const scanner = &self.scanner;
        const arena = scanner.arena;
        const start = scanner.pos;
        scanner.pos += 1;

        var path: std.ArrayList([]const u8) = .empty;
        var pending_dot = false;
        while (true) {
            try scanner.skipInline();
            if (scanner.atLineEnd()) return scanner.fail(start, "unterminated section header", .{});
            var char = scanner.peek().?;
            if (char == ']') {
                if (path.items.len == 0) return scanner.fail(start, "section header cannot be empty", .{});
                if (pending_dot) return scanner.failHere("section header segment cannot be empty", .{});
                scanner.pos += 1;
                break;
            }
            if (char == '"' or char == '\'') {
                try path.append(arena, try scanner.scanString());
            } else {
                const segment = scanner_zig.trimSpace(scanner.scanUntil(.header));
                if (segment.len == 0) return scanner.failHere("section header segment cannot be empty", .{});
                try path.append(arena, segment);
            }
            pending_dot = false;

            try scanner.skipInline();
            char = scanner.peek() orelse 0;
            if (char == '.') {
                scanner.pos += 1;
                pending_dot = true;
                continue;
            }
            if (char == ']') {
                scanner.pos += 1;
                break;
            }
            if (scanner.atLineEnd()) return scanner.fail(start, "unterminated section header", .{});
            return scanner.failHere("expected '.' or ']' in section header, found {f}", .{scanner.describeHere()});
        }

        try scanner.skipInline();
        if (!scanner.atLineEnd()) {
            return scanner.failHere("unexpected {f} after section header", .{scanner.describeHere()});
        }
        return self.openSection(root, path.items, start);
    }

    /// Create or reuse the nested object addressed by `path`.
    fn openSection(self: *Parser, root: *Object, path: []const []const u8, start: usize) Error!*Object {
        const scanner = &self.scanner;
        const arena = scanner.arena;

        // Length-prefixed parts, so no key text can make two paths collide
        var id: std.ArrayList(u8) = .empty;
        for (path) |part| {
            try id.print(arena, "{d}:", .{part.len});
            try id.appendSlice(arena, part);
        }
        const seen = try self.defined.getOrPut(arena, id.items);
        if (seen.found_existing and self.duplicate_keys == .@"error") {
            return scanner.fail(start, "section '[{f}]' is defined twice", .{Dotted{ .parts = path }});
        }

        var node = root;
        for (path, 0..) |part, depth| {
            const slot = try node.getOrPut(arena, part);
            if (!slot.found_existing) {
                slot.value_ptr.* = .{ .object = .empty };
            } else if (slot.value_ptr.* != .object) {
                return scanner.fail(start, "cannot open section '[{f}]': '{f}' is already a non-section value", .{
                    Dotted{ .parts = path },
                    Dotted{ .parts = path[0 .. depth + 1] },
                });
            }
            node = &slot.value_ptr.object;
        }
        return node;
    }

    // -- entries ---------------------------------------------------------

    fn parseEntry(self: *Parser) Error!Entry {
        const scanner = &self.scanner;
        try scanner.skipInline();
        const key_pos = scanner.pos;
        const key = try self.parseKey();

        try scanner.skipInline();
        if (scanner.peek() != ':') {
            return scanner.failHere("expected ':' after key {f}, found {f}", .{ repr(key), scanner.describeHere() });
        }
        scanner.pos += 1;
        return .{
            .key = key,
            .value = try self.optionalValue(),
            .key_pos = key_pos,
        };
    }

    fn parseKey(self: *Parser) Error![]const u8 {
        const scanner = &self.scanner;
        const char = scanner.peek() orelse return scanner.failHere("expected a key, found end of file", .{});
        if (char == '"' or char == '\'') return scanner.scanString();
        if (char == '}' or char == ']') return scanner.failHere("unexpected {f}", .{scanner.describeHere()});

        const start = scanner.pos;
        const key = scanner_zig.trimSpace(scanner.scanUntil(.key));
        if (key.len == 0) return scanner.fail(start, "expected a key, found {f}", .{scanner.describeHere()});
        return key;
    }

    /// The value after a `:`, or null when it is empty.
    fn optionalValue(self: *Parser) Error!Value {
        const scanner = &self.scanner;
        try scanner.skipInline();
        if (scanner.peek() == ',' or scanner.atLineEnd()) return .null;
        return self.parseValue();
    }

    fn parseValue(self: *Parser) Error!Value {
        const scanner = &self.scanner;
        return switch (scanner.peek().?) {
            '{' => .{ .object = try self.object() },
            '[' => .{ .array = try self.array() },
            '"', '\'' => .{ .string = try scanner.scanString() },
            else => interpretBare(scanner.scanUntil(.value)),
        };
    }

    // -- containers ------------------------------------------------------

    fn enter(self: *Parser, start: usize) Error!void {
        self.depth += 1;
        if (self.depth > max_depth) {
            return self.scanner.fail(start, "nesting deeper than {d} levels", .{max_depth});
        }
    }

    fn object(self: *Parser) Error!Object {
        const scanner = &self.scanner;
        const start = scanner.pos;
        try self.enter(start);
        scanner.pos += 1;
        var result: Object = .empty;

        while (true) {
            try scanner.skipIgnorable();
            const char = scanner.peek() orelse return scanner.fail(start, "unterminated object: missing '}}'", .{});
            if (char == '}') {
                scanner.pos += 1;
                self.depth -= 1;
                return result;
            }
            // Tolerate stray or repeated separators
            if (char == ',') {
                scanner.pos += 1;
                continue;
            }
            const entry = try self.parseEntry();
            try self.store(&result, entry);
            try self.separator('}');
        }
    }

    fn array(self: *Parser) Error!Array {
        const scanner = &self.scanner;
        const start = scanner.pos;
        try self.enter(start);
        scanner.pos += 1;
        var items: Array = .empty;

        while (true) {
            try scanner.skipIgnorable();
            const char = scanner.peek() orelse return scanner.fail(start, "unterminated array: missing ']'", .{});
            if (char == ']') {
                scanner.pos += 1;
                self.depth -= 1;
                return items;
            }
            if (char == ',') {
                scanner.pos += 1;
                continue;
            }
            if (char == '}') return scanner.fail(start, "unterminated array: found '}}' before ']'", .{});
            try items.append(scanner.arena, try self.parseValue());
            try self.separator(']');
        }
    }

    // -- separators and storage ------------------------------------------

    /// Require a `,`, a line break, or the closing bracket after a value.
    fn separator(self: *Parser, closing: ?u8) Error!void {
        const scanner = &self.scanner;
        try scanner.skipInline();
        if (scanner.atLineEnd()) return;
        const char = scanner.peek().?;
        if (char == ',') {
            scanner.pos += 1;
            return;
        }
        if (closing) |close| {
            if (char == close) return;
            return scanner.failHere("expected ',', a line break or '{c}' after value, found {f}", .{ close, scanner.describeHere() });
        }
        // A section header always starts its own line, so the previous entry never ended
        if (char == '[') return scanner.failHere("expected a line break before a section header", .{});
        return scanner.failHere("expected ',' or a line break after value, found {f}", .{scanner.describeHere()});
    }

    /// Insert an entry, applying the duplicate-key policy.
    fn store(self: *Parser, target: *Object, entry: Entry) Error!void {
        const slot = try target.getOrPut(self.scanner.arena, entry.key);
        if (slot.found_existing) {
            switch (self.duplicate_keys) {
                .@"error" => return self.scanner.fail(entry.key_pos, "duplicate key {f}", .{repr(entry.key)}),
                .first => return,
                .last => {},
            }
        }
        slot.value_ptr.* = entry.value;
    }
};

/// Formats a section path as `a.b.c`.
const Dotted = struct {
    parts: []const []const u8,

    pub fn format(self: Dotted, writer: *std.Io.Writer) std.Io.Writer.Error!void {
        for (self.parts, 0..) |part, i| {
            if (i > 0) try writer.writeByte('.');
            try writer.writeAll(part);
        }
    }
};

// -- tests -----------------------------------------------------------------

const testing = std.testing;

fn expectSyntaxError(text: []const u8, message: []const u8, line: usize, col: usize) !void {
    var diagnostic: Diagnostic = .{};
    try testing.expectError(error.SyntaxError, parse(testing.allocator, text, .{ .diagnostic = &diagnostic }));
    try testing.expectEqualStrings(message, diagnostic.message);
    try testing.expectEqual(line, diagnostic.line);
    try testing.expectEqual(col, diagnostic.col);
}

test "sections, nested objects and empty values" {
    const parsed = try parse(testing.allocator,
        \\~~ leading comment
        \\version: 2
        \\
        \\[SERVER_ID]
        \\config: {
        \\  disabled_channels:,
        \\  disabled_users: [9892, 82082, 8209]  ~~ trailing
        \\}
        \\
        \\[guild.limits]
        \\rate: 30 ~* inline *~
        \\~* a block
        \\   comment *~
        \\colour: #8496AD
        \\price: $5 $$ not a comment
    , .{});
    defer parsed.deinit();
    const root = parsed.value;

    try testing.expectEqual(@as(i128, 2), root.get("version").?.integer);
    const config = root.get("SERVER_ID").?.object.get("config").?.object;
    try testing.expectEqual(Value.null, config.get("disabled_channels").?);
    try testing.expectEqual(@as(usize, 3), config.get("disabled_users").?.array.items.len);

    const limits = root.get("guild").?.object.get("limits").?.object;
    try testing.expectEqual(@as(i128, 30), limits.get("rate").?.integer);
    try testing.expectEqualStrings("#8496AD", limits.get("colour").?.string);
    try testing.expectEqualStrings("$5 $$ not a comment", limits.get("price").?.string);
}

test "strings and escapes" {
    const parsed = try parse(testing.allocator,
        \\a: "tab\there \u00e9 \U0001F600 \ud83d\ude00"
        \\c: "one \
        \\    two"
        \\url: http://example.com:8080
    , .{ .duplicate_keys = .last });
    defer parsed.deinit();
    const root = parsed.value;

    try testing.expectEqualStrings("tab\there é 😀 😀", root.get("a").?.string);
    try testing.expectEqualStrings("one two", root.get("c").?.string);
    try testing.expectEqualStrings("http://example.com:8080", root.get("url").?.string);
}

test "duplicate keys" {
    try expectSyntaxError("a: 1\na: 2\n", "duplicate key 'a'", 2, 1);
    try expectSyntaxError("[s]\n[s]\n", "section '[s]' is defined twice", 2, 1);

    const first = try parse(testing.allocator, "a: 1\na: 2\n", .{ .duplicate_keys = .first });
    defer first.deinit();
    try testing.expectEqual(@as(i128, 1), first.value.get("a").?.integer);

    const last = try parse(testing.allocator, "a: 1\na: 2\n", .{ .duplicate_keys = .last });
    defer last.deinit();
    try testing.expectEqual(@as(i128, 2), last.value.get("a").?.integer);
}

test "syntax errors point at the right place" {
    try expectSyntaxError("[SERVER_ID]\nprefix \"!\"\n", "expected ':' after key 'prefix', found '\"'", 2, 8);
    try expectSyntaxError("a: {\n  b: 1\n", "unterminated object: missing '}'", 1, 4);
    try expectSyntaxError("a: [}\n", "unterminated array: found '}' before ']'", 1, 4);
    try expectSyntaxError("a: \"x\n", "unterminated string literal (use \\n for a line break)", 1, 4);
    try expectSyntaxError("a: \"\\q\"\n", "invalid escape sequence '\\q'", 1, 5);
    try expectSyntaxError("a: \"x\" b\n", "expected ',' or a line break after value, found 'b'", 1, 8);
    try expectSyntaxError("a: \"x\" [s]\n", "expected a line break before a section header", 1, 8);
    try expectSyntaxError("[]\n", "section header cannot be empty", 1, 1);
    try expectSyntaxError("[a.]\n", "section header segment cannot be empty", 1, 4);
    try expectSyntaxError("[a\n", "unterminated section header", 1, 1);
    try expectSyntaxError("a: 1\n[a]\n", "cannot open section '[a]': 'a' is already a non-section value", 2, 1);
    try expectSyntaxError("~* open\n", "unterminated block comment: missing '*~'", 1, 1);
    try expectSyntaxError("é: \"\\uD800\"\n", "'\\uD800' is an unpaired surrogate", 1, 5);
    try expectSyntaxError("a: \xff\n", "invalid UTF-8", 1, 4);
}

test "nesting limit" {
    const deep = "a: " ++ "[" ** (max_depth + 1);
    try expectSyntaxError(deep, "nesting deeper than 200 levels", 1, 4 + max_depth);
}

test "byte order mark and CRLF" {
    const parsed = try parse(testing.allocator, "\u{FEFF}[a]\r\nx: 1\r\n", .{});
    defer parsed.deinit();
    try testing.expectEqual(@as(i128, 1), parsed.value.get("a").?.object.get("x").?.integer);
}

test "diagnostic render" {
    const text = "[SERVER_ID]\n\tprefix \"!\"\n";
    var diagnostic: Diagnostic = .{};
    try testing.expectError(error.SyntaxError, parse(testing.allocator, text, .{ .diagnostic = &diagnostic }));

    var out: std.Io.Writer.Allocating = .init(testing.allocator);
    defer out.deinit();
    try diagnostic.render(&out.writer, text, "servers.jp");
    try testing.expectEqualStrings(
        "servers.jp:2:9: expected ':' after key 'prefix', found '\"'\n    \tprefix \"!\"\n    \t       ^",
        out.written(),
    );
}
