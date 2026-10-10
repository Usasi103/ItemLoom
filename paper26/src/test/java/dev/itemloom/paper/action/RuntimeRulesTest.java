package dev.itemloom.paper.action;

import static org.junit.jupiter.api.Assertions.*;
import dev.itemloom.compat.ni.NiConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RuntimeRulesTest {
    @Test
    void customDurabilitySeparatesRetainedBreakingUnitFromBrokenUsableState() {
        var surviving = DurabilityOutcome.change(10, 20, 100, 3, true, true);
        assertEquals(7, surviving.remaining());
        assertEquals(65, surviving.displayedDamage());
        assertEquals(1, surviving.count());
        assertFalse(surviving.exhausted());
        var unbreakable = DurabilityOutcome.change(2, 20, 100, 3, false, true);
        assertEquals(0, unbreakable.remaining());
        assertEquals(99, unbreakable.displayedDamage());
        assertEquals(1, unbreakable.count());
        for (boolean remove : List.of(false, true)) {
            var breaking = DurabilityOutcome.change(2, 20, 100, 3, true, remove);
            assertEquals(2, breaking.remaining());
            assertNull(breaking.displayedDamage());
            assertEquals(remove ? 0 : 1, breaking.count());
            assertTrue(breaking.exhausted());
        }
        assertEquals(0, DurabilityOutcome.change(1, 2, 0, 1, false, false).displayedDamage());
        assertThrows(
                IllegalArgumentException.class,
                () -> DurabilityOutcome.change(2, 0, 100, 1, false, false));
    }

    @Test
    void unbreakingTrialsHaveSpecifiedProbabilityAndSupportExtremeLevels() {
        assertEquals(9, DurabilityOutcome.effectiveDamage(9, 0, new Random(3)));
        assertEquals(0, DurabilityOutcome.effectiveDamage(9, Integer.MAX_VALUE, new Random(3)));
        long total = 0;
        Random random = new Random(472);
        for (int sample = 0; sample < 10000; sample++)
            total += DurabilityOutcome.effectiveDamage(40, 3, random);
        assertTrue(total > 98500 && total < 101500, "binomial expected mean is ten per request");
    }

    @Test
    void offsetGrammarDistinguishesMalformedTextFromInvalidNumericRanges() {
        Random random = new Random(123);
        for (String malformed : List.of("", "garbage", "-1", "x-2", "1-x", "1-2-3"))
            assertEquals(0.1, DropMotion.offset(malformed, random));
        for (String invalid : List.of("2-1", "1-1", "1--2"))
            assertThrows(IllegalArgumentException.class, () -> DropMotion.offset(invalid, random));
        assertEquals(2.5, DropMotion.offset(" 2.5 ", random));
        assertTrue(Double.isNaN(DropMotion.offset("NaN", random)));
        assertEquals(Double.POSITIVE_INFINITY, DropMotion.offset("Infinity", random));
        for (int sample = 0; sample < 100; sample++) {
            double value = DropMotion.offset("0.2-0.8", random);
            assertTrue(value >= 0.2 && value < 0.8);
        }
    }

    @Test
    void oneDropPlanUsesOriginalListIndexesAndIndependentRandomAxes() {
        Random random = new Random(4);
        assertNull(DropMotion.prepare(null, "2-1", "round", random));
        assertNull(DropMotion.prepare("2-1", null, "round", random));
        assertNull(DropMotion.prepare("2-1", "1", null, random));
        var round = DropMotion.prepare("2", "0.5", "round", random);
        var east = round.at(0, 4, random);
        var north = round.at(1, 4, random);
        var west = round.at(2, 4, random);
        assertEquals(2, east.x());
        assertEquals(-2, north.z(), 1e-12);
        assertEquals(-2, west.x(), 1e-12);
        assertEquals(east.y(), north.y());
        assertEquals(
                new DropMotion.Velocity(2, 0.5, 0),
                new DropMotion(2, 0.5, "other").at(8, 9, random));
        var scatter = new DropMotion(2, 0.5, "random").at(0, 1, new Random(9));
        assertNotEquals(4, scatter.x() * scatter.x() + scatter.z() * scatter.z(), 1e-6);
    }

    @Test
    void consumptionConservesUnchangedUnitsAndNeverCombinesChargesAcrossUnits() {
        assertEquals(new TriggerRules.Deduction(1, 3, 2), TriggerRules.deduct(3, 5, 2));
        assertEquals(new TriggerRules.Deduction(0, 0, 2), TriggerRules.deduct(3, 2, 2));
        assertNull(TriggerRules.deduct(8, 2, 3));
        assertNull(TriggerRules.deduct(3, null, 4));
        assertNull(TriggerRules.deduct(3, 5, 0));
        assertNull(TriggerRules.deduct(3, null, -1));
        assertEquals(new TriggerRules.Deduction(0, null, 0), TriggerRules.deduct(3, null, 3));
        assertEquals(new TriggerRules.Deduction(1, null, 0), TriggerRules.deduct(3, null, 2));
        for (int count = 1; count <= 12; count++)
            for (int charge = 1; charge <= 12; charge++)
                for (int consumed = 1; consumed <= charge; consumed++) {
                    var plan = TriggerRules.deduct(count, charge, consumed);
                    assertEquals(
                            (long) count * charge - consumed,
                            (long) plan.returnedCount() * charge
                                    + (long) plan.affectedCount() * plan.charge());
                }
    }

    @Test
    void amountsAndTickVisitsUseConfiguredDiscreteRules() {
        assertEquals(1, TriggerRules.amount(null));
        for (String malformed : List.of("", " 2", "2 ", "2.0", "2147483648", "x"))
            assertEquals(1, TriggerRules.amount(malformed));
        assertEquals(2, TriggerRules.amount("+2"));
        assertEquals(-2, TriggerRules.amount("-2"));
        long remaining = 0;
        List<Integer> visits = new ArrayList<>();
        for (int visit = 1; visit <= 7; visit++) {
            if (TriggerRules.skipVisit(2, remaining)) remaining--;
            else {
                visits.add(visit);
                remaining = 2;
            }
        }
        assertEquals(List.of(1, 4, 7), visits);
        assertFalse(TriggerRules.skipVisit(0, 9));
        assertFalse(TriggerRules.skipVisit(-1, 9));
    }

    @Test
    void legacyNormalizationIsDetachedSelectiveAndPreservesUnknownTopLevelKeys() {
        NiConfig source =
                new NiConfig(
                        Map.of(
                                "left",
                                List.of("tell: hi"),
                                "all",
                                "tell: all",
                                "right",
                                Map.of("sync", "already"),
                                "cooldown",
                                7,
                                "group",
                                "pair",
                                "consume",
                                Map.of("left", true, "amount", 2),
                                "unknown",
                                List.of("keep")));
        assertSame(source, TriggerRules.normalize(source, false));
        NiConfig result = TriggerRules.normalize(source, true);
        assertEquals(List.of("tell: hi"), result.strings("left.sync"));
        assertEquals(List.of(), result.strings("all.sync"));
        assertEquals(2, result.get("left.consume.amount"));
        assertEquals(2, result.get("all.consume.amount"));
        assertEquals(7, result.get("left.cooldown"));
        assertEquals("pair", result.get("all.group"));
        assertEquals(source.get("right"), result.get("right"));
        assertEquals(source.get("unknown"), result.get("unknown"));
        assertFalse(result.contains("cooldown"));
        assertFalse(result.contains("consume"));
        assertTrue(source.contains("consume"));
        assertEquals(List.of("tell: hi"), source.get("left"));
        NiConfig modern = new NiConfig(Map.of("left", Map.of("sync", "hello"), "cooldown", 4));
        assertSame(modern, TriggerRules.normalize(modern, true));
        NiConfig defaultAmount =
                TriggerRules.normalize(
                        new NiConfig(Map.of("left", "hello", "consume", Map.of("left", true))),
                        true);
        assertEquals(Map.of(), defaultAmount.get("left.consume"));
        for (Object scalar : List.of("tell: scalar", 17, true))
            assertEquals(
                    List.of(),
                    TriggerRules.normalize(new NiConfig(Map.of("left", scalar)), true)
                            .get("left.sync"));
    }
}
