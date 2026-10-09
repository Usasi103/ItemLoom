package dev.itemloom.probe;

import io.papermc.paper.adventure.PaperAdventure;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import dev.itemloom.compat.ni.NiCompiledItem;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.compat.NiItemMigration;
import dev.itemloom.paper.compat.NiItemSnapshot;
import dev.itemloom.paper.compat.NiPaperRecipe;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Real 26.2 snapshot/recipe round trips; no files, registrations, or player inventories are changed. */
final class ItemSaveProbe {
    private static final String SAVED_ID = "itemloom-save-probe-new-id";
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final NiItemMigration MIGRATION = new NiItemMigration();

    static Map<String, Object> run(JavaPlugin plugin, Plugin reference) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item save probe requires the server thread");
        Checks checks = new Checks();
        List<Map<String, Object>> referenceDifferences = new ArrayList<>();
        Reference ni = null;
        if (reference != null && reference.isEnabled()) {
            try {
                ni = new Reference(plugin, reference);
            } catch (ReflectiveOperationException error) {
                checks.failed("reference/setup", error);
            }
        }
        checks.group(
                "empty",
                () -> {
                    checks.that(
                            NiItemSnapshot.capture(null) == null, "null has no save definition");
                    checks.that(
                            NiItemSnapshot.capture(new ItemStack(Material.AIR)) == null,
                            "AIR has no save definition");
                    ItemStack zeroCount = new ItemStack(Material.STONE);
                    zeroCount.setAmount(0);
                    checks.that(
                            NiItemSnapshot.capture(zeroCount) == null,
                            "zero-count items have no save definition");
                });
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            for (Fixture fixture : fixtures(plugin)) {
                Reference installed = ni;
                checks.group(
                        fixture.name(),
                        () -> roundTrip(fixture, scripts, installed, checks, referenceDifferences));
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("passed", checks.failures.isEmpty());
        report.put(
                "independentPassed",
                checks.failures.keySet().stream().allMatch(name -> name.startsWith("reference/")));
        report.put(
                "referencePassed",
                ni != null
                        && checks.failures.keySet().stream()
                                .noneMatch(name -> name.startsWith("reference/")));
        report.put(
                "referenceSelfRoundTripPassed",
                ni != null
                        && checks.referenceDefects.isEmpty()
                        && referenceDifferences.isEmpty()
                        && checks.failures.keySet().stream()
                                .noneMatch(name -> name.startsWith("reference/")));
        report.put("checks", checks.attempted);
        report.put("passedChecks", checks.verified.size());
        report.put("verified", List.copyOf(checks.verified));
        report.put("failures", Map.copyOf(checks.failures));
        report.put("exceptionDetails", Map.copyOf(checks.exceptionDetails));
        report.put("nativeDifferences", Map.copyOf(checks.nativeDifferences));
        report.put("referenceFailureEvidence", Map.copyOf(checks.referenceFailureEvidence));
        report.put("referenceDefects", Map.copyOf(checks.referenceDefects));
        report.put("referenceAvailable", ni != null);
        report.put("referenceDifferences", referenceDifferences);
        report.put("server", plugin.getServer().getMinecraftVersion());
        report.put(
                "fixtures",
                "owned Bukkit/Craft/NMS stacks; in-memory YAML and actual NiPaperRecipe generation");
        report.put(
                "limits",
                List.of(
                        "Snapshot foundation only: no disk save, registry mutation, or save command is tested",
                        "Unregistered or non-persistent native components must fail instead of being silently discarded"));
        return report;
    }

    private static void roundTrip(
            Fixture fixture,
            NiScripts scripts,
            Reference reference,
            Checks checks,
            List<Map<String, Object>> referenceDifferences)
            throws Exception {
        ItemStack original = fixture.item();
        Tag before = full(original);
        NiConfig captured = NiItemSnapshot.capture(original);
        checks.that(
                captured != null && !captured.contains("amount"),
                fixture.name() + ": captures a definition without quantity");
        checks.that(
                before.equals(full(original)),
                fixture.name() + ": capture leaves the complete source stack unchanged");
        String yaml = NiYaml.write(new NiConfig(Map.of(SAVED_ID, captured.values())));
        NiConfig loaded = NiYaml.read(yaml, "in-memory snapshot.yml").section(SAVED_ID);
        List<String> warnings = new ArrayList<>();
        var recipe =
                new NiPaperRecipe(
                        new NiCompiledItem(SAVED_ID, "in-memory snapshot.yml", loaded),
                        new NiNodes(),
                        scripts,
                        null,
                        Clock.systemUTC(),
                        warnings::add);
        ItemStack rebuilt = recipe.create(new GenerationContext(Map.of(), new Random(7)));
        checks.that(
                warnings.isEmpty(),
                fixture.name() + ": the recipe accepts every saved component: " + warnings);
        checks.that(rebuilt.getAmount() == 1, fixture.name() + ": the definition creates one item");
        checks.sameItem(
                fixture.name(),
                original,
                rebuilt,
                yaml,
                fixture.name()
                        + ": save/YAML/load/recipe preserves native components and foreign NBT types");
        boolean identified = identified(original);
        ItemIdentity identity = CODEC.read(rebuilt).orElseThrow();
        String expectedId = identified ? identity(original).id() : SAVED_ID;
        checks.that(
                identity.id().equals(expectedId),
                fixture.name() + ": identity follows original NI save semantics");
        checks.that(
                !NmsItems.customData(rebuilt).contains("NeigeItems"),
                fixture.name() + ": generated item stores only independent identity");
        if (fixture.name().equals("typed-data")) {
            checks.that(
                    loaded.section("static.nbt") != null,
                    "representable NBT uses NI's readable typed mapping");
        }
        if (fixture.name().equals("opaque-data")) {
            checks.that(
                    loaded.section("static.components").values().get("minecraft:custom_data")
                            instanceof String,
                    "empty arrays, numeric lists and dotted keys use the native custom_data SNBT codec");
        }
        if (fixture.name().equals("removed-component")) {
            checks.that(
                    loaded.section("static.components")
                            .values()
                            .containsKey("!minecraft:max_damage"),
                    "an absent default component is recorded as an explicit removal");
            checks.that(
                    !CraftItemStack.unwrap(rebuilt).has(DataComponents.MAX_DAMAGE),
                    "a removed default component remains absent after the static prototype and material stages");
        }
        checks.that(
                before.equals(full(original)),
                fixture.name() + ": generation also leaves the source untouched");
        if (reference == null) return;
        checks.group(
                "reference/" + fixture.name(),
                () ->
                        referenceRoundTrip(
                                fixture,
                                reference,
                                checks,
                                referenceDifferences,
                                captured,
                                rebuilt,
                                yaml,
                                before));
    }

    private static void referenceRoundTrip(
            Fixture fixture,
            Reference reference,
            Checks checks,
            List<Map<String, Object>> referenceDifferences,
            NiConfig captured,
            ItemStack independent,
            String snapshotYaml,
            Tag before)
            throws Exception {
        ItemStack original = fixture.item();
        // Deletion syntax is a IL correction to the old writer/builder. Other emitted definitions
        // also run through the actual NI generator to prove they use its accepted configuration
        // form.
        if (!fixture.name().equals("removed-component")) {
            ItemStack fromSavedDefinition = reference.generate(captured);
            checks.sameItem(
                    fixture.name() + "/reference-reader",
                    original,
                    fromSavedDefinition,
                    snapshotYaml,
                    fixture.name() + ": actual NI reads the emitted static definition");
        }
        ItemStack referenceSource = original.clone();
        ConfigurationSection oldSaved = reference.save(referenceSource);
        checks.that(
                before.equals(full(referenceSource)),
                fixture.name() + ": reference serializer is read-only");
        NiConfig oldDefinition = NiYaml.fromSection(oldSaved);
        String oldYaml = NiYaml.write(oldDefinition);
        ItemStack oldRebuilt;
        try {
            oldRebuilt = reference.generate(oldDefinition);
        } catch (ReflectiveOperationException error) {
            Map<String, Object> evidence =
                    Map.of(
                            "phase",
                            "original NI serializer output -> original NI generator",
                            "sourceNative",
                            before.toString(),
                            "independentNative",
                            full(independent).toString(),
                            "emittedSnapshotYaml",
                            snapshotYaml,
                            "originalSavedYaml",
                            oldYaml,
                            "exception",
                            exceptionDetails(error));
            // Only the original writer's own rebuild can have these reference defects. The
            // independent round trip and NI reading our snapshot have already passed above.
            ReferenceDefect defect = knownReferenceDefect(error, oldDefinition, original);
            if (defect != null) {
                checks.referenceDefects.put(
                        fixture.name(),
                        Map.of(
                                "code",
                                defect.code(),
                                "reason",
                                defect.reason(),
                                "matchedInput",
                                defect.trigger(),
                                "evidence",
                                evidence));
                return;
            }
            checks.referenceFailureEvidence.put(fixture.name(), evidence);
            throw error;
        }
        NativeComparison comparison = compare(original, oldRebuilt);
        if (comparison.matches())
            checks.that(true, fixture.name() + ": original NI save/rebuild agrees structurally");
        else if (fixture.referenceDifference() != null) {
            referenceDifferences.add(
                    Map.of(
                            "fixture",
                            fixture.name(),
                            "reason",
                            fixture.referenceDifference(),
                            "difference",
                            comparison.evidence(original, oldRebuilt, oldYaml)));
        } else {
            checks.nativeDifferences.put(
                    fixture.name() + "/reference-writer",
                    comparison.evidence(original, oldRebuilt, oldYaml));
            checks.that(
                    false, fixture.name() + ": unexplained original NI save/rebuild difference");
        }
        if (fixture.name().equals("legacy-identity")) {
            checks.that(
                    identity(oldRebuilt).id().equals("original-item-id")
                            && !identity(oldRebuilt).id().equals(SAVED_ID),
                    "actual NI save under a new definition id retains the source item's identity id");
        }
    }

    private static List<Fixture> fixtures(JavaPlugin plugin) {
        List<Fixture> cases = new ArrayList<>();
        cases.add(new Fixture("plain", new ItemStack(Material.STONE, 37), null));
        ItemStack bukkit = new ItemStack(Material.IRON_SWORD, 7);
        var meta = bukkit.getItemMeta();
        meta.getPersistentDataContainer()
                .set(
                        new NamespacedKey(plugin, "saved-token"),
                        PersistentDataType.STRING,
                        "preserved");
        bukkit.setItemMeta(meta);
        cases.add(new Fixture("bukkit-pdc", bukkit, null));

        ItemStack removed = CraftItemStack.asCraftCopy(new ItemStack(Material.DIAMOND_SWORD, 9));
        CraftItemStack.unwrap(removed).remove(DataComponents.MAX_DAMAGE);
        cases.add(
                new Fixture(
                        "removed-component",
                        removed,
                        "NI's serializer discards empty entries in the component patch"));

        ItemStack rich = CraftItemStack.asCraftCopy(new ItemStack(Material.PAPER, 19));
        var richHandle = CraftItemStack.unwrap(rich);
        richHandle.set(
                DataComponents.CUSTOM_NAME,
                PaperAdventure.asVanilla(
                        MiniMessage.miniMessage()
                                .deserialize(
                                        "<#17ad42><bold>保存</bold> <hover:show_text:'<yellow>说明'>悬浮</hover><click:suggest_command:'/probe'>点击</click>")));
        richHandle.set(
                DataComponents.LORE,
                new ItemLore(
                        List.of(
                                Component.literal(""),
                                Component.literal("first\nsecond"),
                                Component.literal("literal <number::1_9> and \\slashes\\"))));
        cases.add(
                new Fixture(
                        "rich-text",
                        rich,
                        "NI re-expands saved node-shaped text, splits embedded lore newlines and normalizes MiniMessage component trees"));
        ItemStack emptyLore = CraftItemStack.asCraftCopy(new ItemStack(Material.PAPER));
        CraftItemStack.unwrap(emptyLore).set(DataComponents.LORE, ItemLore.EMPTY);
        cases.add(
                new Fixture(
                        "empty-lore",
                        emptyLore,
                        "NI's empty mini-lore convenience field drops an explicitly present empty lore component"));

        CompoundTag typed = new CompoundTag();
        typed.putByte("byte", (byte) -12);
        typed.putShort("short", (short) 300);
        typed.putInt("int", -70000);
        typed.putLong("long", Long.MAX_VALUE);
        typed.putFloat("float", 1.25f);
        typed.putDouble("double", -3.125);
        typed.putByteArray("bytes", new byte[] {1, -1, 4});
        typed.putIntArray("ints", new int[] {5, 6, 7});
        typed.putLongArray("longs", new long[] {Long.MIN_VALUE, 8});
        CompoundTag foreign = new CompoundTag();
        foreign.putString("itemloomrefine:state", "opaque-pdc");
        typed.put("PublicBukkitValues", foreign);
        cases.add(new Fixture("typed-data", withData(typed), null));

        CompoundTag opaque = typed.copy();
        opaque.putByteArray("empty-bytes", new byte[0]);
        opaque.putIntArray("empty-ints", new int[0]);
        opaque.putLongArray("empty-longs", new long[0]);
        ListTag numbers = new ListTag();
        numbers.add(IntTag.valueOf(10));
        numbers.add(IntTag.valueOf(20));
        opaque.put("integer-list", numbers);
        opaque.putString("literal.dot", "(Int) 42");
        opaque.putString("literal-node", "<number::1_9>\\");
        ListTag words = new ListTag();
        words.add(StringTag.valueOf("(Int) 7"));
        words.add(StringTag.valueOf("[(Int) 1,(Long) 2]"));
        opaque.put("typed-looking-words", words);
        cases.add(
                new Fixture(
                        "opaque-data",
                        withData(opaque),
                        "NI typed YAML cannot distinguish empty arrays/numeric lists and interprets typed-looking strings and dotted configuration keys"));

        ItemStack uncommon = new ItemStack(Material.STONE, 5);
        uncommon =
                NmsItems.withComponents(
                        uncommon,
                        Map.of(
                                "minecraft:note_block_sound",
                                "minecraft:entity.cat.ambient",
                                "minecraft:item_model",
                                "itemloom:probe_model",
                                "minecraft:enchantment_glint_override",
                                true,
                                "minecraft:custom_model_data",
                                Map.of(
                                        "floats",
                                        List.of(1.25f, 9.5f),
                                        "flags",
                                        List.of(true, false),
                                        "strings",
                                        List.of("opaque-model-state"),
                                        "colors",
                                        List.of(0x123456, 0xabcdef))));
        cases.add(new Fixture("generic-components", uncommon, null));

        CompoundTag legacy = new CompoundTag(), legacyState = new CompoundTag();
        legacyState.putString("id", "original-item-id");
        legacyState.putString("data", "{\"power\":\"8\",\"nullable\":null}");
        legacyState.putInt("hashCode", 173);
        legacyState.putInt("charge", 3);
        legacy.put("NeigeItems", legacyState);
        legacy.putString("other:token", "unchanged");
        cases.add(new Fixture("legacy-identity", withData(legacy), null));
        Map<String, String> rolls = new LinkedHashMap<>();
        rolls.put("power", "8");
        rolls.put("nullable", null);
        CompoundTag properties = new CompoundTag();
        properties.putInt("charge", 3);
        properties.putInt("hashCode", 173);
        CompoundTag modern = new CompoundTag(),
                modernState = CODEC.encode(new ItemIdentity("original-item-id", rolls), properties);
        modernState.put("future:extension", new CompoundTag());
        modernState.getCompoundOrEmpty("future:extension").putByteArray("empty", new byte[0]);
        modern.put(ItemStateCodec.KEY, modernState);
        modern.putString("other:token", "unchanged");
        cases.add(
                new Fixture(
                        "modern-identity",
                        withData(modern),
                        "NI does not know the independent envelope and injects a second legacy identity when serializing an independent item"));
        cases.add(new Fixture("empty-custom-data", withData(new CompoundTag()), null));
        return cases;
    }

    private static ItemStack withData(CompoundTag data) {
        return NmsItems.withCustomData(new ItemStack(Material.STONE, 13), data);
    }

    private static boolean identified(ItemStack item) {
        return CODEC.read(item).isPresent() || MIGRATION.identify(item).isPresent();
    }

    private static ItemIdentity identity(ItemStack item) {
        return CODEC.read(item).or(() -> MIGRATION.identify(item)).orElseThrow();
    }

    private static NativeComparison compare(ItemStack source, ItemStack rebuilt) {
        if (rebuilt == null || rebuilt.isEmpty())
            return new NativeComparison(full(source), null, "rebuilt item is empty");
        boolean inherited = identified(source);
        var expected = CraftItemStack.asNMSCopy(source);
        var actual = CraftItemStack.asNMSCopy(rebuilt);
        expected.setCount(1);
        actual.setCount(1);
        if (inherited) {
            try {
                expected.set(
                        DataComponents.CUSTOM_DATA,
                        CustomData.of(MIGRATION.convert(NmsItems.customData(source))));
                actual.set(
                        DataComponents.CUSTOM_DATA,
                        CustomData.of(MIGRATION.convert(NmsItems.customData(rebuilt))));
            } catch (IllegalArgumentException conflict) {
                return new NativeComparison(full(expected), full(actual), conflict.toString());
            }
        } else {
            CompoundTag actualData = NmsItems.customData(rebuilt);
            actualData.remove(ItemStateCodec.KEY);
            actualData.remove("NeigeItems");
            if (actualData.isEmpty() && expected.get(DataComponents.CUSTOM_DATA) == null)
                actual.remove(DataComponents.CUSTOM_DATA);
            else actual.set(DataComponents.CUSTOM_DATA, CustomData.of(actualData));
        }
        return new NativeComparison(full(expected), full(actual), null);
    }

    private static Tag full(ItemStack item) {
        return full(CraftItemStack.asNMSCopy(item));
    }

    private static Tag full(net.minecraft.world.item.ItemStack item) {
        return net.minecraft.world.item.ItemStack.CODEC
                .encodeStart(
                        CraftRegistry.getMinecraftRegistry()
                                .createSerializationContext(NbtOps.INSTANCE),
                        item)
                .getOrThrow();
    }

    private record Fixture(String name, ItemStack item, String referenceDifference) {}

    private record ReferenceDefect(String code, String reason, Map<String, Object> trigger) {}

    /** Recognizes two executed NI 1.21.176 failures by their cause, call path and unchanged saved input. */
    private static ReferenceDefect knownReferenceDefect(
            Throwable error, NiConfig saved, ItemStack source) {
        Throwable cause = rootCause(error);
        if (!hasFrame(cause, "pers.neige.neigeitems.item.ItemGenerator", "getItemStack")
                || !hasFrame(cause, "pers.neige.neigeitems.item.builder.NewItemBuilder", "load"))
            return null;
        String message = cause.getMessage();
        if (message == null) return null;

        String miniName = saved.string("mini-name");
        if (cause.getClass()
                        .getName()
                        .equals(
                                "net.kyori.adventure.text.minimessage.internal.parser.ParsingExceptionImpl")
                && message.startsWith(
                        "Legacy formatting codes have been detected in a MiniMessage string")
                && hasFrame(
                        cause,
                        "net.kyori.adventure.text.minimessage.internal.parser.TokenParser",
                        "parseString")
                && miniName != null
                && miniName.length() > 9
                && miniName.startsWith("<#")
                && miniName.charAt(8) == '>'
                && miniName.substring(2, 8).matches("[0-9A-Fa-f]{6}")
                && miniName.indexOf('§') < 0) {
            var originalName = CraftItemStack.asNMSCopy(source).get(DataComponents.CUSTOM_NAME);
            StringBuilder legacy = new StringBuilder("§x");
            for (int i = 2; i < 8; i++)
                legacy.append('§').append(Character.toLowerCase(miniName.charAt(i)));
            String rejectedName = legacy + miniName.substring(9);
            if (originalName != null
                    && message.contains(rejectedName)
                    && miniName.equals(
                            MiniMessage.miniMessage()
                                    .serialize(PaperAdventure.asAdventure(originalName)))) {
                return new ReferenceDefect(
                        "ni-dynamic-hex-minimessage",
                        "NI saves a valid MiniMessage hex tag, then BaseActionManager.parseNodeSpec converts it to legacy §x codes before NewItemBuilder.load calls MiniMessage.deserialize",
                        Map.of(
                                "savedField",
                                "mini-name",
                                "savedName",
                                miniName,
                                "rejectedAfterNodeParsing",
                                rejectedName));
            }
        }

        List<String> literals = List.of("(Int) 7", "[(Int) 1,(Long) 2]");
        if (cause.getClass() == ArrayStoreException.class
                && message.contains("java.lang.Integer")
                && hasFrame(cause, "java.util.ArrayList", "toArray")
                && hasFrame(cause, "pers.neige.neigeitems.utils.ItemUtils", "cast")
                && hasFrame(cause, "pers.neige.neigeitems.utils.ItemUtils", "toNbt")
                && literals.equals(saved.get("nbt.typed-looking-words"))) {
            Tag original = NmsItems.customData(source).get("typed-looking-words");
            if (original instanceof ListTag words
                    && words.size() == 2
                    && words.get(0).equals(StringTag.valueOf(literals.get(0)))
                    && words.get(1).equals(StringTag.valueOf(literals.get(1)))) {
                return new ReferenceDefect(
                        "ni-unescaped-mixed-array-string",
                        "NI saves literal NBT strings without a (String) escape; ItemUtils.cast interprets [(Int) 1,(Long) 2] as an Integer array and ArrayList.toArray rejects the Long entry",
                        Map.of(
                                "savedField",
                                "nbt.typed-looking-words",
                                "sourceStringList",
                                literals,
                                "invalidCastTarget",
                                "java.lang.Integer[]"));
            }
        }
        return null;
    }

    private static boolean hasFrame(Throwable error, String className, String methodName) {
        for (StackTraceElement frame : error.getStackTrace()) {
            if (frame.getClassName().equals(className) && frame.getMethodName().equals(methodName))
                return true;
        }
        return false;
    }

    private static Throwable rootCause(Throwable error) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        seen.add(error);
        while (error.getCause() != null && seen.add(error.getCause())) error = error.getCause();
        return error;
    }

    private static Map<String, Object> exceptionDetails(Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        return Map.of("rootCause", rootCause(error).toString(), "stackTrace", trace.toString());
    }

    private record NativeComparison(Tag expected, Tag actual, String failure) {
        boolean matches() {
            return failure == null && expected.equals(actual);
        }

        Map<String, Object> evidence(ItemStack source, ItemStack rebuilt, String yaml) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sourceNative", full(source).toString());
            result.put(
                    "rebuiltNative",
                    rebuilt == null || rebuilt.isEmpty() ? "<empty>" : full(rebuilt).toString());
            result.put("expectedNormalized", expected.toString());
            result.put("actualNormalized", actual == null ? "<empty>" : actual.toString());
            result.put("savedYaml", yaml);
            if (failure != null) result.put("normalizationFailure", failure);
            return result;
        }
    }

    /** Uses only reference serialization and detached constructors; never calls its saveItem/file registry. */
    private static final class Reference {
        private final Method save, create;
        private final Constructor<?> config, generator;
        private final File unusedSource;

        Reference(JavaPlugin probe, Plugin reference) throws ReflectiveOperationException {
            ClassLoader loader = reference.getClass().getClassLoader();
            Class<?> utils =
                    loader.loadClass("pers.neige.neigeitems.libs.bot.inker.bukkit.nbt.NbtUtils");
            save = utils.getMethod("saveAfterV21", ItemStack.class);
            Class<?> itemConfig = loader.loadClass("pers.neige.neigeitems.item.ItemConfig");
            Class<?> itemGenerator = loader.loadClass("pers.neige.neigeitems.item.ItemGenerator");
            config =
                    itemConfig.getConstructor(String.class, File.class, ConfigurationSection.class);
            generator = itemGenerator.getConstructor(itemConfig);
            create = itemGenerator.getMethod("getItemStack", OfflinePlayer.class, Map.class);
            unusedSource = new File(probe.getDataFolder(), "in-memory-save-probe.yml");
        }

        ConfigurationSection save(ItemStack item) throws ReflectiveOperationException {
            return (ConfigurationSection) save.invoke(null, item);
        }

        ItemStack generate(NiConfig definition) throws ReflectiveOperationException {
            ConfigurationSection root =
                    NiYaml.toSection(new NiConfig(Map.of(SAVED_ID, definition.values())));
            Object configValue = config.newInstance(SAVED_ID, unusedSource, root);
            return (ItemStack)
                    create.invoke(
                            generator.newInstance(configValue),
                            null,
                            new LinkedHashMap<String, String>());
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private static final class Checks {
        int attempted;
        final List<String> verified = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> exceptionDetails = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> nativeDifferences = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> referenceFailureEvidence = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> referenceDefects = new LinkedHashMap<>();

        void that(boolean value, String message) {
            attempted++;
            if (!value) throw new AssertionError(message);
            verified.add(message);
        }

        void sameItem(
                String name, ItemStack source, ItemStack rebuilt, String yaml, String message) {
            NativeComparison comparison = compare(source, rebuilt);
            if (!comparison.matches())
                nativeDifferences.put(name, comparison.evidence(source, rebuilt, yaml));
            that(comparison.matches(), message);
        }

        void group(String name, Checked operation) {
            try {
                operation.run();
            } catch (Throwable error) {
                failed(name, error);
            }
        }

        void failed(String name, Throwable error) {
            failures.put(name, rootCause(error).toString());
            exceptionDetails.put(name, ItemSaveProbe.exceptionDetails(error));
        }
    }
}
