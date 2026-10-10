package dev.itemloom.probe;

import dev.itemloom.paper.display.DisplayLedger;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.item.component.SulfurCubeContent;
import org.bukkit.plugin.java.JavaPlugin;

/** Additional R3 ownership and proof-expiry cases using the actual server's NMS value types. */
public final class DisplayProofProbe {
    private static final HashedPatchMap.HashGenerator HASHES = Object::hashCode;

    private DisplayProofProbe() {}

    public static Map<String, Object> run(JavaPlugin owner) {
        var checks = new Checks(owner);
        checks.group("authorization", () -> authorization(checks));
        checks.group("mutable-values", () -> mutableValues(checks));
        checks.group("component-payloads", () -> componentPayloads(checks));
        checks.group("nested-items", () -> nestedItems(checks));
        checks.group("click-claims", () -> clickClaims(checks));
        checks.group("bounded-retention", () -> boundedRetention(checks));
        return Map.of(
                "passed", checks.failures.isEmpty(),
                "checks", checks.assertions.size(),
                "assertions", List.copyOf(checks.assertions),
                "failures", Map.copyOf(checks.failures),
                "realClient", false,
                "boundary",
                        "NMS display proof unit scenarios; network write callbacks are covered by ItemDisplayProbe");
    }

    private static void authorization(Checks c) {
        var ledger = new DisplayLedger();
        var original = named("canonical");
        var proof = ledger.prepare(original, named("appearance"));
        var repeated =
                ledger.prepare(original.copyWithCount(2), named("appearance").copyWithCount(9));
        c.check(
                proof.token().equals(repeated.token()),
                "equivalent preparations share a count-independent token");
        c.check(
                ledger.size() == 0 && ledger.restore(proof.display()) == null,
                "preparation without successful transmission creates no authorization");
        c.check(!ledger.knownOriginal(original), "unsent managed canonical item is not known");
        var foreign = new DisplayLedger();
        foreign.commit(proof);
        c.check(
                foreign.size() == 0 && foreign.restore(proof.display()) == null,
                "another connection cannot commit or restore a prepared proof");
        ledger.commit(
                new DisplayLedger.Sent(named("forged canonical"), proof.display(), proof.token()));
        c.check(
                ledger.size() == 0,
                "a recognized token cannot commit different canonical components");
        ledger.commit(proof);
        var received = proof.display().copyWithCount(37);
        var restored = ledger.restore(received);
        c.check(
                restored != null
                        && restored.getCount() == 37
                        && ItemStack.isSameItemSameComponents(restored, original),
                "successful display proof restores canonical data and retains incoming quantity");
        received.set(DataComponents.REPAIR_COST, 3);
        c.check(
                ledger.restore(received) == null,
                "one altered component invalidates creative return");
        received = proof.display();
        received.setItem(Items.DIRT);
        c.check(
                ledger.restore(received) == null,
                "a valid token on a different material is rejected");
        received = proof.display();
        received.get(DataComponents.CUSTOM_DATA).getUnsafe().putInt(DisplayLedger.MARKER, 1);
        c.check(
                DisplayLedger.marked(received) && ledger.restore(received) == null,
                "malformed marker remains protected and cannot authorize return");
        var same = new DisplayLedger();
        same.commit(DisplayLedger.canonical(original));
        c.check(
                same.knownOriginal(original.copyWithCount(64))
                        && !same.knownOriginal(named("forged managed")),
                "unchanged successful sends authorize only their exact canonical components");
        var beforeClear = ledger.prepare(original, named("pending after success"));
        ledger.clear();
        ledger.commit(beforeClear);
        ledger.commit(proof);
        c.check(
                ledger.size() == 0 && ledger.restore(proof.display()) == null,
                "clear invalidates prepared and committed work even if their callbacks arrive later");
    }

    private static void mutableValues(Checks c) {
        var ledger = new DisplayLedger();
        var original = named("canonical");
        var appearance = named("display");
        var tag = new CompoundTag();
        tag.putString("saved", "original");
        original.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        appearance.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        original.set(
                DataComponents.LORE,
                new ItemLore(
                        List.of(Component.literal("raw")), List.of(Component.literal("styled"))));
        original.set(
                DataComponents.TOOLTIP_DISPLAY,
                new TooltipDisplay(false, new LinkedHashSet<>(List.of(DataComponents.LORE))));
        var explosion =
                new FireworkExplosion(
                        FireworkExplosion.Shape.SMALL_BALL,
                        new IntArrayList(new int[] {0x123456}),
                        new IntArrayList(new int[] {0xabcdef}),
                        true,
                        true);
        original.set(DataComponents.FIREWORK_EXPLOSION, explosion);
        original.set(DataComponents.FIREWORKS, new Fireworks(2, List.of(explosion)));
        var expected = ledger.prepare(original, appearance);
        explosion.colors().clear();
        explosion.fadeColors().clear();
        ((MutableComponent) original.get(DataComponents.CUSTOM_NAME)).append(" changed input");
        original.get(DataComponents.CUSTOM_DATA).getUnsafe().putString("saved", "changed input");
        ((MutableComponent) original.get(DataComponents.LORE).styledLines().getFirst())
                .append(" input");
        original.get(DataComponents.TOOLTIP_DISPLAY).hiddenComponents().clear();
        ((MutableComponent) appearance.get(DataComponents.CUSTOM_NAME)).append(" changed input");
        appearance.get(DataComponents.CUSTOM_DATA).getUnsafe().putString("saved", "changed input");
        c.check(
                expected.original().get(DataComponents.CUSTOM_NAME).getString().equals("canonical")
                        && expected.display()
                                .get(DataComponents.CUSTOM_NAME)
                                .getString()
                                .equals("display"),
                "mutating supplied text does not alter a prepared proof");
        c.check(
                expected.original()
                                .get(DataComponents.CUSTOM_DATA)
                                .getUnsafe()
                                .getStringOr("saved", "")
                                .equals("original")
                        && expected.display()
                                .get(DataComponents.CUSTOM_DATA)
                                .getUnsafe()
                                .getStringOr("saved", "")
                                .equals("original"),
                "mutating supplied NBT does not alter canonical or display evidence");
        var exportedOriginal = expected.original();
        var exportedDisplay = expected.display();
        ((MutableComponent) exportedOriginal.get(DataComponents.CUSTOM_NAME))
                .append(" returned edit");
        ((MutableComponent) exportedDisplay.get(DataComponents.CUSTOM_NAME))
                .append(" returned edit");
        exportedOriginal.get(DataComponents.CUSTOM_DATA).getUnsafe().remove("saved");
        exportedDisplay.get(DataComponents.CUSTOM_DATA).getUnsafe().remove(DisplayLedger.MARKER);
        ((MutableComponent) exportedOriginal.get(DataComponents.LORE).styledLines().getFirst())
                .append(" output");
        exportedOriginal.get(DataComponents.TOOLTIP_DISPLAY).hiddenComponents().clear();
        exportedOriginal.get(DataComponents.FIREWORK_EXPLOSION).colors().clear();
        exportedOriginal.get(DataComponents.FIREWORKS).explosions().getFirst().fadeColors().clear();
        c.check(
                expected.original().get(DataComponents.CUSTOM_NAME).getString().equals("canonical")
                        && expected.display()
                                .get(DataComponents.CUSTOM_NAME)
                                .getString()
                                .equals("display")
                        && DisplayLedger.marked(expected.display()),
                "mutable values obtained through Sent accessors cannot poison a later commit");
        ledger.commit(expected);
        var restored = ledger.restore(expected.display());
        c.check(
                restored.get(DataComponents.FIREWORK_EXPLOSION).colors().getInt(0) == 0x123456
                        && restored.get(DataComponents.FIREWORKS)
                                        .explosions()
                                        .getFirst()
                                        .fadeColors()
                                        .getInt(0)
                                == 0xabcdef,
                "firework color collections are detached from supplied and exported evidence");
        restored.get(DataComponents.FIREWORKS).explosions().getFirst().colors().clear();
        c.check(
                ledger.restore(expected.display())
                                .get(DataComponents.FIREWORKS)
                                .explosions()
                                .getFirst()
                                .colors()
                                .getInt(0)
                        == 0x123456,
                "mutating restored fireworks cannot alter retained evidence");
        c.check(
                restored.get(DataComponents.TOOLTIP_DISPLAY)
                        .hiddenComponents()
                        .contains(DataComponents.LORE),
                "tooltip hidden component collections are independently owned at every boundary");
        c.check(
                restored.get(DataComponents.LORE).lines().getFirst().getString().equals("raw")
                        && restored.get(DataComponents.LORE)
                                .styledLines()
                                .getFirst()
                                .getString()
                                .equals("styled"),
                "copying preserves distinct raw and styled lore channels");
        ((MutableComponent) restored.get(DataComponents.CUSTOM_NAME)).append(" restoration edit");
        restored.get(DataComponents.CUSTOM_DATA).getUnsafe().putString("saved", "restoration edit");
        c.check(
                ledger.restore(expected.display())
                                .get(DataComponents.CUSTOM_NAME)
                                .getString()
                                .equals("canonical")
                        && ledger.restore(expected.display())
                                .get(DataComponents.CUSTOM_DATA)
                                .getUnsafe()
                                .getStringOr("saved", "")
                                .equals("original"),
                "restored item mutation cannot alter retained canonical evidence");
        var direct = named("direct canonical");
        var canonical = DisplayLedger.canonical(direct);
        ((MutableComponent) direct.get(DataComponents.CUSTOM_NAME)).append(" changed");
        ledger.commit(canonical);
        c.check(
                ledger.knownOriginal(named("direct canonical")) && !ledger.knownOriginal(direct),
                "canonical send records own their values before successful-write commit");
    }

    private static void nestedItems(Checks c) {
        var ledger = new DisplayLedger();
        var proof = ledger.prepare(named("original"), named("display"));
        ledger.commit(proof);
        var child = ItemStackTemplate.fromNonEmptyStack(proof.display());
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(
                DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(proof.display())));
        var bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(child)));
        var bow = new ItemStack(Items.CROSSBOW);
        bow.set(DataComponents.CHARGED_PROJECTILES, new ChargedProjectiles(List.of(child)));
        var remainder = new ItemStack(Items.MILK_BUCKET);
        remainder.set(DataComponents.USE_REMAINDER, new UseRemainder(child));
        var sulfur = new ItemStack(Items.STONE);
        sulfur.set(DataComponents.SULFUR_CUBE_CONTENT, new SulfurCubeContent(child));
        for (var container : List.of(box, bundle, bow, remainder, sulfur))
            c.check(
                    DisplayLedger.hasNestedMarker(container),
                    "nested marked child is rejected for " + container.getItem());
        var altered = proof.display();
        altered.set(DataComponents.USE_REMAINDER, new UseRemainder(child));
        c.check(
                ledger.restore(altered) == null,
                "a recognized root marker cannot conceal a marked child");
        ItemStack deep = named("leaf");
        for (int i = 0; i < 64; i++) {
            var parent = new ItemStack(Items.STONE);
            parent.set(
                    DataComponents.USE_REMAINDER,
                    new UseRemainder(ItemStackTemplate.fromNonEmptyStack(deep)));
            deep = parent;
        }
        c.check(
                DisplayLedger.hasNestedMarker(deep),
                "excessive nesting rejects conservatively without recursion");
        var wideChild = new ItemStack(Items.CROSSBOW);
        wideChild.set(
                DataComponents.CHARGED_PROJECTILES,
                new ChargedProjectiles(
                        java.util.Collections.nCopies(
                                1024, ItemStackTemplate.fromNonEmptyStack(named("unmarked")))));
        var wide = new ItemStack(Items.CROSSBOW);
        var wideTemplate = ItemStackTemplate.fromNonEmptyStack(wideChild);
        wide.set(
                DataComponents.CHARGED_PROJECTILES,
                new ChargedProjectiles(List.of(wideTemplate, wideTemplate)));
        c.check(
                DisplayLedger.hasNestedMarker(wide),
                "excessive child count rejects conservatively");
        var unmarkedChild = named("nested canonical");
        var outer = new ItemStack(Items.SHULKER_BOX);
        outer.set(
                DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(unmarkedChild)));
        var record = DisplayLedger.canonical(outer);
        ((MutableComponent) unmarkedChild.get(DataComponents.CUSTOM_NAME))
                .append(" external mutation");
        ledger.commit(record);
        c.check(
                !DisplayLedger.hasNestedMarker(record.original())
                        && record.original()
                                .get(DataComponents.CONTAINER)
                                .copyOne()
                                .get(DataComponents.CUSTOM_NAME)
                                .getString()
                                .equals("nested canonical"),
                "nested canonical snapshots own their child text");
    }

    private static void componentPayloads(Checks c) {
        isolated(
                c,
                DataComponents.BLOCK_STATE,
                new net.minecraft.world.item.component.BlockItemStateProperties(
                        new HashMap<>(Map.of("axis", "x"))),
                value -> value.properties().put("axis", "y"),
                "block state map");
        isolated(
                c,
                DataComponents.WRITABLE_BOOK_CONTENT,
                new net.minecraft.world.item.component.WritableBookContent(
                        new ArrayList<>(
                                List.of(
                                        net.minecraft.server.network.Filterable.passThrough(
                                                "page")))),
                value -> value.pages().clear(),
                "writable book pages");
        var text = Component.literal("attribute display");
        var entry =
                new net.minecraft.world.item.component.ItemAttributeModifiers.Entry(
                        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                        new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                                net.minecraft.resources.Identifier.parse("itemloom:probe"),
                                1,
                                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation
                                        .ADD_VALUE),
                        net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND,
                        new net.minecraft.world.item.component.ItemAttributeModifiers.Display
                                .OverrideText(text));
        isolated(
                c,
                DataComponents.ATTRIBUTE_MODIFIERS,
                new net.minecraft.world.item.component.ItemAttributeModifiers(
                        new ArrayList<>(List.of(entry))),
                value ->
                        ((MutableComponent)
                                        ((net.minecraft.world.item.component.ItemAttributeModifiers
                                                                .Display.OverrideText)
                                                        value.modifiers().getFirst().display())
                                                .component())
                                .append(" changed"),
                "attribute override text");
        var effect =
                new net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect(
                        new net.minecraft.world.effect.MobEffectInstance(
                                net.minecraft.world.effect.MobEffects.SPEED, 40));
        isolated(
                c,
                DataComponents.DEATH_PROTECTION,
                new net.minecraft.world.item.component.DeathProtection(
                        new ArrayList<>(List.of(effect))),
                value ->
                        ((net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect)
                                        value.deathEffects().getFirst())
                                .effects()
                                .getFirst()
                                .update(
                                        new net.minecraft.world.effect.MobEffectInstance(
                                                net.minecraft.world.effect.MobEffects.SPEED, 90)),
                "nested consume effect");
        isolated(
                c,
                DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        new ArrayList<>(
                                List.of(
                                        new net.minecraft.world.effect.MobEffectInstance(
                                                net.minecraft.world.effect.MobEffects.SPEED, 40))),
                        java.util.Optional.empty()),
                value ->
                        value.getAllEffects()
                                .iterator()
                                .next()
                                .update(
                                        new net.minecraft.world.effect.MobEffectInstance(
                                                net.minecraft.world.effect.MobEffects.SPEED, 90)),
                "potion effect");
        var bee = new CompoundTag();
        bee.putString("CustomName", "worker");
        isolated(
                c,
                DataComponents.BEES,
                new net.minecraft.world.item.component.Bees(
                        new ArrayList<>(
                                List.of(
                                        new net.minecraft.world.level.block.entity
                                                .BeehiveBlockEntity.Occupant(
                                                net.minecraft.world.item.component.TypedEntityData
                                                        .of(
                                                                net.minecraft.core.registries
                                                                        .BuiltInRegistries
                                                                        .ENTITY_TYPE
                                                                        .getValue(
                                                                                net.minecraft
                                                                                        .resources
                                                                                        .Identifier
                                                                                        .parse(
                                                                                                "minecraft:bee")),
                                                                bee),
                                                2,
                                                40)))),
                value ->
                        value.bees()
                                .getFirst()
                                .entityData()
                                .getUnsafe()
                                .putString("CustomName", "changed"),
                "bee entity NBT");
        for (var type : List.of(DataComponents.CAN_BREAK, DataComponents.CAN_PLACE_ON)) {
            var tag = new CompoundTag();
            tag.putInt("probe", 1);
            var predicate =
                    new net.minecraft.advancements.predicates.BlockPredicate(
                            java.util.Optional.empty(),
                            java.util.Optional.empty(),
                            java.util.Optional.of(
                                    new net.minecraft.advancements.predicates.NbtPredicate(tag)),
                            net.minecraft.advancements.predicates.DataComponentMatchers.ANY);
            isolated(
                    c,
                    type,
                    new net.minecraft.world.item.AdventureModePredicate(
                            new ArrayList<>(List.of(predicate))),
                    value ->
                            value.predicates
                                    .getFirst()
                                    .nbt()
                                    .orElseThrow()
                                    .tag()
                                    .putInt("probe", 2),
                    "adventure predicate " + type);
        }
    }

    private static <T> void isolated(
            Checks c,
            net.minecraft.core.component.DataComponentType<T> type,
            T value,
            java.util.function.Consumer<T> mutation,
            String description) {
        var ops =
                org.bukkit.craftbukkit.CraftRegistry.getMinecraftRegistry()
                        .createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        var expected = type.codecOrThrow().encodeStart(ops, value).getOrThrow().copy();
        var original = named("payload");
        original.set(type, value);
        var ledger = new DisplayLedger();
        var proof = ledger.prepare(original, named("display"));
        for (var candidate : List.of(original, proof.original())) {
            try {
                mutation.accept(candidate.get(type));
            } catch (UnsupportedOperationException immutable) {
                /* Immutable exports are also safe. */
            }
        }
        c.check(
                expected.equals(
                        type.codecOrThrow()
                                .encodeStart(ops, proof.original().get(type))
                                .getOrThrow()),
                description + " isolates input and Sent accessor mutation");
        ledger.commit(proof);
        var restored = ledger.restore(proof.display());
        c.check(
                restored != null
                        && expected.equals(
                                type.codecOrThrow()
                                        .encodeStart(ops, restored.get(type))
                                        .getOrThrow()),
                description + " survives proof commit and restoration");
        try {
            mutation.accept(restored.get(type));
        } catch (UnsupportedOperationException immutable) {
            /* Immutable exports are also safe. */
        }
        c.check(
                expected.equals(
                        type.codecOrThrow()
                                .encodeStart(ops, ledger.restore(proof.display()).get(type))
                                .getOrThrow()),
                description + " isolates restored mutation");
    }

    private static void clickClaims(Checks c) {
        var ledger = new DisplayLedger();
        var original = named("canonical-a");
        var otherOriginal = named("canonical-b");
        var proof = ledger.prepare(original, named("shared appearance"));
        var hash = HashedStack.create(proof.display().copyWithCount(5), HASHES);
        c.check(
                !ledger.acceptSent(hash).matches(original.copyWithCount(5), HASHES),
                "unsent appearance hash cannot broaden vanilla matching");
        ledger.commit(proof);
        ledger.commit(ledger.prepare(otherOriginal, named("shared appearance")));
        var accepted = ledger.acceptSent(hash);
        c.check(
                accepted.matches(original.copyWithCount(5), HASHES)
                        && !accepted.matches(original.copyWithCount(4), HASHES),
                "display hash is evaluated with actual canonical count");
        c.check(
                !accepted.matches(otherOriginal.copyWithCount(5), HASHES),
                "equal visible appearance cannot interchange different canonical identities");
        c.check(
                ledger.acceptSent(HashedStack.create(original, HASHES)).matches(original, HASHES),
                "canonical vanilla hash matching remains available");
        var actual = (HashedStack.ActualItem) hash;
        var added = new HashMap<>(actual.components().addedComponents());
        var removed = new HashSet<>(actual.components().removedComponents());
        var mutable =
                new HashedStack.ActualItem(
                        actual.item(), actual.count(), new HashedPatchMap(added, removed));
        var snapshot = ledger.acceptSent(mutable);
        added.clear();
        removed.clear();
        c.check(
                snapshot.matches(original.copyWithCount(5), HASHES),
                "incoming click component hash maps are detached before later validation");
        ledger.clear();
        c.check(
                !accepted.matches(original.copyWithCount(5), HASHES),
                "a retained click wrapper loses expanded authorization after clear");
    }

    private static void boundedRetention(Checks c) {
        var ledger = new DisplayLedger();
        var original = named("canonical");
        var initial = ledger.prepare(original, named("initial"));
        ledger.commit(initial);
        for (int i = 0; i < 4; i++)
            ledger.commit(ledger.prepare(original, named("appearance-" + i)));
        c.check(
                ledger.restore(initial.display()) == null,
                "fifth transmitted appearance expires oldest of four retained appearances");
        var valid = ledger.prepare(original, named("appearance-3"));
        var abandoned = ledger.prepare(named("abandoned"), named("appearance"));
        for (int i = 0; i < 2048; i++) ledger.prepare(named("pending-" + i), named("pending"));
        c.check(
                ledger.size() == 1 && ledger.restore(valid.display()) != null,
                "bounded pending work does not evict authorized canonical evidence");
        ledger.commit(abandoned);
        c.check(
                !ledger.knownOriginal(named("abandoned"))
                        && !ledger.prepare(named("abandoned"), named("appearance"))
                                .token()
                                .equals(abandoned.token()),
                "evicted preparations cannot authorize a late successful-write callback");
        for (int i = 0; i < 512; i++)
            ledger.commit(DisplayLedger.canonical(named("identity-" + i)));
        c.check(
                ledger.size() == 512
                        && !ledger.knownOriginal(original)
                        && ledger.restore(valid.display()) == null,
                "canonical identity limit expires associated marked returns");
        for (boolean emptyData : List.of(false, true)) {
            var exact = new DisplayLedger();
            var display = named("exact custom data");
            if (emptyData)
                display.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
            var proof = exact.prepare(original, display);
            exact.commit(proof);
            for (int i = 0; i < 2048; i++) exact.prepare(named("unrelated-" + i), named("pending"));
            c.check(
                    exact.prepare(original, display).token().equals(proof.token()),
                    "successful equivalent appearance keeps its marker after pending expiry: empty custom_data="
                            + emptyData);
        }
    }

    private static ItemStack named(String value) {
        var stack = new ItemStack(Items.STONE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(value));
        return stack;
    }

    private static final class Checks {
        final JavaPlugin owner;
        final List<String> assertions = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();

        Checks(JavaPlugin owner) {
            this.owner = owner;
        }

        void check(boolean condition, String description) {
            if (!condition) throw new AssertionError(description);
            assertions.add(description);
        }

        void group(String name, Runnable test) {
            try {
                test.run();
            } catch (Throwable failure) {
                failures.put(name, failure.toString());
                owner.getLogger()
                        .log(
                                java.util.logging.Level.WARNING,
                                "Display proof probe " + name,
                                failure);
            }
        }
    }
}
