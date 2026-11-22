/* cli/Main.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl.cli;

import gay.doughmination.jpcl.JPDecodeException;
import gay.doughmination.jpcl.JPException;
import gay.doughmination.jpcl.JPObject;
import gay.doughmination.jpcl.Jpcl;
import gay.doughmination.jpcl.ParseOptions;
import gay.doughmination.jpcl.StringifyOptions;
import gay.doughmination.jpcl.Value;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The {@code jpcl} command: check, fmt, get, to-json and from-json. */
public final class Main {
    static final String MAIN_USAGE = """
        usage: jpcl [-h] [--version] {check,fmt,get,to-json,from-json} ...

        Work with .jp configuration files.

        commands:
          check       validate .jp files
          fmt         reformat .jp files
          get         print one value by dotted path
          to-json     convert .jp to JSON
          from-json   convert JSON to .jp

        options:
          -h, --help      show this help message and exit
          --version       show the version and exit
        """;

    private static final String FORMAT_HELP = """
          --indent N      spaces per level (default 2)
          --width N       column budget for inline arrays (default 88)
          --sort-keys     sort keys alphabetically""";

    /** Reads all of stdin. */
    @FunctionalInterface
    interface Input {
        byte[] readAll() throws IOException;
    }

    private record Opt(
        String longName,
        char shortName,
        boolean takesValue
    ) {
        // No short form
        static final char NONE = 0;

        static Opt flag(String longName, char shortName) {
            return new Opt(longName, shortName, false);
        }

        static Opt valued(String longName, char shortName) {
            return new Opt(longName, shortName, true);
        }
    }

    private static final Opt HELP = Opt.flag("help", 'h');

    @FunctionalInterface
    private interface Run {
        int run(Main cli, Flags flags, List<String> positionals) throws IOException, UsageError;
    }

    private record Command(
        String name,
        String usage,
        List<Opt> options,
        // Positional names; a trailing `...` marks one or more
        List<String> positionals,
        Run run
    ) {
    }

    private static List<Opt> withFormatOptions(Opt first) {
        return List.of(
            first,
            Opt.valued("indent", Opt.NONE),
            Opt.valued("width", Opt.NONE),
            Opt.flag("sort-keys", Opt.NONE)
        );
    }

    private static final List<Command> COMMANDS = List.of(
        new Command(
            "check",
            "usage: jpcl check [-q] files...\n\n  -q, --quiet     only report errors",
            List.of(Opt.flag("quiet", 'q')),
            List.of("files..."),
            Main::runCheck
        ),
        new Command(
            "fmt",
            "usage: jpcl fmt [-w] [--indent N] [--width N] [--sort-keys] files...\n\n"
                + "  -w, --write     rewrite files in place\n"
                + FORMAT_HELP,
            withFormatOptions(Opt.flag("write", 'w')),
            List.of("files..."),
            Main::runFmt
        ),
        new Command(
            "get",
            "usage: jpcl get [-r] file path\n\n"
                + "  path            e.g. SERVER_ID.config.disabled_users\n"
                + "  -r, --raw       print strings unquoted",
            List.of(Opt.flag("raw", 'r')),
            List.of("file", "path"),
            Main::runGet
        ),
        new Command(
            "to-json",
            "usage: jpcl to-json [-o OUTPUT] [--indent N] file\n\n"
                + "  -o, --output    write to a file instead of stdout\n"
                + "  --indent N      spaces per level (default 2)",
            List.of(
                Opt.valued("output", 'o'),
                Opt.valued("indent", Opt.NONE)
            ),
            List.of("file"),
            Main::runToJson
        ),
        new Command(
            "from-json",
            "usage: jpcl from-json [-o OUTPUT] [--indent N] [--width N] [--sort-keys] file\n\n"
                + "  -o, --output    write to a file instead of stdout\n"
                + FORMAT_HELP,
            withFormatOptions(Opt.valued("output", 'o')),
            List.of("file"),
            Main::runFromJson
        )
    );

    /** Invalid usage: exit code 2. */
    private static final class UsageError extends Exception {
        private static final long serialVersionUID = 1L;

        UsageError(String message) {
            super(message);
        }
    }

    /** Parsed {@code --flag} values for one command, by long name. */
    private static final class Flags {
        private final Map<String, Optional<String>> map = new HashMap<>();

        boolean has(String name) {
            return map.containsKey(name);
        }

        Optional<String> value(String name) {
            return map.getOrDefault(name, Optional.empty());
        }

        int integer(String name, int fallback) throws UsageError {
            Optional<String> raw = value(name);
            if (raw.isEmpty()) {
                return fallback;
            }
            String text = raw.get();
            boolean digitsOnly = !text.isEmpty() && text.chars().allMatch(unit -> unit >= '0' && unit <= '9');
            if (digitsOnly) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException tooLarge) {
                    // Falls through to the usage error
                }
            }
            throw new UsageError("argument --" + name + ": invalid non-negative int value: '" + text + "'");
        }

        StringifyOptions format() throws UsageError {
            return new StringifyOptions(
                integer("indent", 2),
                integer("width", 88),
                has("sort-keys"),
                false
            );
        }
    }

    private final OutputStream stdout;
    private final OutputStream stderr;
    private final Input stdin;

    Main(OutputStream stdout, OutputStream stderr, Input stdin) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.stdin = stdin;
    }

    private void out(String text) throws IOException {
        stdout.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private void err(String text) throws IOException {
        stderr.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    /** Exit code: 0 on success, 1 when a file fails, 2 for invalid usage. */
    int run(List<String> argv) throws IOException {
        if (argv.isEmpty()) {
            err(firstLine(MAIN_USAGE) + "\njpcl: error: a command is required\n");
            return 2;
        }
        String name = argv.getFirst();
        if (name.equals("-h") || name.equals("--help")) {
            out(MAIN_USAGE);
            return 0;
        }
        if (name.equals("--version")) {
            out("jpcl " + Jpcl.VERSION + "\n");
            return 0;
        }

        Optional<Command> found = COMMANDS.stream()
            .filter(command -> command.name().equals(name))
            .findFirst();
        if (found.isEmpty()) {
            err(firstLine(MAIN_USAGE)
                + "\njpcl: error: invalid command '" + name
                + "' (choose from check, fmt, get, to-json, from-json)\n");
            return 2;
        }
        Command command = found.get();

        try {
            List<String> positionals = new ArrayList<>();
            Flags flags = parseArgs(command, argv.subList(1, argv.size()), positionals);
            if (flags.has("help")) {
                out(command.usage() + "\n");
                return 0;
            }
            checkPositionals(command, positionals);
            return command.run().run(this, flags, positionals);
        } catch (UsageError error) {
            err(firstLine(command.usage())
                + "\njpcl " + command.name() + ": error: " + error.getMessage() + "\n");
            return 2;
        }
    }

    private static String describe(IOException error, String path) {
        if (error instanceof NoSuchFileException) {
            return "no such file or directory";
        }
        if (error instanceof AccessDeniedException) {
            return "permission denied";
        }
        if (Files.isDirectory(Path.of(path))) {
            return "is a directory";
        }
        return error.getMessage();
    }

    /** Read a file, or stdin when {@code path} is {@code -}. Null means it failed and was reported. */
    private byte[] readBytes(String path) throws IOException {
        if (path.equals("-")) {
            return stdin.readAll();
        }
        try {
            if (Files.isDirectory(Path.of(path))) {
                err(path + ": cannot read file: is a directory\n");
                return null;
            }
            return Files.readAllBytes(Path.of(path));
        } catch (IOException error) {
            err(path + ": cannot read file: " + describe(error, path) + "\n");
            return null;
        }
    }

    /** Load and parse a {@code .jp} file. Null means it failed and was reported. */
    private JPObject read(String path) throws IOException {
        byte[] bytes = readBytes(path);
        if (bytes == null) {
            return null;
        }
        try {
            return Jpcl.parseBytes(bytes, ParseOptions.DEFAULT);
        } catch (JPDecodeException error) {
            String filename = path.equals("-") ? "<stdin>" : path;
            err(error.withFilename(filename).getMessage() + "\n");
            return null;
        }
    }

    private int output(Flags flags, String text) throws IOException {
        Optional<String> path = flags.value("output");
        if (path.isEmpty()) {
            out(text);
            return 0;
        }
        try {
            Files.writeString(Path.of(path.get()), text, StandardCharsets.UTF_8);
        } catch (IOException error) {
            err(path.get() + ": cannot write file: " + describe(error, path.get()) + "\n");
            return 1;
        }
        return 0;
    }

    private static Opt findOption(Command command, String longName, char shortName) {
        if ("help".equals(longName) || shortName == 'h') {
            return HELP;
        }
        for (Opt option : command.options()) {
            if (longName != null) {
                if (option.longName().equals(longName)) {
                    return option;
                }
            } else if (shortName != Opt.NONE && option.shortName() == shortName) {
                return option;
            }
        }
        return null;
    }

    private static Flags parseArgs(
        Command command,
        List<String> argv,
        List<String> positionals
    ) throws UsageError {
        Flags flags = new Flags();
        boolean optionsDone = false;

        for (int index = 0; index < argv.size(); index++) {
            String arg = argv.get(index);
            if (optionsDone || arg.length() < 2 || !arg.startsWith("-")) {
                positionals.add(arg);
                continue;
            }
            if (arg.equals("--")) {
                optionsDone = true;
                continue;
            }

            Opt option;
            String inlineValue = null;
            if (arg.startsWith("--")) {
                String body = arg.substring(2);
                int equals = body.indexOf('=');
                String name = equals < 0 ? body : body.substring(0, equals);
                if (equals >= 0) {
                    inlineValue = body.substring(equals + 1);
                }
                option = findOption(command, name, Opt.NONE);
            } else {
                String rest = arg.substring(1);
                boolean single = rest.codePointCount(0, rest.length()) == 1;
                option = single ? findOption(command, null, rest.charAt(0)) : null;
            }
            if (option == null) {
                throw new UsageError("unrecognized arguments: " + arg);
            }

            if (!option.takesValue()) {
                if (inlineValue != null) {
                    throw new UsageError(
                        "argument --" + option.longName() + ": ignored explicit argument '" + inlineValue + "'"
                    );
                }
                flags.map.put(option.longName(), Optional.empty());
                continue;
            }
            String value = inlineValue;
            if (value == null) {
                index++;
                if (index >= argv.size()) {
                    throw new UsageError("argument --" + option.longName() + ": expected one argument");
                }
                value = argv.get(index);
            }
            flags.map.put(option.longName(), Optional.of(value));
        }
        return flags;
    }

    private static void checkPositionals(Command command, List<String> given) throws UsageError {
        List<String> names = command.positionals();
        boolean variadic = !names.isEmpty() && names.getLast().endsWith("...");
        if (given.size() < names.size()) {
            List<String> missing = names.subList(given.size(), names.size())
                .stream()
                .map(name -> name.replaceAll("\\.+$", ""))
                .toList();
            throw new UsageError("the following arguments are required: " + String.join(", ", missing));
        }
        if (!variadic && given.size() > names.size()) {
            List<String> extra = given.subList(names.size(), given.size());
            throw new UsageError("unrecognized arguments: " + String.join(" ", extra));
        }
    }

    // -- commands ------------------------------------------------------------

    private static int runCheck(Main cli, Flags flags, List<String> files) throws IOException {
        int failures = 0;
        for (String path : files) {
            if (cli.read(path) == null) {
                failures++;
            } else if (!flags.has("quiet")) {
                cli.out("ok  " + path + "\n");
            }
        }
        return failures > 0 ? 1 : 0;
    }

    private static int runFmt(Main cli, Flags flags, List<String> files) throws IOException, UsageError {
        StringifyOptions format = flags.format();
        int failures = 0;
        for (String path : files) {
            JPObject root = cli.read(path);
            if (root == null) {
                failures++;
                continue;
            }
            String text = Jpcl.stringify(root, format);
            if (!flags.has("write") || path.equals("-")) {
                cli.out(text);
                continue;
            }
            byte[] current = cli.readBytes(path);
            if (current == null) {
                failures++;
                continue;
            }
            byte[] wanted = text.getBytes(StandardCharsets.UTF_8);
            if (Arrays.equals(current, wanted)) {
                continue;
            }
            try {
                Jpcl.writeAtomic(Path.of(path), wanted);
            } catch (IOException error) {
                cli.err(path + ": cannot write file: " + describe(error, path) + "\n");
                failures++;
                continue;
            }
            cli.err("reformatted " + path + "\n");
        }
        return failures > 0 ? 1 : 0;
    }

    private static int runGet(Main cli, Flags flags, List<String> positionals) throws IOException {
        JPObject root = cli.read(positionals.get(0));
        if (root == null) {
            return 1;
        }
        String path = positionals.get(1);
        Optional<Value> found = Jpcl.getPath(root, path);
        if (found.isEmpty()) {
            cli.err("no such path: " + path + "\n");
            return 1;
        }
        Value value = found.get();
        if (value instanceof Value.Text(String text) && flags.has("raw")) {
            cli.out(text + "\n");
        } else {
            cli.out(Jpcl.toJson(value, 2) + "\n");
        }
        return 0;
    }

    private static int runToJson(Main cli, Flags flags, List<String> positionals) throws IOException, UsageError {
        int indent = flags.integer("indent", 2);
        JPObject root = cli.read(positionals.getFirst());
        if (root == null) {
            return 1;
        }
        return cli.output(flags, Jpcl.toJson(root, indent) + "\n");
    }

    private static int runFromJson(Main cli, Flags flags, List<String> positionals) throws IOException, UsageError {
        StringifyOptions format = flags.format();
        byte[] bytes = cli.readBytes(positionals.getFirst());
        if (bytes == null) {
            return 1;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            cli.err("invalid JSON: not UTF-8\n");
            return 1;
        }

        Value parsed;
        try {
            parsed = Jpcl.fromJson(text);
        } catch (JPException error) {
            cli.err(error.getMessage() + "\n");
            return 1;
        }
        if (!(parsed instanceof JPObject root)) {
            cli.err("the top level of a .jp document must be an object, got '" + typeName(parsed) + "'\n");
            return 1;
        }
        return cli.output(flags, Jpcl.stringify(root, format));
    }

    private static String typeName(Value value) {
        return switch (value) {
            case Value.Null ignored -> "null";
            case Value.Bool ignored -> "boolean";
            case Value.Int ignored -> "number";
            case Value.Float ignored -> "number";
            case Value.Text ignored -> "string";
            case Value.Array ignored -> "Array";
            case JPObject ignored -> "Object";
        };
    }

    public static void main(String[] args) {
        OutputStream stdout = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out));
        OutputStream stderr = new FileOutputStream(FileDescriptor.err);
        Main cli = new Main(stdout, stderr, System.in::readAllBytes);
        int code;
        try {
            code = cli.run(List.of(args));
            stdout.flush();
        } catch (IOException error) {
            // A closed stdout, as in `jpcl to-json file | head`, is not an error worth reporting
            String message = String.valueOf(error.getMessage());
            if (message.contains("Broken pipe")) {
                code = 0;
            } else {
                System.err.println("jpcl: " + message);
                code = 1;
            }
        }
        System.exit(code);
    }
}
