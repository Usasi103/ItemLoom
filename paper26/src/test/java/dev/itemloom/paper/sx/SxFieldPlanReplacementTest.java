package dev.itemloom.paper.sx;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemloom.compat.sx.SxConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SxFieldPlanReplacementTest {
    @Test
    void preparingThePlanDoesNotEvaluateOrValidateDynamicFields() {
        assertDoesNotThrow(
                () ->
                        new SxFieldPlan(
                                new SxConfig(
                                        Map.of(
                                                "Amount",
                                                "<unknown:failure>",
                                                "Durability",
                                                "not a number",
                                                "ItemFlagList",
                                                List.of("unknown flag"),
                                                "Attributes",
                                                List.of("invalid"),
                                                "Potion",
                                                Map.of(
                                                        "<unknown:effect>",
                                                        Map.of("duration", "invalid")),
                                                "CustomModelData",
                                                "invalid"))));
    }

    @Test
    void durabilitySupportsAbsoluteRemainingAndPercentageValues() {
        assertEquals(9, SxFieldPlan.damage("9", 100));
        assertEquals(0, SxFieldPlan.damage("-2", 100));
        assertEquals(75, SxFieldPlan.damage("<25", 100));
        assertEquals(75, SxFieldPlan.damage("25%", 101));
        assertEquals(0, SxFieldPlan.damage("150%", 100));
        assertEquals(200, SxFieldPlan.damage("-100%", 100));
    }

    @Test
    void durabilityRetainsJavaShortNarrowingAndWrapperFailures() {
        assertEquals(0, SxFieldPlan.damage("0%", 40000));
        assertEquals(0, SxFieldPlan.damage("<-1", 32767));
        assertEquals(1, SxFieldPlan.damage("<0", 65537));
        assertEquals(0, SxFieldPlan.damage("NaN%", 100));
        assertEquals(0, SxFieldPlan.damage("Infinity%", 100));
        for (String invalid : List.of("32768", "<32768", "oops%", " 2")) {
            assertThrows(NumberFormatException.class, () -> SxFieldPlan.damage(invalid, 100));
        }
    }

    @Test
    void everyAmpersandIsConvertedIncludingNonColorCodes() {
        assertEquals("\u00a7aName \u00a7z \u00a7\u00a7", SxFieldPlan.colors("&aName &z &&"));
    }

    @Test
    void enchantmentsSplitAtTheLastColonAndRetainZeroLevels() {
        assertEquals(
                new SxFieldPlan.EnchantmentInput("minecraft:sharpness", 7),
                SxFieldPlan.EnchantmentInput.parse("minecraft:sharpness:7"));
        assertEquals(
                new SxFieldPlan.EnchantmentInput("DAMAGE_ALL", 0),
                SxFieldPlan.EnchantmentInput.parse("DAMAGE_ALL:0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SxFieldPlan.EnchantmentInput.parse("sharpness"));
        assertThrows(
                NumberFormatException.class,
                () -> SxFieldPlan.EnchantmentInput.parse("sharpness:"));
    }

    @Test
    void attributeNamesAndSlotsHaveTheirSpecifiedAliases() {
        assertEquals(
                new SxFieldPlan.AttributeInput("attack_damage", 2.5, 1, "mainhand"),
                SxFieldPlan.AttributeInput.parse("GENERIC_ATTACK_DAMAGE:2.5:1:HAND"));
        assertEquals(
                new SxFieldPlan.AttributeInput("jump_strength", 0, 2, "offhand"),
                SxFieldPlan.AttributeInput.parse("horse_jump_strength:0:2:off_hand"));
        assertEquals(
                new SxFieldPlan.AttributeInput("spawn_reinforcements", -2, 0, "any"),
                SxFieldPlan.AttributeInput.parse("zombie_spawn_reinforcements:-2:0"));
        assertEquals(
                "horse_jump_strength",
                SxFieldPlan.AttributeInput.parse("generic_horse_jump_strength:1:0").name());
    }

    @Test
    void attributeSplittingDropsTrailingEmptySlotsAndRejectsInvalidNumbers() {
        assertEquals("any", SxFieldPlan.AttributeInput.parse("armor:1:0::").slot());
        for (String invalid :
                List.of(
                        "armor:1",
                        "armor:1:0:hand:extra",
                        "armor:NaN:0",
                        "armor:Infinity:0",
                        "armor:1:-1",
                        "armor:1:3",
                        "armor:1:bad")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SxFieldPlan.AttributeInput.parse(invalid),
                    invalid);
        }
    }

    @Test
    void profileUsesShortNamesAndStandardFourIntegerUuidLayout() {
        assertEquals(
                Map.of("name", "1234567890123456"), SxFieldPlan.profileValue("1234567890123456"));
        assertEquals(Map.of("name", ""), SxFieldPlan.profileValue(""));
        Map<String, Object> profile =
                SxFieldPlan.profileValue("00112233-4455-6677-8899-aabbccddeeff");
        assertArrayEquals(
                new int[] {0x00112233, 0x44556677, 0x8899aabb, 0xccddeeff},
                (int[]) profile.get("id"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SxFieldPlan.profileValue("12345678901234567"));
    }

    @Test
    void rootModifierUuidsConvertAnyLengthWithoutChangingInputs() {
        Map<Object, Object> modifier = new LinkedHashMap<>();
        modifier.put("UUID", List.of(1, "-2", 3L));
        modifier.put(7, "retained key");
        Map<String, Object> unchanged = Map.of("Name", "another");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("AttributeModifiers", List.of(modifier, unchanged, "literal"));
        input.put("other", "retained value");
        Map<String, Object> result = SxFieldPlan.nbtValues(input);
        List<?> modifiers = (List<?>) result.get("AttributeModifiers");
        Map<?, ?> converted = (Map<?, ?>) modifiers.getFirst();
        assertNotSame(input, result);
        assertNotSame(modifier, converted);
        assertArrayEquals(new int[] {1, -2, 3}, (int[]) converted.get("UUID"));
        assertEquals("retained key", converted.get(7));
        assertSame(unchanged, modifiers.get(1));
        assertEquals("literal", modifiers.get(2));
        assertEquals("retained value", result.get("other"));
        assertEquals(List.of(1, "-2", 3L), modifier.get("UUID"));
    }

    @Test
    void nbtConversionOnlyVisitsRootModifiersAndPreservesExistingArrays() {
        int[] existing = {1, 2, 3, 4};
        Map<String, Object> arrayModifier = Map.of("UUID", existing);
        Map<String, Object> nested =
                Map.of("AttributeModifiers", List.of(Map.of("UUID", List.of(4))));
        Map<String, Object> input =
                Map.of(
                        "AttributeModifiers",
                        List.of(arrayModifier, Map.of("UUID", List.of())),
                        "nested",
                        nested);
        Map<String, Object> result = SxFieldPlan.nbtValues(input);
        List<?> modifiers = (List<?>) result.get("AttributeModifiers");
        assertSame(arrayModifier, modifiers.getFirst());
        assertSame(nested, result.get("nested"));
        assertArrayEquals(new int[0], (int[]) ((Map<?, ?>) modifiers.get(1)).get("UUID"));
        Map<String, Object> noRootModifiers = Map.of("nested", nested);
        assertSame(noRootModifiers, SxFieldPlan.nbtValues(noRootModifiers));
    }

    @Test
    void nbtUuidPartsMustBeIntegerText() {
        Map<String, Object> invalid =
                Map.of("AttributeModifiers", List.of(Map.of("UUID", List.of("1.5"))));
        assertThrows(NumberFormatException.class, () -> SxFieldPlan.nbtValues(invalid));
    }
}
