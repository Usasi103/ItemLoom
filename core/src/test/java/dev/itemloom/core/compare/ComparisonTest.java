package dev.itemloom.core.compare;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemloom.core.compare.ComparisonModel.Attribute;
import dev.itemloom.core.compare.ComparisonModel.Bar;
import dev.itemloom.core.compare.ComparisonModel.Settings;
import dev.itemloom.core.compare.ComparisonModel.Slot;
import dev.itemloom.core.compare.ComparisonModel.Snapshot;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ComparisonTest {
    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static Settings settings(
            boolean hide, Set<String> lower, Map<String, Attribute> definitions) {
        return new Settings(
                Map.of("head", "head", "ring_1", "provider"),
                definitions,
                lower,
                hide,
                "+{diff}",
                "-{diff}",
                "same",
                "self",
                "equipped",
                40,
                new Bar(true, "up:{from}:{to}", "down:{from}:{to}", 40, 4, 10));
    }

    private static Snapshot snapshot(String stat, String value) {
        return new Snapshot(
                Map.of("head", new Slot(false, "abcdefgh", Map.of(stat, decimal(value)))),
                Map.of());
    }

    @Test
    void preservesLegacyDecimalDifferenceAndInstanceIdentity() {
        Settings settings = settings(false, Set.of(), Map.of());
        Snapshot snapshot = snapshot("armor", "1.45");
        assertEquals("+0.15", Comparison.resolve("head_armor_1.6_other", snapshot, settings));
        assertEquals("self", Comparison.resolve("head_armor_9_abcdefgh", snapshot, settings));
        assertEquals("equipped", Comparison.resolve("equipped_head_abcdefgh", snapshot, settings));
        assertEquals("", Comparison.resolve("equipped_head_other", snapshot, settings));
    }

    @Test
    void emptyPlainAndMissingStatsKeepTheirDifferentMeanings() {
        Snapshot empty = new Snapshot(Map.of(), Map.of());
        assertEquals(
                "+2",
                Comparison.resolve(
                        "head_armor_2_other", empty, settings(false, Set.of(), Map.of())));
        assertEquals(
                "",
                Comparison.resolve(
                        "head_armor_2_other", empty, settings(true, Set.of(), Map.of())));
        Snapshot plain = new Snapshot(Map.of("head", Slot.PLAIN), Map.of());
        assertEquals(
                "",
                Comparison.resolve(
                        "head_armor_2_other", plain, settings(false, Set.of(), Map.of())));
        assertEquals(
                "+2",
                Comparison.resolve(
                        "head_speed_2_other",
                        snapshot("armor", "4"),
                        settings(false, Set.of(), Map.of())));
    }

    @Test
    void lowerValuesReverseArrowsAndSuppressBarOverlays() {
        Settings settings = settings(false, Set.of("cooldown"), Map.of());
        Snapshot snapshot = snapshot("cooldown", "2");
        assertEquals("+1", Comparison.resolve("head_cooldown_1_other", snapshot, settings));
        assertEquals("", Comparison.resolve("bar_head_cooldown_1_0_10_other", snapshot, settings));
    }

    @Test
    void arbitraryConfiguredSlotsWithUnderscoresAndRejectedParameters() {
        Settings settings = settings(false, Set.of(), Map.of());
        Snapshot empty = new Snapshot(Map.of(), Map.of());
        assertEquals("+3", Comparison.resolve("ring_1_armor_3_other", empty, settings));
        for (String request :
                new String[] {
                    "",
                    "head_armor_NaN_other",
                    "head_armor_1e3_other",
                    "head_armor_2_",
                    "unknown_armor_2_other",
                    "bar_head_armor_1_3_2_other",
                    "head_bad%_2_other",
                    "equipped_head_bad!"
                }) {
            assertEquals("", Comparison.resolve(request, empty, settings), request);
        }
    }

    @Test
    void divisionBarRoundingAndOverlayAdvancesMatch() {
        Settings settings = settings(false, Set.of(), Map.of());
        assertEquals(
                "<shift:-40>up:c:f<shift:20>",
                Comparison.resolve(
                        "bar_head_armor_5_0_10_other", snapshot("armor", "2"), settings));
        assertEquals(
                "<shift:-40>down:c:k",
                Comparison.resolve(
                        "bar_head_armor_2_0_10_other", snapshot("armor", "20"), settings));
        assertEquals(6, Comparison.level(decimal("5.5"), decimal("0"), decimal("10"), 10));
        assertEquals(0, Comparison.level(decimal("-1"), decimal("0"), decimal("10"), 10));
        assertEquals(10, Comparison.level(decimal("11"), decimal("0"), decimal("10"), 10));
    }

    @Test
    void percentagesUseRawViewerBaseAndMissingBaseHidesComparison() {
        Settings settings =
                settings(
                        false,
                        Set.of(),
                        Map.of("powerpct", new Attribute("power%", true, "power")));
        Snapshot snapshot =
                new Snapshot(
                        Map.of(
                                "head",
                                new Slot(false, "equipped", Map.of("powerpct", decimal("5")))),
                        Map.of("power", decimal("200")));
        assertEquals("+10.00", Comparison.resolve("head_powerpct_10_other", snapshot, settings));
        assertEquals(
                "",
                Comparison.resolve(
                        "head_powerpct_10_other",
                        new Snapshot(snapshot.slots(), Map.of()),
                        settings));
    }

    @Test
    void completeCandidatesCompareFixedAndPercentTogetherWithoutCountingAliasesTwice() {
        Settings settings =
                settings(
                        false,
                        Set.of(),
                        Map.of(
                                "power", new Attribute("attack", false, "attack"),
                                "oldpower", new Attribute("attack", false, "attack"),
                                "powerpct", new Attribute("attack%", true, "attack")));
        Snapshot snapshot =
                new Snapshot(
                        Map.of(
                                "head",
                                new Slot(
                                        false,
                                        "equipped",
                                        Map.of("power", decimal("5"), "powerpct", decimal("10")))),
                        Map.of("attack", decimal("100")),
                        Map.of("other", Map.of("power", decimal("20"), "powerpct", decimal("1"))));
        assertEquals("+6.00", Comparison.resolve("head_power_20_other", snapshot, settings));
        assertEquals("+6.00", Comparison.resolve("head_powerpct_1_other", snapshot, settings));
        assertEquals(
                "<shift:-40>down:b:f<shift:20>",
                Comparison.resolve("bar_head_powerpct_1_0_20_other", snapshot, settings));
    }

    @Test
    void legacyStatTextKeepsNativeProviderKeysAndLastValue() {
        Map<String, BigDecimal> parsed =
                Comparison.parseStats(
                        " armor = 1.2;attack%=5;crit_damage=7;armor=2;broken;nan=NaN ");
        assertEquals(
                Map.of("armor", decimal("2"), "attack%", decimal("5"), "crit_damage", decimal("7")),
                parsed);
        assertThrows(UnsupportedOperationException.class, () -> parsed.put("x", BigDecimal.ONE));
    }
}
