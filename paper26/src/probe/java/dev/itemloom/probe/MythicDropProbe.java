package dev.itemloom.probe;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.api.ItemLoom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Actual MM parsing, load events, LootBag dispatch and equipment; no NI plugin required. */
final class MythicDropProbe implements Listener {
    private final JavaPlugin plugin;
    private final Plugin mythic;
    private final ClassLoader loader;
    private final ItemLoom items;
    private final List<String> passed = new ArrayList<>();
    private final List<ItemGenerateEvent> generations = new ArrayList<>();
    private final List<Entity> spawned = new ArrayList<>();
    private final Map<String, Object> tables = new LinkedHashMap<>();
    private final CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
    private Player player;
    private Object meta;
    private Object mob;
    private final List<Object> loadedProviders = new ArrayList<>();

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        MythicDropProbe probe = new MythicDropProbe(plugin);
        probe.start();
        return probe.report;
    }

    private MythicDropProbe(JavaPlugin plugin) {
        this.plugin = plugin;
        mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mythic == null || !mythic.isEnabled())
            throw new IllegalStateException("Actual MythicMobs required");
        loader = mythic.getClass().getClassLoader();
        items = Bukkit.getServicesManager().load(ItemLoom.class);
    }

    @EventHandler
    public void generated(ItemGenerateEvent event) {
        if (event.getId().startsWith("ILProbeNative")) generations.add(event);
    }

    private void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            Class<? extends org.bukkit.event.Event> loadEvent =
                    type("bukkit.events.MythicDropLoadEvent")
                            .asSubclass(org.bukkit.event.Event.class);
            Bukkit.getPluginManager()
                    .registerEvent(
                            loadEvent,
                            this,
                            org.bukkit.event.EventPriority.MONITOR,
                            (listener, event) -> {
                                try {
                                    if ("itemloom"
                                            .equalsIgnoreCase(
                                                    call(event, "getDropName").toString()))
                                        ((java.util.Optional<?>) call(event, "getDrop"))
                                                .ifPresent(loadedProviders::add);
                                } catch (Exception error) {
                                    throw new org.bukkit.event.EventException(error);
                                }
                            },
                            plugin);
            var location = Bukkit.getWorlds().getFirst().getSpawnLocation().add(0, 3, 0);
            var handle =
                    new ServerPlayer(
                            ((CraftServer) Bukkit.getServer()).getServer(),
                            ((CraftWorld) location.getWorld()).getHandle(),
                            new GameProfile(UUID.randomUUID(), "IL_Native"),
                            ClientInformation.createDefault());
            handle.setPos(location.getX(), location.getY(), location.getZ());
            player = handle.getBukkitEntity();
            Object manager = call(mythic, "getMobManager");
            mob =
                    call(
                            manager,
                            "spawnMob",
                            new Class<?>[] {String.class, org.bukkit.Location.class, double.class},
                            "ILProbeNativeMob",
                            location,
                            7.0);
            Object abstractMob = call(mob, "getEntity");
            spawned.add((Entity) call(abstractMob, "getBukkitEntity"));
            meta =
                    type("core.drops.DropMetadataImpl")
                            .getConstructor(
                                    type("api.skills.SkillCaster"),
                                    type("api.adapters.AbstractEntity"),
                                    double.class)
                            .newInstance(mob, adapt(player, Entity.class), 7.0);
            table(
                    "stack",
                    "ItEmLoOm{id=ILProbeNativeStone;quality=rare;data.amount=parameter;who=<trigger.name>} 3 1");
            table("zero", "itemloom{id=ILProbeNativeStone} 1 0");
            table("range", "itemloom{item=ILProbeNativeStone;id=ignored} 2-4 1");
            table("equip", "itemloom{id=ILProbeNativeSword} HAND 1 1");
            table("repeat", "ILProbeNativeRepeated 3 1");
            table("dynamic", "itemloom{id=<trigger.name>;mob_level=explicit} 1 1");
            table("sx", "itemloom{id=ILProbeNativeSX;quality=rare} 2 1");
            // The native CustomDrop registers through MythicDropLoadEvent on its next tick.
            later(8, this::verify);
        } catch (Throwable error) {
            finish(error);
        }
    }

    private void verify() throws Exception {
        check(
                !Bukkit.getPluginManager().isPluginEnabled("NeigeItems"),
                "native drops work without NI");
        generations.clear();
        ItemStack sx = single("sx");
        check(
                sx.getType() == Material.PAPER && sx.getAmount() == 2 && generations.size() == 1,
                "MM native drop routes SX configuration without SX plugin and controls amount");
        check(
                items.identify(sx).orElseThrow().rolls().get("quality").equals("rare"),
                "MM parameters reach SX locked random node");
        check(
                ((LivingEntity) spawned.getFirst()).getEquipment().getItemInMainHand().getType()
                        == Material.DIAMOND_SWORD,
                "MM mob configuration equips native ItemLoom at actual spawn");
        generations.clear();
        ItemStack stack = single("stack");
        check(
                stack.getType() == Material.STONE
                        && stack.getAmount() == 3
                        && generations.size() == 1,
                "stack amount 3 invokes the generator once");
        ItemGenerateEvent event = generations.getFirst();
        check(event.getViewer() == player, "drop cause is the generation viewer");
        check(
                "rare".equals(event.getSavedRolls().get("quality"))
                        && "parameter".equals(event.getSavedRolls().get("amount")),
                "custom parameters and data.* escape reach generator");
        check(
                player.getName().equals(event.getSavedRolls().get("who")),
                "MM trigger placeholder resolved on invocation");
        check(
                "7.0".equals(event.getSavedRolls().get("mob_level"))
                        && "ILProbeNativeMob".equals(event.getSavedRolls().get("mob_name_internal"))
                        && spawned.getFirst()
                                .getUniqueId()
                                .toString()
                                .equals(event.getSavedRolls().get("mob_uuid")),
                "active mob level, type and UUID reach generator");
        generations.clear();
        Object empty = bag("zero");
        check(
                ((Number) call(empty, "size")).intValue() == 0 && generations.isEmpty(),
                "MM probability zero rejects before item generation");
        for (int i = 0; i < 16; i++) {
            int count = single("range").getAmount();
            check(count >= 2 && count <= 4, "MM amount range respected " + i);
        }
        Object equipped = bag("equip");
        call(
                equipped,
                "equip",
                new Class<?>[] {type("api.adapters.AbstractEntity")},
                adapt(spawned.getFirst(), Entity.class));
        check(
                ((LivingEntity) spawned.getFirst()).getEquipment().getItemInMainHand().getType()
                        == Material.DIAMOND_SWORD,
                "LootBag equipment dispatcher recognizes IItemDrop");
        generations.clear();
        List<ItemStack> received = new ArrayList<>();
        List<ItemStack> overflow = new ArrayList<>();
        BiFunction<Player, ItemStack, HashMap<Integer, ItemStack>> insert =
                (viewer, item) -> {
                    received.add(item.clone());
                    var leftovers = new HashMap<Integer, ItemStack>();
                    leftovers.put(0, item);
                    return leftovers;
                };
        BiConsumer<Player, HashMap<Integer, ItemStack>> leftover =
                (viewer, value) -> overflow.addAll(value.values());
        call(
                bag("repeat"),
                "give",
                new Class<?>[] {
                    type("api.adapters.AbstractPlayer"),
                    boolean.class,
                    BiFunction.class,
                    BiConsumer.class
                },
                adapt(player, Player.class),
                false,
                insert,
                leftover);
        check(
                generations.size() == 3 && received.size() == 3,
                "nested MM table amount 3 independently generates three items");
        check(
                overflow.size() == 3,
                "native give dispatcher forwards all uninserted items to overflow handler");
        check(
                received.stream()
                                .map(item -> items.identify(item).orElseThrow().rolls().get("roll"))
                                .distinct()
                                .count()
                        == 3,
                "nested rolls preserve different generated UUID parameters");
        // Use a nonplayer cause to test invocation-time ID and viewer isolation.
        var cause =
                spawned.getFirst()
                        .getWorld()
                        .spawn(
                                spawned.getFirst().getLocation().add(2, 0, 0),
                                org.bukkit.entity.ArmorStand.class,
                                stand -> {
                                    stand.customName(
                                            net.kyori.adventure.text.Component.text(
                                                    "ILProbeNativeStone"));
                                    stand.setGravity(false);
                                    stand.setPersistent(false);
                                });
        spawned.add(cause);
        Object second =
                type("core.drops.DropMetadataImpl")
                        .getConstructor(
                                type("api.skills.SkillCaster"),
                                type("api.adapters.AbstractEntity"),
                                double.class)
                        .newInstance(mob, adapt(cause, Entity.class), 11.0);
        Object old = meta;
        meta = second;
        generations.clear();
        single("dynamic");
        check(
                generations.getFirst().getViewer() == null
                        && "explicit"
                                .equals(generations.getFirst().getSavedRolls().get("mob_level")),
                "dynamic item id, nonplayer viewer and explicit context override");
        meta = old;
        check(
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "il reload"),
                "ItemLoom reload command accepted");
        check(
                single("stack").getAmount() == 3,
                "existing MM table resolves the current ItemLoom catalog after reload");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm reload");
        later(
                12,
                () -> {
                    Object manager = call(mythic, "getDropManager");
                    Object configured =
                            ((java.util.Optional<?>)
                                            call(
                                                    manager,
                                                    "getDropTable",
                                                    new Class<?>[] {String.class},
                                                    "ILProbeNativeRepeated"))
                                    .orElseThrow();
                    Object reloaded =
                            call(
                                    configured,
                                    "generate",
                                    new Class<?>[] {type("api.drops.DropMetadata")},
                                    meta);
                    check(
                            asItem(call(reloaded, "getSingleItem")).getType()
                                    == Material.DIAMOND_SWORD,
                            "MM config reload re-registers ItemLoom native type");
                    Object provider = loadedProviders.getFirst();
                    Method getDrop =
                            type("api.drops.IItemDrop")
                                    .getMethod(
                                            "getDrop",
                                            type("api.drops.DropMetadata"),
                                            double.class);
                    int before = generations.size();
                    for (double amount : new double[] {0, -1, 0.5})
                        check(
                                asItem(getDrop.invoke(provider, meta, amount)).isEmpty(),
                                "nonpositive/fractional-below-one amount creates no phantom item: "
                                        + amount);
                    check(generations.size() == before, "zero quantities skip generation entirely");
                    check(
                            asItem(getDrop.invoke(provider, meta, 1.9)).getAmount() == 1,
                            "fractional count uses floor");
                    for (double amount :
                            new double[] {
                                Double.NaN, Double.POSITIVE_INFINITY, (double) Integer.MAX_VALUE + 1
                            }) {
                        boolean rejected = false;
                        try {
                            getDrop.invoke(provider, meta, amount);
                        } catch (java.lang.reflect.InvocationTargetException expected) {
                            rejected = expected.getCause() instanceof IllegalArgumentException;
                        }
                        check(rejected, "invalid amount rejected: " + amount);
                    }
                    Bukkit.getScheduler()
                            .runTaskAsynchronously(
                                    plugin,
                                    () -> {
                                        boolean rejected;
                                        try {
                                            getDrop.invoke(provider, meta, 1.0);
                                            rejected = false;
                                        } catch (Exception expected) {
                                            rejected =
                                                    expected.getCause()
                                                            instanceof IllegalStateException;
                                        }
                                        boolean asyncRejected = rejected;
                                        Bukkit.getScheduler()
                                                .runTask(
                                                        plugin,
                                                        () -> {
                                                            try {
                                                                check(
                                                                        asyncRejected,
                                                                        "MM callback rejects worker-thread generation");
                                                                Bukkit.getPluginManager()
                                                                        .disablePlugin(
                                                                                Bukkit
                                                                                        .getPluginManager()
                                                                                        .getPlugin(
                                                                                                "ItemLoom"));
                                                                boolean closed = false;
                                                                try {
                                                                    getDrop.invoke(
                                                                            provider, meta, 1.0);
                                                                } catch (
                                                                        java.lang.reflect
                                                                                        .InvocationTargetException
                                                                                expected) {
                                                                    closed =
                                                                            expected.getCause()
                                                                                    instanceof
                                                                                    IllegalStateException;
                                                                }
                                                                check(
                                                                        closed,
                                                                        "retained MM callback fails after actual ItemLoom plugin disable");
                                                                finish(null);
                                                            } catch (Throwable error) {
                                                                finish(error);
                                                            }
                                                        });
                                    });
                });
    }

    private void table(String name, String line) throws Exception {
        tables.put(
                name,
                type("core.drops.DropTable")
                        .getConstructor(String.class, String.class, List.class)
                        .newInstance("native-probe", name, List.of(line)));
    }

    private Object bag(String name) throws Exception {
        return call(
                tables.get(name),
                "generate",
                new Class<?>[] {type("api.drops.DropMetadata")},
                meta);
    }

    private ItemStack single(String name) throws Exception {
        return asItem(call(bag(name), "getSingleItem"));
    }

    private ItemStack asItem(Object value) throws Exception {
        return (ItemStack)
                type("bukkit.BukkitAdapter")
                        .getMethod("adapt", type("api.adapters.AbstractItemStack"))
                        .invoke(null, value);
    }

    private Object adapt(Object value, Class<?> source) throws Exception {
        return type("bukkit.BukkitAdapter").getMethod("adapt", source).invoke(null, value);
    }

    private Class<?> type(String suffix) throws ClassNotFoundException {
        return loader.loadClass("io.lumine.mythic." + suffix);
    }

    private static Object call(Object object, String name) throws Exception {
        return call(object, name, new Class<?>[0]);
    }

    private static Object call(Object object, String name, Class<?>[] parameters, Object... args)
            throws Exception {
        Method method = object.getClass().getMethod(name, parameters);
        return method.invoke(object, args);
    }

    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed.add(name);
    }

    private interface Checked {
        void run() throws Exception;
    }

    private void later(long ticks, Checked action) {
        Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            try {
                                action.run();
                            } catch (Throwable error) {
                                finish(error);
                            }
                        },
                        ticks);
    }

    private void finish(Throwable failure) {
        HandlerList.unregisterAll(this);
        spawned.forEach(Entity::remove);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", failure == null);
        result.put("checks", passed);
        result.put("mythicVersion", mythic.getPluginMeta().getVersion());
        result.put(
                "limits",
                "Real MM and NMS; detached player and give callbacks, no connected client. Not a load or bandwidth benchmark.");
        if (failure != null) {
            result.put("failure", failure.toString());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Native Mythic probe", failure);
        }
        report.complete(result);
    }
}
