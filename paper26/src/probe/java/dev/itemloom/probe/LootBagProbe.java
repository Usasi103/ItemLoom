package dev.itemloom.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.api.ItemProvider;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.action.ReturnLedger;
import dev.itemloom.paper.integration.MythicLootTables;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.block.BlockFace;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual dispatcher/ledger/NMS and optional MM API, with synthetic player inventories. */
final class LootBagProbe implements Listener {
    private final List<String> checks = new ArrayList<>();
    private ItemsService items;
    private boolean cancelDrops;
    private int spawns;
    private final List<org.bukkit.entity.Item> spawned = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final JavaPlugin plugin;

    private LootBagProbe(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    static Map<String, Object> run(JavaPlugin plugin) throws Exception {
        return new LootBagProbe(plugin).run();
    }

    private Map<String, Object> run() throws Exception {
        Path root = plugin.getDataFolder().toPath().resolve("bag-fixture-" + UUID.randomUUID());
        Files.createDirectories(root.resolve("Items"));
        Files.createDirectories(root.resolve("ItemPacks"));
        StringBuilder definitions = new StringBuilder("Reward:\n  material: STONE\n");
        for (String id :
                List.of(
                        "Shared",
                        "Inline",
                        "Pack",
                        "Empty",
                        "Disabled",
                        "Missing",
                        "Callback",
                        "Overflow",
                        "Huge",
                        "HugePack",
                        "Snapshot",
                        "Mythic")) definitions.append(id).append(":\n  material: PAPER\n");
        Files.writeString(root.resolve("Items/bags.yml"), definitions);
        Files.writeString(
                root.resolve("ItemPacks/rewards.yml"),
                "NativePack:\n  Items: ['Reward 3 1 false']\nEmptyPack:\n  Items: []\nHugePack:\n  Items: ['Reward 1000000 1 true']\n");
        String config =
                """
                tables:
                  shared:
                    rolls: 2
                    entries:
                    - {item: 'minecraft:diamond', amount: 2}
                bags:
                  Shared: {table: shared, legacy-id: probe_legacy_bag}
                  Inline:
                    loot:
                      rolls: {min: 2, max: 3}
                      entries:
                      - {item: Reward, amount: 2, weight: 1}
                  Pack: {pack: NativePack}
                  HugePack: {pack: HugePack}
                  Empty: {pack: EmptyPack}
                  Disabled: {enabled: false}
                  Missing:
                    loot:
                      entries: [{item: UnknownReward}]
                  Callback:
                    loot:
                      entries: [{item: 'bagprobe:callback'}]
                  Overflow:
                    loot:
                      entries: [{item: 'minecraft:emerald', amount: 3}]
                  Snapshot:
                    loot:
                      rolls: 2
                      entries: [{item: 'minecraft:emerald', amount: 3}]
                  Huge:
                    loot:
                      rolls: 64
                      entries: [{item: 'minecraft:diamond_sword', amount: 64}]
                  Mythic: {mythic: ILBagNested}
                """;
        Files.writeString(root.resolve("loot-bags.yml"), config);
        items = new ItemsService(plugin, root, (viewer, text) -> null, root.resolve("ledger.json"));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            check(
                    items.reload(Bukkit.getConsoleSender()),
                    "bag configuration loads as a complete candidate");
            Player shared = player("Shared", 2);
            click(shared, EquipmentSlot.HAND);
            check(
                    shared.getInventory().getItem(0).getAmount() == 1
                            && count(shared, Material.DIAMOND) == 4,
                    "shared table consumes one and delivers both rolls");
            click(shared, EquipmentSlot.HAND);
            check(
                    shared.getInventory().getItem(0).getAmount() == 1
                            && count(shared, Material.DIAMOND) == 4,
                    "duplicate right-click same tick does not consume or reward twice");
            Player inline = player("Inline", 1);
            click(inline, EquipmentSlot.HAND);
            check(
                    inline.getInventory().getItem(0).isEmpty()
                            && List.of(4, 6).contains(count(inline, Material.STONE)),
                    "inline dedicated table uses bounded random roll count");
            Player pack = player("Pack", 1);
            click(pack, EquipmentSlot.HAND);
            check(
                    pack.getInventory().getItem(0).isEmpty() && count(pack, Material.STONE) == 3,
                    "NI item pack is referenced without duplicating its definition");
            for (String id : List.of("Empty", "Disabled", "Missing", "Huge", "HugePack")) {
                Player player = player(id, 1);
                click(player, EquipmentSlot.HAND);
                check(
                        player.getInventory().getItem(0).getAmount() == 1
                                && count(player, Material.STONE) == 0,
                        id + " does not consume the bag");
            }
            Player old = player("Shared", 2);
            CompoundTag data = new CompoundTag(), itemloom = new CompoundTag();
            itemloom.putString("loot_bag", "probe_legacy_bag");
            data.put("itemloom", itemloom);
            old.getInventory()
                    .setItem(0, NmsItems.withCustomData(new ItemStack(Material.PAPER, 2), data));
            click(old, EquipmentSlot.HAND);
            check(
                    old.getInventory().getItem(0).getAmount() == 1
                            && count(old, Material.DIAMOND) == 4,
                    "old CE marker opens without CE or NI loaded");
            Player visual = player("Shared", 1);
            visual.getInventory().setItem(0, new ItemStack(Material.PAPER));
            click(visual, EquipmentSlot.HAND);
            check(
                    count(visual, Material.DIAMOND) == 0
                            && visual.getInventory().getItem(0).getAmount() == 1,
                    "plain paper never inherits bag behavior");
            Player offhand = player("Shared", 1);
            offhand.getInventory().setItem(40, offhand.getInventory().getItem(0).clone());
            click(offhand, EquipmentSlot.OFF_HAND);
            check(
                    count(offhand, Material.DIAMOND) == 0
                            && offhand.getInventory().getItem(40).getAmount() == 1,
                    "offhand event is claimed without opening");
            Player denied = player("Shared", 1);
            PlayerInteractEvent blocked = event(denied, EquipmentSlot.HAND);
            blocked.setUseItemInHand(Event.Result.DENY);
            Bukkit.getPluginManager().callEvent(blocked);
            check(
                    count(denied, Material.DIAMOND) == 0
                            && denied.getInventory().getItem(0).getAmount() == 1,
                    "explicit prior item-use denial is honored");
            for (String mode : List.of("replace", "reload", "reenter", "throw")) {
                Player callback = player("Callback", 1);
                try (var registration =
                        items.registerProvider(
                                plugin,
                                "bagprobe",
                                new ItemProvider(
                                        Map.of(
                                                "callback",
                                                ctx -> {
                                                    switch (mode) {
                                                        case "replace" ->
                                                                callback.getInventory()
                                                                        .setItem(
                                                                                0,
                                                                                callback.getInventory()
                                                                                        .getItem(0)
                                                                                        .clone());
                                                        case "reload" ->
                                                                items.reload(
                                                                        Bukkit.getConsoleSender());
                                                        case "reenter" ->
                                                                click(callback, EquipmentSlot.HAND);
                                                        case "throw" ->
                                                                throw new IllegalStateException(
                                                                        "expected bag generator failure");
                                                    }
                                                    return new ItemStack(Material.DIAMOND);
                                                })))) {
                    click(callback, EquipmentSlot.HAND);
                    boolean success = mode.equals("reenter");
                    check(
                            count(callback, Material.DIAMOND) == (success ? 1 : 0)
                                    && callback.getInventory().getItem(0).isEmpty() == success,
                            "generation "
                                    + mode
                                    + " preserves physical source/revision and prevents reentry");
                }
            }
            Object before = items.placeholderRevision();
            Files.writeString(
                    root.resolve("loot-bags.yml"),
                    config.replace("table: shared", "table: unknown"));
            check(
                    !items.reload(Bukkit.getConsoleSender())
                            && before == items.placeholderRevision(),
                    "unknown table rolls back reload");
            Files.writeString(
                    root.resolve("loot-bags.yml"),
                    config.replace(
                            "Shared: {table: shared,",
                            "Shared: {pack: NativePack, table: shared,"));
            check(
                    !items.reload(Bukkit.getConsoleSender())
                            && before == items.placeholderRevision(),
                    "ambiguous reward sources rejected");
            Files.writeString(root.resolve("loot-bags.yml"), config);
            check(items.reload(Bukkit.getConsoleSender()), "valid reload restores rules");
            var field = ItemsService.class.getDeclaredField("players");
            field.setAccessible(true);
            ReturnLedger ledger = ((PlayerActionState) field.get(items)).returnLedger();
            check(
                    ledger.diagnostics().isEmpty(),
                    "completed and aborted generations leave no reward obligations");
            Player full = player("Overflow", 1);
            fill(full);
            full.getWorld().getChunkAt(full.getLocation()).load();
            click(full, EquipmentSlot.HAND);
            check(
                    full.getInventory().getItem(0).isEmpty()
                            && spawns == 1
                            && spawned.getLast().getItemStack().getAmount() == 3,
                    "full inventory drops exactly the prepared remainder");
            cancelDrops = true;
            Player cancelled = player("Overflow", 1);
            fill(cancelled);
            click(cancelled, EquipmentSlot.HAND);
            check(
                    cancelled.getInventory().getItem(0).isEmpty()
                            && ledger.diagnostics().size() == 1
                            && ledger.diagnostics().getFirst().status()
                                    == ReturnLedger.Status.UNKNOWN,
                    "cancelled spawn quarantines reward without refunding consumed bag");
            int attempts = spawns;
            ledger.flushAll();
            check(spawns == attempts, "uncertain reward is not automatically replayed");
            check(
                    items.reload(Bukkit.getConsoleSender()) && ledger.diagnostics().size() == 1,
                    "reward diagnostic survives catalog reload");
            Player snapshot = player("Snapshot", 2);
            fill(snapshot);
            click(snapshot, EquipmentSlot.HAND);
            var records =
                    ledger.diagnostics().stream()
                            .filter(record -> record.source().equals("loot-bag:Snapshot"))
                            .toList();
            check(
                    records.size() == 2
                            && records.get(0).before() == records.get(1).before()
                            && records.get(0).prepared() == records.get(1).prepared(),
                    "multi-reward transaction shares immutable encoded source snapshots");
            snapshot.getInventory().getItem(0).setAmount(9);
            check(
                    ItemStack.deserializeBytes(
                                                    java.util.Base64.getDecoder()
                                                            .decode(records.getFirst().before()))
                                            .getAmount()
                                    == 2
                            && ItemStack.deserializeBytes(
                                                    java.util.Base64.getDecoder()
                                                            .decode(records.getFirst().prepared()))
                                            .getAmount()
                                    == 1,
                    "shared diagnostics retain before/after quantities after live source changes");
            if (Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) mythic();
            else {
                Player noMm = player("Mythic", 1);
                click(noMm, EquipmentSlot.HAND);
                check(
                        noMm.getInventory().getItem(0).getAmount() == 1,
                        "missing optional MythicMobs retains bag");
            }
            return Map.of("passed", true, "checks", checks);
        } finally {
            HandlerList.unregisterAll(this);
            spawned.forEach(org.bukkit.entity.Item::remove);
            if (items != null) items.close();
        }
    }

    private void mythic() throws Exception {
        var world = (org.bukkit.craftbukkit.CraftWorld) Bukkit.getWorlds().getFirst();
        var handle =
                new net.minecraft.server.level.ServerPlayer(
                        ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).getServer(),
                        world.getHandle(),
                        new com.mojang.authlib.GameProfile(UUID.randomUUID(), "IL_Bag"),
                        net.minecraft.server.level.ClientInformation.createDefault());
        Player player = handle.getBukkitEntity();
        var drops = MythicLootTables.generate("ILBagNested", player);
        check(
                drops.stream().mapToInt(ItemStack::getAmount).sum() == 4
                        && drops.stream().allMatch(i -> i.getType() == Material.DIAMOND),
                "actual MM nested table controls repeat amount and zero-probability entries");
        var nativeItems = MythicLootTables.generate("ILBagItemLoom", player);
        check(
                nativeItems.size() == 1
                        && nativeItems.getFirst().getType() == Material.STONE
                        && nativeItems.getFirst().getAmount() == 3,
                "MM native itemloom reward remains connected to generator");
        try {
            MythicLootTables.generate("ILBagBad", player);
            throw new AssertionError("nonitem table accepted");
        } catch (IllegalArgumentException expected) {
            checks.add("MM experience/command rewards rejected before giving anything");
        }
        try {
            MythicLootTables.generate("ILBagAbsent", player);
            throw new AssertionError("absent table accepted");
        } catch (IllegalArgumentException expected) {
            checks.add("unknown MM table rejected");
        }
        checks.addAll(MythicTableProbe.run(player));
    }

    private Player player(String id, int amount) {
        Player player = ProbePlayer.create("Bag" + players.size());
        players.add(player);
        items.joined(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()));
        ItemStack item = items.create(id, player, Map.of());
        item.setAmount(amount);
        player.getInventory().setItem(0, item);
        return player;
    }

    private static void fill(Player player) {
        for (int slot = 1; slot < 36; slot++)
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
    }

    private static int count(Player player, Material type) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            var item = player.getInventory().getItem(i);
            if (item != null && item.getType() == type) count += item.getAmount();
        }
        return count;
    }

    private static PlayerInteractEvent event(Player player, EquipmentSlot hand) {
        return new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_AIR,
                player.getInventory().getItem(hand),
                null,
                BlockFace.SELF,
                hand);
    }

    private static void click(Player player, EquipmentSlot hand) {
        Bukkit.getPluginManager().callEvent(event(player, hand));
    }

    @EventHandler
    public void spawned(ItemSpawnEvent event) {
        if (event.getEntity().getItemStack().getType() != Material.EMERALD) return;
        spawns++;
        spawned.add(event.getEntity());
        if (cancelDrops) event.setCancelled(true);
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks.add(label);
    }
}
