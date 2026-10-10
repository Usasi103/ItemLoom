package dev.itemloom.compat.sx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SxBranchTimeTest {
    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);
    private static final String FORMAT = "yyyy/MM/dd HH:mm";

    @Test
    void branchUsesTheFirstExactMatchAndKeepsRemainingSeparators() {
        assertEquals("first", SxBranchTime.branch("A$B:other|A:first|A:second|default:fallback"));
        assertEquals("x:y$z", SxBranchTime.branch("A$A:x:y$z|A:second"));
        assertEquals("", SxBranchTime.branch("A$A:|default:fallback"));
        assertEquals("empty", SxBranchTime.branch("$:empty|default:fallback"));
    }

    @Test
    void branchUsesTheLastFallbackUnlessItsNameMatchesExactly() {
        assertEquals("second", SxBranchTime.branch("Z$default:first|else:second"));
        assertEquals("first", SxBranchTime.branch("default$default:first|default:second"));
        assertEquals("exact", SxBranchTime.branch("else$default:fallback|else:exact|else:later"));
    }

    @Test
    void branchDoesNotTrimOrFoldLabelsAndIgnoresMalformedAlternatives() {
        assertEquals("fallback", SxBranchTime.branch("A$A |a:lower| A:space|default:fallback"));
        assertEquals(" value ", SxBranchTime.branch(" A $ A : value |default:fallback"));
        assertEquals("", SxBranchTime.branch("A:A"));
        assertEquals("", SxBranchTime.branch("A$missing|B:other"));
    }

    @Test
    void appliesSecondsAndNamedCalendarUnits() {
        assertEquals("2026/10/09 12:10", time("600"));
        assertEquals("2027/11/10 13:01", time("1Y1M1D1h1m"));
        assertEquals(
                "2027/11/10 13:01:01",
                SxBranchTime.time("1y1M1d1H1m1S", "yyyy/MM/dd HH:mm:ss", NOW));
    }

    @Test
    void ignoresUnknownCharactersWithoutDiscardingAccumulatedDigits() {
        assertEquals("2026/10/09 12:12", time("1?2m"));
        assertEquals("2026/10/09 12:13", time("1\u06623m"));
        assertEquals("2026/10/09 12:02", time("2m9"));
        assertEquals("2026/10/09 12:00", time("\u0662"));
        assertEquals("2026/10/09 12:00", time("YmDs"));
        assertEquals("2026/10/09 12:00", time(""));
    }

    @Test
    void appliesMonthAndDayOffsetsInInputOrder() {
        Clock january = Clock.fixed(Instant.parse("2026-01-30T12:00:00Z"), ZoneOffset.UTC);
        assertEquals("2026/03/01 12:00", SxBranchTime.time("1M1d", FORMAT, january));
        assertEquals("2026/02/28 12:00", SxBranchTime.time("1d1M", FORMAT, january));
    }

    @Test
    void calendarDaysHonorTheClockZoneAcrossDaylightSaving() {
        Clock spring =
                Clock.fixed(Instant.parse("2026-03-08T05:30:00Z"), ZoneId.of("America/New_York"));
        assertEquals("2026/03/09 01:30", SxBranchTime.time("1d1h", FORMAT, spring));
        assertEquals("2026/03/09 02:30", SxBranchTime.time("25h", FORMAT, spring));
        assertEquals("2026/03/09 01:30", SxBranchTime.time("86400", FORMAT, spring));
    }

    @Test
    void retainsClockMillisecondsAndSupportsCustomFormatting() {
        Clock fractional = Clock.fixed(Instant.parse("2026-10-09T12:00:00.456Z"), ZoneOffset.UTC);
        assertEquals(
                "2026-10-09 12:00:01.456",
                SxBranchTime.time("1", "yyyy-MM-dd HH:mm:ss.SSS", fractional));
        assertEquals(
                "2026-10-09 12:00:01.456",
                SxBranchTime.time("1s", "yyyy-MM-dd HH:mm:ss.SSS", fractional));
    }

    @Test
    void rejectsOffsetsThatOverflowTheirNumericRanges() {
        assertThrows(NumberFormatException.class, () -> time("9223372036854775808"));
        assertThrows(ArithmeticException.class, () -> time("9223372036854776"));
        assertThrows(ArithmeticException.class, () -> time("9223372036854775"));
        assertThrows(NumberFormatException.class, () -> time("2147483648m"));
        assertThrows(NumberFormatException.class, () -> time("2m2147483648"));
    }

    private static String time(String input) {
        return SxBranchTime.time(input, FORMAT, NOW);
    }
}
