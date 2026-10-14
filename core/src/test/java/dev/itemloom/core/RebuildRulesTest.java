package dev.itemloom.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RebuildRulesTest {
    @Test
    void keptStateIsTheOriginalValueWhenItFitsTheNewMaximum() {
        assertEquals(120, RebuildRules.keptState(120, 250));
        assertEquals(250, RebuildRules.keptState(250, 250));
    }

    @Test
    void keptStateIsClampedToTheNewMaximumAndNeverNegative() {
        assertEquals(100, RebuildRules.keptState(300, 100));
        assertEquals(0, RebuildRules.keptState(-5, 100));
    }

    @Test
    void keptStateIsDroppedWithoutAPositiveMaximum() {
        assertNull(RebuildRules.keptState(120, null));
        assertNull(RebuildRules.keptState(120, 0));
        assertNull(RebuildRules.keptState(120, -1));
    }

    @Test
    void missingOverrideReportsTheFirstRequestedValueThatTheRebuildDoesNotCarry() {
        Map<String, String> rebuilt = Map.of("quality", "rare", "damage", "17.5");
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("quality", "rare");
        overrides.put("damage", "20");

        assertEquals("damage", RebuildRules.missingOverride(rebuilt, overrides));
    }

    @Test
    void missingOverrideIsNullWhenEveryRequestedValueIsCarried() {
        Map<String, String> rebuilt = Map.of("quality", "rare");

        assertNull(RebuildRules.missingOverride(rebuilt, Map.of("quality", "rare")));
        assertNull(RebuildRules.missingOverride(rebuilt, null));
    }

    @Test
    void nullOverridesRemoveRollsAndAreNotChecked() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("owner", null);

        assertNull(RebuildRules.missingOverride(Map.of("quality", "rare"), overrides));
    }
}
