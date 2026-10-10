package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class NiGradientTextTest {
    @Test
    void groupsTextAtTheRequestedStep() {
        assertEquals(
                color("ff0000") + "ab",
                NiGradientText.render(List.of("FF0000", "0000FF", "2", "ab"), false));
        assertEquals(
                color("000000") + "ab" + color("aaaaaa") + "cd",
                NiGradientText.render(List.of("000000", "ffffff", "2", "abcd"), false));
    }

    @Test
    void truncatesEachGroupsIncrementBeforeProjectingItsColor() {
        assertEquals(
                color("000000")
                        + "a"
                        + color("555555")
                        + "b"
                        + color("aaaaaa")
                        + "c"
                        + color("ffffff")
                        + "d",
                NiGradientText.render(List.of("000000", "ffffff", "1", "abcd"), false));
        assertEquals(
                color("000000")
                        + "a"
                        + color("3f3f3f")
                        + "b"
                        + color("7e7e7e")
                        + "c"
                        + color("bdbdbd")
                        + "d"
                        + color("fcfcfc")
                        + "e",
                NiGradientText.render(List.of("000000", "ffffff", "1", "abcde"), false));
        assertEquals(
                color("000000") + "ab" + color("7f7f7f") + "cd" + color("fefefe") + "e",
                NiGradientText.render(List.of("000000", "ffffff", "2", "abcde"), false));
        assertEquals(
                color("ffffff") + "ab" + color("808080") + "cd" + color("010101") + "e",
                NiGradientText.render(List.of("ffffff", "000000", "2", "abcde"), false));
        assertEquals(
                color("ff8040") + "ab" + color("80409f") + "cd" + color("0100fe") + "e",
                NiGradientText.render(List.of("ff8040", "0000ff", "2", "abcde"), false));
    }

    @Test
    void countsUtf16UnitsInsteadOfCodePoints() {
        assertEquals(
                color("000000") + '\ud83d' + color("7f7f7f") + '\ude00' + color("fefefe") + "a",
                NiGradientText.render(List.of("000000", "ffffff", "1", "\ud83d\ude00a"), false));
        assertEquals(
                color("000000") + "\ud83d\ude00" + color("ffffff") + "a",
                NiGradientText.render(List.of("000000", "ffffff", "2", "\ud83d\ude00a"), false));
    }

    @Test
    void stepsCoveringTheWholeTextKeepOnlyItsStartColor() {
        for (String step : List.of("5", "6", "2147483647")) {
            for (boolean legacy : List.of(false, true)) {
                assertEquals(
                        color("123456") + "abcde",
                        NiGradientText.render(List.of("123456", "abcdef", step, "abcde"), legacy));
            }
        }
    }

    @Test
    void missingRequiredArgumentsReturnNull() {
        assertNull(NiGradientText.render(List.of("000000", "ffffff", "text"), false));
        assertNull(NiGradientText.render(Arrays.asList(null, "ffffff", "1", "x"), false));
        assertNull(NiGradientText.render(Arrays.asList("000000", null, "1", "x"), true));
        assertNull(NiGradientText.render(Arrays.asList("000000", "ffffff", "1", null), true));
    }

    @Test
    void colorFailuresFollowTheSelectedMode() {
        for (List<String> arguments :
                List.of(
                        List.of("invalid", "ffffff", "1", "ab"),
                        List.of("ffffff", "80000000", "1", "ab"))) {
            assertNull(NiGradientText.render(arguments, false));
            assertEquals(
                    color("000000") + "a" + color("000000") + "b",
                    NiGradientText.render(arguments, true));
        }
    }

    @Test
    void clampsSignedHexValuesAfterParsing() {
        assertEquals(
                color("000000") + "a" + color("ffffff") + "b",
                NiGradientText.render(List.of("-1", "+7fffffff", "1", "ab"), false));
    }

    @Test
    void invalidStepUsesOneAndNonpositiveStepDependsOnMode() {
        String graduated = color("000000") + "a" + color("ffffff") + "b";
        for (String step : Arrays.asList(null, "invalid", "2147483648")) {
            assertEquals(
                    graduated,
                    NiGradientText.render(Arrays.asList("000000", "ffffff", step, "ab"), false));
        }
        for (String step : List.of("0", "-2")) {
            List<String> arguments = List.of("000000", "ffffff", step, "ab");
            assertEquals(color("000000") + "ab", NiGradientText.render(arguments, false));
            assertEquals(graduated, NiGradientText.render(arguments, true));
        }
    }

    @Test
    void emptyAndSingleUnitTextKeepTheStartColor() {
        for (String step : List.of("1", "0", "-1")) {
            for (String text : List.of("", "x")) {
                for (boolean legacy : List.of(false, true)) {
                    assertEquals(
                            color("123456") + text,
                            NiGradientText.render(List.of("123456", "abcdef", step, text), legacy));
                }
            }
        }
    }

    private static String color(String hex) {
        return "\u00a7x\u00a7" + String.join("\u00a7", hex.split(""));
    }
}
