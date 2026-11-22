/* ParserTest.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ParserTest {
    static Value at(JPObject root, String... path) {
        Value node = root.get(path[0]).orElseThrow();
        for (int index = 1; index < path.length; index++) {
            node = node.asObject().orElseThrow().get(path[index]).orElseThrow();
        }
        return node;
    }

    static void expectError(String text, String message, int line, int col) {
        JPDecodeException error = assertThrows(JPDecodeException.class, () -> Jpcl.parse(text));
        assertEquals(message, error.rawMessage(), "for " + text);
        assertEquals(line, error.line(), "line for " + text);
        assertEquals(col, error.col(), "col for " + text);
    }

    @Test
    void sectionsNestedObjectsAndEmptyValues() throws JPDecodeException {
        JPObject root = Jpcl.parse(String.join(
            "",
            "~~ leading comment\n",
            "version: 2\n",
            "\n",
            "[SERVER_ID]\n",
            "config: {\n",
            "  disabled_channels:,\n",
            "  disabled_users: [9892, 82082, 8209]  ~~ trailing\n",
            "}\n",
            "\n",
            "[guild.limits]\n",
            "rate: 30 ~* inline *~\n",
            "~* a block\n",
            "   comment *~\n",
            "colour: #8496AD\n",
            "price: $5 $$ not a comment\n"
        ));
        assertEquals(Value.of(2), root.get("version").orElseThrow());
        assertEquals(Value.Null.INSTANCE, at(root, "SERVER_ID", "config", "disabled_channels"));
        assertEquals(
            Value.Array.of(Value.of(9892), Value.of(82082), Value.of(8209)),
            at(root, "SERVER_ID", "config", "disabled_users")
        );
        assertEquals(Value.of(30), at(root, "guild", "limits", "rate"));
        assertEquals(Value.of("#8496AD"), at(root, "guild", "limits", "colour"));
        assertEquals(Value.of("$5 $$ not a comment"), at(root, "guild", "limits", "price"));
    }

    @Test
    void stringsAndEscapes() throws JPDecodeException {
        JPObject root = Jpcl.parse(String.join(
            "",
            "a: \"tab\\there \\u00e9 \\U0001F600 \\ud83d\\ude00\"\n",
            "c: \"one \\\n    two\"\n",
            "url: http://example.com:8080\n"
        ));
        assertEquals(Value.of("tab\there é 😀 😀"), root.get("a").orElseThrow());
        assertEquals(Value.of("one two"), root.get("c").orElseThrow());
        assertEquals(Value.of("http://example.com:8080"), root.get("url").orElseThrow());
    }

    @Test
    void duplicateKeys() throws JPDecodeException {
        expectError("a: 1\na: 2\n", "duplicate key 'a'", 2, 1);
        expectError("[s]\n[s]\n", "section '[s]' is defined twice", 2, 1);

        JPObject first = Jpcl.parse("a: 1\na: 2\n", new ParseOptions(DuplicateKeys.FIRST));
        assertEquals(Value.of(1), first.get("a").orElseThrow());

        JPObject last = Jpcl.parse("a: 1\na: 2\n", new ParseOptions(DuplicateKeys.LAST));
        assertEquals(Value.of(2), last.get("a").orElseThrow());
    }

    @Test
    void syntaxErrorsPointAtTheRightPlace() {
        expectError("[SERVER_ID]\nprefix \"!\"\n", "expected ':' after key 'prefix', found '\"'", 2, 8);
        expectError("a: {\n  b: 1\n", "unterminated object: missing '}'", 1, 4);
        expectError("a: [}\n", "unterminated array: found '}' before ']'", 1, 4);
        expectError("a: \"x\n", "unterminated string literal (use \\n for a line break)", 1, 4);
        expectError("a: \"\\q\"\n", "invalid escape sequence '\\q'", 1, 5);
        expectError("a: \"x\" b\n", "expected ',' or a line break after value, found 'b'", 1, 8);
        expectError("a: \"x\" [s]\n", "expected a line break before a section header", 1, 8);
        expectError("[]\n", "section header cannot be empty", 1, 1);
        expectError("[a.]\n", "section header segment cannot be empty", 1, 4);
        expectError("[a\n", "unterminated section header", 1, 1);
        expectError("a: 1\n[a]\n", "cannot open section '[a]': 'a' is already a non-section value", 2, 1);
        expectError("~* open\n", "unterminated block comment: missing '*~'", 1, 1);
        expectError("é: \"\\uD800\"\n", "'\\uD800' is an unpaired surrogate", 1, 5);
        expectError("a: \"\\U00110000\"\n", "'\\U00110000' is outside the Unicode range", 1, 5);
    }

    @Test
    void nestingLimit() {
        String deep = "a: " + "[".repeat(Jpcl.MAX_DEPTH + 1);
        expectError(deep, "nesting deeper than 200 levels", 1, 4 + Jpcl.MAX_DEPTH);
    }

    @Test
    void byteOrderMarkAndCrlf() throws JPDecodeException {
        JPObject root = Jpcl.parse("\uFEFF[a]\r\nx: 1\r\n");
        assertEquals(Value.of(1), at(root, "a", "x"));
    }

    @Test
    void messageRendersACaret() {
        JPDecodeException error = assertThrows(
            JPDecodeException.class,
            () -> Jpcl.parse("[SERVER_ID]\n\tprefix \"!\"\n")
        );
        assertEquals(
            "servers.jp:2:9: expected ':' after key 'prefix', found '\"'\n    \tprefix \"!\"\n    \t       ^",
            error.withFilename("servers.jp").getMessage()
        );
    }

    @Test
    void asciiOnlyKeywords() throws JPDecodeException {
        // The long s folds to S in Java's equalsIgnoreCase, but not here
        JPObject root = Jpcl.parse("a: falſe\nb: FALSE\n");
        assertEquals(Value.of("falſe"), root.get("a").orElseThrow());
        assertEquals(Value.of(false), root.get("b").orElseThrow());
    }

    @Test
    void exampleFileParses() throws Exception {
        JPObject root = Jpcl.load(java.nio.file.Path.of("data/servers.example.jp"));
        assertEquals(
            List.of(
                "version",
                "SERVER_ID",
                "SERVER_ID_2",
                "1234567890"
            ),
            List.copyOf(root.keys())
        );
        assertTrue(at(root, "1234567890", "log_channel").isNull());
    }
}
