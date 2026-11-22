/* WriterTest.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WriterTest {
    static String roundTrip(String source, StringifyOptions options) throws JPDecodeException {
        return Jpcl.stringify(Jpcl.parse(source), options);
    }

    @Test
    void floatFormattingMatchesTheOtherWriters() {
        assertEquals("3", Writer.formatFinite(3.0));
        assertEquals("0", Writer.formatFinite(-0.0));
        assertEquals("0.1", Writer.formatFinite(0.1));
        assertEquals("123.456", Writer.formatFinite(123.456));
        assertEquals("1e-05", Writer.formatFinite(1e-5));
        assertEquals("0.0001", Writer.formatFinite(1e-4));
        assertEquals("-2.5e-07", Writer.formatFinite(-2.5e-7));
        assertEquals("1e+16", Writer.formatFinite(1e16));
        assertEquals("1.5e+300", Writer.formatFinite(1.5e300));
        assertEquals("1234567890123456.8", Writer.formatFinite(1234567890123456.8));
    }

    @Test
    void floatFormattingUsesTheShortestDigits() {
        // Double.toString gives 4.9E-324 and 1.0E23 style output here
        assertEquals("5e-324", Writer.formatFinite(Double.MIN_VALUE));
        assertEquals("1e+23", Writer.formatFinite(1e23));
        assertEquals("1.7976931348623157e+308", Writer.formatFinite(Double.MAX_VALUE));
        assertEquals("2.2250738585072014e-308", Writer.formatFinite(Double.MIN_NORMAL));
        assertEquals("0.30000000000000004", Writer.formatFinite(0.1 + 0.2));
    }

    @Test
    void documentLayout() throws JPDecodeException {
        String source = String.join(
            "",
            "[b]\nx: 1\nversion: 2\n[SERVER_ID]\n",
            "config: {disabled_channels:, disabled_users: [9892, 82082, 8209], e: {}}\n",
            "\"needs quotes\": \"tab\\t é\"\n"
        );
        String expected = String.join(
            "",
            "[b]\nx: 1\nversion: 2\n\n[SERVER_ID]\nconfig: {\n",
            "  disabled_channels:,\n  disabled_users: [9892, 82082, 8209],\n  e: {}\n}\n",
            "\"needs quotes\": \"tab\\t é\"\n"
        );
        assertEquals(expected, roundTrip(source, StringifyOptions.DEFAULT));
    }

    @Test
    void rootScalarsAreHoistedAboveSections() throws JPDecodeException {
        String text = roundTrip("a: [null, 1.0, nan, -inf]\n[s]\nk:\n", StringifyOptions.DEFAULT);
        assertEquals("a: [null, 1, nan, -inf]\n\n[s]\nk:\n", text);
        assertEquals("", roundTrip("", StringifyOptions.DEFAULT));
    }

    @Test
    void arraysWrapWhenTheyOutgrowTheWidth() throws JPDecodeException {
        String text = roundTrip(
            "[s]\nids: [111111111111111111, 222222222222222222]\n",
            StringifyOptions.DEFAULT.withWidth(30)
        );
        assertEquals("[s]\nids: [\n  111111111111111111,\n  222222222222222222\n]\n", text);
    }

    @Test
    void sortKeysAndEnsureAscii() throws JPDecodeException {
        StringifyOptions options = StringifyOptions.DEFAULT
            .withSortKeys(true)
            .withEnsureAscii(true);
        String text = roundTrip("b: \"é😀\"\na: 1\n", options);
        assertEquals("a: 1\nb: \"\\u00e9\\ud83d\\ude00\"\n", text);
    }

    @Test
    void sortKeysUsesCodePointOrder() throws JPDecodeException {
        // UTF-16 order would put the emoji before U+FF5E
        String text = roundTrip("\"😀\": 1\n\"～\": 2\n", StringifyOptions.DEFAULT.withSortKeys(true));
        assertEquals("\"～\": 2\n\"😀\": 1\n", text);
    }

    @Test
    void exampleFileIsStable() throws Exception {
        JPObject root = Jpcl.load(java.nio.file.Path.of("data/servers.example.jp"));
        String once = Jpcl.stringify(root);
        assertEquals(once, Jpcl.stringify(Jpcl.parse(once)));
    }
}
