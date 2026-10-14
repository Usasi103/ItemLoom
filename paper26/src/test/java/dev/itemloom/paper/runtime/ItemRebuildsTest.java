package dev.itemloom.paper.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemloom.api.ItemLoom;
import dev.itemloom.api.ItemRebuildResult;
import dev.itemloom.core.ItemIdentity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class ItemRebuildsTest {
    private static final String PDC = ItemRebuilds.PDC_ROOT;

    @Test
    void overridesReplaceChangeAddAndRemoveWithoutTouchingTheCurrentRolls() {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("quality", "common");
        current.put("damage", "10");
        current.put("owner", "Alice");
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("damage", "17.5");
        overrides.put("owner", null);
        overrides.put("grade", "A");

        Map<String, String> merged = ItemRebuilds.mergeRolls(current, overrides);

        assertEquals("common", merged.get("quality"));
        assertEquals("17.5", merged.get("damage"));
        assertFalse(merged.containsKey("owner"));
        assertEquals("A", merged.get("grade"));
        assertEquals("10", current.get("damage"));
        assertEquals("Alice", current.get("owner"));
    }

    @Test
    void nullOverrideMapKeepsEveryRoll() {
        Map<String, String> merged = ItemRebuilds.mergeRolls(Map.of("quality", "rare"), null);

        assertEquals(Map.of("quality", "rare"), merged);
    }

    @Test
    void nullOverrideNameIsRejected() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put(null, "x");

        assertThrows(
                IllegalArgumentException.class,
                () -> ItemRebuilds.mergeRolls(Map.of("quality", "rare"), overrides));
    }

    @Test
    void itemWithoutIdentityFailsAsNotItemLoom() {
        ItemRebuildResult result =
                ItemRebuilds.rejection(Optional.empty(), Set.of("example_sword")).orElseThrow();

        assertEquals(ItemRebuildResult.Status.NOT_ITEMLOOM, result.status());
        assertFalse(result.rebuilt());
        assertTrue(result.item().isEmpty());
    }

    @Test
    void identityWhoseIdIsNotConfiguredFailsAsUnknownId() {
        Optional<ItemIdentity> identity =
                Optional.of(new ItemIdentity("Removed_Sword", Map.of("quality", "rare")));

        ItemRebuildResult result =
                ItemRebuilds.rejection(identity, Set.of("example_sword")).orElseThrow();

        assertEquals(ItemRebuildResult.Status.UNKNOWN_ID, result.status());
        assertTrue(result.message().contains("Removed_Sword"));
    }

    @Test
    void definedIdPassesTheRejectionCheck() {
        Optional<ItemIdentity> identity =
                Optional.of(new ItemIdentity("example_sword", Map.of("quality", "rare")));

        assertTrue(ItemRebuilds.rejection(identity, Set.of("example_sword")).isEmpty());
    }

    @Test
    void namedPdcKeysComeFromTheOriginalAndTheRestFromTheCandidate() {
        CompoundTag before = pdc("plugin:keep", "old");
        before.put("other:keep", StringTag.valueOf("drop"));
        CompoundTag candidate = pdc("plugin:keep", "new");
        candidate.put("plugin:fresh", IntTag.valueOf(4));

        CompoundTag result =
                ItemRebuilds.keepPdc(root(before), root(candidate), Set.of("plugin:keep"));
        CompoundTag values = result.getCompoundOrEmpty(PDC);

        assertEquals("old", values.getString("plugin:keep").orElse(""));
        assertEquals(4, values.getInt("plugin:fresh").orElse(0));
        assertFalse(values.contains("other:keep"));
    }

    @Test
    void namedPdcKeyAbsentFromTheOriginalIsRemovedFromTheCandidate() {
        CompoundTag before = new CompoundTag();
        CompoundTag candidate = pdc("plugin:keep", "new");

        CompoundTag result = ItemRebuilds.keepPdc(before, root(candidate), Set.of("plugin:keep"));

        assertFalse(result.contains(PDC));
    }

    @Test
    void keepAllCopiesTheWholeOriginalPdc() {
        CompoundTag before = pdc("plugin:keep", "old");
        before.put("other:keep", StringTag.valueOf("kept"));
        CompoundTag candidate = pdc("plugin:fresh", "new");

        CompoundTag result =
                ItemRebuilds.keepPdc(
                        root(before), root(candidate), Set.of(ItemLoom.KEEP_ALL_PDC));

        assertEquals(before, result.getCompoundOrEmpty(PDC));
    }

    @Test
    void keepAllWithoutOriginalPdcRemovesTheCandidatePdc() {
        CompoundTag candidate = pdc("plugin:fresh", "new");

        CompoundTag result =
                ItemRebuilds.keepPdc(
                        new CompoundTag(), root(candidate), Set.of(ItemLoom.KEEP_ALL_PDC));

        assertFalse(result.contains(PDC));
    }

    @Test
    void emptyKeepSetLeavesTheCandidateUnchanged() {
        CompoundTag before = pdc("plugin:keep", "old");
        CompoundTag candidate = root(pdc("plugin:fresh", "new"));

        CompoundTag result = ItemRebuilds.keepPdc(root(before), candidate, Set.of());

        assertEquals(candidate, result);
    }

    @Test
    void keepPdcDoesNotMutateItsInputs() {
        CompoundTag beforeRoot = root(pdc("plugin:keep", "old"));
        CompoundTag candidateRoot = root(pdc("plugin:keep", "new"));
        CompoundTag beforeCopy = beforeRoot.copy();
        CompoundTag candidateCopy = candidateRoot.copy();

        ItemRebuilds.keepPdc(beforeRoot, candidateRoot, Set.of("plugin:keep"));

        assertEquals(beforeCopy, beforeRoot);
        assertEquals(candidateCopy, candidateRoot);
    }

    private static CompoundTag pdc(String key, String value) {
        CompoundTag values = new CompoundTag();
        values.put(key, StringTag.valueOf(value));
        return values;
    }

    private static CompoundTag root(CompoundTag pdcValues) {
        CompoundTag root = new CompoundTag();
        if (!pdcValues.isEmpty()) {
            root.put(PDC, pdcValues);
        }
        return root;
    }
}
