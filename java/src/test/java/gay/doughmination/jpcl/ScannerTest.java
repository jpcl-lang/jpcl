/* ScannerTest.java
 * MIT Licence — (c) 2026 Clove Twilight
 */
package gay.doughmination.jpcl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class ScannerTest {
    @Test
    void bareTokens() {
        assertEquals(Value.Null.INSTANCE, Scanner.interpretBare("   "));
        assertEquals(Value.of(true), Scanner.interpretBare("True"));
        assertEquals(Value.Null.INSTANCE, Scanner.interpretBare("None"));
        assertEquals(Value.of(1000), Scanner.interpretBare("1_000"));
        assertEquals(Value.of(-255), Scanner.interpretBare("-0xff"));
        assertEquals(Value.of(493), Scanner.interpretBare("0o755"));
        assertEquals(Value.of(5), Scanner.interpretBare("0b_101"));
        assertEquals(Value.of(0.5), Scanner.interpretBare(".5"));
        assertEquals(Value.of(5.0), Scanner.interpretBare("5."));
        assertEquals(Value.of(1e-7), Scanner.interpretBare("1e-7"));
        assertEquals(Value.of(Double.NaN), Scanner.interpretBare("-NaN"));
        assertEquals(Value.of(Double.NEGATIVE_INFINITY), Scanner.interpretBare("-Infinity"));
        assertEquals(Value.of("12abc"), Scanner.interpretBare("12abc"));
        assertEquals(Value.of("1__0"), Scanner.interpretBare("1__0"));
        assertEquals(Value.of("0x"), Scanner.interpretBare("0x"));
        assertEquals(Value.of("0xf_"), Scanner.interpretBare("0xf_"));
        assertEquals(Value.of("#8496AD"), Scanner.interpretBare("#8496AD"));
        assertEquals(Value.of(111111111111111111L), Scanner.interpretBare("111111111111111111"));
    }

    @Test
    void integersBeyond128Bits() {
        BigInteger largest = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
        assertEquals(Value.of(largest), Scanner.interpretBare(largest.toString()));
        assertEquals(Value.of(largest.negate().subtract(BigInteger.ONE)), Scanner.interpretBare("-" + largest.add(BigInteger.ONE)));

        String tooBig = largest.add(BigInteger.ONE).toString();
        assertEquals(Value.of(Double.parseDouble(tooBig)), Scanner.interpretBare(tooBig));

        String radixTooBig = "0x" + "f".repeat(33);
        assertEquals(Value.of(radixTooBig), Scanner.interpretBare(radixTooBig));
    }

    @Test
    void trimMatchesPython() {
        assertEquals("x y", Scanner.trimSpace("　 \tx y \u0085\n"));
        assertEquals("é", Scanner.trimSpace("é"));
        assertEquals("", Scanner.trimSpace("   "));
    }

    @Test
    void reprMatchesPython() {
        assertEquals("'prefix'", Scanner.repr("prefix"));
        assertEquals("\"it's\"", Scanner.repr("it's"));
        assertEquals("'a\\tb\\x01'", Scanner.repr("a\tb\u0001"));
    }
}
