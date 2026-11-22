/* Writer.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Turns values back into {@code .jp} source in one fixed, deterministic style. */
final class Writer {
    private final StringifyOptions options;

    private Writer(StringifyOptions options) {
        this.options = options;
    }

    static String stringify(JPObject root, StringifyOptions options) {
        return new Writer(options).document(root);
    }

    private String document(JPObject root) {
        List<String> preamble = new ArrayList<>();
        List<String> blocks = new ArrayList<>();

        for (Map.Entry<String, Value> item : items(root)) {
            if (item.getValue() instanceof JPObject section) {
                StringBuilder block = new StringBuilder();
                block.append('[').append(formatKey(item.getKey())).append(']');
                for (Map.Entry<String, Value> child : items(section)) {
                    block.append('\n').append(entry(child.getKey(), child.getValue(), 0));
                }
                blocks.add(block.toString());
            } else {
                // A root scalar after a header would be read back into that section, so hoist it
                preamble.add(entry(item.getKey(), item.getValue(), 0));
            }
        }

        if (!preamble.isEmpty()) {
            blocks.addFirst(String.join("\n", preamble));
        }
        if (blocks.isEmpty()) {
            return "";
        }
        return String.join("\n\n", blocks) + "\n";
    }

    /** One {@code key: value} line, possibly spanning several lines. */
    private String entry(String key, Value value, int level) {
        String prefix = pad(level) + formatKey(key) + ":";
        if (value.isNull()) {
            return prefix;
        }
        int column = codePoints(prefix) + 1;
        return prefix + " " + render(value, level, column);
    }

    private String pad(int level) {
        return " ".repeat(options.indent() * level);
    }

    private String formatKey(String key) {
        return isBareKey(key) ? key : formatString(key);
    }

    /** A string literal with the minimum necessary escaping. */
    private String formatString(String value) {
        StringBuilder out = new StringBuilder("\"");
        value.codePoints().forEach(point -> {
            switch (point) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case 0x08 -> out.append("\\b");
                case 0x0c -> out.append("\\f");
                default -> {
                    boolean control = point < ' ' || point == 0x7f;
                    boolean escapeWide = options.ensureAscii() && point > 0x7e;
                    if (control || escapeWide) {
                        appendUnicodeEscape(out, point);
                    } else {
                        out.appendCodePoint(point);
                    }
                }
            }
        });
        out.append('"');
        return out.toString();
    }

    /** Render any value; {@code column} is where it starts on the current line. */
    private String render(Value value, int level, int column) {
        return switch (value) {
            case Value.Null ignored -> "null";
            case Value.Bool(boolean flag) -> Boolean.toString(flag);
            case Value.Text(String text) -> formatString(text);
            case Value.Int(var integer) -> integer.toString();
            case Value.Float(double number) -> formatNumber(number);
            case JPObject object -> renderObject(object, level);
            case Value.Array(List<Value> items) -> renderArray(items, level, column);
        };
    }

    private String renderObject(JPObject object, int level) {
        if (object.isEmpty()) {
            return "{}";
        }
        List<String> body = new ArrayList<>();
        for (Map.Entry<String, Value> item : items(object)) {
            body.add(entry(item.getKey(), item.getValue(), level + 1));
        }
        return "{\n" + String.join(",\n", body) + "\n" + pad(level) + "}";
    }

    /** {@code [...]}, inline when it fits on the line. */
    private String renderArray(List<Value> items, int level, int column) {
        if (items.isEmpty()) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for (Value item : items) {
            parts.add(render(item, level + 1, column));
        }
        String inline = "[" + String.join(", ", parts) + "]";
        boolean fits = column + codePoints(inline) <= options.width();
        if (inline.indexOf('\n') < 0 && fits) {
            return inline;
        }
        String inner = pad(level + 1);
        List<String> body = new ArrayList<>();
        for (String part : parts) {
            body.add(inner + part);
        }
        return "[\n" + String.join(",\n", body) + "\n" + pad(level) + "]";
    }

    /** Entries in output order. */
    private List<Map.Entry<String, Value>> items(JPObject object) {
        List<Map.Entry<String, Value>> items = new ArrayList<>(object.asMap().entrySet());
        if (options.sortKeys()) {
            items.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));
        }
        return items;
    }

    // UTF-16 order differs from code point order above U+FFFF, so compare code points
    static final Comparator<String> CODE_POINT_ORDER = (left, right) -> {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    };

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** Keys matching {@code [A-Za-z0-9_][A-Za-z0-9_-]*} are written without quotes. */
    static boolean isBareKey(String key) {
        if (key.isEmpty() || key.charAt(0) == '-') {
            return false;
        }
        for (int index = 0; index < key.length(); index++) {
            char unit = key.charAt(index);
            boolean allowed = (unit >= 'a' && unit <= 'z')
                || (unit >= 'A' && unit <= 'Z')
                || (unit >= '0' && unit <= '9')
                || unit == '_'
                || unit == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** {@code \}{@code uXXXX}, or a UTF-16 surrogate pair for astral characters. */
    static void appendUnicodeEscape(StringBuilder out, int point) {
        for (char unit : Character.toChars(point)) {
            out.append(String.format("\\u%04x", (int) unit));
        }
    }

    /** A number, including the non-finite literals {@code nan}, {@code inf} and {@code -inf}. */
    private static String formatNumber(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        return formatFinite(value);
    }

    /**
     * Render a finite float exactly as the other ports do, so all writers emit
     * the same bytes: a whole number below 1e16 as an integer, anything else as
     * the shortest round-tripping digits, in scientific notation when the
     * exponent is below -4 or at least 16 ({@code 1e-05}, {@code 1e+16}).
     */
    static String formatFinite(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e16) {
            return Long.toString((long) value);
        }

        BigDecimal shortest = shortestDigits(Math.abs(value));
        String digits = shortest.unscaledValue().toString();
        int exponent = digits.length() - shortest.scale() - 1;
        String sign = value < 0 ? "-" : "";

        if (exponent < -4 || exponent >= 16) {
            String fraction = digits.length() > 1 ? "." + digits.substring(1) : "";
            char exponentSign = exponent < 0 ? '-' : '+';
            return sign
                + digits.charAt(0)
                + fraction
                + "e"
                + exponentSign
                + String.format("%02d", Math.abs(exponent));
        }
        if (exponent < 0) {
            String zeros = "0".repeat(-exponent - 1);
            return sign + "0." + zeros + digits;
        }
        int point = exponent + 1;
        return sign + digits.substring(0, point) + "." + digits.substring(point);
    }

    /**
     * The fewest significant digits that read back as {@code value}, the closest
     * such decimal when there are two. Double.toString cannot be used directly:
     * it never prints fewer than two digits, so it writes 4.9E-324 where the
     * other ports write 5e-324.
     */
    private static BigDecimal shortestDigits(double value) {
        BigDecimal exact = new BigDecimal(value);
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal down = exact.round(new MathContext(precision, RoundingMode.FLOOR));
            BigDecimal up = exact.round(new MathContext(precision, RoundingMode.CEILING));
            boolean downWorks = down.doubleValue() == value;
            boolean upWorks = up.doubleValue() == value;
            if (!downWorks && !upWorks) {
                continue;
            }
            BigDecimal chosen;
            if (downWorks && upWorks) {
                int nearer = exact.subtract(down).compareTo(up.subtract(exact));
                if (nearer == 0) {
                    chosen = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
                } else {
                    chosen = nearer < 0 ? down : up;
                }
            } else {
                chosen = downWorks ? down : up;
            }
            return chosen.stripTrailingZeros();
        }
        // Seventeen digits always round-trip, so this is unreachable
        return exact.round(MathContext.DECIMAL64).stripTrailingZeros();
    }
}
