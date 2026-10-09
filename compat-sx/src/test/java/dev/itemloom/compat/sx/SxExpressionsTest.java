package dev.itemloom.compat.sx;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SxExpressionsTest {
    static SxExpressions handler(
            Map<String, ?> local, Map<String, ?> global, Map<String, String> other) {
        return new SxExpressions(
                null,
                SxRandom.compile(local),
                SxRandom.compile(global),
                other,
                new Random(17),
                text -> text.replace("%player_level%", "12"),
                (f, n, h, a) -> List.of(h.replace("<l:grade>"), a[0]),
                new SxConfig(Map.of()),
                Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "<i:7_7>|7",
                "<r:-9_-9>|-9",
                "<d:1.25_1.25>|1.25",
                "<c:100/3>|33.33",
                "<c:int -2.5>|-2",
                "<c:12.42+24.13-36.2*2.3/-(-4.1+6.5)%3>|38.24",
                "<c:(2+3)*4>|20.0",
                "<max:-4:-2:-9>|-2",
                "<min:4:-2:9>|-2",
                "<eq:A==A>|true",
                "<eq:5.0==5>|false",
                "<like:长剑==钢铁长剑>|true",
                "<cmp:5.0 eq 5>|true",
                "<cmp:b gt a>|true",
                "<cmp:12 ge 13>|false",
                "<if:<cmp:%player_level% ge 10>?可用:不可用>|可用",
                "'<when:A$B:二号|A:一号|default:无>'|一号",
                "<t:600>|2026/10/09 12:10",
                "<t:1Y1M1D1h1m>|2027/11/10 13:01",
                "prefix<null:x>suffix|prefixsuffix",
                "<literal>|<literal>",
                "$<i:1_5>|<i:1_5>"
            })
    void expressionContract(String input, String expected) {
        assertEquals(expected, handler(Map.of(), Map.of(), Map.of()).replace(input));
    }

    @Test
    void nestedLocksAndParameterOverrides() {
        var h =
                handler(
                        Map.of("grade", List.of("common", "rare"), "rareColor", "red"),
                        Map.of(),
                        Map.of("grade", "rare"));
        assertEquals("red:rare:rare", h.replace("<l:<l:grade>Color>:<l:grade>:<l:grade#other:no>"));
        assertEquals(Map.of("grade", "rare", "rareColor", "red"), h.getLockMap());
    }

    @Test
    void nullFallsThroughAndGroupsExpandAndDeleteIndependently() {
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("grade", null);
        local.put("group", List.of(List.of("one", "<s:missing>", "two")));
        var h = handler(local, Map.of("grade", "rare"), Map.of());
        assertEquals("rare", h.replace("<l:grade>"));
        assertEquals(
                List.of("one", "two", "tail"),
                h.replace(List.of("<s:group>", "<b:A:B>deleted", "<b:A#A>tail")));
    }

    @Test
    void literalColonUnknownTypeAndCycles() {
        assertThrows(
                IllegalArgumentException.class,
                () -> handler(Map.of(), Map.of(), Map.of()).replace("<extension:test>"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        handler(Map.of("a", "<s:b>", "b", "<s:a>"), Map.of(), Map.of())
                                .replace("<s:a>"));
        assertThrows(
                IllegalArgumentException.class,
                () -> handler(Map.of(), Map.of(), Map.of()).replace("<c:1/0>"));
    }

    @Test
    void weightedGroupsAndNullDoNotMutateInputs() {
        Map<String, Object> nil = new LinkedHashMap<>();
        nil.put("2", null);
        var h =
                handler(
                        Map.of("grade", List.of(Map.of("0", "never"), nil)),
                        Map.of("grade", "fallback"),
                        Map.of());
        assertEquals("fallback", h.replace("<s:grade>"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SxRandom.compile(Map.of("x", List.of(Map.of("-1", "bad")))));
        assertThrows(
                IllegalArgumentException.class, () -> SxRandom.compile(Map.of("x", List.of())));
    }

    @Test
    void typedNbtUsesNumericTypesWithoutReplacingKeys() {
        var h = handler(Map.of(), Map.of(), Map.of());
        Object result =
                h.replace(
                        (Object)
                                Map.of(
                                        "<i:3_3>",
                                        List.of(
                                                "[byte]-128",
                                                "[short]32767",
                                                "[int]2147483647",
                                                "[long]9223372036854775807",
                                                "[float]2.5",
                                                "[double]<d:1_1>")));
        assertEquals(
                Map.of(
                        "<i:3_3>",
                        List.of(
                                (byte) -128,
                                (short) 32767,
                                Integer.MAX_VALUE,
                                Long.MAX_VALUE,
                                2.5F,
                                1D)),
                result);
    }

    @Test
    void scriptReturnsMultipleLoreLines() {
        assertEquals(
                List.of("rare", "payload"),
                handler(Map.of("grade", "rare"), Map.of(), Map.of())
                        .replace(List.of("<j:Default.item#payload>")));
    }

    @Test
    void randomBoundsIncludeBothEndsAndAcceptReverseRanges() {
        var h = handler(Map.of(), Map.of(), Map.of());
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < 40; i++) seen.add(h.replace("<i:2_-2>"));
        assertEquals(java.util.Set.of("-2", "-1", "0", "1", "2"), seen);
    }
}
