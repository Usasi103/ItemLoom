package dev.itemloom.probe;

import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.compat.sx.SxConfig;
import dev.itemloom.compat.sx.SxRandom;
import dev.itemloom.compat.sx.SxRepository;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import dev.itemloom.paper.sx.SxCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Synthetic actual-Paper regression matrix for revision fingerprints and atomic SX replacement. */
@SuppressWarnings("deprecation")
final class SxRewriteProbe implements Listener {
    private final List<String> checks = new ArrayList<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final ItemStateCodec codec = new ItemStateCodec();
    private Consumer<ItemGenerateEvent> hook;

    static Map<String, Object> run(JavaPlugin plugin) {
        SxRewriteProbe probe = new SxRewriteProbe();
        Bukkit.getPluginManager().registerEvents(probe, plugin);
        try {
            probe.group("hash-and-legacy", () -> probe.hashAndLegacy(plugin));
            probe.group("global-and-scripts", () -> probe.globalAndScripts(plugin));
            probe.group("settings", () -> probe.settings(plugin));
            probe.group("protected-components", () -> probe.components(plugin));
            probe.group("protected-nbt", () -> probe.nbt(plugin));
            probe.group("events-alias-import", () -> probe.lifecycle(plugin));
        } finally {
            HandlerList.unregisterAll(probe);
        }
        return Map.of(
                "passed",
                probe.failures.isEmpty(),
                "checks",
                probe.checks,
                "assertions",
                probe.checks.size(),
                "failures",
                probe.failures,
                "realClient",
                false);
    }

    @EventHandler
    public void generate(ItemGenerateEvent event) {
        if (hook != null) {
            hook.accept(event);
        }
    }

    private void group(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            failures.put(name, error.toString());
        } finally {
            hook = null;
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks.add(message);
    }

    private void rejects(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            checks.add(message);
            return;
        }
        throw new AssertionError(message);
    }

    private void hashAndLegacy(JavaPlugin plugin) {
        Map<String, Object> first = item(Map.of("Name", "Aa", "Lore", List.of("<l:keep>")));
        Map<String, Object> second = item(Map.of("Name", "BB", "Lore", List.of("<l:keep>")));
        check(
                first.hashCode() == second.hashCode(),
                "Aa/BB fixture collides under old 32-bit config hash");
        try (SxCatalog a = catalog(plugin, first, Map.of(), Map.of(), Map.of());
                SxCatalog b = catalog(plugin, second, Map.of(), Map.of(), Map.of());
                SxCatalog same = catalog(plugin, second, Map.of(), Map.of(), Map.of())) {
            ItemStack live = a.generate("Entry", null, Map.of("keep", "saved"));
            check(
                    codec.properties(live).getInt("sx_hash").orElseThrow() == first.hashCode(),
                    "fresh catalog generation retains persisted sx_hash compatibility data");
            live.setAmount(17);
            check(b.update(null, live), "new fingerprint detects Aa -> BB");
            check(
                    live.getAmount() == 17
                            && live.getItemMeta().getDisplayName().equals("BB")
                            && codec.read(live).orElseThrow().rolls().get("keep").equals("saved"),
                    "hash collision update retains quantity and expanded lock");
            check(
                    !b.update(null, live) && !same.update(null, live),
                    "equivalent revisions do not churn updates");
            ItemIdentity identity = codec.read(live).orElseThrow();
            CompoundTag properties = codec.properties(live);
            properties.remove(SxCatalog.FINGERPRINT_PROPERTY);
            properties.putInt("sx_hash", second.hashCode());
            ItemStack legacy = codec.write(live, identity, properties);
            check(
                    b.update(null, legacy),
                    "sx_hash-only item upgrades even when legacy hash matches");
            check(
                    codec.properties(legacy).getString(SxCatalog.FINGERPRINT_PROPERTY).isPresent()
                            && !b.update(null, legacy),
                    "legacy upgrade installs one-time fingerprint");
            check(
                    codec.properties(legacy).getInt("sx_hash").orElseThrow() == second.hashCode(),
                    "fingerprint upgrade preserves current sx_hash compatibility data");
            properties.putString("source", "other");
            ItemStack wrongSource = codec.write(live, identity, properties);
            ItemStack before = wrongSource.clone();
            check(
                    !b.update(null, wrongSource) && wrongSource.equals(before),
                    "foreign source remains untouched");
            properties.putString("source", "sx");
            ItemStack unknown =
                    codec.write(live, new ItemIdentity("Missing", identity.rolls()), properties);
            before = unknown.clone();
            check(
                    !b.update(null, unknown) && unknown.equals(before),
                    "unknown saved identity remains untouched");
        }
    }

    private void globalAndScripts(JavaPlugin plugin) {
        Map<String, Object> config =
                item(Map.of("Name", "<s:unlocked>", "Lore", List.of("<l:locked>")));
        try (SxCatalog a =
                        catalog(
                                plugin,
                                config,
                                Map.of(),
                                Map.of("unlocked", "before", "locked", "saved"),
                                Map.of());
                SxCatalog b =
                        catalog(
                                plugin,
                                config,
                                Map.of(),
                                Map.of("unlocked", "after", "locked", "changed"),
                                Map.of())) {
            ItemStack live = a.generate("Entry", null, Map.of());
            check(
                    b.update(null, live) && live.getItemMeta().getDisplayName().equals("after"),
                    "global-only input change updates unlocked output");
            check(
                    live.getItemMeta().getLore().equals(List.of("saved")),
                    "global-only update retains previously locked rolls");
        }
        config = item(Map.of("Name", "<j:Local.value>"));
        Map<String, String> aScripts = new LinkedHashMap<>();
        aScripts.put("Global/base.js", "var shared = 'before';");
        aScripts.put("Local.js", "function value() { return shared; }");
        Map<String, String> bScripts = new LinkedHashMap<>(aScripts);
        bScripts.put("Global/base.js", "var shared = 'after';");
        try (SxCatalog a = catalog(plugin, config, Map.of(), Map.of(), aScripts);
                SxCatalog b = catalog(plugin, config, Map.of(), Map.of(), bScripts)) {
            ItemStack live = a.generate("Entry", null, Map.of());
            check(
                    b.update(null, live) && live.getItemMeta().getDisplayName().equals("after"),
                    "shared script source change updates observed output");
        }
        bScripts.put("Local.js", "function value() { return shared + '!'; }");
        try (SxCatalog a = catalog(plugin, config, Map.of(), Map.of(), aScripts);
                SxCatalog b = catalog(plugin, config, Map.of(), Map.of(), bScripts)) {
            ItemStack live = a.generate("Entry", null, Map.of());
            check(
                    b.update(null, live) && live.getItemMeta().getDisplayName().equals("after!"),
                    "local script source change updates observed output");
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("A.js", "var initial = 'a';");
        ordered.putAll(bScripts);
        Map<String, String> reordered = new LinkedHashMap<>(bScripts);
        reordered.put("A.js", "var initial = 'a';");
        try (SxCatalog a = catalog(plugin, config, Map.of(), Map.of(), ordered);
                SxCatalog b = catalog(plugin, config, Map.of(), Map.of(), reordered)) {
            check(
                    b.update(null, a.generate("Entry", null, Map.of())),
                    "local route encounter order participates in revision");
        }
    }

    private void settings(JavaPlugin plugin) {
        Map<String, Object> config = item(Map.of("Name", "<c:1/3> <t:0>"));
        try (SxCatalog a =
                        catalog(
                                plugin,
                                config,
                                Map.of("DecimalPrecision", 2, "TimeFormat", "'before'"),
                                Map.of(),
                                Map.of());
                SxCatalog b =
                        catalog(
                                plugin,
                                config,
                                Map.of("DecimalPrecision", 3, "TimeFormat", "'after'"),
                                Map.of(),
                                Map.of())) {
            ItemStack live = a.generate("Entry", null, Map.of());
            check(
                    live.getItemMeta().getDisplayName().equals("0.33 before"),
                    "initial decimal/time settings render");
            check(
                    b.update(null, live)
                            && live.getItemMeta().getDisplayName().equals("0.333 after"),
                    "settings-only change regenerates decimal/time output");
        }
        config = item(Map.of("NBT", Map.of("value", "candidate")));
        try (SxCatalog a = catalog(plugin, config, Map.of(), Map.of(), Map.of());
                SxCatalog b =
                        catalog(
                                plugin,
                                config,
                                Map.of("ProtectNBT", List.of("value")),
                                Map.of(),
                                Map.of())) {
            ItemStack live = a.generate("Entry", null, Map.of());
            CompoundTag custom = NmsItems.customData(live);
            custom.putString("value", "player");
            live = NmsItems.withCustomData(live, custom);
            check(
                    b.update(null, live)
                            && NmsItems.customData(live)
                                    .getString("value")
                                    .orElseThrow()
                                    .equals("player"),
                    "ProtectNBT-only settings revision changes update preservation");
        }
    }

    private void components(JavaPlugin plugin) {
        Map<String, Object> first =
                item(
                        Map.of(
                                "Name",
                                "old",
                                "ProtectNBT",
                                List.of("components.minecraft:custom_name")));
        Map<String, Object> second =
                item(
                        Map.of(
                                "Name",
                                "new",
                                "ProtectNBT",
                                List.of("components.minecraft:custom_name")));
        try (SxCatalog a = catalog(plugin, first, Map.of(), Map.of(), Map.of());
                SxCatalog b = catalog(plugin, second, Map.of(), Map.of(), Map.of())) {
            ItemStack present = a.generate("Entry", null, Map.of());
            check(
                    b.update(null, present) && present.getItemMeta().getDisplayName().equals("old"),
                    "protected old component value wins");
            var oldHandle = CraftItemStack.asNMSCopy(a.generate("Entry", null, Map.of()));
            oldHandle.remove(DataComponents.CUSTOM_NAME);
            ItemStack absent = CraftItemStack.asCraftMirror(oldHandle);
            check(
                    b.update(null, absent)
                            && !CraftItemStack.asNMSCopy(absent).has(DataComponents.CUSTOM_NAME),
                    "protected absence removes candidate explicit component");
        }
        first =
                item(
                        Map.of(
                                "ID",
                                "DIAMOND_SWORD",
                                "ClearAttribute",
                                true,
                                "ProtectNBT",
                                List.of("components.minecraft:attribute_modifiers")));
        second =
                item(
                        Map.of(
                                "ID",
                                "DIAMOND_SWORD",
                                "ProtectNBT",
                                List.of("components.minecraft:attribute_modifiers")));
        try (SxCatalog a = catalog(plugin, first, Map.of(), Map.of(), Map.of());
                SxCatalog b = catalog(plugin, second, Map.of(), Map.of(), Map.of())) {
            ItemStack absent = a.generate("Entry", null, Map.of());
            check(
                    !CraftItemStack.asNMSCopy(absent).has(DataComponents.ATTRIBUTE_MODIFIERS),
                    "material default absence fixture is absent");
            check(
                    CraftItemStack.asNMSCopy(b.generate("Entry", null, Map.of()))
                            .has(DataComponents.ATTRIBUTE_MODIFIERS),
                    "regenerated candidate starts with material default");
            check(
                    b.update(null, absent)
                            && !CraftItemStack.asNMSCopy(absent)
                                    .has(DataComponents.ATTRIBUTE_MODIFIERS),
                    "protected absence removes effective material default");
        }
    }

    private void nbt(JavaPlugin plugin) {
        Map<String, Object> first =
                item(Map.of("Name", "old", "ProtectNBT", List.of("tree.missing", "absent.child")));
        Map<String, Object> second =
                item(
                        Map.of(
                                "Name",
                                "new",
                                "ProtectNBT",
                                List.of("tree.missing", "absent.child"),
                                "NBT",
                                Map.of(
                                        "tree",
                                        Map.of("missing", "remove", "sibling", "keep"),
                                        "absent",
                                        "scalar")));
        try (SxCatalog a = catalog(plugin, first, Map.of(), Map.of(), Map.of());
                SxCatalog b = catalog(plugin, second, Map.of(), Map.of(), Map.of())) {
            ItemStack live = a.generate("Entry", null, Map.of());
            check(b.update(null, live), "protected NBT absence update succeeds");
            CompoundTag custom = NmsItems.customData(live);
            check(
                    !custom.getCompoundOrEmpty("tree").contains("missing")
                            && custom.getCompoundOrEmpty("tree")
                                    .getString("sibling")
                                    .orElseThrow()
                                    .equals("keep")
                            && custom.getString("absent").orElseThrow().equals("scalar"),
                    "NBT absence removes only requested leaf and retains scalar parent");
        }
        first = item(Map.of("NBT", Map.of("value", "old")));
        second = item(Map.of("NBT", Map.of("value", "new"), "ProtectNBT", List.of("!value")));
        try (SxCatalog a =
                        catalog(
                                plugin,
                                first,
                                Map.of("ProtectNBT", List.of("value")),
                                Map.of(),
                                Map.of());
                SxCatalog b =
                        catalog(
                                plugin,
                                second,
                                Map.of("ProtectNBT", List.of("value")),
                                Map.of(),
                                Map.of())) {
            ItemStack live = a.generate("Entry", null, Map.of());
            check(
                    b.update(null, live)
                            && NmsItems.customData(live)
                                    .getString("value")
                                    .orElseThrow()
                                    .equals("new"),
                    "per-item !path removes global protection");
        }
        for (String forbidden :
                List.of(
                        ItemStateCodec.KEY,
                        "components.custom_data",
                        "components.minecraft:custom_data.child")) {
            rejects(
                    () -> {
                        try (SxCatalog ignored =
                                catalog(
                                        plugin,
                                        item(Map.of("ProtectNBT", List.of(forbidden))),
                                        Map.of(),
                                        Map.of(),
                                        Map.of())) {}
                    },
                    "native identity protection rejected: " + forbidden);
        }
    }

    private void lifecycle(JavaPlugin plugin) {
        try (SxCatalog a =
                        catalog(plugin, item(Map.of("Name", "old")), Map.of(), Map.of(), Map.of());
                SxCatalog b =
                        catalog(
                                plugin,
                                item(Map.of("Name", "new")),
                                Map.of(),
                                Map.of(),
                                Map.of())) {
            ItemStack live = a.generate("Entry", null, Map.of());
            hook = event -> live.setAmount(9);
            check(
                    !b.update(null, live)
                            && live.getAmount() == 9
                            && live.getItemMeta().getDisplayName().equals("old"),
                    "event mutation of original live item prevents replacement");
            hook = event -> b.close();
            rejects(
                    () -> b.generate("Entry", null, Map.of()),
                    "event closing catalog rejects stale generated result");
            hook = null;
        }
        Map<String, SxRepository.Definition> definitions = new LinkedHashMap<>();
        definitions.put(
                "Entry",
                new SxRepository.Definition(
                        "Entry", "synthetic", new SxConfig(item(Map.of())), null));
        definitions.put("Alias", new SxRepository.Definition("Alias", "synthetic", null, "Entry"));
        ItemStack prototype = new ItemStack(Material.EMERALD, 4);
        definitions.put(
                "Imported",
                new SxRepository.Definition(
                        "Imported",
                        "synthetic",
                        new SxConfig(
                                Map.of(
                                        "Type", "Import", "Item", prototype, "Name", "ignored",
                                        "Update", true)),
                        null));
        try (SxCatalog catalog =
                new SxCatalog(
                        new SxRepository.Input(
                                new SxConfig(Map.of()), definitions, Map.of(), Map.of(), Map.of()),
                        plugin,
                        (viewer, token) -> null)) {
            List<String> requested = new ArrayList<>();
            hook = event -> requested.add(event.getId());
            ItemStack alias = catalog.generate("Alias", null, Map.of());
            check(
                    requested.equals(List.of("Alias"))
                            && codec.read(alias).orElseThrow().id().equals("Entry"),
                    "alias event retains requested ID while native identity is canonical");
            ItemStack imported = catalog.generate("Imported", null, Map.of());
            imported.setAmount(99);
            check(
                    catalog.generate("Imported", null, Map.of()).getAmount() == 4
                            && prototype.getAmount() == 4
                            && !imported.getItemMeta().hasDisplayName()
                            && !catalog.update(null, imported),
                    "Import clones fields, ignores defaults and disables automatic update");
            hook = null;
        }
    }

    private static Map<String, Object> item(Map<String, Object> fields) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("ID", "PAPER");
        config.put("Update", true);
        config.putAll(fields);
        return config;
    }

    private static SxCatalog catalog(
            JavaPlugin plugin,
            Map<String, Object> config,
            Map<String, Object> settings,
            Map<String, ?> random,
            Map<String, String> scripts) {
        return new SxCatalog(
                new SxRepository.Input(
                        new SxConfig(settings),
                        Map.of(
                                "Entry",
                                new SxRepository.Definition(
                                        "Entry", "synthetic", new SxConfig(config), null)),
                        SxRandom.compile(random),
                        scripts,
                        Map.of()),
                plugin,
                (viewer, token) -> null);
    }
}
