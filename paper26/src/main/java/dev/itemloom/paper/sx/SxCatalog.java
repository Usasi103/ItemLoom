package dev.itemloom.paper.sx;

import dev.itemloom.api.ItemGenerateEvent;
import dev.itemloom.compat.sx.SxConfig;
import dev.itemloom.compat.sx.SxExpressions;
import dev.itemloom.compat.sx.SxFingerprint;
import dev.itemloom.compat.sx.SxRepository;
import dev.itemloom.compat.sx.SxScripts;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.integration.OptionalItemSources;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.dynalink.beans.StaticClass;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** An all-or-nothing SX revision, owning script registrations and canonical construction recipes. */
public final class SxCatalog implements AutoCloseable {
    public static final String FINGERPRINT_PROPERTY = "sx_fingerprint";
    private static final Pattern PLACEHOLDER = Pattern.compile("%([^%]+)%");
    private final Map<String, SxPaperRecipe> recipes = new LinkedHashMap<>();
    private final Set<String> generating = new HashSet<>();
    private final Listener listener = new Listener() {};
    private final ItemStateCodec identities = new ItemStateCodec();
    private final SxRepository.Input input;
    private final BiFunction<Object, String, String> placeholders;
    private final String fingerprint;
    private final Map<String, Integer> restrictions;
    private SxScripts scripts;
    private boolean active = true;

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
        fingerprint = SxFingerprint.of(input);
        Map<String, Integer> rules = new LinkedHashMap<>();
        int mask =
                (input.settings().bool("CraftingTable", true) ? 1 : 0)
                        | (input.settings().bool("EnchantingTable", true) ? 2 : 0)
                        | (input.settings().bool("BlockPlace", true) ? 4 : 0);
        if (mask != 0) {
            input.items().keySet().forEach(id -> rules.put(id, mask));
        }
        restrictions = Collections.unmodifiableMap(rules);
        try {
            // Validate evaluator settings even for an empty catalog before any script side effects.
            handler(null, Map.of(), Map.of());
            Map<String, Object> globals = new LinkedHashMap<>();
            globals.put("Bukkit", StaticClass.forClass(Bukkit.class));
            globals.put("Arrays", StaticClass.forClass(Arrays.class));
            globals.put("Utils", new ScriptUtils(placeholders));
            globals.put("SXItem", new ScriptHost(plugin));
            globals.put("listener", listener);
            scripts = new SxScripts(input, globals);
            Map<String, SxPaperRecipe> canonical = new LinkedHashMap<>();
            for (String requested : input.items().keySet()) {
                SxRepository.Definition definition = resolve(requested);
                SxPaperRecipe recipe = canonical.get(definition.id());
                if (recipe == null) {
                    recipe = new SxPaperRecipe(definition, input.settings(), itemSources);
                    recipe.prepare(handler(null, recipe.random, Map.of()));
                    canonical.put(definition.id(), recipe);
                }
                recipes.put(requested, recipe);
            }
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    private SxRepository.Definition resolve(String requested) {
        Set<String> visited = new LinkedHashSet<>();
        String current = requested;
        while (true) {
            if (!visited.add(current)) {
                throw new IllegalArgumentException(
                        "Cyclic SX alias: " + visited + " -> " + current);
            }
            if (visited.size() > 64) {
                throw new IllegalArgumentException("SX alias depth exceeds 64: " + requested);
            }
            SxRepository.Definition definition = input.items().get(current);
            if (definition == null) {
                throw new IllegalArgumentException("Missing SX alias target: " + current);
            }
            if (definition.alias() == null) {
                return definition;
            }
            current = definition.alias();
        }
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(recipes.keySet());
    }

    public boolean contains(String id) {
        return recipes.containsKey(id);
    }

    public boolean hasUpdates() {
        return active && recipes.values().stream().anyMatch(recipe -> recipe.update);
    }

    public Map<String, Integer> restrictions() {
        return restrictions;
    }

    public ItemStack generate(String id, OfflinePlayer viewer, Map<String, String> parameters) {
        ItemsService.requireThread();
        ensureActive();
        if (!generating.add(id)) {
            throw new IllegalArgumentException("Recursive SX generation: " + id);
        }
        try {
            SxPaperRecipe recipe = recipes.get(id);
            if (recipe == null) {
                throw new IllegalArgumentException("Unknown SX item: " + id);
            }
            SxExpressions handler =
                    handler(viewer, recipe.random, parameters == null ? Map.of() : parameters);
            ItemStack created = recipe.create(handler);
            CompoundTag properties = identities.properties(created);
            properties.putString(FINGERPRINT_PROPERTY, fingerprint);
            created =
                    identities.write(
                            created, new ItemIdentity(recipe.id, handler.getLockMap()), properties);
            ensureActive();
            ItemGenerateEvent event =
                    new ItemGenerateEvent(id, viewer, handler.getLockMap(), created);
            Bukkit.getPluginManager().callEvent(event);
            ensureActive();
            return event.getItem().clone();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Cannot generate SX item " + id, error);
        } finally {
            generating.remove(id);
        }
    }

    private SxExpressions handler(
            OfflinePlayer viewer,
            Map<String, dev.itemloom.compat.sx.SxRandom> local,
            Map<String, String> parameters) {
        Player player = viewer instanceof Player online && online.isOnline() ? online : null;
        return new SxExpressions(
                player,
                local,
                input.random(),
                parameters,
                ThreadLocalRandom.current(),
                text -> placeholders(viewer, text, placeholders),
                (file, function, handler, arguments) -> {
                    Object[] converted = arguments == null ? null : new Object[arguments.length];
                    if (arguments != null) {
                        for (int index = 0; index < arguments.length; index++) {
                            Player exact = Bukkit.getPlayerExact(arguments[index]);
                            converted[index] = exact == null ? arguments[index] : exact;
                        }
                    }
                    return scripts.call(file, function, handler, converted);
                },
                input.settings(),
                Clock.systemDefaultZone());
    }

    public boolean update(Player player, ItemStack item) {
        ItemsService.requireThread();
        ensureActive();
        if (!hasUpdates() || item == null || item.isEmpty()) {
            return false;
        }
        ItemIdentity identity;
        try {
            identity = identities.read(item).orElse(null);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (identity == null) {
            return false;
        }
        SxPaperRecipe recipe = recipes.get(identity.id());
        CompoundTag properties = identities.properties(item);
        if (recipe == null
                || !recipe.update
                || !properties.getString("source").orElse("").equals("sx")
                || properties.getString(FINGERPRINT_PROPERTY).orElse("").equals(fingerprint)) {
            return false;
        }
        // Legacy sx_hash is deliberately not an equality decision: it upgrades once to this
        // fingerprint.
        ItemStack before = item.clone();
        ItemStack candidate = generate(recipe.id, player, identity.rolls());
        ensureActive();
        if (!item.equals(before)) {
            return false;
        }
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException(
                    "SX update produced an empty item: " + identity.id());
        }
        candidate.setAmount(before.getAmount());
        ItemStack protectedCandidate =
                SxProtectedState.restore(before, candidate, recipe.protectedPaths);
        ensureActive();
        if (!item.equals(before)) {
            return false;
        }
        NmsItems.replace(item, protectedCandidate);
        return true;
    }

    /**
     * Applies this recipe's ProtectNBT to a rebuild replacement, restoring the original's values
     * (or their absence) for every protected path, the same restore that {@link #update} performs.
     */
    public ItemStack protectRebuild(String id, ItemStack before, ItemStack candidate) {
        ItemsService.requireThread();
        ensureActive();
        SxPaperRecipe recipe = recipes.get(id);
        if (recipe == null) {
            throw new IllegalArgumentException("Unknown SX item: " + id);
        }
        ItemStack sized = candidate.clone();
        sized.setAmount(before.getAmount());
        return SxProtectedState.restore(before, sized, recipe.protectedPaths);
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("SX catalog is closed");
        }
    }

    @Override
    public void close() {
        if (!active) {
            return;
        }
        active = false;
        HandlerList.unregisterAll(listener);
        if (scripts != null) {
            scripts.close();
        }
    }

    private static String placeholders(
            Object viewer, String text, BiFunction<Object, String, String> callback) {
        if (viewer == null || text.indexOf('%') < 0) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = callback.apply(viewer, matcher.group(1));
            matcher.appendReplacement(
                    result, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static final class ScriptHost {
        private final JavaPlugin plugin;
        private final Random random = new Random();

        ScriptHost(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        public JavaPlugin getInst() {
            return plugin;
        }

        public Random getRandom() {
            return random;
        }
    }

    public static final class ScriptUtils {
        public final Random random = new Random();
        private final BiFunction<Object, String, String> placeholders;

        ScriptUtils(BiFunction<Object, String, String> placeholders) {
            this.placeholders = placeholders;
        }

        public List<Object> mutableList(Object... values) {
            return new ArrayList<>(Arrays.asList(values));
        }

        @SuppressWarnings("deprecation")
        public String asColor(String text) {
            return ChatColor.translateAlternateColorCodes('&', text);
        }

        public int randomInt(int a, int b) {
            return (int) random.nextLong(Math.min(a, b), (long) Math.max(a, b) + 1);
        }

        public double randomDouble(double a, double b) {
            return a + random.nextDouble() * (b - a);
        }

        public String fromPlaceholderAPI(Player player, String text) {
            return placeholders(player, text, placeholders);
        }
    }
}
