package dev.itemloom.probe;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.action.ItemDurabilityService.DamageResult;
import dev.itemloom.paper.action.ItemListeners;
import dev.itemloom.paper.action.ItemMaintenance;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Synthetic events verify retained runtime integration; this probe has no connected client. */
public final class RuntimeRulesProbe {
    private static final ItemStateCodec CODEC = new ItemStateCodec();
    private static final String CALLBACKS = "runtime-rule-callbacks";
    private static final String EXPANSION =
            """
            var actions = Java.type('pers.neige.neigeitems.manager.ActionManager').INSTANCE;
            var players = Java.type('pers.neige.neigeitems.utils.PlayerUtils');
            function enable() {
                actions.addConsumer('runtime-rule', false, function(context, text) {
                    players.getMetadataEZ(context.getPlayer(), 'runtime-rule-callbacks', null)
                        .get(String(text)).accept(context);
                });
            }
            """;

    private RuntimeRulesProbe() {}

    public static Map<String, Object> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Runtime rule probe requires server thread");
        List<String> checks = new ArrayList<>();
        for (String edit :
                List.of("component", "replace", "move", "identity", "reconnect", "close")) {
            try (Fixture fixture = new Fixture(plugin, edit)) {
                fixture.hold(Material.APPLE, 3, Map.of("charge", 5));
                fixture.first =
                        context -> {
                            fixture.bodySawCommitted =
                                    context.getItemStack() == fixture.hand()
                                            && fixture.hand().getAmount() == 1
                                            && property(fixture.hand(), "charge") == 3;
                            fixture.retainedFacadeUpdated =
                                    fixture.preTag.getCompound("NeigeItems").getInt("charge") == 3
                                            && context.getData() == fixture.preData;
                            switch (edit) {
                                case "component" ->
                                        ((LegacyNbt.Compound) context.getNbt())
                                                .putString("body_marker", "keep");
                                case "replace" ->
                                        fixture.player
                                                .getInventory()
                                                .setItemInMainHand(fixture.hand().clone());
                                case "move" -> {
                                    fixture.player.getInventory().setItem(9, fixture.hand());
                                    fixture.player
                                            .getInventory()
                                            .setItemInMainHand(new ItemStack(Material.DIAMOND));
                                }
                                case "identity" ->
                                        ((LegacyNbt.Compound) context.getNbt())
                                                .getCompound("NeigeItems")
                                                .putString("id", "different");
                                case "reconnect" -> {
                                    fixture.state.quit(fixture.player.getUniqueId());
                                    fixture.state.join(fixture.player);
                                }
                                case "close" -> fixture.catalog.close();
                                default -> throw new AssertionError(edit);
                            }
                        };
                fixture.click();
                fixture.rethrowCallback();
                check(
                        checks,
                        fixture.firstCalls == 1 && fixture.bodySawCommitted,
                        edit + ": basic body sees committed charged unit");
                check(
                        checks,
                        fixture.retainedFacadeUpdated,
                        edit
                                + ": pre-exposed NBT and data facades retain correct charge and identity");
                check(
                        checks,
                        fixture.allCalls == (edit.equals("component") ? 1 : 0),
                        edit
                                + ": paired body respects physical ownership, item identity and revision/session");
                fixture.catalog.triggers().flushReturns(fixture.player);
                int untouched = 0;
                for (ItemStack item : fixture.player.getInventory().getContents())
                    if (item != null && !item.isEmpty() && property(item, "charge") == 5)
                        untouched += item.getAmount();
                check(
                        checks,
                        untouched == 2,
                        edit + ": exactly two unchanged charged units return after source commit");
            }
        }
        try (Fixture fixture = new Fixture(plugin, "eat")) {
            fixture.hold(Material.APPLE, 3, Map.of("charge", 5));
            fixture.first =
                    context -> ((PlayerItemConsumeEvent) context.getEvent()).setCancelled(false);
            var event =
                    new PlayerItemConsumeEvent(fixture.player, fixture.hand(), EquipmentSlot.HAND);
            fixture.listeners.consume(event);
            fixture.rethrowCallback();
            fixture.catalog.triggers().flushReturns(fixture.player);
            check(
                    checks,
                    event.isCancelled()
                            && fixture.firstCalls == 1
                            && property(fixture.hand(), "charge") == 3,
                    "food body cannot re-enable vanilla consumption of committed charge result");
        }
        try (Fixture fixture = new Fixture(plugin, "durability")) {
            try (ItemMaintenance maintenance = new ItemMaintenance(fixture.catalog, plugin)) {
                for (boolean eventBased : List.of(false, true)) {
                    ItemStack tool =
                            fixture.hold(
                                    Material.IRON_SWORD,
                                    4,
                                    Map.of(
                                            "durability",
                                            2,
                                            "maxDurability",
                                            20,
                                            "itemBreak",
                                            true));
                    CraftItemStack.unwrap(tool).set(DataComponents.DAMAGE, 7);
                    var event =
                            eventBased ? new PlayerItemDamageEvent(fixture.player, tool, 3) : null;
                    DamageResult result =
                            maintenance
                                    .durability()
                                    .damage(fixture.player, tool, 3, eventBased, event);
                    check(
                            checks,
                            result == DamageResult.BREAK
                                    && tool.getAmount() == 1
                                    && property(tool, "durability") == 2,
                            eventBased
                                    + ": breaking retained unit keeps original custom durability");
                    if (event != null)
                        check(
                                checks,
                                event.getDamage() == tool.getType().getMaxDurability() - 7 + 1,
                                "lethal vanilla damage exceeds remaining ordinary durability by one");
                    fixture.catalog.triggers().flushReturns(fixture.player);
                    int returned = 0;
                    for (int slot = 1; slot < 36; slot++) {
                        ItemStack item = fixture.player.getInventory().getItem(slot);
                        if (!item.isEmpty()) {
                            check(
                                    checks,
                                    property(item, "durability") == 2
                                            && CraftItemStack.unwrap(item)
                                                            .getOrDefault(DataComponents.DAMAGE, 0)
                                                    == 7,
                                    eventBased
                                            + ": untouched durability remainder preserves original state");
                            returned += item.getAmount();
                        }
                    }
                    check(
                            checks,
                            returned == 3,
                            eventBased + ": damage returns exactly three unaffected units");
                }
            }
        }
        return Map.of(
                "passed",
                true,
                "checks",
                checks.size(),
                "assertions",
                checks,
                "syntheticPlayers",
                true,
                "realClient",
                false);
    }

    private static void check(List<String> checks, boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        checks.add(description);
    }

    private static int property(ItemStack item, String key) {
        return CODEC.properties(item).getInt(key).orElse(-1);
    }

    private static final class Fixture implements AutoCloseable {
        final Player player;
        final PlayerActionState state = new PlayerActionState();
        final NiCatalog catalog;
        final ItemListeners listeners;
        Consumer<NiActionContext> first = context -> {};
        LegacyNbt.Compound preTag;
        Map<String, String> preData;
        int firstCalls, allCalls;
        boolean bodySawCommitted, retainedFacadeUpdated;
        Throwable callbackFailure;

        Fixture(JavaPlugin plugin, String name) {
            player = ProbePlayer.create("RuntimeRules-" + name);
            state.join(player);
            Map<String, Consumer<NiActionContext>> callbacks = new HashMap<>();
            callbacks.put(
                    "pre",
                    context -> {
                        preTag = (LegacyNbt.Compound) context.getNbt();
                        preData = context.getData();
                    });
            callbacks.put(
                    "first",
                    context -> {
                        firstCalls++;
                        try {
                            first.accept(context);
                        } catch (Throwable error) {
                            callbackFailure = error;
                        }
                    });
            callbacks.put("all", context -> allCalls++);
            state.setMetadata(player.getUniqueId(), CALLBACKS, callbacks);
            Map<String, Object> basic =
                    Map.of(
                            "cooldown",
                            0,
                            "consume",
                            Map.of("amount", "2", "pre", "runtime-rule: pre"),
                            "sync",
                            "runtime-rule: first");
            NiRepository.Input input =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            Map.of(
                                    "rules",
                                    new NiRepository.Definition(
                                            "rules",
                                            "memory/rules.yml",
                                            new NiConfig(Map.of("material", "APPLE")))),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(
                                    "rules",
                                    Map.of(
                                            "right",
                                            basic,
                                            "eat",
                                            basic,
                                            "all",
                                            Map.of("cooldown", 0, "sync", "runtime-rule: all"))),
                            Map.of(),
                            Map.of(),
                            Map.of("rules.js", EXPANSION),
                            Map.of());
            catalog = new NiCatalog(1, input, plugin, (viewer, text) -> null, state);
            listeners = new ItemListeners(catalog::triggers, plugin.getLogger());
        }

        ItemStack hold(Material material, int count, Map<String, Object> properties) {
            for (int slot = 0; slot < 41; slot++)
                player.getInventory().setItem(slot, new ItemStack(Material.AIR));
            CompoundTag custom = new CompoundTag();
            custom.put(
                    ItemStateCodec.KEY,
                    CODEC.encode(
                            new ItemIdentity("rules", Map.of("seed", "unchanged")),
                            (CompoundTag) NmsItems.tag(properties)));
            ItemStack item = NmsItems.withCustomData(new ItemStack(material, count), custom);
            player.getInventory().setItemInMainHand(item);
            return hand();
        }

        ItemStack hand() {
            return player.getInventory().getItemInMainHand();
        }

        void click() {
            var event =
                    new PlayerInteractEvent(
                            player,
                            Action.RIGHT_CLICK_AIR,
                            hand(),
                            null,
                            BlockFace.SELF,
                            EquipmentSlot.HAND);
            event.setCancelled(false);
            listeners.interact(event);
        }

        void rethrowCallback() {
            if (callbackFailure != null)
                throw new AssertionError("Runtime callback failed", callbackFailure);
        }

        @Override
        public void close() {
            try {
                catalog.close();
            } finally {
                state.close();
            }
        }
    }
}
