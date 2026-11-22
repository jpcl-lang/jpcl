/* Value.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Any value a {@code .jp} document can hold. An empty value ({@code key:}) parses to {@link Null}. */
public sealed interface Value
    permits Value.Null,
        Value.Bool,
        Value.Int,
        Value.Float,
        Value.Text,
        Value.Array,
        JPObject {

    /** {@code null}, {@code none}, {@code nil}, or nothing after the colon. */
    enum Null implements Value {
        INSTANCE
    }

    /** {@code true} or {@code false}. */
    record Bool(boolean value) implements Value {
        public static final Bool TRUE = new Bool(true);
        public static final Bool FALSE = new Bool(false);

        public static Bool of(boolean value) {
            return value ? TRUE : FALSE;
        }
    }

    /** An integer that fits in 128 signed bits, as in the Zig and Rust ports. */
    record Int(BigInteger value) implements Value {
        public Int {
            Objects.requireNonNull(value, "value");
            if (!fits(value)) {
                throw new IllegalArgumentException("integer does not fit in 128 signed bits: " + value);
            }
        }

        public static Int of(long value) {
            return new Int(BigInteger.valueOf(value));
        }

        /** True when {@code value} fits in 128 signed bits. */
        public static boolean fits(BigInteger value) {
            return value.bitLength() <= 127;
        }
    }

    /** A 64-bit float, including {@code nan}, {@code inf} and {@code -inf}. */
    record Float(double value) implements Value {
        // Equal by bit pattern, so NaN equals NaN as in Double.equals
        @Override
        public boolean equals(java.lang.Object other) {
            return other instanceof Float(double that)
                && Double.compare(value, that) == 0;
        }

        @Override
        public int hashCode() {
            return Double.hashCode(value);
        }
    }

    /** A string. */
    record Text(String value) implements Value {
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A {@code [...]} array. The list is unmodifiable. */
    record Array(List<Value> items) implements Value {
        public Array {
            items = List.copyOf(items);
        }

        public static Array of(Value... items) {
            return new Array(List.of(items));
        }
    }

    // -- helpers -------------------------------------------------------------

    static Value of(boolean value) {
        return Bool.of(value);
    }

    static Value of(long value) {
        return Int.of(value);
    }

    static Value of(BigInteger value) {
        return new Int(value);
    }

    static Value of(double value) {
        return new Float(value);
    }

    static Value of(String value) {
        return value == null ? Null.INSTANCE : new Text(value);
    }

    default boolean isNull() {
        return this == Null.INSTANCE;
    }

    /** The string, when this is a {@link Text}. */
    default Optional<String> asString() {
        return this instanceof Text(String text) ? Optional.of(text) : Optional.empty();
    }

    /** The object, when this is a {@link JPObject}. */
    default Optional<JPObject> asObject() {
        return this instanceof JPObject object ? Optional.of(object) : Optional.empty();
    }

    /** The items, when this is an {@link Array}. */
    default Optional<List<Value>> asArray() {
        return this instanceof Array(List<Value> items) ? Optional.of(items) : Optional.empty();
    }
}
