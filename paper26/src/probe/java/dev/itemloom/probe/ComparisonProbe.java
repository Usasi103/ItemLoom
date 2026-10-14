package dev.itemloom.probe;

import dev.itemloom.api.ComparisonStatProvider;
import dev.itemloom.api.EquipmentProvider;
import dev.itemloom.api.ItemLoom;
import dev.itemloom.api.ItemProvider;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.api.ProviderRegistration;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compare.EquipmentComparison;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Synthetic runtime check of snapshots, data/provider contracts, reload and the actual CE API. */
final class ComparisonProbe {
    private ComparisonProbe() {}

    static Map<String, Object> run(JavaPlugin plugin) throws Exception {
        ItemsService service = (ItemsService) Bukkit.getServicesManager().load(ItemLoom.class);
        Path configPath =
                Bukkit.getPluginManager()
                        .getPlugin("ItemLoom")
                        .getDataFolder()
                        .toPath()
                        .resolve("comparison.yml");
        byte[] original = Files.readAllBytes(configPath);
        List<String> checks = new ArrayList<>();
        ProviderRegistration equipment = null;
        ProviderRegistration stats = null;
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.load(configPath.toFile());
            config.set("slots.ring_1", "provider");
            config.set("attributes.power.provider-key", "attack");
            config.set("attributes.powerpct.provider-key", "attack%");
            config.set("attributes.powerpct.percent", true);
            config.set("attributes.powerpct.base-key", "attack");
            config.set("format.better", "+{diff}");
            config.set("format.worse", "-{diff}");
            config.set("format.equipped", "equipped");
            config.save(configPath.toFile());
            check(service.reload(Bukkit.getConsoleSender()), "valid comparison reload", checks);
            Player base = ProbePlayer.create("ComparisonProbe");
            AtomicInteger resends = new AtomicInteger();
            GameMode[] mode = {GameMode.SURVIVAL};
            Player[] player = new Player[1];
            Inventory top = Bukkit.createInventory(null, 9);
            InventoryView view =
                    (InventoryView)
                            Proxy.newProxyInstance(
                                    InventoryView.class.getClassLoader(),
                                    new Class<?>[] {InventoryView.class},
                                    (self, method, arguments) ->
                                            switch (method.getName()) {
                                                case "getTopInventory" -> top;
                                                case "getBottomInventory" -> base.getInventory();
                                                case "getPlayer" -> player[0];
                                                case "getType" -> InventoryType.CHEST;
                                                default -> null;
                                            });
            player[0] =
                    (Player)
                            Proxy.newProxyInstance(
                                    Player.class.getClassLoader(),
                                    new Class<?>[] {Player.class},
                                    (self, method, arguments) -> {
                                        return switch (method.getName()) {
                                            case "getOpenInventory" -> view;
                                            case "getItemOnCursor" -> new ItemStack(Material.AIR);
                                            case "getGameMode" -> mode[0];
                                            case "updateInventory" -> {
                                                resends.incrementAndGet();
                                                yield null;
                                            }
                                            case "getInventory" -> {
                                                if (!Bukkit.isPrimaryThread())
                                                    throw new AssertionError(
                                                            "Live inventory read off the server thread");
                                                yield base.getInventory();
                                            }
                                            default -> method.invoke(base, arguments);
                                        };
                                    });
            ItemStack equipped = tagged("equipped", "{\"attack\":5,\"attack%\":10}", false);
            ItemStack candidate = tagged("other", "attack=20;attack%=1", false);
            ItemStack[] current = {equipped};
            boolean[] eligible = {true};
            equipment =
                    service.registerEquipmentProvider(
                            plugin,
                            new EquipmentProvider() {
                                public Map<String, ItemStack> equipment(Player viewer) {
                                    return Map.of("ring_1", current[0]);
                                }

                                public boolean eligible(
                                        Player viewer, String slot, ItemStack item) {
                                    return eligible[0];
                                }
                            });
            stats =
                    service.registerComparisonStatProvider(
                            plugin,
                            new ComparisonStatProvider() {
                                public Map<String, BigDecimal> stats(
                                        Player viewer, String slot, ItemStack item) {
                                    return null;
                                }

                                public Map<String, BigDecimal> base(Player viewer) {
                                    return Map.of("attack", new BigDecimal("100"));
                                }
                            });
            check(
                    service.compare(player[0], candidate, "ring_1_power_20_other").equals("+6.00"),
                    "JSON equipped plus legacy candidate fixed/percent total",
                    checks);
            check(
                    service.compare(player[0], candidate, "ring_1_powerpct_1_other")
                            .equals("+6.00"),
                    "percentage line uses the same total",
                    checks);
            check(
                    service.compare(player[0], equipped, "equipped_ring_1_equipped")
                            .equals("equipped"),
                    "legacy uid equipped marker",
                    checks);
            current[0] = tagged("equipped", "attack=5;attack%=10", true);
            check(
                    service.compare(player[0], candidate, "ring_1_power_20_other").equals("+6.00"),
                    "native ItemLoom keys",
                    checks);

            var field = ItemsService.class.getDeclaredField("comparison");
            field.setAccessible(true);
            EquipmentComparison comparison = (EquipmentComparison) field.get(service);
            var refresh =
                    EquipmentComparison.class.getDeclaredMethod(
                            "refresh", Player.class, boolean.class);
            refresh.setAccessible(true);
            refresh.invoke(comparison, player[0], false);
            int survival = resends.get();
            check(survival == 1, "survival snapshot refresh resends once", checks);
            refresh.invoke(comparison, player[0], false);
            check(resends.get() == survival, "unchanged snapshot does not resend", checks);
            mode[0] = GameMode.CREATIVE;
            current[0] = tagged("equipped", "attack=6;attack%=10", false);
            refresh.invoke(comparison, player[0], false);
            check(
                    resends.get() == survival,
                    "creative changed snapshot keeps client cursor",
                    checks);
            check(
                    CompletableFuture.supplyAsync(
                                    () ->
                                            comparison.request(
                                                    player[0].getUniqueId(),
                                                    "ring_1_power_20_other"))
                            .join()
                            .equals("+5.00"),
                    "async snapshot has current values without inventory access",
                    checks);
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                var resolve =
                        papi.getMethod(
                                "setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
                check(
                        resolve.invoke(null, player[0], "%gearcompare_ring_1_power_20_other%")
                                .equals("+5.00"),
                        "legacy PAPI alias",
                        checks);
                check(
                        resolve.invoke(null, player[0], "%itemloom_ring_1_power_20_other%")
                                .equals("+5.00"),
                        "native PAPI identifier",
                        checks);
            }
            service.registerComparisonCandidate(
                    player[0], tagged("immediate", "attack=30;attack%=1", false));
            check(
                    comparison
                            .request(player[0].getUniqueId(), "ring_1_power_30_immediate")
                            .equals("+15.00"),
                    "candidate published before the first outgoing request",
                    checks);
            AtomicInteger recipes = new AtomicInteger();
            AtomicInteger events = new AtomicInteger();
            org.bukkit.event.Listener generation =
                    new org.bukkit.event.Listener() {
                        @org.bukkit.event.EventHandler
                        public void generated(ItemGenerateEvent event) {
                            if (!event.getId().equals("comparisonprobe:generated")) return;
                            events.incrementAndGet();
                            event.getItem()
                                    .editPersistentDataContainer(
                                            pdc ->
                                                    pdc.set(
                                                            new NamespacedKey(
                                                                    "gearcompare", "stats"),
                                                            PersistentDataType.STRING,
                                                            "attack=30;attack%=1"));
                        }
                    };
            Bukkit.getPluginManager().registerEvents(generation, plugin);
            try (ProviderRegistration recipe =
                    service.registerProvider(
                            plugin,
                            "comparisonprobe",
                            new ItemProvider(
                                    Map.of(
                                            "generated",
                                            context -> {
                                                recipes.incrementAndGet();
                                                return tagged(
                                                        "generated", "attack=20;attack%=1", false);
                                            }),
                                    Map.of()))) {
                service.create("comparisonprobe:generated", player[0], Map.of());
                check(
                        comparison
                                .request(player[0].getUniqueId(), "ring_1_power_1_generated")
                                .equals("+15.00"),
                        "create publishes final event-modified stats before first render",
                        checks);
                check(
                        recipes.get() == 1 && events.get() == 1,
                        "candidate publication does not reroll or repeat generation events",
                        checks);
            } finally {
                org.bukkit.event.HandlerList.unregisterAll(generation);
            }
            refresh.invoke(comparison, player[0], false);
            check(
                    resends.get() == survival,
                    "new candidate does not force an unrelated inventory resend",
                    checks);
            eligible[0] = false;
            check(
                    service.compare(player[0], candidate, "ring_1_power_20_other").equals("+21.00"),
                    "ineligible equipped item is excluded",
                    checks);
            eligible[0] = true;
            Files.writeString(configPath, "slots: [broken\n");
            byte[] broken = Files.readAllBytes(configPath);
            check(
                    !service.reload(Bukkit.getConsoleSender()),
                    "invalid comparison reload rejected",
                    checks);
            check(
                    java.util.Arrays.equals(broken, Files.readAllBytes(configPath)),
                    "invalid reload leaves file bytes unchanged",
                    checks);
            check(
                    service.compare(player[0], candidate, "ring_1_power_20_other").equals("+5.00"),
                    "invalid reload retains prior settings and providers",
                    checks);
            Files.write(configPath, original);
            craftEngine(service, checks);
            equipment.close();
            equipment.close();
            equipment = null;
            check(
                    service.compare(player[0], candidate, "ring_1_power_20_other").equals("+21.00"),
                    "idempotent equipment registration removal",
                    checks);
            return Map.of(
                    "passed",
                    true,
                    "checks",
                    checks,
                    "limits",
                    List.of(
                            "synthetic player, no connected client or multiplayer load",
                            "CE checks run only when CraftEngine is installed"));
        } finally {
            if (equipment != null) equipment.close();
            if (stats != null) stats.close();
            Files.write(configPath, original);
            service.reload(Bukkit.getConsoleSender());
        }
    }

    private static ItemStack tagged(String uid, String values, boolean nativeKeys) {
        ItemStack item = new ItemStack(Material.DIAMOND);
        String namespace = nativeKeys ? "itemloom" : "gearcompare";
        item.editPersistentDataContainer(
                pdc -> {
                    pdc.set(
                            new NamespacedKey(namespace, "slot"),
                            PersistentDataType.STRING,
                            "ring_1");
                    pdc.set(new NamespacedKey(namespace, "uid"), PersistentDataType.STRING, uid);
                    pdc.set(
                            new NamespacedKey(namespace, "stats"),
                            PersistentDataType.STRING,
                            values);
                });
        return item;
    }

    private static void craftEngine(ItemLoom service, List<String> checks) throws Exception {
        var ce = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (ce == null || !ce.isEnabled()) return;
        ClassLoader loader = ce.getClass().getClassLoader();
        String root = "net.momirealms.craftengine.";
        Class<?> engineType = Class.forName(root + "core.plugin.CraftEngine", true, loader);
        Class<?> registryType =
                Class.forName(
                        root + "core.plugin.compatibility.CompatibilityManager", true, loader);
        Class<?> sourceType =
                Class.forName(root + "core.plugin.compatibility.ItemSource", true, loader);
        Class<?> contextType = Class.forName(root + "core.item.ItemBuildContext", true, loader);
        Class<?> itemType = Class.forName(root + "core.item.Item", true, loader);
        Object engine = engineType.getMethod("instance").invoke(null);
        Object registry = engineType.getMethod("compatibilityManager").invoke(engine);
        Object source =
                registryType.getMethod("getItemSource", String.class).invoke(registry, "ItemLoom");
        check(source != null, "CraftEngine native source registered", checks);
        String id =
                service.ids().stream()
                        .filter(value -> !value.contains(":"))
                        .sorted()
                        .findFirst()
                        .orElseThrow();
        Object context = contextType.getMethod("empty").invoke(null);
        Object wrapped =
                sourceType
                        .getMethod("build", String.class, contextType)
                        .invoke(source, id, context);
        check(wrapped != null, "CraftEngine native source builds an ItemLoom definition", checks);
        check(
                id.equals(sourceType.getMethod("id", itemType).invoke(source, wrapped)),
                "CraftEngine native source identifies the generated item",
                checks);
        check(
                sourceType
                                .getMethod("build", String.class, contextType)
                                .invoke(source, "comparison_missing", context)
                        == null,
                "CraftEngine missing id returns null",
                checks);
    }

    private static void check(boolean passed, String description, List<String> checks) {
        if (!passed) throw new AssertionError(description);
        checks.add(description);
    }
}
