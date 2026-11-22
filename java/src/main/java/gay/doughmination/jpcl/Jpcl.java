/* Jpcl.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** jpcl: a hybrid JSON/TOML configuration language ({@code .jp} files). */
public final class Jpcl {
    /** The version of the {@code .jp} format and of this library. */
    public static final String VERSION = "2.0.0";

    /** Conventional file extension. */
    public static final String SUFFIX = ".jp";

    /** How deep <code>&#123;</code> / {@code [} nesting may go, so hostile input fails cleanly instead of overflowing the stack. */
    public static final int MAX_DEPTH = 200;

    private Jpcl() {
    }

    // -- reading -------------------------------------------------------------

    /** Parse {@code text} as a {@code .jp} document. */
    public static JPObject parse(String text) throws JPDecodeException {
        return parse(text, ParseOptions.DEFAULT);
    }

    /** Parse {@code text} as a {@code .jp} document. */
    public static JPObject parse(String text, ParseOptions options) throws JPDecodeException {
        return Parser.parse(text, options);
    }

    /** Parse raw bytes, reporting invalid UTF-8 as a {@link JPDecodeException} at the first bad byte. */
    public static JPObject parseBytes(byte[] bytes, ParseOptions options) throws JPDecodeException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(bytes.length + 1);

        CoderResult result = decoder.decode(input, output, true);
        if (!result.isError()) {
            result = decoder.flush(output);
        }
        if (!result.isError()) {
            output.flip();
            return parse(output.toString(), options);
        }

        // Point at the first bad byte inside the lossy text, past any byte order marks
        output.flip();
        String validPrefix = output.toString();
        String lossy = new String(bytes, StandardCharsets.UTF_8);
        String stripped = Scanner.stripBom(lossy);
        int bomUnits = lossy.length() - stripped.length();
        int position = Math.max(validPrefix.length() - bomUnits, 0);
        throw JPDecodeException.at("invalid UTF-8", stripped, position);
    }

    /** Read and parse a {@code .jp} file. Decode errors name the file. */
    public static JPObject load(Path path) throws IOException, JPDecodeException {
        return load(path, ParseOptions.DEFAULT);
    }

    /** Read and parse a {@code .jp} file. Decode errors name the file. */
    public static JPObject load(Path path, ParseOptions options) throws IOException, JPDecodeException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return parseBytes(bytes, options);
        } catch (JPDecodeException error) {
            throw error.withFilename(path.toString());
        }
    }

    // -- writing -------------------------------------------------------------

    /** Serialise a document. The result ends with a newline; an empty object gives {@code ""}. */
    public static String stringify(JPObject root) {
        return stringify(root, StringifyOptions.DEFAULT);
    }

    /** Serialise a document. The result ends with a newline; an empty object gives {@code ""}. */
    public static String stringify(JPObject root, StringifyOptions options) {
        return Writer.stringify(root, options);
    }

    /** Serialise {@code root} and write it to {@code path} atomically, creating missing parent folders. */
    public static void dump(JPObject root, Path path) throws IOException {
        dump(root, path, StringifyOptions.DEFAULT);
    }

    /** Serialise {@code root} and write it to {@code path} atomically, creating missing parent folders. */
    public static void dump(JPObject root, Path path, StringifyOptions options) throws IOException {
        writeAtomic(path, stringify(root, options).getBytes(StandardCharsets.UTF_8));
    }

    /** Write to a temporary file beside {@code path}, then rename it over {@code path}, so readers never see half a file. */
    public static void writeAtomic(Path path, byte[] data) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path folder = absolute.getParent();
        Files.createDirectories(folder);
        String name = ".%s.%d.tmp".formatted(absolute.getFileName(), ProcessHandle.current().pid());
        Path temporary = folder.resolve(name);

        try {
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveOver(temporary, absolute);
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
    }

    private static void moveOver(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // -- paths and JSON ------------------------------------------------------

    /**
     * The value at a dotted path such as {@code SERVER_ID.config.disabled_users}, or empty if
     * any part is missing. An empty value ({@code key:}) is present and returns {@link Value.Null}.
     */
    public static Optional<Value> getPath(JPObject root, String path) {
        String[] parts = path.split("\\.", -1);
        Optional<Value> node = root.get(parts[0]);
        for (int index = 1; index < parts.length && node.isPresent(); index++) {
            String part = parts[index];
            node = node.flatMap(Value::asObject).flatMap(object -> object.get(part));
        }
        return node;
    }

    /** Render a value as indented JSON, as {@code jpcl to-json} does. */
    public static String toJson(Value value, int indent) {
        return Json.toJson(value, indent);
    }

    /** Parse JSON text, keeping key order and integers up to 128 bits. Duplicate keys keep the last value. */
    public static Value fromJson(String text) throws JPException {
        return Json.fromJson(text);
    }
}
