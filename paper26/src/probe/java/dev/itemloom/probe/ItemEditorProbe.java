package dev.itemloom.probe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.CustomData;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.script.LegacyItemEditorManager;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual 26.2 stacks and the installed NI reference; no inventories, generation, or player effects. */
@SuppressWarnings("deprecation")
final class ItemEditorProbe {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final NamespacedKey PDC = new NamespacedKey("item_editor_probe", "opaque");

    private record Outcome(Boolean result, String failure) {}

    private interface Call {
        Object call() throws Exception;
    }

    static Map<String, Object> run(JavaPlugin plugin, Plugin reference) throws Exception {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Editor probe must run on the server thread");
        if (reference == null || !reference.isEnabled())
            throw new IllegalStateException(
                    "Enabled NI reference is required for editor differential checks");
        var referenceClass =
                reference
                        .getClass()
                        .getClassLoader()
                        .loadClass("pers.neige.neigeitems.manager.ItemEditorManager");
        Object old = referenceClass.getField("INSTANCE").get(null);
        Method run =
                referenceClass.getMethod(
                        "runEditorWithResult",
                        String.class,
                        String.class,
                        ItemStack.class,
                        Player.class);
        Host host = new Host();
        var adapter = new LegacyItemEditorManager(host);
        Map<String, Object> differences = new LinkedHashMap<>();
        List<String> verified = new ArrayList<>();
        List<String> intentional = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, ?> oldEditors =
                (Map<String, ?>) referenceClass.getMethod("getItemEditors").invoke(old);
        Set<String> expected = new HashSet<>(oldEditors.keySet());
        if (!expected.equals(adapter.getItemEditors().keySet())
                || adapter.getItemEditors().size() != 147)
            differences.put(
                    "registered-names",
                    Map.of("reference", expected, "adapter", adapter.getItemEditors().keySet()));
        else verified.add("all 147 registered names and derived suffixes match NI");
        int checked = 1;
        for (var entry : samples().entrySet()) {
            List<String> variants =
                    entry.getKey().contains("Regex")
                            ? List.of(entry.getKey())
                            : List.of(
                                    entry.getKey(),
                                    entry.getKey() + "Papi",
                                    entry.getKey() + "Section");
            for (String id : variants) {
                ItemStack before = fixture(false), after = fixture(false);
                Outcome expectedResult =
                        outcome(() -> run.invoke(old, id, entry.getValue(), before, null));
                Outcome actualResult =
                        outcome(
                                () ->
                                        adapter.runEditorWithResult(
                                                id, entry.getValue(), after, null));
                checked++;
                if (!expectedResult.equals(actualResult)
                        || !snapshot(before).equals(snapshot(after))) {
                    differences.put(id, detail(expectedResult, actualResult, before, after));
                } else verified.add(id + " result and normalized complete item match NI");
            }
        }
        for (var sample :
                List.of(
                        Map.entry("replaceName", "{\"a\":\"aa\"}"),
                        Map.entry("replaceAllName", "{\"a\":\"aa\"}"),
                        Map.entry("replaceAllName", "{\"aa\":\"long\",\"a\":\"short\"}"),
                        Map.entry("replaceAllNameRegex", "{\"a(b)?\":\"[$0|$1|$9]$\"}"),
                        Map.entry("replaceLore", "{\"a\":\"x\\ny\"}"),
                        Map.entry("replaceLoreRegex", "{\"a\":\"x\\ny\"}"),
                        Map.entry("setLore", "one\\ntwo\nthree\\n"),
                        Map.entry("setNBTWithList", "{\"rows.0.value\":\"(Int) 4\"}"),
                        Map.entry("setNBTWithList", "{\"bytes.1\":\"(Byte) 7\"}"),
                        Map.entry("setNBTWithList", "{\"bytes.3\":\"(Byte) 8\"}"),
                        Map.entry("setNBTWithList", "{\"literal\\\\.dot.value\":\"text\"}"),
                        Map.entry("setAmount", "999"),
                        Map.entry("takeDamage", "100"),
                        Map.entry("takeCharge", "999"),
                        Map.entry("takeCustomDurability", "999"))) {
            ItemStack before = fixture(false), after = fixture(false);
            Outcome expectedResult =
                    outcome(
                            () ->
                                    run.invoke(
                                            old, sample.getKey(), sample.getValue(), before, null));
            Outcome actualResult =
                    outcome(
                            () ->
                                    adapter.runEditorWithResult(
                                            sample.getKey(), sample.getValue(), after, null));
            checked++;
            if (!expectedResult.equals(actualResult) || !snapshot(before).equals(snapshot(after)))
                differences.put(
                        "edge-" + checked + "/" + sample.getKey(),
                        detail(expectedResult, actualResult, before, after));
            else verified.add("edge " + sample + " matches NI");
        }
        for (var entry : samples().entrySet()) {
            if (entry.getKey().contains("NBT")
                    || entry.getKey().contains("Charge")
                    || entry.getKey().contains("Durability")) continue;
            ItemStack item = fixture(true);
            CompoundTag custom = NmsItems.customData(item);
            var identity = CODEC.read(item).orElseThrow();
            adapter.runEditorWithResult(entry.getKey(), entry.getValue(), item, null);
            checked++;
            if (!item.isEmpty()
                    && (!custom.equals(NmsItems.customData(item))
                            || !CODEC.read(item).orElseThrow().equals(identity)
                            || !"kept"
                                    .equals(
                                            item.getPersistentDataContainer()
                                                    .get(PDC, PersistentDataType.STRING))
                            || !Identifier.parse("item_editor_probe:opaque_model")
                                    .equals(
                                            CraftItemStack.unwrap(item)
                                                    .get(DataComponents.ITEM_MODEL))))
                differences.put(
                        "preservation/" + entry.getKey(),
                        "unrelated components, independent identity or PDC changed");
            else
                verified.add(
                        entry.getKey()
                                + " retains foreign custom_data/PDC/independent identity/item_model");
        }
        for (var invalid :
                List.of(
                        Map.entry("setEnchantment", "{"),
                        Map.entry("setEnchantment", "{\"SHARPNESS\":1.5}"),
                        Map.entry("replaceNameRegex", "{\"[\":\"broken\"}"),
                        Map.entry("setNBT", "{\"x\":{}}"),
                        Map.entry("setNBT", "{\"NeigeItems.id\":\" \"}"))) {
            ItemStack item = fixture(true);
            Tag original = raw(item);
            Outcome result =
                    outcome(
                            () ->
                                    adapter.runEditorWithResult(
                                            invalid.getKey(), invalid.getValue(), item, null));
            checked++;
            if (result.failure == null || !original.equals(raw(item)))
                differences.put(
                        "invalid/" + invalid, "invalid edit did not fail atomically: " + result);
            else
                verified.add("invalid " + invalid + " leaves the complete original item unchanged");
        }
        intentional.add(
                "Malformed setEnchantment is validated on a detached copy: unlike NI, existing enchantments are retained on failure.");
        checked += contract(adapter, host, differences, verified);
        checked += scriptRegistration(plugin, differences, verified);
        return Map.of(
                "passed",
                differences.isEmpty(),
                "checked",
                checked,
                "differences",
                differences,
                "verified",
                verified,
                "intentionalDifferences",
                intentional,
                "referenceRequired",
                true,
                "fixtures",
                "private fresh Craft stacks; regeneration uses recording host only; Papi/Section differential uses literal inputs; third-party material/Papi providers not exercised; no players or inventory writes",
                "server",
                plugin.getServer().getMinecraftVersion());
    }

    private static Map<String, Object> detail(
            Outcome old, Outcome current, ItemStack before, ItemStack after) {
        return Map.of(
                "referenceResult",
                old.toString(),
                "adapterResult",
                current.toString(),
                "referenceItem",
                snapshot(before).toString(),
                "adapterItem",
                snapshot(after).toString());
    }

    private static int contract(
            LegacyItemEditorManager adapter,
            Host host,
            Map<String, Object> differences,
            List<String> verified) {
        ItemStack item = fixture(true);
        List<String> invocations = new ArrayList<>();
        adapter.addItemEditor(
                "MiXeD-Custom",
                (player, received, text) -> {
                    invocations.add((received == item) + ":" + text);
                    return false;
                });
        boolean custom =
                Boolean.FALSE.equals(
                                adapter.runEditorWithResult(
                                        "MIXED-CUSTOM", "unaltered text", item, null))
                        && invocations.equals(List.of("true:unaltered text"))
                        && adapter.runEditorWithResult("missing-editor", "", item, null) == null;
        if (!custom) differences.put("extension-registration", invocations);
        else
            verified.add(
                    "custom registration retains exact stack/content, false result, case folding and missing=null");
        adapter.reload();
        if (adapter.getItemEditors().size() != 147
                || adapter.getItemEditors().containsKey("mixed-custom"))
            differences.put("reload", "custom registrations leaked");
        else verified.add("reload restores precisely the built-in registrations");
        host.calls.clear();
        adapter.runEditorWithResult("refresh", "power escaped\\ key", item, null);
        adapter.runEditorWithResult("refreshAmount", "bad power escaped\\ key", item, null);
        adapter.runEditorWithResult(
                "rebuildAmount", "0 {\"power\":\"8\",\"remove\":null}", item, null);
        adapter.runEditorWithResult("rebuild", "{\"power\":\"9\"}", item, null);
        List<String> expected =
                List.of(
                        "refresh:[power, escaped key]:null:null",
                        "refresh:[power, escaped key]:null:1",
                        "refresh:null:{power=8, remove=null}:1",
                        "rebuild:{power=9}");
        if (!host.calls.equals(expected))
            differences.put(
                    "host-parameters",
                    Map.of("actual", host.calls.toString(), "expected", expected));
        else
            verified.add(
                    "refresh/rebuild/Amount dispatch preserves escaped arguments, defaults, null removals and distinct routes");
        host.expansions.clear();
        adapter.runEditorWithResult(
                "replaceAllNameRegexPapi", "{\"a(b)?\":\"$1-%probe%\"}", item, null);
        boolean groupsFirst =
                host.expansions.contains("papi:b-%probe%")
                        && host.expansions.contains("papi:-%probe%");
        if (!groupsFirst) differences.put("regex-expansion-order", host.expansions);
        else verified.add("regex groups are substituted before Papi expansion");
        return 4;
    }

    private static int scriptRegistration(
            JavaPlugin plugin, Map<String, Object> differences, List<String> verified) {
        var input =
                new NiRepository.Input(
                        new NiConfig(Map.of()),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of());
        try (var players = new PlayerActionState();
                var catalog = new NiCatalog(991, input, plugin, (viewer, text) -> null, players)) {
            ItemStack item = fixture(true);
            Object value =
                    catalog.actionContext(null, Map.of("editorStack", item))
                            .evaluate(
                                    """
                    var editor = Java.type('pers.neige.neigeitems.manager.ItemEditorManager').INSTANCE;
                    editor.addItemEditor('probe-js-extension', function(player, item, text) {
                        new NbtItemStack(item).getOrCreateTag().putString('script-editor', text);
                        return false;
                    });
                    editor.runEditorWithResult('PROBE-JS-EXTENSION', 'preserved', editorStack, null) === false;
                    """);
            if (!Boolean.TRUE.equals(value)
                    || !"preserved"
                            .equals(NmsItems.customData(item).getStringOr("script-editor", "")))
                differences.put(
                        "script-extension",
                        "old Java.type import or JS Editor SAM adaptation failed");
            else
                verified.add(
                        "old script import and addItemEditor JavaScript function execute against the independent alias");
        } catch (Throwable error) {
            differences.put("script-extension", error.toString());
        }
        return 1;
    }

    private static Map<String, String> samples() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("setMaterial", "IRON_SWORD");
        for (String action : List.of("set", "add", "take"))
            for (String field :
                    List.of(
                            "Amount",
                            "Damage",
                            "Charge",
                            "MaxCharge",
                            "CustomDurability",
                            "MaxCustomDurability")) values.put(action + field, "2");
        values.put("setName", "&bname");
        values.put("addNamePrefix", "&a[");
        values.put("addNamePostfix", "]&r");
        values.put("setLore", "&cfirst\\nsecond\\n");
        values.put("addLore", "third\\nfourth");
        for (String field : List.of("Name", "Lore"))
            for (String all : List.of("replace", "replaceAll")) {
                values.put(all + field, "{\"a\":\"changed\"}");
                for (String suffix : List.of("", "Papi", "Section"))
                    values.put(all + field + "Regex" + suffix, "{\"a(b)?\":\"[$0|$1|$9]$\"}");
            }
        values.put("setCustomModelData", "42");
        values.put("setUnbreakable", "true");
        for (String operation : List.of("set", "add", "addNotCover", "levelUp", "levelDown"))
            values.put(operation + "Enchantment", "{\"SHARPNESS\":2,\"UNBREAKING\":3}");
        values.put("removeEnchantment", "SHARPNESS UNKNOWN");
        for (String operation : List.of("set", "add", "remove"))
            values.put(operation + "ItemFlag", "HIDE_ENCHANTS HIDE_UNBREAKABLE UNKNOWN");
        values.put("setNBT", "{\"probe.value\":\"(Int) 9\",\"probe.text\":\"preserved\"}");
        values.put("setNBTWithList", "{\"rows.0.value\":\"(Int) 9\"}");
        return values;
    }

    private static ItemStack fixture(boolean modern) {
        ItemStack item = CraftItemStack.asCraftCopy(new ItemStack(Material.DIAMOND_SWORD));
        var meta = item.getItemMeta();
        meta.setDisplayName("ab a aa");
        meta.setLore(List.of("a a", "second a", "untouched"));
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(PDC, PersistentDataType.STRING, "kept");
        item.setItemMeta(meta);
        item.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        var handle = CraftItemStack.unwrap(item);
        handle.set(DataComponents.DAMAGE, 10);
        handle.set(DataComponents.ITEM_MODEL, Identifier.parse("item_editor_probe:opaque_model"));
        handle.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
        CompoundTag properties = new CompoundTag();
        properties.putInt("charge", 6);
        properties.putInt("maxCharge", 10);
        properties.putInt("durability", 8);
        properties.putInt("maxDurability", 10);
        properties.putBoolean("itemBreak", true);
        CompoundTag custom = NmsItems.customData(item);
        custom.putString("foreign:opaque", "unchanged");
        custom.putByteArray("bytes", new byte[] {1, 2, 3});
        if (modern) {
            CompoundTag state =
                    CODEC.encode(
                            new ItemIdentity("editor-probe", Map.of("power", "7")), properties);
            state.putLong("future-field", 9876543210L);
            custom.put(ItemStateCodec.KEY, state);
        } else {
            properties.putString("id", "editor-probe");
            properties.putString("data", "{\"power\":\"7\"}");
            custom.put("NeigeItems", properties);
        }
        handle.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        return item;
    }

    private static Outcome outcome(Call call) {
        try {
            return new Outcome((Boolean) call.call(), null);
        } catch (Throwable error) {
            while (error instanceof InvocationTargetException && error.getCause() != null)
                error = error.getCause();
            return new Outcome(null, error.getClass().getName());
        }
    }

    private static Tag raw(ItemStack item) {
        return net.minecraft.world.item.ItemStack.OPTIONAL_CODEC
                .encodeStart(
                        CraftRegistry.getMinecraftRegistry()
                                .createSerializationContext(NbtOps.INSTANCE),
                        CraftItemStack.asNMSCopy(item))
                .getOrThrow();
    }

    private static Tag snapshot(ItemStack item) {
        if (item.isEmpty()) return raw(item);
        var copy = CraftItemStack.asCraftCopy(item);
        CraftItemStack.unwrap(copy)
                .set(DataComponents.CUSTOM_DATA, CustomData.of(NiItemNodes.legacyData(item)));
        return raw(copy);
    }

    private static final class Host implements LegacyItemEditorManager.Host {
        final List<String> calls = new ArrayList<>(), expansions = new ArrayList<>();

        @Override
        public Material material(String value) {
            return Material.matchMaterial(value);
        }

        @Override
        public String papi(Player player, String text) {
            expansions.add("papi:" + text);
            return text;
        }

        @Override
        public String section(Player player, ItemStack item, String text) {
            expansions.add("section:" + text);
            return text;
        }

        @Override
        public boolean refresh(
                Player player,
                ItemStack item,
                List<String> remove,
                Map<String, String> overrides,
                Integer amount) {
            calls.add(
                    "refresh:"
                            + remove
                            + ":"
                            + (overrides == null ? null : new java.util.TreeMap<>(overrides))
                            + ":"
                            + amount);
            return true;
        }

        @Override
        public boolean rebuild(Player player, ItemStack item, Map<String, String> overrides) {
            calls.add("rebuild:" + new java.util.TreeMap<>(overrides));
            return true;
        }
    }
}
