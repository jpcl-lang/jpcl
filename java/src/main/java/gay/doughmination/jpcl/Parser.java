/* Parser.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Recursive-descent parser for {@code .jp}; {@code [} opens a section only at the top level. */
final class Parser {
    private final Scanner scanner;
    private final DuplicateKeys duplicateKeys;
    private int depth;

    // Section paths opened by an explicit header, to catch `[a]` twice
    private final Set<List<String>> defined = new HashSet<>();

    private record Entry(
        String key,
        Value value,
        int keyPos
    ) {
    }

    private Parser(String text, ParseOptions options) {
        this.scanner = new Scanner(text);
        this.duplicateKeys = options.duplicateKeys();
    }

    static JPObject parse(String text, ParseOptions options) throws JPDecodeException {
        return new Parser(text, options).document();
    }

    private JPObject document() throws JPDecodeException {
        JPObject root = new JPObject();
        // The open section, as an object inside the root
        JPObject current = root;

        while (true) {
            scanner.skipIgnorable();
            if (scanner.eof()) {
                return root;
            }
            if (scanner.peek() == '[') {
                current = header(root);
                continue;
            }
            Entry entry = entry();
            store(current, entry);
            separator(Scanner.EOF);
        }
    }

    // -- sections ------------------------------------------------------------

    /** Parse {@code [a.b.c]}, open its section, and return it. */
    private JPObject header(JPObject root) throws JPDecodeException {
        int start = scanner.pos;
        scanner.pos++;

        List<String> path = new ArrayList<>();
        boolean pendingDot = false;
        while (true) {
            scanner.skipInline();
            if (scanner.atLineEnd()) {
                throw scanner.errorAt(start, "unterminated section header");
            }
            int unit = scanner.peek();
            if (unit == ']') {
                if (path.isEmpty()) {
                    throw scanner.errorAt(start, "section header cannot be empty");
                }
                if (pendingDot) {
                    throw scanner.errorHere("section header segment cannot be empty");
                }
                scanner.pos++;
                break;
            }
            if (unit == '"' || unit == '\'') {
                path.add(scanner.scanString());
            } else {
                String segment = Scanner.trimSpace(scanner.scanUntil(Scanner.Terminators.HEADER));
                if (segment.isEmpty()) {
                    throw scanner.errorHere("section header segment cannot be empty");
                }
                path.add(segment);
            }

            scanner.skipInline();
            int next = scanner.peek();
            if (next == '.') {
                scanner.pos++;
                pendingDot = true;
                continue;
            }
            if (next == ']') {
                scanner.pos++;
                break;
            }
            if (scanner.atLineEnd()) {
                throw scanner.errorAt(start, "unterminated section header");
            }
            String found = scanner.describeHere();
            throw scanner.errorHere("expected '.' or ']' in section header, found " + found);
        }

        scanner.skipInline();
        if (!scanner.atLineEnd()) {
            String found = scanner.describeHere();
            throw scanner.errorHere("unexpected " + found + " after section header");
        }
        return openSection(root, path, start);
    }

    /** Create or reuse the nested object addressed by {@code path}. */
    private JPObject openSection(
        JPObject root,
        List<String> path,
        int start
    ) throws JPDecodeException {
        boolean fresh = defined.add(List.copyOf(path));
        String joined = String.join(".", path);
        if (!fresh && duplicateKeys == DuplicateKeys.ERROR) {
            throw scanner.errorAt(start, "section '[" + joined + "]' is defined twice");
        }

        JPObject node = root;
        for (int index = 0; index < path.size(); index++) {
            String part = path.get(index);
            if (!node.containsKey(part)) {
                node.put(part, new JPObject());
            }
            if (!(node.get(part).orElseThrow() instanceof JPObject child)) {
                String blocked = String.join(".", path.subList(0, index + 1));
                throw scanner.errorAt(
                    start,
                    "cannot open section '[" + joined + "]': '" + blocked + "' is already a non-section value"
                );
            }
            node = child;
        }
        return node;
    }

    // -- entries -------------------------------------------------------------

    private Entry entry() throws JPDecodeException {
        scanner.skipInline();
        int keyPos = scanner.pos;
        String key = key();

        scanner.skipInline();
        if (scanner.peek() != ':') {
            String found = scanner.describeHere();
            throw scanner.errorHere("expected ':' after key " + Scanner.repr(key) + ", found " + found);
        }
        scanner.pos++;
        return new Entry(key, optionalValue(), keyPos);
    }

    private String key() throws JPDecodeException {
        int unit = scanner.peek();
        if (unit == Scanner.EOF) {
            throw scanner.errorHere("expected a key, found end of file");
        }
        if (unit == '"' || unit == '\'') {
            return scanner.scanString();
        }
        if (unit == '}' || unit == ']') {
            throw scanner.errorHere("unexpected " + scanner.describeHere());
        }
        int start = scanner.pos;
        String key = Scanner.trimSpace(scanner.scanUntil(Scanner.Terminators.KEY));
        if (key.isEmpty()) {
            throw scanner.errorAt(start, "expected a key, found " + scanner.describeHere());
        }
        return key;
    }

    /** The value after a {@code :}, or null when it is empty. */
    private Value optionalValue() throws JPDecodeException {
        scanner.skipInline();
        if (scanner.peek() == ',' || scanner.atLineEnd()) {
            return Value.Null.INSTANCE;
        }
        return value();
    }

    private Value value() throws JPDecodeException {
        return switch (scanner.peek()) {
            case '{' -> object();
            case '[' -> array();
            case '"', '\'' -> new Value.Text(scanner.scanString());
            default -> Scanner.interpretBare(scanner.scanUntil(Scanner.Terminators.VALUE));
        };
    }

    // -- containers ----------------------------------------------------------

    private void enter(int start) throws JPDecodeException {
        depth++;
        if (depth > Jpcl.MAX_DEPTH) {
            throw scanner.errorAt(start, "nesting deeper than " + Jpcl.MAX_DEPTH + " levels");
        }
    }

    private JPObject object() throws JPDecodeException {
        int start = scanner.pos;
        enter(start);
        scanner.pos++;
        JPObject result = new JPObject();

        while (true) {
            scanner.skipIgnorable();
            int unit = scanner.peek();
            if (unit == Scanner.EOF) {
                throw scanner.errorAt(start, "unterminated object: missing '}'");
            }
            if (unit == '}') {
                scanner.pos++;
                depth--;
                return result;
            }
            // Tolerate stray or repeated separators
            if (unit == ',') {
                scanner.pos++;
                continue;
            }
            Entry entry = entry();
            store(result, entry);
            separator('}');
        }
    }

    private Value.Array array() throws JPDecodeException {
        int start = scanner.pos;
        enter(start);
        scanner.pos++;
        List<Value> items = new ArrayList<>();

        while (true) {
            scanner.skipIgnorable();
            int unit = scanner.peek();
            if (unit == Scanner.EOF) {
                throw scanner.errorAt(start, "unterminated array: missing ']'");
            }
            if (unit == ']') {
                scanner.pos++;
                depth--;
                return new Value.Array(items);
            }
            if (unit == ',') {
                scanner.pos++;
                continue;
            }
            if (unit == '}') {
                throw scanner.errorAt(start, "unterminated array: found '}' before ']'");
            }
            items.add(value());
            separator(']');
        }
    }

    // -- separators and storage ----------------------------------------------

    /** Require a {@code ,}, a line break, or the closing bracket (EOF for none) after a value. */
    private void separator(int closing) throws JPDecodeException {
        scanner.skipInline();
        if (scanner.atLineEnd()) {
            return;
        }
        int unit = scanner.peek();
        if (unit == ',') {
            scanner.pos++;
            return;
        }
        String found = scanner.describeHere();
        if (closing != Scanner.EOF) {
            if (unit == closing) {
                return;
            }
            throw scanner.errorHere(
                "expected ',', a line break or '" + (char) closing + "' after value, found " + found
            );
        }
        // A section header always starts its own line, so the previous entry never ended
        if (unit == '[') {
            throw scanner.errorHere("expected a line break before a section header");
        }
        throw scanner.errorHere("expected ',' or a line break after value, found " + found);
    }

    /** Insert an entry, applying the duplicate-key policy. */
    private void store(JPObject target, Entry entry) throws JPDecodeException {
        if (target.containsKey(entry.key())) {
            switch (duplicateKeys) {
                case ERROR -> throw scanner.errorAt(entry.keyPos(), "duplicate key " + Scanner.repr(entry.key()));
                case FIRST -> {
                    return;
                }
                case LAST -> {
                }
            }
        }
        target.put(entry.key(), entry.value());
    }
}
