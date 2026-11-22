/* Scanner.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.math.BigInteger;

/** A cursor over the source text with lexical helpers: whitespace, comments, strings and bare tokens. */
final class Scanner {
    // Starts a comment that runs to the end of the line
    static final String LINE_COMMENT = "~~";

    // Opens a block comment, which may span lines
    static final String BLOCK_OPEN = "~*";

    // Closes a block comment
    static final String BLOCK_CLOSE = "*~";

    // End of input, returned by peek()
    static final int EOF = -1;

    enum Terminators {
        VALUE,
        KEY,
        HEADER;

        boolean ends(char unit) {
            return switch (this) {
                case VALUE -> unit == ','
                    || unit == '}'
                    || unit == ']'
                    || isLineBreak(unit);

                // A bare key may contain spaces but not brackets or quotes
                case KEY -> unit == ':'
                    || unit == ','
                    || unit == '{'
                    || unit == '}'
                    || unit == '['
                    || unit == ']'
                    || unit == '"'
                    || unit == '\''
                    || isLineBreak(unit);

                case HEADER -> unit == '.'
                    || unit == ']'
                    || isLineBreak(unit);
            };
        }
    }

    final String text;
    int pos;

    Scanner(String text) {
        this.text = stripBom(text);
        this.pos = 0;
    }

    static boolean isInlineSpace(char unit) {
        return unit == ' '
            || unit == '\t'
            || unit == 0x0b
            || unit == 0x0c;
    }

    static boolean isLineBreak(char unit) {
        return unit == '\r' || unit == '\n';
    }

    /** Drop any leading byte order marks. */
    static String stripBom(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == '﻿') {
            start++;
        }
        return text.substring(start);
    }

    // -- cursor --------------------------------------------------------------

    boolean eof() {
        return pos >= text.length();
    }

    int peek() {
        return pos < text.length() ? text.charAt(pos) : EOF;
    }

    boolean startsWith(String needle) {
        return text.startsWith(needle, pos);
    }

    JPDecodeException errorAt(int at, String message) {
        return JPDecodeException.at(message, text, at);
    }

    JPDecodeException errorHere(String message) {
        return errorAt(pos, message);
    }

    /** Describe the current character for an error message. */
    String describeHere() {
        if (eof()) {
            return "end of file";
        }
        int point = text.codePointAt(pos);
        if (point == '\r' || point == '\n') {
            return "end of line";
        }
        return repr(Character.toString(point));
    }

    // -- trivia --------------------------------------------------------------

    /**
     * Skip spaces, tabs and comments, but never a line break. A block comment
     * that spans lines counts as a line break, so the cursor stops on its {@code ~*}.
     */
    void skipInline() throws JPDecodeException {
        skip(false);
    }

    /** Skip all whitespace (line breaks included) and comments. */
    void skipIgnorable() throws JPDecodeException {
        skip(true);
    }

    private void skip(boolean newlines) throws JPDecodeException {
        int index = pos;
        while (index < text.length()) {
            char unit = text.charAt(index);
            if (isInlineSpace(unit) || (newlines && isLineBreak(unit))) {
                index++;
            } else if (text.startsWith(LINE_COMMENT, index)) {
                while (index < text.length() && !isLineBreak(text.charAt(index))) {
                    index++;
                }
            } else if (text.startsWith(BLOCK_OPEN, index)) {
                int close = text.indexOf(BLOCK_CLOSE, index + BLOCK_OPEN.length());
                if (close < 0) {
                    throw errorAt(index, "unterminated block comment: missing '*~'");
                }
                int after = close + BLOCK_CLOSE.length();
                String comment = text.substring(index, after);
                boolean crossesLine = comment.indexOf('\r') >= 0 || comment.indexOf('\n') >= 0;
                if (crossesLine && !newlines) {
                    break;
                }
                index = after;
            } else {
                break;
            }
        }
        pos = index;
    }

    /** At a line break, the end of the file, or a block comment that spans lines. */
    boolean atLineEnd() {
        if (eof()) {
            return true;
        }
        return isLineBreak(text.charAt(pos)) || startsWith(BLOCK_OPEN);
    }

    // -- literals ------------------------------------------------------------

    /** Read a quoted string at the cursor and return its decoded value. */
    String scanString() throws JPDecodeException {
        int start = pos;
        char quote = text.charAt(start);
        StringBuilder out = new StringBuilder();
        int index = start + 1;
        int literalFrom = index;

        while (true) {
            if (index >= text.length()) {
                throw errorAt(start, "unterminated string literal");
            }
            char unit = text.charAt(index);
            if (unit == quote) {
                pos = index + 1;
                out.append(text, literalFrom, index);
                return out.toString();
            }
            if (isLineBreak(unit)) {
                throw errorAt(start, "unterminated string literal (use \\n for a line break)");
            }
            if (unit != '\\') {
                index++;
                continue;
            }

            out.append(text, literalFrom, index);
            int escapeAt = index;
            index++;
            if (index >= text.length()) {
                throw errorAt(escapeAt, "unterminated escape sequence");
            }
            char marker = text.charAt(index);
            int decoded = simpleEscape(marker);

            if (decoded >= 0) {
                out.append((char) decoded);
                index++;
            } else if (marker == 'x' || marker == 'u' || marker == 'U') {
                index = scanHexEscape(out, index, escapeAt);
            } else if (isLineBreak(marker)) {
                // Backslash-newline continues the string, eating the indent
                index++;
                if (marker == '\r' && index < text.length() && text.charAt(index) == '\n') {
                    index++;
                }
                while (index < text.length() && isInlineSpace(text.charAt(index))) {
                    index++;
                }
            } else {
                String shown = Character.toString(text.codePointAt(index));
                throw errorAt(escapeAt, "invalid escape sequence '\\" + shown + "'");
            }
            literalFrom = index;
        }
    }

    /** Decode {@code \xNN}, {@code \}{@code uNNNN} or {@code \UNNNNNNNN} whose marker is at {@code markerAt}. */
    private int scanHexEscape(
        StringBuilder out,
        int markerAt,
        int escapeAt
    ) throws JPDecodeException {
        char marker = text.charAt(markerAt);
        int width = switch (marker) {
            case 'x' -> 2;
            case 'u' -> 4;
            default -> 8;
        };
        String digits = hexDigits(text, markerAt + 1, width);
        if (digits == null) {
            throw errorAt(escapeAt, "'\\" + marker + "' escape needs " + width + " hex digits");
        }
        long code = Long.parseLong(digits, 16);
        int index = markerAt + 1 + width;

        // Recombine a UTF-16 surrogate pair, as JSON encoders emit them
        boolean isHigh = code >= 0xd800 && code <= 0xdbff;
        if (marker == 'u' && isHigh && text.startsWith("\\u", index)) {
            String lowDigits = hexDigits(text, index + 2, 4);
            if (lowDigits != null) {
                long low = Long.parseLong(lowDigits, 16);
                if (low >= 0xdc00 && low <= 0xdfff) {
                    code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
                    index += 6;
                }
            }
        }

        if (code >= 0xd800 && code <= 0xdfff) {
            throw errorAt(escapeAt, "'\\" + marker + digits + "' is an unpaired surrogate");
        }
        if (code > Character.MAX_CODE_POINT) {
            throw errorAt(escapeAt, "'\\" + marker + digits + "' is outside the Unicode range");
        }
        out.appendCodePoint((int) code);
        return index;
    }

    /** Consume up to (not including) a terminator or a comment. */
    String scanUntil(Terminators kind) {
        int index = pos;
        while (index < text.length()
            && !kind.ends(text.charAt(index))
            && !isCommentAt(index)) {
            index++;
        }
        String raw = text.substring(pos, index);
        pos = index;
        return raw;
    }

    private boolean isCommentAt(int index) {
        return text.startsWith(LINE_COMMENT, index) || text.startsWith(BLOCK_OPEN, index);
    }

    /** {@code width} hex digits starting at {@code at}, or null. */
    private static String hexDigits(String text, int at, int width) {
        if (at + width > text.length()) {
            return null;
        }
        for (int index = at; index < at + width; index++) {
            if (asciiDigit(text.charAt(index), 16) < 0) {
                return null;
            }
        }
        return text.substring(at, at + width);
    }

    /** The decoded character for a one-letter escape, or -1. */
    private static int simpleEscape(char marker) {
        return switch (marker) {
            case '"', '\'', '\\', '/' -> marker;
            case '0' -> 0;
            case 'a' -> 0x07;
            case 'b' -> 0x08;
            case 'e' -> 0x1b;
            case 'f' -> 0x0c;
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'v' -> 0x0b;
            default -> -1;
        };
    }

    /** A string shown the way Python's {@code repr()} does, for error messages. */
    static String repr(String value) {
        boolean preferDouble = value.indexOf('\'') >= 0 && value.indexOf('"') < 0;
        char quote = preferDouble ? '"' : '\'';
        StringBuilder out = new StringBuilder();
        out.append(quote);
        value.codePoints().forEach(point -> {
            if (point == quote || point == '\\') {
                out.append('\\').appendCodePoint(point);
            } else if (point == '\n') {
                out.append("\\n");
            } else if (point == '\r') {
                out.append("\\r");
            } else if (point == '\t') {
                out.append("\\t");
            } else if (point <= 0x1f || point == 0x7f) {
                out.append(String.format("\\x%02x", point));
            } else {
                out.appendCodePoint(point);
            }
        });
        out.append(quote);
        return out.toString();
    }

    // -- bare tokens ---------------------------------------------------------

    /** Unicode whitespace, as Python's {@code str.isspace} defines it. */
    static boolean isUnicodeSpace(int point) {
        return (point >= '\t' && point <= '\r')
            || (point >= 0x1c && point <= 0x20)
            || point == 0x85
            || point == 0xa0
            || point == 0x1680
            || (point >= 0x2000 && point <= 0x200a)
            || point == 0x2028
            || point == 0x2029
            || point == 0x202f
            || point == 0x205f
            || point == 0x3000;
    }

    /** Trim Unicode whitespace from both ends, as Python's {@code str.strip()} does. */
    static String trimSpace(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isUnicodeSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isUnicodeSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static final String[] KEYWORD_NAMES = {
        "true",
        "false",
        "null",
        "none",
        "nil",
        "nan",
        "inf",
        "infinity",
        "+inf",
        "+infinity",
        "-inf",
        "-infinity",
        "+nan",
        "-nan",
    };

    private static final Value[] KEYWORD_VALUES = {
        Value.Bool.TRUE,
        Value.Bool.FALSE,
        Value.Null.INSTANCE,
        Value.Null.INSTANCE,
        Value.Null.INSTANCE,
        new Value.Float(Double.NaN),
        new Value.Float(Double.POSITIVE_INFINITY),
        new Value.Float(Double.POSITIVE_INFINITY),
        new Value.Float(Double.POSITIVE_INFINITY),
        new Value.Float(Double.POSITIVE_INFINITY),
        new Value.Float(Double.NEGATIVE_INFINITY),
        new Value.Float(Double.NEGATIVE_INFINITY),
        new Value.Float(Double.NaN),
        new Value.Float(Double.NaN),
    };

    /**
     * Interpret an unquoted token: keyword, then number, then plain string.
     * An empty or whitespace-only token means "no value" and yields null.
     */
    static Value interpretBare(String raw) {
        String token = trimSpace(raw);
        if (token.isEmpty()) {
            return Value.Null.INSTANCE;
        }
        for (int index = 0; index < KEYWORD_NAMES.length; index++) {
            if (equalsIgnoreAsciiCase(token, KEYWORD_NAMES[index])) {
                return KEYWORD_VALUES[index];
            }
        }
        Value number = parseNumber(token);
        return number != null ? number : new Value.Text(token);
    }

    // Only ASCII letters fold, so 'ſ' or 'ı' never match a keyword
    private static boolean equalsIgnoreAsciiCase(String token, String word) {
        if (token.length() != word.length()) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            char unit = token.charAt(index);
            char lowered = (unit >= 'A' && unit <= 'Z') ? (char) (unit + 32) : unit;
            if (lowered != word.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    /** The value of an ASCII digit in {@code base}, or -1. */
    static int asciiDigit(char unit, int base) {
        int digit;
        if (unit >= '0' && unit <= '9') {
            digit = unit - '0';
        } else if (unit >= 'a' && unit <= 'z') {
            digit = unit - 'a' + 10;
        } else if (unit >= 'A' && unit <= 'Z') {
            digit = unit - 'A' + 10;
        } else {
            return -1;
        }
        return digit < base ? digit : -1;
    }

    private static boolean isDecimal(String text, int index) {
        return index < text.length() && asciiDigit(text.charAt(index), 10) >= 0;
    }

    private static boolean charIs(String text, int index, char wanted) {
        return index < text.length() && text.charAt(index) == wanted;
    }

    /** Match {@code D+(_D+)*} at {@code index} and return the end, or -1. */
    private static int groupedDigits(String text, int index) {
        while (true) {
            int runStart = index;
            while (isDecimal(text, index)) {
                index++;
            }
            if (index == runStart) {
                return -1;
            }
            if (charIs(text, index, '_') && isDecimal(text, index + 1)) {
                index++;
                continue;
            }
            return index;
        }
    }

    /** Match {@code (_?D)+} for a radix body, which must use up the whole text. */
    private static boolean radixDigits(int base, String body) {
        if (body.isEmpty()) {
            return false;
        }
        int index = 0;
        while (index < body.length()) {
            if (body.charAt(index) == '_') {
                index++;
            }
            if (index >= body.length() || asciiDigit(body.charAt(index), base) < 0) {
                return false;
            }
            index++;
        }
        return true;
    }

    private static boolean isFloat(String text) {
        int index = (charIs(text, 0, '+') || charIs(text, 0, '-')) ? 1 : 0;
        if (charIs(text, index, '.')) {
            int end = groupedDigits(text, index + 1);
            if (end < 0) {
                return false;
            }
            index = end;
        } else {
            int end = groupedDigits(text, index);
            if (end < 0) {
                return false;
            }
            index = end;
            if (charIs(text, index, '.')) {
                index++;
                int fractionEnd = groupedDigits(text, index);
                if (fractionEnd >= 0) {
                    index = fractionEnd;
                }
            }
        }
        if (charIs(text, index, 'e') || charIs(text, index, 'E')) {
            index++;
            if (charIs(text, index, '+') || charIs(text, index, '-')) {
                index++;
            }
            int end = groupedDigits(text, index);
            if (end < 0) {
                return false;
            }
            index = end;
        }
        return index == text.length();
    }

    /** Numbers with {@code _} separators, a sign and {@code 0x}/{@code 0o}/{@code 0b} prefixes, as in TOML and Python. */
    private static Value parseNumber(String text) {
        boolean signed = text.charAt(0) == '+' || text.charAt(0) == '-';
        boolean negative = text.charAt(0) == '-';
        String body = text.substring(signed ? 1 : 0);

        if (body.length() > 2 && body.charAt(0) == '0') {
            int base = switch (body.charAt(1)) {
                case 'x', 'X' -> 16;
                case 'o', 'O' -> 8;
                case 'b', 'B' -> 2;
                default -> 0;
            };
            if (base != 0) {
                if (!radixDigits(base, body.substring(2))) {
                    return null;
                }
                BigInteger magnitude = new BigInteger(body.substring(2).replace("_", ""), base);
                // Beyond 128 bits a radix literal stays a plain string, as in the Go, Zig and Rust ports
                if (!Value.Int.fits(magnitude)) {
                    return null;
                }
                return new Value.Int(negative ? magnitude.negate() : magnitude);
            }
        }

        String clean = text.replace("_", "");
        if (groupedDigits(text, signed ? 1 : 0) == text.length()) {
            BigInteger integer = new BigInteger(clean);
            if (Value.Int.fits(integer)) {
                return new Value.Int(integer);
            }
            // Beyond 128 bits, fall back to a float, as the Go, Zig and Rust ports do
            return new Value.Float(Double.parseDouble(clean));
        }
        if (isFloat(text)) {
            return new Value.Float(Double.parseDouble(clean));
        }
        return null;
    }
}
