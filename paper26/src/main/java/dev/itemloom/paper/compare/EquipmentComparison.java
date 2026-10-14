package dev.itemloom.paper.compare;

import dev.itemloom.api.ComparisonStatProvider;
import dev.itemloom.api.EquipmentProvider;
import dev.itemloom.api.ProviderRegistration;
import dev.itemloom.core.compare.Comparison;
import dev.itemloom.core.compare.ComparisonModel.Settings;
import dev.itemloom.core.compare.ComparisonModel.Slot;
import dev.itemloom.core.compare.ComparisonModel.Snapshot;
import dev.itemloom.paper.ItemsService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Live reads occur on the main thread; each placeholder captures a coherent immutable revision. */
public final class EquipmentComparison implements AutoCloseable, Listener {
    private record Published(Settings settings, Map<UUID, Snapshot> players) {}

    private final JavaPlugin plugin;
    private final Map<Plugin, EquipmentProvider> equipment = new LinkedHashMap<>();
    private final Map<Plugin, ComparisonStatProvider> stats = new LinkedHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final LinkedHashMap<String, Map<String, BigDecimal>> candidates =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<UUID, LinkedHashMap<String, Map<String, BigDecimal>>> viewerCandidates =
            new LinkedHashMap<>();
    private volatile Published published;
    private BukkitTask sweep;
    private ComparisonListener listener;
    private boolean started;
    private volatile boolean closed;

    public EquipmentComparison(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void install(Settings settings) {
        ItemsService.requireThread();
        published = new Published(settings, Map.of());
        if (started) {
            startSweep();
            refreshAll();
        }
    }

    public void start() {
        if (started) return;
        started = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listener = new ComparisonListener(this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        startSweep();
        refreshAll();
    }

    private void startSweep() {
        if (sweep != null) sweep.cancel();
        sweep = null;
        int ticks = published.settings().sweepTicks();
        if (ticks > 0)
            sweep = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, ticks, ticks);
    }

    public ProviderRegistration register(Plugin owner, EquipmentProvider provider) {
        return registration(owner, Objects.requireNonNull(provider), equipment);
    }

    public ProviderRegistration register(Plugin owner, ComparisonStatProvider provider) {
        return registration(owner, Objects.requireNonNull(provider), stats);
    }

    private <T> ProviderRegistration registration(
            Plugin owner, T provider, Map<Plugin, T> registry) {
        ItemsService.requireThread();
        Objects.requireNonNull(owner, "owner");
        if (closed || !owner.isEnabled())
            throw new IllegalStateException("Comparison provider owner is unavailable");
        if (registry.containsKey(owner))
            throw new IllegalArgumentException(
                    "Comparison provider already registered by " + owner.getName());
        registry.put(owner, provider);
        refreshAll();
        return () -> {
            ItemsService.requireThread();
            if (registry.remove(owner, provider) && !closed) refreshAll();
        };
    }

    public String request(UUID viewer, String parameters) {
        Published captured = published;
        return closed || captured == null || viewer == null
                ? ""
                : Comparison.resolve(
                        parameters, captured.players().get(viewer), captured.settings());
    }

    public void schedule(Player viewer) {
        ItemsService.requireThread();
        if (closed || !pending.add(viewer.getUniqueId())) return;
        UUID id = viewer.getUniqueId();
        Bukkit.getScheduler()
                .runTask(
                        plugin,
                        () -> {
                            pending.remove(id);
                            Player player = Bukkit.getPlayer(id);
                            if (!closed && player != null) refresh(player, false);
                        });
    }

    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) refresh(player, false);
    }

    public void remember(Player viewer, ItemStack item) {
        ItemsService.requireThread();
        if (closed || published == null || item == null || item.isEmpty()) return;
        String uid = tag(item, "uid");
        if (uid == null || uid.isBlank()) return;
        Map<String, BigDecimal> values = readStats(item);
        candidates.put(uid, Map.copyOf(values));
        while (candidates.size() > 512) candidates.remove(candidates.keySet().iterator().next());
        if (viewer != null) {
            for (ComparisonStatProvider provider : new ArrayList<>(stats.values())) {
                try {
                    Map<String, BigDecimal> supplied =
                            provider.stats(viewer, tag(item, "slot"), item.clone());
                    if (supplied != null) values = supplied;
                } catch (RuntimeException failure) {
                    plugin.getLogger()
                            .log(
                                    Level.WARNING,
                                    "Could not capture generated comparison stats",
                                    failure);
                }
            }
            var local =
                    viewerCandidates.computeIfAbsent(
                            viewer.getUniqueId(), ignored -> new LinkedHashMap<>(16, 0.75f, true));
            local.put(uid, Map.copyOf(values));
            while (local.size() > 128) local.remove(local.keySet().iterator().next());
        }
        // Generation is complete before remember is called. Publish candidate values immediately so
        // the first outgoing packet can compare the final item without another render or
        // generation.
        Map<UUID, Snapshot> updated = new LinkedHashMap<>(published.players());
        Map<String, BigDecimal> mapped = mapStats(values, published.settings());
        UUID recipient = viewer == null ? null : viewer.getUniqueId();
        for (var entry : published.players().entrySet()) {
            if (recipient != null && !recipient.equals(entry.getKey())) continue;
            Snapshot previous = entry.getValue();
            Map<String, Map<String, BigDecimal>> hovered =
                    new LinkedHashMap<>(previous.candidates());
            hovered.put(uid, mapped);
            while (hovered.size() > 768) hovered.remove(hovered.keySet().iterator().next());
            updated.put(entry.getKey(), new Snapshot(previous.slots(), previous.base(), hovered));
        }
        published = new Published(published.settings(), Map.copyOf(updated));
        if (viewer != null) schedule(viewer);
    }

    public String compare(Player viewer, ItemStack item, String parameters) {
        remember(viewer, item);
        Snapshot current = build(viewer, published.settings());
        return Comparison.resolve(parameters, current, published.settings());
    }

    private void refresh(Player player, boolean force) {
        if (closed || published == null || !player.isOnline()) return;
        Published old = published;
        Snapshot next;
        try {
            next = build(player, old.settings());
        } catch (RuntimeException failure) {
            // A failed eligibility reader must not keep showing excluded equipment as valid.
            next = new Snapshot(Map.of(), Map.of());
            plugin.getLogger()
                    .log(
                            Level.WARNING,
                            "Comparison snapshot failed for " + player.getUniqueId(),
                            failure);
        }
        Snapshot previous = old.players().get(player.getUniqueId());
        if (next.equals(previous) && !force) return;
        boolean gearChanged =
                previous == null
                        || !next.slots().equals(previous.slots())
                        || !next.base().equals(previous.base());
        Map<UUID, Snapshot> players = new LinkedHashMap<>(old.players());
        players.put(player.getUniqueId(), next);
        published = new Published(old.settings(), Map.copyOf(players));
        // Creative owns the cursor locally. Full inventory refresh would erase a picked-up stack.
        if ((force || gearChanged) && player.getGameMode() != GameMode.CREATIVE)
            player.updateInventory();
    }

    private Snapshot build(Player viewer, Settings settings) {
        PlayerInventory inventory = viewer.getInventory();
        Map<String, ItemStack> items = new LinkedHashMap<>();
        settings.slots()
                .forEach(
                        (slot, source) ->
                                items.put(
                                        slot,
                                        switch (source) {
                                            case "head" -> inventory.getHelmet();
                                            case "chest" -> inventory.getChestplate();
                                            case "legs" -> inventory.getLeggings();
                                            case "feet" -> inventory.getBoots();
                                            case "mainhand" -> inventory.getItemInMainHand();
                                            case "offhand" -> inventory.getItemInOffHand();
                                            default -> null;
                                        }));
        for (EquipmentProvider provider : new ArrayList<>(equipment.values())) {
            Map<String, ItemStack> supplied =
                    Objects.requireNonNull(provider.equipment(viewer), "Provider equipment");
            supplied.forEach(
                    (slot, item) -> {
                        if ("provider".equals(settings.slots().get(slot))) items.put(slot, item);
                    });
        }
        Map<String, BigDecimal> base = new LinkedHashMap<>();
        for (ComparisonStatProvider provider : new ArrayList<>(stats.values())) {
            Map<String, BigDecimal> supplied = provider.base(viewer);
            if (supplied != null) base.putAll(supplied);
        }
        Map<String, Slot> result = new LinkedHashMap<>();
        for (var entry : items.entrySet()) {
            String slot = entry.getKey();
            ItemStack item = entry.getValue();
            if (item == null || item.isEmpty()) {
                result.put(slot, Slot.EMPTY);
                continue;
            }
            boolean eligible = true;
            for (EquipmentProvider provider : new ArrayList<>(equipment.values())) {
                if (!provider.eligible(viewer, slot, item.clone())) eligible = false;
            }
            if (!eligible) {
                result.put(slot, Slot.EMPTY);
                continue;
            }
            String taggedSlot = tag(item, "slot");
            String uid = tag(item, "uid");
            if (taggedSlot != null
                    && !slot.equals(taggedSlot.trim().toLowerCase(java.util.Locale.ROOT))) {
                result.put(slot, Slot.PLAIN);
                continue;
            }
            Map<String, BigDecimal> values = mapStats(readStats(item), settings);
            boolean provided = false;
            for (ComparisonStatProvider provider : new ArrayList<>(stats.values())) {
                Map<String, BigDecimal> supplied = provider.stats(viewer, slot, item.clone());
                if (supplied != null) {
                    values = mapStats(supplied, settings);
                    provided = true;
                }
            }
            result.put(
                    slot,
                    taggedSlot == null && !provided ? Slot.PLAIN : new Slot(false, uid, values));
        }
        Map<String, Map<String, BigDecimal>> hovered = new LinkedHashMap<>();
        candidates.forEach((uid, values) -> hovered.put(uid, mapStats(values, settings)));
        viewerCandidates
                .getOrDefault(viewer.getUniqueId(), new LinkedHashMap<>())
                .forEach((uid, values) -> hovered.put(uid, mapStats(values, settings)));
        for (ItemStack item : viewer.getInventory().getContents())
            capture(viewer, item, settings, hovered);
        for (ItemStack item : viewer.getOpenInventory().getTopInventory().getContents())
            capture(viewer, item, settings, hovered);
        capture(viewer, viewer.getItemOnCursor(), settings, hovered);
        return new Snapshot(result, base, hovered);
    }

    private void capture(
            Player viewer,
            ItemStack item,
            Settings settings,
            Map<String, Map<String, BigDecimal>> target) {
        if (item == null || item.isEmpty()) return;
        String uid = tag(item, "uid");
        if (uid == null || uid.isBlank()) return;
        Map<String, BigDecimal> values = readStats(item);
        for (ComparisonStatProvider provider : new ArrayList<>(stats.values())) {
            Map<String, BigDecimal> supplied =
                    provider.stats(viewer, tag(item, "slot"), item.clone());
            if (supplied != null) values = supplied;
        }
        target.put(uid, mapStats(values, settings));
    }

    private static Map<String, BigDecimal> readStats(ItemStack item) {
        String text = tag(item, "stats");
        if (text == null || !text.stripLeading().startsWith("{"))
            return Comparison.parseStats(text);
        try {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            com.google.gson.JsonObject object =
                    com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            object.entrySet()
                    .forEach(
                            entry -> {
                                BigDecimal value =
                                        Comparison.number(entry.getValue().getAsString());
                                if (value != null) result.put(entry.getKey(), value);
                            });
            return Map.copyOf(result);
        } catch (RuntimeException malformed) {
            return Map.of();
        }
    }

    private static Map<String, BigDecimal> mapStats(
            Map<String, BigDecimal> source, Settings settings) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> {
                    if (Comparison.KEY.matcher(key).matches() && value != null)
                        result.put(key, value);
                });
        settings.attributes()
                .forEach(
                        (key, attribute) -> {
                            BigDecimal value = source.get(attribute.providerKey());
                            if (value != null) result.put(key, value);
                        });
        return result;
    }

    private static String tag(ItemStack item, String key) {
        var pdc = item.getPersistentDataContainer();
        String nativeValue = pdc.get(new NamespacedKey("itemloom", key), PersistentDataType.STRING);
        return nativeValue != null
                ? nativeValue
                : pdc.get(new NamespacedKey("gearcompare", key), PersistentDataType.STRING);
    }

    @EventHandler
    public void joined(PlayerJoinEvent event) {
        refresh(event.getPlayer(), false);
        Bukkit.getScheduler().runTask(plugin, () -> refresh(event.getPlayer(), true));
    }

    @EventHandler
    public void left(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        viewerCandidates.remove(id);
        if (published == null) return;
        Map<UUID, Snapshot> players = new LinkedHashMap<>(published.players());
        players.remove(id);
        published = new Published(published.settings(), Map.copyOf(players));
    }

    @EventHandler
    public void disabled(PluginDisableEvent event) {
        boolean changed = equipment.remove(event.getPlugin()) != null;
        changed |= stats.remove(event.getPlugin()) != null;
        if (changed && !closed) refreshAll();
    }

    @Override
    public void close() {
        closed = true;
        if (sweep != null) sweep.cancel();
        equipment.clear();
        stats.clear();
        pending.clear();
        candidates.clear();
        viewerCandidates.clear();
        published = null;
        HandlerList.unregisterAll(this);
        if (listener != null) HandlerList.unregisterAll(listener);
    }
}
