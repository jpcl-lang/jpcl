// main.zig
//! The `jpcl` command: check, fmt, get, to-json and from-json.

const std = @import("std");
const Allocator = std.mem.Allocator;
const Io = std.Io;

const jpcl = @import("jpcl");

pub fn main(init: std.process.Init) !u8 {
    const io = init.io;
    const arena = init.arena.allocator();
    const args = try init.minimal.args.toSlice(arena);

    var stdout_buffer: [4096]u8 = undefined;
    var stdout_file = Io.File.stdout().writer(io, &stdout_buffer);
    var stderr_buffer: [1024]u8 = undefined;
    var stderr_file = Io.File.stderr().writer(io, &stderr_buffer);

    var cli: Cli = .{
        .gpa = arena,
        .io = io,
        .stdout = &stdout_file.interface,
        .stderr = &stderr_file.interface,
        .stdin = readStdin,
    };
    const code = try cli.run(args[1..]);
    try cli.stdout.flush();
    try cli.stderr.flush();
    return code;
}

fn readStdin(gpa: Allocator, io: Io) ![]u8 {
    var buffer: [4096]u8 = undefined;
    var reader = Io.File.stdin().readerStreaming(io, &buffer);
    return reader.interface.allocRemaining(gpa, .limited(jpcl.max_file_size));
}

const main_usage =
    \\usage: jpcl [-h] [--version] {check,fmt,get,to-json,from-json} ...
    \\
    \\Work with .jp configuration files.
    \\
    \\commands:
    \\  check       validate .jp files
    \\  fmt         reformat .jp files
    \\  get         print one value by dotted path
    \\  to-json     convert .jp to JSON
    \\  from-json   convert JSON to .jp
    \\
    \\options:
    \\  -h, --help      show this help message and exit
    \\  --version       show the version and exit
    \\
;

const format_help =
    \\  --indent N      spaces per level (default 2)
    \\  --width N       column budget for inline arrays (default 88)
    \\  --sort-keys     sort keys alphabetically
;

const Option = struct {
    long: []const u8,
    short: ?u8 = null,
    takes_value: bool = false,
};

const format_options = [_]Option{
    .{ .long = "indent", .takes_value = true },
    .{ .long = "width", .takes_value = true },
    .{ .long = "sort-keys" },
};

const Command = struct {
    name: []const u8,
    usage: []const u8,
    options: []const Option,
    /// Positional names; a trailing `...` marks one or more.
    positionals: []const []const u8,
    run: *const fn (cli: *Cli, flags: *const Flags, positionals: []const []const u8) anyerror!u8,
};

const commands = [_]Command{
    .{
        .name = "check",
        .usage =
        \\usage: jpcl check [-q] files...
        \\
        \\  -q, --quiet     only report errors
        ,
        .options = &.{.{ .long = "quiet", .short = 'q' }},
        .positionals = &.{"files..."},
        .run = runCheck,
    },
    .{
        .name = "fmt",
        .usage = "usage: jpcl fmt [-w] [--indent N] [--width N] [--sort-keys] files...\n\n" ++
            "  -w, --write     rewrite files in place\n" ++ format_help,
        .options = &([_]Option{.{ .long = "write", .short = 'w' }} ++ format_options),
        .positionals = &.{"files..."},
        .run = runFmt,
    },
    .{
        .name = "get",
        .usage =
        \\usage: jpcl get [-r] file path
        \\
        \\  path            e.g. SERVER_ID.config.disabled_users
        \\  -r, --raw       print strings unquoted
        ,
        .options = &.{.{ .long = "raw", .short = 'r' }},
        .positionals = &.{ "file", "path" },
        .run = runGet,
    },
    .{
        .name = "to-json",
        .usage =
        \\usage: jpcl to-json [-o OUTPUT] [--indent N] file
        \\
        \\  -o, --output    write to a file instead of stdout
        \\  --indent N      spaces per level (default 2)
        ,
        .options = &.{
            .{ .long = "output", .short = 'o', .takes_value = true },
            .{ .long = "indent", .takes_value = true },
        },
        .positionals = &.{"file"},
        .run = runToJson,
    },
    .{
        .name = "from-json",
        .usage = "usage: jpcl from-json [-o OUTPUT] [--indent N] [--width N] [--sort-keys] file\n\n" ++
            "  -o, --output    write to a file instead of stdout\n" ++ format_help,
        .options = &([_]Option{.{ .long = "output", .short = 'o', .takes_value = true }} ++ format_options),
        .positionals = &.{"file"},
        .run = runFromJson,
    },
};

/// Parsed `--flag` values for one command, by long name.
const Flags = struct {
    map: std.StringHashMapUnmanaged(?[]const u8) = .empty,

    fn has(self: *const Flags, name: []const u8) bool {
        return self.map.contains(name);
    }

    fn value(self: *const Flags, name: []const u8) ?[]const u8 {
        return self.map.get(name) orelse null;
    }

    fn integer(self: *const Flags, name: []const u8, fallback: usize) UsageError!usize {
        const raw = self.value(name) orelse return fallback;
        const parsed = std.fmt.parseUnsigned(usize, raw, 10) catch null;
        const all_digits = raw.len > 0 and for (raw) |char| {
            if (!std.ascii.isDigit(char)) break false;
        } else true;
        if (parsed == null or !all_digits) {
            return usageFail("argument --{s}: invalid non-negative int value: '{s}'", .{ name, raw });
        }
        return parsed.?;
    }

    fn format(self: *const Flags) UsageError!jpcl.StringifyOptions {
        return .{
            .indent = try self.integer("indent", 2),
            .width = try self.integer("width", 88),
            .sort_keys = self.has("sort-keys"),
        };
    }
};

const UsageError = error{Usage};

// The message for the UsageError being raised; the CLI is single-threaded
var usage_buffer: [512]u8 = undefined;
var usage_message: []const u8 = "";

fn usageFail(comptime format: []const u8, args: anytype) UsageError {
    usage_message = std.fmt.bufPrint(&usage_buffer, format, args) catch "invalid arguments";
    return error.Usage;
}

const Cli = struct {
    gpa: Allocator,
    io: Io,
    stdout: *Io.Writer,
    stderr: *Io.Writer,
    stdin: *const fn (gpa: Allocator, io: Io) anyerror![]u8,

    /// Exit code: 0 on success, 1 when a file fails, 2 for invalid usage.
    fn run(self: *Cli, argv: []const []const u8) !u8 {
        const first_line = comptime main_usage[0..std.mem.indexOfScalar(u8, main_usage, '\n').?];
        if (argv.len == 0) {
            try self.stderr.print("{s}\njpcl: error: a command is required\n", .{first_line});
            return 2;
        }
        const name = argv[0];
        if (std.mem.eql(u8, name, "-h") or std.mem.eql(u8, name, "--help")) {
            try self.stdout.writeAll(main_usage);
            return 0;
        }
        if (std.mem.eql(u8, name, "--version")) {
            try self.stdout.print("jpcl {s}\n", .{jpcl.version});
            return 0;
        }

        const command = for (&commands) |*command| {
            if (std.mem.eql(u8, command.name, name)) break command;
        } else {
            try self.stderr.print(
                "{s}\njpcl: error: invalid command '{s}' (choose from check, fmt, get, to-json, from-json)\n",
                .{ first_line, name },
            );
            return 2;
        };

        var flags: Flags = .{};
        var positionals: std.ArrayList([]const u8) = .empty;
        parseArgs(self.gpa, command, argv[1..], &flags, &positionals) catch |err| switch (err) {
            error.Usage => return self.usageError(command),
            else => |other| return other,
        };
        if (flags.has("help")) {
            try self.stdout.print("{s}\n", .{command.usage});
            return 0;
        }
        checkPositionals(command, positionals.items) catch return self.usageError(command);
        return command.run(self, &flags, positionals.items) catch |err| switch (err) {
            error.Usage => self.usageError(command),
            else => |other| other,
        };
    }

    fn usageError(self: *Cli, command: *const Command) !u8 {
        const first_line = command.usage[0 .. std.mem.indexOfScalar(u8, command.usage, '\n') orelse command.usage.len];
        try self.stderr.print("{s}\njpcl {s}: error: {s}\n", .{ first_line, command.name, usage_message });
        return 2;
    }

    /// Read a file, or stdin when `path` is `-`. Null means it failed and was reported.
    fn readText(self: *Cli, path: []const u8) !?[]const u8 {
        if (std.mem.eql(u8, path, "-")) return try self.stdin(self.gpa, self.io);
        return Io.Dir.cwd().readFileAlloc(self.io, path, self.gpa, .limited(jpcl.max_file_size)) catch |err| {
            try self.stderr.print("{s}: cannot read file: {s}\n", .{ path, describeFileError(err) });
            return null;
        };
    }

    /// Load and parse a `.jp` file. Null means it failed and was reported.
    fn read(self: *Cli, path: []const u8) !?jpcl.Object {
        const text = try self.readText(path) orelse return null;
        var diagnostic: jpcl.Diagnostic = .{};
        return jpcl.parseLeaky(self.gpa, text, .{ .diagnostic = &diagnostic }) catch |err| switch (err) {
            error.SyntaxError => {
                const filename = if (std.mem.eql(u8, path, "-")) "<stdin>" else path;
                try diagnostic.render(self.stderr, text, filename);
                try self.stderr.writeByte('\n');
                return null;
            },
            error.OutOfMemory => return err,
        };
    }

    fn output(self: *Cli, flags: *const Flags, text: []const u8) !u8 {
        const path = flags.value("output") orelse {
            try self.stdout.writeAll(text);
            return 0;
        };
        Io.Dir.cwd().writeFile(self.io, .{ .sub_path = path, .data = text }) catch |err| {
            try self.stderr.print("{s}: cannot write file: {s}\n", .{ path, describeFileError(err) });
            return 1;
        };
        return 0;
    }
};

fn describeFileError(err: anyerror) []const u8 {
    return switch (err) {
        error.FileNotFound => "no such file or directory",
        error.IsDir => "is a directory",
        error.AccessDenied, error.PermissionDenied => "permission denied",
        error.StreamTooLong => "file is too large",
        else => @errorName(err),
    };
}

fn findOption(command: *const Command, long: ?[]const u8, short: ?u8) ?Option {
    if (long) |name| if (std.mem.eql(u8, name, "help")) return .{ .long = "help", .short = 'h' };
    if (short == 'h') return .{ .long = "help", .short = 'h' };
    for (command.options) |option| {
        if (long) |name| if (std.mem.eql(u8, option.long, name)) return option;
        if (short != null and option.short == short) return option;
    }
    return null;
}

fn parseArgs(
    gpa: Allocator,
    command: *const Command,
    argv: []const []const u8,
    flags: *Flags,
    positionals: *std.ArrayList([]const u8),
) !void {
    var i: usize = 0;
    var options_done = false;
    while (i < argv.len) : (i += 1) {
        const arg = argv[i];
        if (options_done or arg.len < 2 or arg[0] != '-') {
            try positionals.append(gpa, arg);
            continue;
        }
        if (std.mem.eql(u8, arg, "--")) {
            options_done = true;
            continue;
        }

        const is_long = std.mem.startsWith(u8, arg, "--");
        const body = if (is_long) arg[2..] else arg[1..];
        const equals = if (is_long) std.mem.indexOfScalar(u8, body, '=') else null;
        const option = if (is_long)
            findOption(command, body[0 .. equals orelse body.len], null)
        else if (body.len == 1)
            findOption(command, null, body[0])
        else
            null;
        const found = option orelse return usageFail("unrecognized arguments: {s}", .{arg});

        if (!found.takes_value) {
            if (equals != null) return usageFail("argument --{s}: ignored explicit argument '{s}'", .{ found.long, body[equals.? + 1 ..] });
            try flags.map.put(gpa, found.long, null);
            continue;
        }
        const value = if (equals) |at| body[at + 1 ..] else blk: {
            i += 1;
            if (i >= argv.len) return usageFail("argument --{s}: expected one argument", .{found.long});
            break :blk argv[i];
        };
        try flags.map.put(gpa, found.long, value);
    }
}

fn checkPositionals(command: *const Command, given: []const []const u8) UsageError!void {
    const names = command.positionals;
    const variadic = std.mem.endsWith(u8, names[names.len - 1], "...");
    if (given.len < names.len) {
        var missing: [256]u8 = undefined;
        var writer: Io.Writer = .fixed(&missing);
        for (names[given.len..], 0..) |name, i| {
            if (i > 0) writer.writeAll(", ") catch {};
            writer.writeAll(std.mem.trimEnd(u8, name, ".")) catch {};
        }
        return usageFail("the following arguments are required: {s}", .{writer.buffered()});
    }
    if (!variadic and given.len > names.len) {
        var extra: [256]u8 = undefined;
        var writer: Io.Writer = .fixed(&extra);
        for (given[names.len..], 0..) |arg, i| {
            if (i > 0) writer.writeByte(' ') catch {};
            writer.writeAll(arg) catch {};
        }
        return usageFail("unrecognized arguments: {s}", .{writer.buffered()});
    }
}

// -- commands --------------------------------------------------------------

fn runCheck(cli: *Cli, flags: *const Flags, files: []const []const u8) !u8 {
    var failures: usize = 0;
    for (files) |path| {
        if (try cli.read(path) == null) {
            failures += 1;
        } else if (!flags.has("quiet")) {
            try cli.stdout.print("ok  {s}\n", .{path});
        }
    }
    return if (failures > 0) 1 else 0;
}

fn runFmt(cli: *Cli, flags: *const Flags, files: []const []const u8) !u8 {
    const format = try flags.format();
    var failures: usize = 0;
    for (files) |path| {
        const data = try cli.read(path) orelse {
            failures += 1;
            continue;
        };
        const text = try jpcl.stringify(cli.gpa, data, format);
        if (!flags.has("write") or std.mem.eql(u8, path, "-")) {
            try cli.stdout.writeAll(text);
            continue;
        }
        const current = try cli.readText(path) orelse {
            failures += 1;
            continue;
        };
        if (std.mem.eql(u8, current, text)) continue;
        jpcl.writeAtomic(cli.io, path, text) catch |err| {
            try cli.stderr.print("{s}: cannot write file: {s}\n", .{ path, describeFileError(err) });
            failures += 1;
            continue;
        };
        try cli.stderr.print("reformatted {s}\n", .{path});
    }
    return if (failures > 0) 1 else 0;
}

fn runGet(cli: *Cli, flags: *const Flags, positionals: []const []const u8) !u8 {
    const data = try cli.read(positionals[0]) orelse return 1;
    const path = positionals[1];
    const value = jpcl.getPath(data, path) orelse {
        try cli.stderr.print("no such path: {s}\n", .{path});
        return 1;
    };
    if (value == .string and flags.has("raw")) {
        try cli.stdout.print("{s}\n", .{value.string});
    } else {
        try cli.stdout.print("{s}\n", .{try jpcl.toJson(cli.gpa, value, 2)});
    }
    return 0;
}

fn runToJson(cli: *Cli, flags: *const Flags, positionals: []const []const u8) !u8 {
    const indent = try flags.integer("indent", 2);
    const data = try cli.read(positionals[0]) orelse return 1;
    const json = try jpcl.toJson(cli.gpa, .{ .object = data }, indent);
    return cli.output(flags, try std.mem.concat(cli.gpa, u8, &.{ json, "\n" }));
}

fn runFromJson(cli: *Cli, flags: *const Flags, positionals: []const []const u8) !u8 {
    const format = try flags.format();
    const raw = try cli.readText(positionals[0]) orelse return 1;
    const parsed = std.json.parseFromSliceLeaky(std.json.Value, cli.gpa, raw, .{}) catch |err| {
        try cli.stderr.print("invalid JSON: {s}\n", .{@errorName(err)});
        return 1;
    };
    if (parsed != .object) {
        try cli.stderr.print("the top level of a .jp document must be an object, got '{s}'\n", .{jsonTypeName(parsed)});
        return 1;
    }
    const value = try fromJson(cli.gpa, parsed);
    return cli.output(flags, try jpcl.stringify(cli.gpa, value.object, format));
}

fn jsonTypeName(value: std.json.Value) []const u8 {
    return switch (value) {
        .null => "null",
        .bool => "boolean",
        .integer, .float, .number_string => "number",
        .string => "string",
        .array => "Array",
        .object => "Object",
    };
}

/// Convert parsed JSON to a `.jp` value, keeping big integers exact.
fn fromJson(gpa: Allocator, value: std.json.Value) Allocator.Error!jpcl.Value {
    return switch (value) {
        .null => .null,
        .bool => |flag| .{ .bool = flag },
        .integer => |integer| .{ .integer = integer },
        .float => |float| .{ .float = float },
        .number_string => |text| if (std.fmt.parseInt(i128, text, 10)) |integer|
            .{ .integer = integer }
        else |_|
            .{ .float = std.fmt.parseFloat(f64, text) catch std.math.nan(f64) },
        .string => |text| .{ .string = text },
        .array => |array| blk: {
            var items: jpcl.Array = try .initCapacity(gpa, array.items.len);
            for (array.items) |item| items.appendAssumeCapacity(try fromJson(gpa, item));
            break :blk .{ .array = items };
        },
        .object => |object| blk: {
            var result: jpcl.Object = .empty;
            try result.ensureTotalCapacity(gpa, object.count());
            var entries = object.iterator();
            while (entries.next()) |entry| {
                result.putAssumeCapacity(entry.key_ptr.*, try fromJson(gpa, entry.value_ptr.*));
            }
            break :blk .{ .object = result };
        },
    };
}

// -- tests -----------------------------------------------------------------

const testing = std.testing;

const TestRun = struct {
    code: u8,
    stdout: Io.Writer.Allocating,
    stderr: Io.Writer.Allocating,
    arena: std.heap.ArenaAllocator,

    fn deinit(self: *TestRun) void {
        self.stdout.deinit();
        self.stderr.deinit();
        self.arena.deinit();
    }
};

var test_stdin: []const u8 = "";

fn testStdin(gpa: Allocator, _: Io) ![]u8 {
    return gpa.dupe(u8, test_stdin);
}

fn runForTest(argv: []const []const u8, stdin: []const u8) !TestRun {
    var result: TestRun = .{
        .code = 0,
        .stdout = .init(testing.allocator),
        .stderr = .init(testing.allocator),
        .arena = .init(testing.allocator),
    };
    test_stdin = stdin;
    var cli: Cli = .{
        .gpa = result.arena.allocator(),
        .io = testing.io,
        .stdout = &result.stdout.writer,
        .stderr = &result.stderr.writer,
        .stdin = testStdin,
    };
    result.code = try cli.run(argv);
    return result;
}

test "check reports the first error with a caret" {
    var result = try runForTest(&.{ "check", "-" }, "[s]\nprefix \"!\"\n");
    defer result.deinit();
    try testing.expectEqual(@as(u8, 1), result.code);
    try testing.expectEqualStrings(
        "<stdin>:2:8: expected ':' after key 'prefix', found '\"'\n    prefix \"!\"\n           ^\n",
        result.stderr.written(),
    );
}

test "fmt prints canonical text" {
    var result = try runForTest(&.{ "fmt", "--indent=4", "-" }, "[s]\nk: {a: 1}\n");
    defer result.deinit();
    try testing.expectEqual(@as(u8, 0), result.code);
    try testing.expectEqualStrings("[s]\nk: {\n    a: 1\n}\n", result.stdout.written());
}

test "get and to-json" {
    var got = try runForTest(&.{ "get", "-r", "-", "s.name" }, "[s]\nname: hello\n");
    defer got.deinit();
    try testing.expectEqualStrings("hello\n", got.stdout.written());

    var missing = try runForTest(&.{ "get", "-", "s.nope" }, "[s]\nname: hello\n");
    defer missing.deinit();
    try testing.expectEqual(@as(u8, 1), missing.code);
    try testing.expectEqualStrings("no such path: s.nope\n", missing.stderr.written());

    var json = try runForTest(&.{ "to-json", "-" }, "a: [1]\n");
    defer json.deinit();
    try testing.expectEqualStrings("{\n  \"a\": [\n    1\n  ]\n}\n", json.stdout.written());
}

test "from-json keeps big integers and key order" {
    var result = try runForTest(&.{ "from-json", "-" }, "{\"z\": 1, \"s\": {\"id\": 123456789012345678901234}}");
    defer result.deinit();
    try testing.expectEqualStrings("z: 1\n\n[s]\nid: 123456789012345678901234\n", result.stdout.written());
}

test "usage errors exit with 2" {
    var none = try runForTest(&.{}, "");
    defer none.deinit();
    try testing.expectEqual(@as(u8, 2), none.code);

    var bad = try runForTest(&.{ "fmt", "--indent", "x", "-" }, "");
    defer bad.deinit();
    try testing.expectEqual(@as(u8, 2), bad.code);
    try testing.expectEqualStrings(
        "usage: jpcl fmt [-w] [--indent N] [--width N] [--sort-keys] files...\njpcl fmt: error: argument --indent: invalid non-negative int value: 'x'\n",
        bad.stderr.written(),
    );

    var missing = try runForTest(&.{"get"}, "");
    defer missing.deinit();
    try testing.expectEqualStrings(
        "usage: jpcl get [-r] file path\njpcl get: error: the following arguments are required: file, path\n",
        missing.stderr.written(),
    );
}
