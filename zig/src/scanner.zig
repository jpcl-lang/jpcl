// scanner.zig
//! Lexical scanning for `.jp`: whitespace, comments, strings and bare tokens.

const std = @import("std");
const Allocator = std.mem.Allocator;

const Value = @import("values.zig").Value;

// Starts a comment that runs to the end of the line
pub const line_comment = "~~";

// Opens a block comment, which may span lines
pub const block_open = "~*";

// Closes a block comment
pub const block_close = "*~";

pub const Error = error{ SyntaxError, OutOfMemory };

/// Where and why parsing failed. Filled in when `parse` returns `error.SyntaxError`.
pub const Diagnostic = struct {
    /// The message without the location prefix or source excerpt.
    message: []const u8 = "",
    /// Zero-based byte offset of the error inside the parsed text.
    pos: usize = 0,
    /// One-based line number.
    line: usize = 1,
    /// One-based column, counted in code points.
    col: usize = 1,

    buffer: [512]u8 = undefined,

    /// Write `file:line:col: message`, then the source line with a caret under the error.
    pub fn render(
        self: *const Diagnostic,
        writer: *std.Io.Writer,
        text: []const u8,
        filename: ?[]const u8,
    ) std.Io.Writer.Error!void {
        const doc = stripBom(text);
        try writer.print("{s}:{d}:{d}: {s}", .{
            filename orelse "<string>",
            self.line,
            self.col,
            self.message,
        });
        if (doc.len == 0) return;

        const line_start = if (std.mem.lastIndexOfScalar(u8, doc[0..self.pos], '\n')) |i| i + 1 else 0;
        const line_end = std.mem.indexOfScalarPos(u8, doc, self.pos, '\n') orelse doc.len;
        const source = std.mem.trimEnd(u8, doc[line_start..line_end], "\r");
        try writer.print("\n    {s}\n    ", .{source});

        // Keep tabs in the caret prefix so the marker stays aligned
        var seen: usize = 0;
        var view = std.unicode.Utf8View.initUnchecked(source).iterator();
        while (seen + 1 < self.col) : (seen += 1) {
            const char = view.nextCodepoint() orelse break;
            try writer.writeByte(if (char == '\t') '\t' else ' ');
        }
        try writer.writeByte('^');
    }
};

/// Drop any leading UTF-8 byte order marks.
pub fn stripBom(text: []const u8) []const u8 {
    var rest = text;
    while (std.mem.startsWith(u8, rest, "\u{FEFF}")) rest = rest[3..];
    return rest;
}

fn isInlineSpace(char: u8) bool {
    return char == ' ' or char == '\t' or char == 0x0c or char == 0x0b;
}

fn isLineBreak(char: u8) bool {
    return char == '\r' or char == '\n';
}

pub const Terminators = enum { value, key, header };

fn ends(kind: Terminators, char: u8) bool {
    return switch (kind) {
        .value => switch (char) {
            ',', '}', ']', '\r', '\n' => true,
            else => false,
        },
        // A bare key may contain spaces but not brackets or quotes
        .key => switch (char) {
            ':', ',', '{', '}', '[', ']', '"', '\'', '\r', '\n' => true,
            else => false,
        },
        .header => switch (char) {
            '.', ']', '\r', '\n' => true,
            else => false,
        },
    };
}

/// A cursor over the source text with lexical helpers.
pub const Scanner = struct {
    text: []const u8,
    pos: usize = 0,
    arena: Allocator,
    diagnostic: ?*Diagnostic,

    pub fn init(text: []const u8, arena: Allocator, diagnostic: ?*Diagnostic) Scanner {
        return .{
            .text = stripBom(text),
            .arena = arena,
            .diagnostic = diagnostic,
        };
    }

    // -- cursor ----------------------------------------------------------

    pub fn eof(self: *const Scanner) bool {
        return self.pos >= self.text.len;
    }

    pub fn peek(self: *const Scanner) ?u8 {
        return if (self.pos < self.text.len) self.text[self.pos] else null;
    }

    pub fn startsWith(self: *const Scanner, needle: []const u8) bool {
        return std.mem.startsWith(u8, self.text[self.pos..], needle);
    }

    /// Record a syntax error at `pos` and return `error.SyntaxError`.
    pub fn fail(self: *Scanner, pos: usize, comptime format: []const u8, args: anytype) Error {
        const diagnostic = self.diagnostic orelse return error.SyntaxError;
        const at = @min(pos, self.text.len);
        diagnostic.* = .{ .pos = at };
        diagnostic.message = std.fmt.bufPrint(&diagnostic.buffer, format, args) catch blk: {
            const cut = diagnostic.buffer.len - 3;
            @memcpy(diagnostic.buffer[cut..], "...");
            break :blk diagnostic.buffer[0..];
        };

        const before = self.text[0..at];
        diagnostic.line = std.mem.count(u8, before, "\n") + 1;
        const line_start = if (std.mem.lastIndexOfScalar(u8, before, '\n')) |i| i + 1 else 0;
        diagnostic.col = (std.unicode.utf8CountCodepoints(before[line_start..]) catch before.len - line_start) + 1;
        return error.SyntaxError;
    }

    pub fn failHere(self: *Scanner, comptime format: []const u8, args: anytype) Error {
        return self.fail(self.pos, format, args);
    }

    /// Describe the current character for an error message.
    pub fn describeHere(self: *const Scanner) Repr {
        const char = self.peek() orelse return .{ .literal = "end of file" };
        if (isLineBreak(char)) return .{ .literal = "end of line" };
        const length = std.unicode.utf8ByteSequenceLength(char) catch 1;
        const end = @min(self.pos + length, self.text.len);
        return .{ .quoted = self.text[self.pos..end] };
    }

    // -- trivia ----------------------------------------------------------

    /// Skip spaces, tabs and comments, but never a line break. A block comment
    /// that spans lines counts as a line break, so the cursor stops on its `~*`.
    pub fn skipInline(self: *Scanner) Error!void {
        return self.skip(false);
    }

    /// Skip all whitespace (line breaks included) and comments.
    pub fn skipIgnorable(self: *Scanner) Error!void {
        return self.skip(true);
    }

    fn skip(self: *Scanner, newlines: bool) Error!void {
        const text = self.text;
        var i = self.pos;
        while (i < text.len) {
            const char = text[i];
            if (isInlineSpace(char) or (newlines and isLineBreak(char))) {
                i += 1;
            } else if (std.mem.startsWith(u8, text[i..], line_comment)) {
                while (i < text.len and !isLineBreak(text[i])) i += 1;
            } else if (std.mem.startsWith(u8, text[i..], block_open)) {
                const close = std.mem.indexOfPos(u8, text, i + block_open.len, block_close) orelse
                    return self.fail(i, "unterminated block comment: missing '*~'", .{});
                const after = close + block_close.len;
                const crosses_line = std.mem.indexOfAny(u8, text[i..after], "\r\n") != null;
                if (crosses_line and !newlines) break;
                i = after;
            } else {
                break;
            }
        }
        self.pos = i;
    }

    /// At a line break, the end of the file, or a block comment that spans lines.
    pub fn atLineEnd(self: *const Scanner) bool {
        const char = self.peek() orelse return true;
        return isLineBreak(char) or self.startsWith(block_open);
    }

    // -- literals --------------------------------------------------------

    /// Read a quoted string at the cursor and return its decoded value.
    pub fn scanString(self: *Scanner) Error![]const u8 {
        const text = self.text;
        const start = self.pos;
        const quote = text[start];
        var out: std.ArrayList(u8) = .empty;
        var i = start + 1;
        var literal_from = i;

        while (true) {
            if (i >= text.len) return self.fail(start, "unterminated string literal", .{});
            const char = text[i];
            if (char == quote) {
                self.pos = i + 1;
                if (out.items.len == 0) return text[literal_from..i];
                try out.appendSlice(self.arena, text[literal_from..i]);
                return out.items;
            }
            if (isLineBreak(char)) {
                return self.fail(start, "unterminated string literal (use \\n for a line break)", .{});
            }
            if (char != '\\') {
                i += 1;
                continue;
            }

            try out.appendSlice(self.arena, text[literal_from..i]);
            const escape_at = i;
            i += 1;
            if (i >= text.len) return self.fail(escape_at, "unterminated escape sequence", .{});
            const marker = text[i];

            if (simpleEscape(marker)) |decoded| {
                try out.append(self.arena, decoded);
                i += 1;
            } else if (marker == 'x' or marker == 'u' or marker == 'U') {
                i = try self.scanHexEscape(&out, i, escape_at);
            } else if (isLineBreak(marker)) {
                // Backslash-newline continues the string, eating the indent
                i += 1;
                if (marker == '\r' and i < text.len and text[i] == '\n') i += 1;
                while (i < text.len and isInlineSpace(text[i])) i += 1;
            } else {
                const length = std.unicode.utf8ByteSequenceLength(marker) catch 1;
                const shown = text[i..@min(i + length, text.len)];
                return self.fail(escape_at, "invalid escape sequence '\\{s}'", .{shown});
            }
            literal_from = i;
        }
    }

    /// Decode `\xNN`, `\uNNNN` or `\UNNNNNNNN` whose marker is at `marker_at`.
    fn scanHexEscape(self: *Scanner, out: *std.ArrayList(u8), marker_at: usize, escape_at: usize) Error!usize {
        const text = self.text;
        const marker = text[marker_at];
        const width: usize = switch (marker) {
            'x' => 2,
            'u' => 4,
            else => 8,
        };
        const digits_end = @min(marker_at + 1 + width, text.len);
        const digits = text[marker_at + 1 .. digits_end];
        var code = parseHex(digits, width) orelse
            return self.fail(escape_at, "'\\{c}' escape needs {d} hex digits", .{ marker, width });
        var i = digits_end;

        // Recombine a UTF-16 surrogate pair, as JSON encoders emit them
        if (marker == 'u' and code >= 0xd800 and code <= 0xdbff and std.mem.startsWith(u8, text[i..], "\\u")) {
            if (parseHex(text[i + 2 .. @min(i + 6, text.len)], 4)) |low| {
                if (low >= 0xdc00 and low <= 0xdfff) {
                    code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
                    i += 6;
                }
            }
        }

        if (code >= 0xd800 and code <= 0xdfff) {
            return self.fail(escape_at, "'\\{c}{s}' is an unpaired surrogate", .{ marker, digits });
        }
        if (code > 0x10ffff) {
            return self.fail(escape_at, "'\\{c}{s}' is outside the Unicode range", .{ marker, digits });
        }
        var buffer: [4]u8 = undefined;
        const length = std.unicode.utf8Encode(@intCast(code), &buffer) catch unreachable;
        try out.appendSlice(self.arena, buffer[0..length]);
        return i;
    }

    /// Consume up to (not including) a terminator or a comment.
    pub fn scanUntil(self: *Scanner, kind: Terminators) []const u8 {
        const text = self.text;
        var i = self.pos;
        while (i < text.len and !ends(kind, text[i]) and !isCommentAt(text, i)) i += 1;
        const raw = text[self.pos..i];
        self.pos = i;
        return raw;
    }
};

fn isCommentAt(text: []const u8, i: usize) bool {
    const rest = text[i..];
    return std.mem.startsWith(u8, rest, line_comment) or std.mem.startsWith(u8, rest, block_open);
}

fn simpleEscape(marker: u8) ?u8 {
    return switch (marker) {
        '"', '\'', '\\', '/' => marker,
        '0' => 0,
        'a' => 0x07,
        'b' => 0x08,
        'e' => 0x1b,
        'f' => 0x0c,
        'n' => '\n',
        'r' => '\r',
        't' => '\t',
        'v' => 0x0b,
        else => null,
    };
}

fn parseHex(digits: []const u8, width: usize) ?u32 {
    if (digits.len < width) return null;
    for (digits) |digit| if (!std.ascii.isHex(digit)) return null;
    return std.fmt.parseInt(u32, digits, 16) catch null;
}

/// A string shown the way Python's `repr()` does, for error messages.
pub const Repr = union(enum) {
    literal: []const u8,
    quoted: []const u8,

    pub fn format(self: Repr, writer: *std.Io.Writer) std.Io.Writer.Error!void {
        switch (self) {
            .literal => |text| try writer.writeAll(text),
            .quoted => |text| try writeRepr(writer, text),
        }
    }
};

pub fn repr(text: []const u8) Repr {
    return .{ .quoted = text };
}

fn writeRepr(writer: *std.Io.Writer, value: []const u8) std.Io.Writer.Error!void {
    const has_single = std.mem.indexOfScalar(u8, value, '\'') != null;
    const has_double = std.mem.indexOfScalar(u8, value, '"') != null;
    const quote: u8 = if (has_single and !has_double) '"' else '\'';
    try writer.writeByte(quote);
    for (value) |char| {
        if (char == quote or char == '\\') {
            try writer.writeByte('\\');
            try writer.writeByte(char);
        } else if (char == '\n') {
            try writer.writeAll("\\n");
        } else if (char == '\r') {
            try writer.writeAll("\\r");
        } else if (char == '\t') {
            try writer.writeAll("\\t");
        } else if (char < 0x20 or char == 0x7f) {
            try writer.print("\\x{x:0>2}", .{char});
        } else {
            try writer.writeByte(char);
        }
    }
    try writer.writeByte(quote);
}

// -- bare tokens ---------------------------------------------------------

/// Unicode whitespace, as Python's `str.isspace` defines it.
fn isUnicodeSpace(code: u21) bool {
    return switch (code) {
        '\t'...'\r', 0x1c...0x20, 0x85, 0xa0, 0x1680, 0x2000...0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000 => true,
        else => false,
    };
}

/// Trim Unicode whitespace from both ends, as `str.strip()` and `String.trim()` do.
pub fn trimSpace(text: []const u8) []const u8 {
    var start: usize = 0;
    var view = std.unicode.Utf8View.initUnchecked(text).iterator();
    while (view.nextCodepointSlice()) |slice| {
        if (!isUnicodeSpace(std.unicode.utf8Decode(slice) catch break)) break;
        start += slice.len;
    }
    var end = text.len;
    while (end > start) {
        var first = end - 1;
        while (first > start and text[first] & 0xc0 == 0x80) first -= 1;
        if (!isUnicodeSpace(std.unicode.utf8Decode(text[first..end]) catch break)) break;
        end = first;
    }
    return text[start..end];
}

test "trimSpace" {
    try std.testing.expectEqualStrings("x y", trimSpace("\u{3000} \tx y\u{a0}\u{85}\n"));
    try std.testing.expectEqualStrings("é", trimSpace("é"));
    try std.testing.expectEqualStrings("", trimSpace(" \u{2028} "));
}

const keywords = [_]struct { []const u8, Value }{
    .{ "true", .{ .bool = true } },
    .{ "false", .{ .bool = false } },
    .{ "null", .null },
    .{ "none", .null },
    .{ "nil", .null },
    .{ "nan", .{ .float = std.math.nan(f64) } },
    .{ "inf", .{ .float = std.math.inf(f64) } },
    .{ "infinity", .{ .float = std.math.inf(f64) } },
    .{ "+inf", .{ .float = std.math.inf(f64) } },
    .{ "+infinity", .{ .float = std.math.inf(f64) } },
    .{ "-inf", .{ .float = -std.math.inf(f64) } },
    .{ "-infinity", .{ .float = -std.math.inf(f64) } },
    .{ "+nan", .{ .float = std.math.nan(f64) } },
    .{ "-nan", .{ .float = std.math.nan(f64) } },
};

/// Interpret an unquoted token: keyword, then number, then plain string.
/// An empty or whitespace-only token means "no value" and yields null.
pub fn interpretBare(text: []const u8) Value {
    const token = trimSpace(text);
    if (token.len == 0) return .null;
    for (keywords) |keyword| {
        if (std.ascii.eqlIgnoreCase(token, keyword[0])) return keyword[1];
    }
    return parseNumber(token) orelse .{ .string = token };
}

const Digits = enum { decimal, hex, octal, binary };

fn isDigit(kind: Digits, char: u8) bool {
    return switch (kind) {
        .decimal => std.ascii.isDigit(char),
        .hex => std.ascii.isHex(char),
        .octal => char >= '0' and char <= '7',
        .binary => char == '0' or char == '1',
    };
}

/// Match `D+(_D+)*` at `i` and return the end, or null.
fn groupedDigits(text: []const u8, i: usize) ?usize {
    var j = i;
    while (true) {
        const run_start = j;
        while (j < text.len and std.ascii.isDigit(text[j])) j += 1;
        if (j == run_start) return null;
        if (j + 1 < text.len and text[j] == '_' and std.ascii.isDigit(text[j + 1])) {
            j += 1;
            continue;
        }
        return j;
    }
}

/// Match `(_?D)+` for a radix body, which must use up the whole text.
fn radixDigits(kind: Digits, body: []const u8) bool {
    if (body.len == 0) return false;
    var i: usize = 0;
    while (i < body.len) {
        if (body[i] == '_') i += 1;
        if (i >= body.len or !isDigit(kind, body[i])) return false;
        i += 1;
    }
    return true;
}

fn isFloat(text: []const u8) bool {
    var i: usize = 0;
    if (text.len > 0 and (text[0] == '+' or text[0] == '-')) i += 1;
    if (i < text.len and text[i] == '.') {
        i = groupedDigits(text, i + 1) orelse return false;
    } else {
        i = groupedDigits(text, i) orelse return false;
        if (i < text.len and text[i] == '.') {
            i += 1;
            if (groupedDigits(text, i)) |end| i = end;
        }
    }
    if (i < text.len and (text[i] == 'e' or text[i] == 'E')) {
        i += 1;
        if (i < text.len and (text[i] == '+' or text[i] == '-')) i += 1;
        i = groupedDigits(text, i) orelse return false;
    }
    return i == text.len;
}

fn withoutUnderscores(buffer: []u8, text: []const u8) []const u8 {
    var length: usize = 0;
    for (text) |char| {
        if (char == '_') continue;
        buffer[length] = char;
        length += 1;
    }
    return buffer[0..length];
}

/// Numbers with `_` separators, a sign and `0x`/`0o`/`0b` prefixes, as in TOML and Python.
fn parseNumber(text: []const u8) ?Value {
    var scratch: [128]u8 = undefined;
    // shortcut: tokens over 128 bytes are never numbers; a literal that long is not a real config value
    if (text.len > scratch.len) return null;

    const signed = text[0] == '+' or text[0] == '-';
    const body = if (signed) text[1..] else text;

    if (body.len > 2 and body[0] == '0') {
        const kind: ?Digits = switch (body[1]) {
            'x', 'X' => .hex,
            'o', 'O' => .octal,
            'b', 'B' => .binary,
            else => null,
        };
        if (kind) |radix_kind| {
            if (!radixDigits(radix_kind, body[2..])) return null;
            const base: u8 = switch (radix_kind) {
                .hex => 16,
                .octal => 8,
                .binary => 2,
                .decimal => unreachable,
            };
            const digits = withoutUnderscores(&scratch, body[2..]);
            const magnitude = std.fmt.parseInt(i128, digits, base) catch return null;
            return .{ .integer = if (text[0] == '-') -magnitude else magnitude };
        }
    }

    const end = groupedDigits(text, @intFromBool(signed));
    if (end == text.len) {
        const clean = withoutUnderscores(&scratch, text);
        // Beyond i128, fall back to a float, as the Go port does for int64
        if (std.fmt.parseInt(i128, clean, 10)) |integer| {
            return .{ .integer = integer };
        } else |_| {}
        return .{ .float = std.fmt.parseFloat(f64, clean) catch return null };
    }

    if (isFloat(text)) {
        return .{ .float = std.fmt.parseFloat(f64, withoutUnderscores(&scratch, text)) catch return null };
    }
    return null;
}

test "bare tokens" {
    const expect = std.testing.expect;
    const expectEqual = std.testing.expectEqual;
    const expectEqualStrings = std.testing.expectEqualStrings;

    try expectEqual(Value.null, interpretBare("   "));
    try expectEqual(true, interpretBare("True").bool);
    try expectEqual(Value.null, interpretBare("None"));
    try expectEqual(@as(i128, 1000), interpretBare("1_000").integer);
    try expectEqual(@as(i128, -255), interpretBare("-0xff").integer);
    try expectEqual(@as(i128, 493), interpretBare("0o755").integer);
    try expectEqual(@as(i128, 5), interpretBare("0b_101").integer);
    try expectEqual(@as(f64, 0.5), interpretBare(".5").float);
    try expectEqual(@as(f64, 5), interpretBare("5.").float);
    try expectEqual(@as(f64, 1e-7), interpretBare("1e-7").float);
    try expect(std.math.isNan(interpretBare("-NaN").float));
    try expect(std.math.isNegativeInf(interpretBare("-Infinity").float));
    try expectEqualStrings("12abc", interpretBare("12abc").string);
    try expectEqualStrings("1__0", interpretBare("1__0").string);
    try expectEqualStrings("0x", interpretBare("0x").string);
    try expectEqualStrings("0xf_", interpretBare("0xf_").string);
    try expectEqualStrings("#8496AD", interpretBare("#8496AD").string);
    try expectEqual(@as(i128, 111111111111111111), interpretBare("111111111111111111").integer);
}
