package dev.itemloom.compat.sx;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SxMathBoundaryTest {
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "1+2*3|7",
                "(1+2)*3|9",
                "17%5*2|4",
                "18/3/2|3",
                "9-3-2|4",
                "24/3*2%5|1",
                "3*-(-2+5)|-9",
                "1--2|3",
                "2+++3|5",
                "-+-+5|5",
                ".5+1.|1.5",
                "00012.5/2|6.25",
                "((1))+(2)|3"
            })
    void preservesArithmeticGrammarAndAssociation(String source, double expected) {
        assertEquals(expected, SxMath.evaluate(source));
    }

    @Test
    void acceptsCharacterWhitespaceBetweenTokensOnly() {
        assertEquals(-3, SxMath.evaluate(" \t-\u2003( 1 +\n2 )\r"));
        assertThrows(IllegalArgumentException.class, () -> SxMath.evaluate("1 2"));
        assertThrows(IllegalArgumentException.class, () -> SxMath.evaluate("1 .5"));
        assertThrows(IllegalArgumentException.class, () -> SxMath.evaluate("1\u00a0+2"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                ".",
                "1..2",
                "1e2",
                "1E2",
                "0x1p0",
                "NaN",
                "Infinity",
                "2(3)",
                "(1)2",
                "(1)(2)",
                "()",
                "(1",
                "1)",
                "1+",
                "*1",
                "1**2",
                "1//2",
                "1,2",
                "Math.random()",
                "1;2",
                "1_000",
                "1f",
                "1d",
                "\u0661",
                "\uff11"
            })
    void rejectsUnconsumedOrUnsupportedSyntax(String source) {
        assertThrows(IllegalArgumentException.class, () -> SxMath.evaluate(source));
    }

    @Test
    void nullFailsAndIeeeResultsAreKept() {
        assertThrows(IllegalArgumentException.class, () -> SxMath.evaluate(null));
        assertEquals(Double.POSITIVE_INFINITY, SxMath.evaluate("1/0"));
        assertEquals(Double.NEGATIVE_INFINITY, SxMath.evaluate("-1/0"));
        assertTrue(Double.isNaN(SxMath.evaluate("0/0")));
        assertTrue(Double.isNaN(SxMath.evaluate("1%0")));
        assertEquals(Double.POSITIVE_INFINITY, SxMath.evaluate("9".repeat(400)));
        assertTrue(Double.isNaN(SxMath.evaluate("9".repeat(400) + "%2")));
    }

    @Test
    void preservesSignedZeroAndFloatingPointRounding() {
        for (String source : new String[] {"-0", "+-0", "-0*1", "0/-1", "-0%1"})
            assertEquals(
                    Double.doubleToRawLongBits(-0.0),
                    Double.doubleToRawLongBits(SxMath.evaluate(source)),
                    source);
        assertEquals(
                Double.doubleToRawLongBits(0.0),
                Double.doubleToRawLongBits(SxMath.evaluate("--0")));
        assertEquals((0.1 + 0.2) - 0.3, SxMath.evaluate("0.1+0.2-0.3"));
        assertEquals(
                (10000000000000000d + 1) - 10000000000000000d,
                SxMath.evaluate("10000000000000000+1-10000000000000000"));
    }

    @Test
    void capsStructuralOperandNestingWithoutLimitingFlatWork() {
        assertEquals(1, SxMath.evaluate("(".repeat(63) + "1" + ")".repeat(63)));
        assertEquals(-1, SxMath.evaluate("-".repeat(63) + "1"));
        assertEquals(1, SxMath.evaluate("(" + "+".repeat(62) + "1)"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SxMath.evaluate("(".repeat(64) + "1" + ")".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> SxMath.evaluate("-".repeat(64) + "1"));
        assertThrows(
                IllegalArgumentException.class, () -> SxMath.evaluate("(" + "+".repeat(63) + "1)"));
        assertEquals(10001, SxMath.evaluate("1+".repeat(10000) + "1"));
        String nested = "(".repeat(63) + "1" + ")".repeat(63);
        assertEquals(2, SxMath.evaluate(nested + "+" + nested));
    }
}
