package dev.itemloom.probe;

import dev.itemloom.paper.integration.ExternalItemMaterial;
import dev.itemloom.paper.integration.OptionalItemSources;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Exercises the actual shaded ItemBridge through scoped providers, without replacing server plugins. */
public final class ItemBridgeProbe {
    private ItemBridgeProbe() {}

    @FunctionalInterface
    public interface Factory {
        ItemStack build(String id, Player player, Map<String, Object> context);
    }

    /** Shared by recipe probes; the constructor and ItemBridge types are located after relocation. */
    public static OptionalItemSources sources(JavaPlugin owner, String provider, Factory factory)
            throws ReflectiveOperationException {
        return sources(Map.of(provider, owner), Map.of(provider, factory));
    }

    public static Map<String, Object> run(JavaPlugin owner) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("ItemBridge probe requires the server thread");
        List<String> verified = new ArrayList<>();
        List<String> limits = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            cloneAndContext(owner, verified);
            legacyOrder(owner, verified);
            failures(owner, verified);
            availability(verified);
            excludedProviders(verified);
            actualCraftEngine(verified, limits);
            report.put("passed", true);
        } catch (Throwable failure) {
            report.put("passed", false);
            report.put("failure", failure.toString());
        }
        report.put("checks", verified.size());
        report.put("verified", verified);
        report.put("limits", limits);
        report.put("realClient", false);
        return report;
    }

    private static void cloneAndContext(JavaPlugin owner, List<String> verified) throws Exception {
        var parsed = ExternalItemMaterial.parse("itembridge:FIXTURE:demo:blade");
        check(
                parsed.provider().equals("fixture") && parsed.id().equals("demo:blade"),
                "explicit material preserves namespaced provider item ids",
                verified);
        ItemStack shared = new ItemStack(Material.DIAMOND_SWORD, 5);
        NamespacedKey foreign = new NamespacedKey("foreign", "identity");
        shared.editMeta(
                meta -> {
                    meta.itemName(net.kyori.adventure.text.Component.text("External prototype"));
                    meta.setItemModel(new NamespacedKey("foreign", "blade"));
                    meta.getPersistentDataContainer()
                            .set(foreign, PersistentDataType.STRING, "retained");
                });
        AtomicInteger calls = new AtomicInteger();
        List<Player> players = new ArrayList<>();
        List<Map<String, Object>> contexts = new ArrayList<>();
        OptionalItemSources sources =
                sources(
                        owner,
                        "fixture",
                        (id, player, context) -> {
                            if (!id.equals("demo:blade")) throw new AssertionError(id);
                            calls.incrementAndGet();
                            players.add(player);
                            contexts.add(context);
                            return shared;
                        });
        Player player = ProbePlayer.create("ItemBridgeViewer");
        ItemStack first =
                sources.material(
                        "itembridge:fixture:demo:blade", player, Map.of("roll", 7, "name", "one"));
        check(
                first != shared && first.equals(shared),
                "the complete item and foreign components are cloned",
                verified);
        first.setAmount(1);
        first.editMeta(
                meta ->
                        meta.getPersistentDataContainer()
                                .set(foreign, PersistentDataType.STRING, "changed"));
        ItemStack second =
                sources.material("itembridge:fixture:demo:blade", null, Map.of("roll", 9));
        check(
                calls.get() == 2
                        && first != second
                        && second != shared
                        && second.equals(shared)
                        && shared.getAmount() == 5,
                "each request invokes the provider and caller mutations do not affect its prototype",
                verified);
        check(
                players.get(0) == player
                        && players.get(1) == null
                        && contexts.get(0).equals(Map.of("roll", 7, "name", "one"))
                        && contexts.get(1).equals(Map.of("roll", 9)),
                "the original player and fresh typed context reach each provider invocation",
                verified);
        String asyncFailure =
                CompletableFuture.supplyAsync(
                                () ->
                                        rejected(
                                                () ->
                                                        sources.material(
                                                                "itembridge:fixture:demo:blade",
                                                                null,
                                                                Map.of())))
                        .get(5, TimeUnit.SECONDS);
        check(
                asyncFailure.contains("server thread") && calls.get() == 2,
                "worker calls fail before touching a provider",
                verified);
    }

    private static void legacyOrder(JavaPlugin owner, List<String> verified) throws Exception {
        List<String> calls = new ArrayList<>();
        Map<String, Plugin> owners = new LinkedHashMap<>();
        Map<String, Factory> factories = new LinkedHashMap<>();
        for (String provider : List.of("mythicmobs", "magicgem", "itemsadder", "oraxen")) {
            owners.put(provider, owner);
            factories.put(
                    provider,
                    (id, player, context) -> {
                        calls.add(provider + ':' + id);
                        return id.equals("gem") || (provider.equals("oraxen") && id.equals("found"))
                                ? new ItemStack(Material.DIAMOND)
                                : null;
                    });
        }
        OptionalItemSources sources = sources(owners, factories);
        check(
                sources.getHookedItem("found").getType() == Material.DIAMOND
                        && calls.equals(
                                List.of(
                                        "mythicmobs:found",
                                        "magicgem:found",
                                        "itemsadder:found",
                                        "oraxen:found")),
                "legacy bare ids retain MythicMobs, MagicGem, ItemsAdder, Oraxen order",
                verified);
        calls.clear();
        for (String alias : List.of("mm", "mg", "ia", "or"))
            check(
                    sources.getHookedItem(alias + ":gem").getType() == Material.DIAMOND,
                    "legacy alias " + alias + " resolves directly",
                    verified);
        check(
                calls.equals(
                        List.of("mythicmobs:gem", "magicgem:gem", "itemsadder:gem", "oraxen:gem")),
                "legacy aliases do not invoke unrelated providers when found",
                verified);
        calls.clear();
        check(
                sources.getHookedItem("vn:stone").getType() == Material.STONE && calls.isEmpty(),
                "explicit vanilla alias bypasses external providers",
                verified);
        check(
                sources.getHookedItem("stone").getType() == Material.STONE && calls.size() == 4,
                "vanilla remains the last fallback for bare ids",
                verified);
    }

    private static void failures(JavaPlugin owner, List<String> verified) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OptionalItemSources source =
                sources(
                        owner,
                        "fixture",
                        (id, player, context) -> {
                            calls.incrementAndGet();
                            return switch (id) {
                                case "null" -> null;
                                case "air" -> new ItemStack(Material.AIR);
                                case "exception" ->
                                        throw new IllegalStateException("fixture failure");
                                default -> throw new NoSuchMethodError("fixture API mismatch");
                            };
                        });
        for (String id : List.of("null", "air", "exception", "linkage")) {
            String message =
                    rejected(() -> source.material("itembridge:fixture:" + id, null, Map.of()));
            check(
                    message.contains("itembridge:fixture:" + id),
                    "diagnostic identifies provider and item for " + id,
                    verified);
        }
        int before = calls.get();
        for (String forbidden :
                List.of("ni", "neigeitems", "sx", "sxitem", "sx-item", "itemloom")) {
            check(
                    rejected(() -> source.getHookedItem(forbidden + ":blocked"))
                                    .contains("disabled")
                            && rejected(
                                            () ->
                                                    source.material(
                                                            "itembridge:" + forbidden + ":blocked",
                                                            null,
                                                            Map.of()))
                                    .contains("disabled"),
                    "forbidden source " + forbidden + " cannot fall back or delegate",
                    verified);
        }
        check(calls.get() == before, "forbidden sources never invoke any provider", verified);
        check(
                rejected(() -> source.material("itembridge:missing:entry", null, Map.of()))
                        .contains("missing or disabled"),
                "missing plugin has a direct availability diagnostic",
                verified);
    }

    private static void availability(List<String> verified) throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        Plugin owner =
                (Plugin)
                        Proxy.newProxyInstance(
                                Plugin.class.getClassLoader(),
                                new Class<?>[] {Plugin.class},
                                (self, method, args) ->
                                        switch (method.getName()) {
                                            case "isEnabled" -> enabled.get();
                                            case "getName", "toString" -> "ScopedFixtureOwner";
                                            case "hashCode" -> System.identityHashCode(self);
                                            case "equals" -> self == args[0];
                                            default ->
                                                    throw new UnsupportedOperationException(
                                                            method.toString());
                                        });
        OptionalItemSources source =
                sources(
                        Map.of("fixture", owner),
                        Map.of(
                                "fixture",
                                (id, player, context) -> {
                                    calls.incrementAndGet();
                                    return new ItemStack(Material.STONE);
                                }));
        source.material("itembridge:fixture:item", null, Map.of());
        enabled.set(false);
        check(
                rejected(() -> source.material("itembridge:fixture:item", null, Map.of()))
                                .contains("missing or disabled")
                        && calls.get() == 1,
                "a registered provider is never called after its owner is disabled",
                verified);
    }

    private static void actualCraftEngine(List<String> verified, List<String> limits) {
        String id = System.getProperty("itemloom.probe.craftengine-item");
        if (id == null || id.isBlank()) {
            limits.add(
                    "Actual CraftEngine generation requires -Ditemloom.probe.craftengine-item=<id>");
            return;
        }
        OptionalItemSources sources = new OptionalItemSources();
        ItemStack first = sources.material("itembridge:craftengine:" + id, null, Map.of());
        ItemStack second = sources.material("itembridge:craftengine:" + id, null, Map.of());
        check(
                !first.isEmpty() && !second.isEmpty() && first != second,
                "installed CraftEngine builds the explicitly selected fixture twice",
                verified);
    }

    private static void excludedProviders(List<String> verified) throws Exception {
        ClassLoader loader = OptionalItemSources.class.getClassLoader();
        for (String name : List.of("NeigeItemsProvider", "SXItemProvider")) {
            String type = "dev.itemloom.internal.itembridge.hook." + name;
            check(
                    loader.getResource(type.replace('.', '/') + ".class") == null,
                    name + " has no packaged bytecode",
                    verified);
            try {
                Class.forName(type, false, loader);
                throw new AssertionError(type + " remains loadable");
            } catch (ClassNotFoundException expected) {
                verified.add(name + " cannot be loaded by the plugin classloader");
            }
        }
        // Exercise discovery even with no optional provider installed; merely loading the
        // resolver must not link a removed provider or require a missing plugin's API.
        OptionalItemSources discovered = new OptionalItemSources();
        check(
                discovered.getHookedItem("vn:stone").getType() == Material.STONE,
                "provider discovery remains usable without NI/SX adapters",
                verified);
    }

    private static OptionalItemSources sources(
            Map<String, Plugin> owners, Map<String, Factory> factories)
            throws ReflectiveOperationException {
        Constructor<?> constructor =
                Arrays.stream(OptionalItemSources.class.getDeclaredConstructors())
                        .filter(candidate -> candidate.getParameterCount() == 2)
                        .findFirst()
                        .orElseThrow();
        Class<?> bridgeType = constructor.getParameterTypes()[0];
        String root = bridgeType.getPackageName().replaceFirst("\\.core$", "");
        ClassLoader loader = bridgeType.getClassLoader();
        Class<?> providerType = Class.forName(root + ".api.Provider", true, loader);
        Class<?> contextType = Class.forName(root + ".api.context.BuildContext", true, loader);
        var builderMethod = bridgeType.getMethod("builder");
        Class<?> builderType = builderMethod.getReturnType();
        Object builder = builderMethod.invoke(null);
        for (Map.Entry<String, Factory> entry : factories.entrySet()) {
            Object provider =
                    Proxy.newProxyInstance(
                            loader,
                            new Class<?>[] {providerType},
                            (self, method, args) ->
                                    switch (method.getName()) {
                                        case "plugin", "toString" -> entry.getKey();
                                        case "has" -> true;
                                        case "buildOrNull" ->
                                                entry.getValue()
                                                        .build(
                                                                (String) args[0],
                                                                (Player) args[1],
                                                                context(contextType, args[2]));
                                        case "build" ->
                                                Optional.ofNullable(
                                                        entry.getValue()
                                                                .build(
                                                                        (String) args[0],
                                                                        (Player) args[1],
                                                                        context(
                                                                                contextType,
                                                                                args[2])));
                                        case "hashCode" -> System.identityHashCode(self);
                                        case "equals" -> self == args[0];
                                        default ->
                                                throw new UnsupportedOperationException(
                                                        method.toString());
                                    });
            builderType.getMethod("register", providerType).invoke(builder, provider);
        }
        builderType.getMethod("immutable", boolean.class).invoke(builder, true);
        Object bridge = builderType.getMethod("build").invoke(builder);
        constructor.setAccessible(true);
        return (OptionalItemSources) constructor.newInstance(bridge, owners);
    }

    private static Map<String, Object> context(Class<?> contextType, Object value)
            throws ReflectiveOperationException {
        Map<?, ?> values = (Map<?, ?>) contextType.getMethod("contextData").invoke(value);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            Object key = entry.getKey();
            String name = (String) key.getClass().getMethod("key").invoke(key);
            result.put(name, ((Supplier<?>) entry.getValue()).get());
        }
        return result;
    }

    private static String rejected(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return expected.getMessage();
        }
        throw new AssertionError("Expected a source failure");
    }

    private static void check(boolean condition, String description, List<String> verified) {
        if (!condition) throw new AssertionError(description);
        verified.add(description);
    }
}
