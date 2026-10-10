package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class NiFormulaTokensTest {
    @Test
    void retainsOperatorsForTheCalculationEvaluator() {
        assertEquals(List.of("2", "^", "3", "^", "2"), NiFormulaTokens.parse("2^3^2"));
        assertEquals(List.of("(", "2", "+", "3", ")", "*", "4"), NiFormulaTokens.parse("(2+3)*4"));
        assertEquals(List.of("8", "/", "2", "%", "3"), NiFormulaTokens.parse("8/2%3"));
    }

    @Test
    void signsBelongToNumbersOnlyAtOriginalOperatorBoundaries() {
        assertEquals(List.of("1", "+", "-2"), NiFormulaTokens.parse("1+-2"));
        assertEquals(List.of("2", "*", "-3"), NiFormulaTokens.parse("2*-3"));
        assertEquals(List.of("2", "*", "+3"), NiFormulaTokens.parse("2*+3"));
        assertEquals(List.of("-2", "^", "2"), NiFormulaTokens.parse("-2^2"));
        assertEquals(List.of("(", "-2", ")"), NiFormulaTokens.parse("(-2)"));
        assertEquals(List.of("+2"), NiFormulaTokens.parse("+2"));
        assertEquals(List.of("(", "2", ")", "-", "3"), NiFormulaTokens.parse("(2)-3"));
    }

    @Test
    void skippedSpacesDoNotChangeOriginalSignAdjacency() {
        assertEquals(List.of("12"), NiFormulaTokens.parse("1 2"));
        assertEquals(List.of("2", "*", "-", "3"), NiFormulaTokens.parse("2* -3"));
        assertEquals(List.of("2", "*", "+", "3"), NiFormulaTokens.parse("2* +3"));
        assertEquals(List.of("(", "+", "2", ")"), NiFormulaTokens.parse("( +2)"));
    }

    @Test
    void otherWhitespaceRemainsPartOfTheDoubleLiteral() {
        assertEquals(List.of("\t2"), NiFormulaTokens.parse("\t2"));
        assertEquals(List.of("2\t"), NiFormulaTokens.parse("2\t"));
        for (String input : List.of("1\t2", "1\n2", "1\u00a02", "2*\t-3", "\t")) {
            assertThrows(NumberFormatException.class, () -> NiFormulaTokens.parse(input), input);
        }
    }

    @Test
    void rejectsMalformedNumbersAtTheNumberBoundary() {
        for (String input : List.of("-(2+3)", "1e-3", "1e+3", "2.3.4", "word")) {
            assertThrows(NumberFormatException.class, () -> NiFormulaTokens.parse(input), input);
        }
    }

    @Test
    void retainsDoubleLiteralsWithoutChangingTheirPrecision() {
        assertEquals(
                List.of(".125", "+", "3.5", "*", "1e3"), NiFormulaTokens.parse(".125+3.5*1e3"));
        assertEquals(List.of("NaN"), NiFormulaTokens.parse("NaN"));
        assertEquals(List.of("Infinity"), NiFormulaTokens.parse("Infinity"));
    }

    @Test
    void leavesExpressionShapeValidationToTheEvaluator() {
        assertEquals(List.of(), NiFormulaTokens.parse(""));
        assertEquals(List.of(), NiFormulaTokens.parse("   "));
        assertEquals(List.of("(", ")"), NiFormulaTokens.parse("()"));
        assertEquals(List.of("1", ")"), NiFormulaTokens.parse("1)"));
    }
}
