/* Json.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSON in and out for the CLI: big integers keep every digit, and key order is kept. */
final class Json {
    private Json() {
    }

    // -- writing -------------------------------------------------------------

    /** Render a value as indented JSON, as {@code jpcl to-json} does in every port. */
    static String toJson(Value value, int indent) {
        StringBuilder out = new StringBuilder();
        render(out, value, indent, 0);
        return out.toString();
    }

    private static void render(
        StringBuilder out,
        Value value,
        int indent,
        int level
    ) {
        switch (value) {
            case Value.Null ignored -> out.append("null");
            case Value.Bool(boolean flag) -> out.append(flag);
            case Value.Int(var integer) -> out.append(integer);
            case Value.Float(double number) -> out.append(formatFloat(number));
            case Value.Text(String text) -> quote(out, text);
            case Value.Array(List<Value> items) -> {
                if (items.isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append('[');
                for (int index = 0; index < items.size(); index++) {
                    if (index > 0) {
                        out.append(',');
                    }
                    newline(out, indent * (level + 1));
                    render(out, items.get(index), indent, level + 1);
                }
                newline(out, indent * level);
                out.append(']');
            }
            case JPObject object -> {
                if (object.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Value> item : object) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    newline(out, indent * (level + 1));
                    quote(out, item.getKey());
                    out.append(": ");
                    render(out, item.getValue(), indent, level + 1);
                }
                newline(out, indent * level);
                out.append('}');
            }
        }
    }

    private static String formatFloat(double number) {
        if (Double.isNaN(number)) {
            return "NaN";
        }
        if (Double.isInfinite(number)) {
            return number > 0 ? "Infinity" : "-Infinity";
        }
        return Writer.formatFinite(number);
    }

    private static void newline(StringBuilder out, int spaces) {
        out.append('\n').append(" ".repeat(spaces));
    }

    /** A JSON string literal, escaped as {@code JSON.stringify} does. */
    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char unit = text.charAt(index);
            switch (unit) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case 0x08 -> out.append("\\b");
                case 0x0c -> out.append("\\f");
                default -> {
                    if (unit <= 0x1f) {
                        out.append(String.format("\\u%04x", (int) unit));
                    } else {
                        out.append(unit);
                    }
                }
            }
        }
        out.append('"');
    }

    // -- reading -------------------------------------------------------------

    /** Parse JSON text. Duplicate keys keep the last value, as {@code JSON.parse} does. */
    static Value fromJson(String text) throws JPException {
        Reader reader = new Reader(text);
        reader.skipSpace();
        Value value = reader.value();
        reader.skipSpace();
        if (reader.pos < text.length()) {
            throw reader.error("unexpected text after the value");
        }
        return value;
    }

    private static final class Reader {
        private final String text;
        private int pos;
        private int depth;

        Reader(String text) {
            this.text = text;
        }

        // Positions are reported in UTF-8 bytes, as the other ports do
        JPException error(String message) {
            int bytes = text.substring(0, Math.min(pos, text.length()))
                .getBytes(StandardCharsets.UTF_8)
                .length;
            return new JPException("invalid JSON at byte " + bytes + ": " + message);
        }

        private int peek() {
            return pos < text.length() ? text.charAt(pos) : -1;
        }

        void skipSpace() {
            while (true) {
                int unit = peek();
                if (unit != ' ' && unit != '\t' && unit != '\n' && unit != '\r') {
                    return;
                }
                pos++;
            }
        }

        private boolean eat(char wanted) {
            if (peek() == wanted) {
                pos++;
                return true;
            }
            return false;
        }

        Value value() throws JPException {
            int unit = peek();
            if (unit == '{') {
                return container(true);
            }
            if (unit == '[') {
                return container(false);
            }
            if (unit == '"') {
                return new Value.Text(string());
            }
            if (unit == '-' || (unit >= '0' && unit <= '9')) {
                return number();
            }
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Value.Bool.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Value.Bool.FALSE;
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return Value.Null.INSTANCE;
            }
            throw error("expected a value");
        }

        private Value container(boolean isObject) throws JPException {
            depth++;
            if (depth > Jpcl.MAX_DEPTH) {
                throw error("nesting too deep");
            }
            pos++;
            char close = isObject ? '}' : ']';
            JPObject object = new JPObject();
            List<Value> items = new ArrayList<>();

            skipSpace();
            if (!eat(close)) {
                while (true) {
                    skipSpace();
                    if (isObject) {
                        if (peek() != '"') {
                            throw error("expected a string key");
                        }
                        String key = string();
                        skipSpace();
                        if (!eat(':')) {
                            throw error("expected ':'");
                        }
                        skipSpace();
                        object.put(key, value());
                    } else {
                        items.add(value());
                    }
                    skipSpace();
                    if (eat(close)) {
                        break;
                    }
                    if (!eat(',')) {
                        throw error("expected ',' or a closing bracket");
                    }
                }
            }
            depth--;
            return isObject ? object : new Value.Array(items);
        }

        private String string() throws JPException {
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                int start = pos;
                while (pos < text.length()) {
                    char unit = text.charAt(pos);
                    if (unit == '"' || unit == '\\' || unit <= 0x1f) {
                        break;
                    }
                    pos++;
                }
                out.append(text, start, pos);
                int unit = peek();
                if (unit == '"') {
                    pos++;
                    return out.toString();
                }
                if (unit != '\\') {
                    throw error("unterminated string");
                }
                pos++;
                int marker = peek();
                pos++;
                switch (marker) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.appendCodePoint(unicodeEscape());
                    default -> throw error("invalid escape");
                }
            }
        }

        private int hex4() throws JPException {
            if (pos + 4 > text.length()) {
                throw error("short \\u escape");
            }
            int code = 0;
            for (int index = pos; index < pos + 4; index++) {
                int digit = Scanner.asciiDigit(text.charAt(index), 16);
                if (digit < 0) {
                    throw error("invalid \\u escape");
                }
                code = code * 16 + digit;
            }
            pos += 4;
            return code;
        }

        private int unicodeEscape() throws JPException {
            int high = hex4();
            boolean isHigh = high >= 0xd800 && high <= 0xdbff;
            if (isHigh && text.startsWith("\\u", pos)) {
                pos += 2;
                int low = hex4();
                if (low >= 0xdc00 && low <= 0xdfff) {
                    return 0x10000 + ((high - 0xd800) << 10) + (low - 0xdc00);
                }
                throw error("unpaired surrogate");
            }
            if (high >= 0xd800 && high <= 0xdfff) {
                throw error("unpaired surrogate");
            }
            return high;
        }

        private boolean isDigitHere() {
            int unit = peek();
            return unit >= '0' && unit <= '9';
        }

        private Value number() throws JPException {
            int start = pos;
            eat('-');
            int digitsStart = pos;
            while (isDigitHere()) {
                pos++;
            }
            int integerDigits = pos - digitsStart;
            boolean leadingZero = integerDigits > 1 && text.charAt(digitsStart) == '0';
            if (integerDigits == 0 || leadingZero) {
                throw error("invalid number");
            }
            boolean isInteger = true;
            if (eat('.')) {
                isInteger = false;
                int fractionStart = pos;
                while (isDigitHere()) {
                    pos++;
                }
                if (pos == fractionStart) {
                    throw error("invalid number");
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                isInteger = false;
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                int exponentStart = pos;
                while (isDigitHere()) {
                    pos++;
                }
                if (pos == exponentStart) {
                    throw error("invalid number");
                }
            }
            String literal = text.substring(start, pos);
            if (isInteger) {
                BigInteger integer = new BigInteger(literal);
                if (Value.Int.fits(integer)) {
                    return new Value.Int(integer);
                }
            }
            return new Value.Float(Double.parseDouble(literal));
        }
    }
}
