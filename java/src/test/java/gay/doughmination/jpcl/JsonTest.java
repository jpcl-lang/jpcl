/* JsonTest.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void jsonOutput() throws JPDecodeException {
        JPObject root = Jpcl.parse("id: 111111111111111111\nn: [nan, -inf, 0.5]\ne: {}\ns: \"a\\\"b\\u0001\"\n");
        String expected = String.join(
            "",
            "{\n  \"id\": 111111111111111111,\n  \"n\": [\n    NaN,\n    -Infinity,\n    0.5\n  ],\n",
            "  \"e\": {},\n  \"s\": \"a\\\"b\\u0001\"\n}"
        );
        assertEquals(expected, Jpcl.toJson(root, 2));
    }

    @Test
    void jsonInput() throws JPException {
        Value value = Jpcl.fromJson(
            " {\"z\": 1, \"s\": {\"id\": 123456789012345678901234, \"f\": -1.5e3, \"u\": \"é😀\"}, \"a\": [true, null]} "
        );
        JPObject root = value.asObject().orElseThrow();
        assertEquals(List.of("z", "s", "a"), List.copyOf(root.keys()));

        JPObject section = root.get("s").flatMap(Value::asObject).orElseThrow();
        assertEquals(Value.of(new BigInteger("123456789012345678901234")), section.get("id").orElseThrow());
        assertEquals(Value.of(-1500.0), section.get("f").orElseThrow());
        assertEquals(Value.of("é😀"), section.get("u").orElseThrow());

        assertThrows(JPException.class, () -> Jpcl.fromJson("{\"a\": 01}"));
        assertThrows(JPException.class, () -> Jpcl.fromJson("[1,]"));
        assertThrows(JPException.class, () -> Jpcl.fromJson("{} x"));
    }

    @Test
    void errorsCountUtf8Bytes() {
        JPException error = assertThrows(JPException.class, () -> Jpcl.fromJson("[\"é\" x]"));
        assertEquals("invalid JSON at byte 6: expected ',' or a closing bracket", error.getMessage());
    }
}
