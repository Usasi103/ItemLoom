package dev.itemloom.paper.sx;

import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.compat.sx.SxConfig;
import dev.itemloom.compat.sx.SxExpressions;
import dev.itemloom.compat.sx.SxRepository;
import dev.itemloom.compat.sx.SxScripts;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import dev.itemloom.paper.integration.OptionalItemSources;

/** Revision-owned SX frontend. Construction prepares recipes; no live catalog is mutated. */
public final class SxCatalog implements AutoCloseable {
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("%([^%]+)%");
    private final SxRepository.Input input;
    private final BiFunction<Object, String, String> placeholders;
    private final OptionalItemSources itemSources;
    private final Map<String, SxPaperRecipe> recipes = new LinkedHashMap<>();
    private final Listener scriptListener = new Listener() {};
    private final ItemStateCodec state = new ItemStateCodec();
    private final Set<String> generating = new HashSet<>();
    private SxScripts scripts;
    private boolean closed;
    private boolean updates;

    public SxCatalog(
            SxRepository.Input input,
            JavaPlugin plugin,
            BiFunction<Object, String, String> placeholders) {
        this(input, plugin, placeholders, new OptionalItemSources());
    }

    public SxCatalog(
            SxRepository.Input input,
            JavaPlugin plugin,
            BiFunction<Object, String, String> placeholders,
            OptionalItemSources itemSources) {
        this.input = input;
        this.placeholders = placeholders;
        this.itemSources = itemSources;
        try {
            for (String id : input.items().keySet()) resolve(id, new java.util.LinkedHashSet<>());
            // Validate global formatting before publication, even when no item uses it yet.
            new java.text.SimpleDateFormat(input.settings().text("TimeFormat", "yyyy/MM/dd HH:mm"));
            handler(null, Map.of(), Map.of());
            for (SxPaperRecipe recipe : new HashSet<>(recipes.values()))
                recipe.prepare(handler(null, recipe.random, Map.of()));
            Map<String, Object> globals = new LinkedHashMap<>();
            globals.put("Bukkit", jdk.dynalink.beans.StaticClass.forClass(Bukkit.class));
            globals.put("Arrays", jdk.dynalink.beans.StaticClass.forClass(java.util.Arrays.class));
            globals.put("Utils", new ScriptUtils(placeholders));
            globals.put("SXItem", new ScriptHost(plugin));
            globals.put("listener", scriptListener);
            scripts = new SxScripts(input, globals);
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    private SxPaperRecipe resolve(String id, Set<String> path) {
        SxPaperRecipe existing = recipes.get(id);
        if (existing != null) return existing;
        if (!path.add(id))
            throw new IllegalArgumentException("Cyclic SX item alias: " + path + " -> " + id);
        if (path.size() > 64) throw new IllegalArgumentException("SX item alias depth exceeds 64");
        SxRepository.Definition definition = input.items().get(id);
        if (definition == null)
            throw new IllegalArgumentException("Unknown SX alias target: " + id);
        SxPaperRecipe recipe =
                definition.alias() == null
                        ? new SxPaperRecipe(definition, input.settings(), itemSources)
                        : resolve(definition.alias(), path);
        recipes.put(id, recipe);
        updates |= recipe.update;
        path.remove(id);
        return recipe;
    }

    public Set<String> ids() {
        return Set.copyOf(recipes.keySet());
    }

    public boolean contains(String id) {
        return recipes.containsKey(id);
    }

    public boolean hasUpdates() {
        return updates;
    }

    public Map<String, Integer> restrictions() {
        SxConfig flags = input.settings().section("Restrictions");
        int mask =
                (flags.bool("CraftingTable", true) ? 1 : 0)
                        | (flags.bool("EnchantingTable", true) ? 2 : 0)
                        | (flags.bool("BlockPlace", true) ? 4 : 0);
        Map<String, Integer> result = new LinkedHashMap<>();
        if (mask != 0) recipes.keySet().forEach(id -> result.put(id, mask));
        return Map.copyOf(result);
    }

    public ItemStack generate(String id, OfflinePlayer viewer, Map<String, String> parameters) {
        ItemsService.requireThread();
        ensureActive();
        SxPaperRecipe recipe = recipes.get(id);
        if (recipe == null) throw new IllegalArgumentException("Unknown SX item: " + id);
        if (!generating.add(id))
            throw new IllegalArgumentException("Recursive SX item generation: " + id);
        try {
            SxExpressions handler =
                    handler(viewer, recipe.random, parameters == null ? Map.of() : parameters);
            ItemStack item = recipe.create(handler);
            ensureActive();
            ItemGenerateEvent event = new ItemGenerateEvent(id, viewer, handler.getLockMap(), item);
            Bukkit.getPluginManager().callEvent(event);
            ensureActive();
            return event.getItem().clone();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("SX item " + id + ": " + error.getMessage(), error);
        } finally {
            generating.remove(id);
        }
    }

    private SxExpressions handler(
            OfflinePlayer viewer,
            Map<String, dev.itemloom.compat.sx.SxRandom> local,
            Map<String, String> parameters) {
        return new SxExpressions(
                viewer instanceof Player ? viewer : null,
                local,
                input.random(),
                parameters,
                ThreadLocalRandom.current(),
                text -> replacePlaceholders(viewer, text),
                (file, function, handler, arguments) -> {
                    Object[] values =
                            arguments == null
                                    ? null
                                    : java.util.Arrays.stream(arguments)
                                            .map(
                                                    value -> {
                                                        Player player =
                                                                Bukkit.getPlayerExact(value);
                                                        return player == null ? value : player;
                                                    })
                                            .toArray();
                    return scripts.call(file, function, handler, values);
                },
                input.settings(),
                Clock.systemDefaultZone());
    }

    private String replacePlaceholders(Object viewer, String text) {
        return replacePlaceholders(placeholders, viewer, text);
    }

    private static String replacePlaceholders(
            BiFunction<Object, String, String> placeholders, Object viewer, String text) {
        if (viewer == null || text.indexOf('%') < 0) return text;
        var matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = placeholders.apply(viewer, matcher.group(1));
            matcher.appendReplacement(
                    result,
                    java.util.regex.Matcher.quoteReplacement(
                            value == null ? matcher.group() : value));
        }
        return matcher.appendTail(result).toString();
    }

    /** Update only this frontend's generated items, retaining lock values and stack quantity. */
    public boolean update(Player player, ItemStack item) {
        ItemsService.requireThread();
        ensureActive();
        if (!updates || item == null || item.isEmpty()) return false;
        String id = state.readId(item);
        SxPaperRecipe recipe = id == null ? null : recipes.get(id);
        if (recipe == null || !recipe.update) return false;
        CompoundTag properties = state.properties(item);
        if (!properties.getString("source").orElse("").equals("sx")) return false;
        if (properties.getInt("sx_hash").orElse(Integer.MIN_VALUE) == recipe.hash) return false;
        var identity = state.read(item).orElseThrow();
        ItemStack before = item.clone();
        ItemStack candidate = generate(identity.id(), player, identity.rolls());
        ensureActive();
        if (!item.equals(before)) return false;
        if (candidate.isEmpty())
            throw new IllegalArgumentException("SX update returned an empty item");
        candidate.setAmount(before.getAmount());
        candidate = protect(candidate, before, recipe.protectedPaths);
        NmsItems.replace(item, candidate);
        return true;
    }

    private ItemStack protect(ItemStack target, ItemStack source, Set<String> paths) {
        if (paths.isEmpty()) return target;
        CompoundTag oldData = NmsItems.customData(source), newData = NmsItems.customData(target);
        for (String path : paths)
            if (!path.startsWith("components.")) {
                String[] keys = path.split("\\.");
                CompoundTag oldPart = oldData, newPart = newData;
                for (int i = 0; i < keys.length - 1; i++)
                    oldPart = oldPart.getCompoundOrEmpty(keys[i]);
                String key = keys[keys.length - 1];
                Tag value = oldPart.get(key);
                boolean absent = false;
                for (int i = 0; i < keys.length - 1; i++) {
                    CompoundTag next = newPart.getCompound(keys[i]).orElse(null);
                    if (next == null) {
                        if (value == null) {
                            absent = true;
                            break;
                        }
                        next = new CompoundTag();
                        newPart.put(keys[i], next);
                    }
                    newPart = next;
                }
                if (absent) continue;
                if (value == null) newPart.remove(key);
                else newPart.put(key, value.copy());
            }
        var result = CraftItemStack.asNMSCopy(NmsItems.withCustomData(target, newData));
        var original = CraftItemStack.asNMSCopy(source);
        for (String path : paths)
            if (path.startsWith("components.")) {
                String key = path.substring(11).split("\\.", 2)[0];
                DataComponentType<?> type =
                        BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(key));
                if (type == null)
                    throw new IllegalArgumentException("Unknown protected SX component: " + key);
                copyComponent(original, result, type);
            }
        return CraftItemStack.asCraftMirror(result);
    }

    private static <T> void copyComponent(
            net.minecraft.world.item.ItemStack source,
            net.minecraft.world.item.ItemStack target,
            DataComponentType<T> type) {
        T value = source.get(type);
        if (value != null) target.set(type, value);
    }

    private void ensureActive() {
        if (closed) throw new IllegalStateException("SX catalog is closed");
    }

    @Override
    public void close() {
        closed = true;
        HandlerList.unregisterAll(scriptListener);
        if (scripts != null) scripts.close();
    }

    /** Bindings used by the upstream example scripts; this is not the SX Java plugin API. */
    public static final class ScriptHost {
        private final JavaPlugin plugin;

        ScriptHost(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        public JavaPlugin getInst() {
            return plugin;
        }

        public java.util.Random getRandom() {
            return ThreadLocalRandom.current();
        }
    }

    public static final class ScriptUtils {
        private final BiFunction<Object, String, String> placeholders;
        public final java.util.Random random = ThreadLocalRandom.current();

        ScriptUtils(BiFunction<Object, String, String> placeholders) {
            this.placeholders = placeholders;
        }

        public java.util.List<Object> mutableList(Object... values) {
            return new java.util.ArrayList<>(java.util.Arrays.asList(values));
        }

        @SuppressWarnings("deprecation")
        public String asColor(String text) {
            return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
        }

        public int randomInt(int a, int b) {
            return (int) random.nextLong(Math.min(a, b), (long) Math.max(a, b) + 1);
        }

        public double randomDouble(double a, double b) {
            return a + random.nextDouble() * (b - a);
        }

        public String fromPlaceholderAPI(Player player, String text) {
            return replacePlaceholders(placeholders, player, text);
        }
    }
}
