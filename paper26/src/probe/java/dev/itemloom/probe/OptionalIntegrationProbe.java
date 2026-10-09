package dev.itemloom.probe;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.paper.compat.script.LegacyHooks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Real optional APIs; Vault responses are a scoped service fixture, Mythic skills run on NMS entities. */
final class OptionalIntegrationProbe {
    private static final String SKILL = "ItemLoomProbeOptional";
    private static final String SELF_TAG = "itemloom_probe_self";
    private static final String TRIGGER_TAG = "itemloom_probe_trigger";

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Integration probe starts on the server thread");
        Runner runner = new Runner(plugin);
        runner.start();
        return runner.report;
    }

    private static final class Runner {
        final JavaPlugin plugin;
        final BuiltinActionProbe.Fixture fixture;
        final LegacyHooks hooks;
        final List<String> verified = new ArrayList<>();
        final List<String> limits = new ArrayList<>();
        final List<Entity> spawned = new ArrayList<>();
        final List<ServerPlayer> detached = new ArrayList<>();
        final Queue<BukkitTask> controls = new ConcurrentLinkedQueue<>();
        final CompletableFuture<Map<String, Object>> report = new CompletableFuture<>();
        final Map<String, Object> evidence = new LinkedHashMap<>();
        VaultService vaultService;
        int attempted;

        Runner(JavaPlugin plugin) {
            this.plugin = plugin;
            fixture = new BuiltinActionProbe.Fixture(plugin);
            hooks = new LegacyHooks((viewer, text) -> null, fixture.actions.integrations());
        }

        void start() {
            later(
                    240,
                    () ->
                            finish(
                                    new AssertionError(
                                            "Optional integration probe timed out after 240 ticks")));
            try {
                check(
                        new LegacyHooks((viewer, text) -> null)
                                .papi(null, "unchanged")
                                .equals("unchanged"),
                        "the existing LegacyHooks constructor remains usable");
                vault();
            } catch (Throwable error) {
                finish(error);
            }
        }

        NiActionContext context(Object caster, Map<String, Object> additional) {
            Map<String, Object> params = new LinkedHashMap<>(additional);
            params.put("hooks", hooks);
            return fixture.context(caster, params);
        }

        void vault() throws Exception {
            Plugin provider = enabled("Vault");
            evidence.put("vaultAvailable", provider != null);
            if (provider == null) {
                check(
                        fixture.actions.integrations().vault() == null
                                && hooks.getVaultHooker() == null,
                        "missing or disabled Vault returns null without loading its API");
                var context = context(fixture.player.value, Map.of());
                check(
                        !fixture.ready(
                                                context,
                                                List.of(
                                                        "give-money: 5",
                                                        "takeMoney: 5",
                                                        "js: global.put('continued', hooks.INSTANCE.vaultHooker === null); true;"))
                                        .stopped()
                                && Boolean.TRUE.equals(context.getGlobal().get("continued")),
                        "money actions without Vault are successful no-ops and preserve script null checks");
                limits.add(
                        "Vault API/provider cases require the separate sandbox with real Vault installed");
                mythic();
                return;
            }
            evidence.put("vaultVersion", provider.getPluginMeta().getVersion());
            var bridge = fixture.actions.integrations().vault();
            check(
                    bridge != null && hooks.getVaultHooker() == bridge,
                    "script and action routes share the same Vault facade");
            vaultService = new VaultService(plugin, provider);
            if (vaultService.original == null) {
                bridge.giveMoney(fixture.player.value, 3);
                bridge.takeMoney(fixture.player.value, 3);
                check(
                        bridge.getMoney(fixture.player.value) == 0,
                        "Vault without an Economy service is a no-op and reports zero");
            } else
                limits.add(
                        "No-provider Vault case skipped because the sandbox already has an economy service");
            vaultService.install(17.25);
            check(
                    bridge.getMoney(fixture.player.value) == 17.25,
                    "Vault reads the currently registered real Economy interface");
            vaultService.calls.clear();
            NiActionContext context = context(fixture.player.value, Map.of());
            var result =
                    fixture.ready(
                            context,
                            List.of(
                                    "NeigeItems.give-money: -7.5",
                                    "takeMoney: -3",
                                    "giveMoney: invalid",
                                    "take-money: 1e3",
                                    "give-money: NaN",
                                    "take-money: Infinity",
                                    "js: hooks.INSTANCE.vaultHooker.giveMoney(player, -2); hooks.getVaultHooker().takeMoney(player, -4);"
                                            + " global.put('balance', hooks.getVaultHooker().getMoney(player)); global.put('continued', true); true;"));
            check(
                    !result.stopped() && Boolean.TRUE.equals(context.getGlobal().get("continued")),
                    "failed EconomyResponse values do not stop NI money actions or direct void script calls");
            List<VaultCall> mutations = vaultService.mutations();
            check(
                    mutations.stream()
                            .map(VaultCall::amount)
                            .toList()
                            .equals(List.of(-7.5, -3.0, 0.0, 0.0, 0.0, 0.0, -2.0, -4.0)),
                    "negative amounts are forwarded while invalid, exponent and non-finite words parse as zero");
            check(
                    mutations.stream()
                            .map(VaultCall::method)
                            .toList()
                            .equals(
                                    List.of(
                                            "depositPlayer",
                                            "withdrawPlayer",
                                            "depositPlayer",
                                            "withdrawPlayer",
                                            "depositPlayer",
                                            "withdrawPlayer",
                                            "depositPlayer",
                                            "withdrawPlayer")),
                    "hyphenated, camel-case and namespaced actions choose the correct economy operation");
            check(
                    ((Number) context.getGlobal().get("balance")).doubleValue() == 17.25,
                    "script getMoney returns the provider balance");
            check(
                    vaultService.calls.stream()
                            .allMatch(
                                    call ->
                                            call.offlineOverload
                                                    && call.player.equals(fixture.player.id)),
                    "all money operations use the OfflinePlayer overload with the original player");
            vaultService.replace(41.5);
            check(
                    hooks.getVaultHooker() == bridge
                            && bridge.getMoney(fixture.player.value) == 41.5,
                    "a cached facade follows a replacement Economy service instead of retaining the previous provider");
            int previous = vaultService.mutations().size();
            check(
                    !fixture.ready(
                                            context(null, Map.of()),
                                            List.of("give-money: 7", "take-money: 7"))
                                    .stopped()
                            && vaultService.mutations().size() == previous,
                    "money actions without a Player never call the provider");
            Queue<Boolean> threads = new ConcurrentLinkedQueue<>();
            var asynchronous = context(fixture.player.value, Map.of("threads", threads));
            after(
                    fixture.run(
                            asynchronous,
                            List.of(
                                    "async",
                                    "give-money: 11",
                                    "js: threads.add(Java.type('org.bukkit.Bukkit').isPrimaryThread()); true;",
                                    "take-money: 12",
                                    "sync")),
                    outcome -> {
                        check(
                                !outcome.stopped() && List.copyOf(threads).equals(List.of(false)),
                                "async money branches restore their worker continuation after the effect");
                        check(
                                vaultService.calls.stream().allMatch(VaultCall::primaryThread),
                                "all configured money effects invoke the Economy service on the main thread");
                        evidence.put(
                                "vaultCalls",
                                vaultService.calls.stream().map(Object::toString).toList());
                        vaultService.close();
                        check(
                                vaultService.restored(),
                                "the temporary Economy providers are removed and the previous registration is restored");
                        try {
                            mythic();
                        } catch (Throwable error) {
                            finish(error);
                        }
                    });
        }

        void mythic() throws Exception {
            Plugin provider = enabled("MythicMobs");
            evidence.put("mythicAvailable", provider != null);
            if (provider == null) {
                check(
                        fixture.actions.integrations().mythic() == null
                                && hooks.getMythicMobsHooker() == null,
                        "missing or disabled MythicMobs returns null without loading its API");
                var context = context(fixture.player.value, Map.of());
                check(
                        !fixture.ready(
                                                context,
                                                List.of(
                                                        "cast-skill: absent",
                                                        "NeigeItems.castSkill: absent",
                                                        "js: global.put('continued', hooks.INSTANCE.mythicMobsHooker === null); true;"))
                                        .stopped()
                                && Boolean.TRUE.equals(context.getGlobal().get("continued")),
                        "cast-skill without MythicMobs succeeds and preserves script null checks");
                limits.add(
                        "Real Mythic skill execution requires the separate sandbox with MythicMobs and ItemLoomProbeOptional fixture");
                finish(null);
                return;
            }
            evidence.put("mythicVersion", provider.getPluginMeta().getVersion());
            var bridge = fixture.actions.integrations().mythic();
            check(
                    bridge != null && hooks.getMythicMobsHooker() == bridge,
                    "script and action routes share the same Mythic facade");
            Class<?> entry =
                    provider.getClass()
                            .getClassLoader()
                            .loadClass("io.lumine.mythic.bukkit.MythicBukkit");
            Object instance = entry.getMethod("inst").invoke(null);
            Object api = entry.getMethod("getAPIHelper").invoke(instance);
            Method cast =
                    api.getClass()
                            .getMethod(
                                    "castSkill",
                                    Entity.class,
                                    String.class,
                                    Entity.class,
                                    Location.class,
                                    Collection.class,
                                    Collection.class,
                                    float.class);
            Object manager = entry.getMethod("getSkillManager").invoke(instance);
            Object configured =
                    manager.getClass().getMethod("getSkill", String.class).invoke(manager, SKILL);
            check(
                    configured instanceof java.util.Optional<?> skill && skill.isPresent(),
                    "the real Mythic provider loaded the controlled skill fixture " + SKILL);
            ArmorStand caster = stand(),
                    trigger = stand(),
                    defaultBridge = stand(),
                    defaultApi = stand();
            evidence.put(
                    "mythicEntityType", ((CraftEntity) caster).getHandle().getClass().getName());
            check(
                    bridge.getMythicId(caster) == null,
                    "a real ordinary ArmorStand has no Mythic mob identity");
            String absent = "ItemLoomProbeMissing_" + UUID.randomUUID();
            check(
                    Boolean.FALSE.equals(
                            cast.invoke(
                                    api,
                                    caster,
                                    absent,
                                    trigger,
                                    caster.getLocation(),
                                    null,
                                    null,
                                    1.0f)),
                    "the real Mythic API reports false for an unknown skill");
            bridge.castSkill(caster, absent);
            bridge.castSkill(caster, absent, trigger);
            check(
                    caster.getScoreboardTags().isEmpty() && trigger.getScoreboardTags().isEmpty(),
                    "both bridge overloads tolerate unknown skills without effects");
            check(
                    !fixture.ready(context(caster, Map.of()), "cast-skill: " + SKILL).stopped()
                            && caster.getScoreboardTags().isEmpty(),
                    "the configured cast-skill action retains its player-only caster contract");
            var explicitContext = context(caster, Map.of("triggerEntity", trigger));
            check(
                    !fixture.ready(
                                    explicitContext,
                                    "js: hooks.INSTANCE.mythicMobsHooker.castSkill(target, '"
                                            + SKILL
                                            + "', triggerEntity); true;")
                            .stopped(),
                    "legacy script property and three-argument castSkill dispatch to the real provider");
            bridge.castSkill(defaultBridge, SKILL);
            check(
                    Boolean.TRUE.equals(
                            cast.invoke(
                                    api,
                                    defaultApi,
                                    SKILL,
                                    null,
                                    defaultApi.getLocation(),
                                    null,
                                    null,
                                    1.0f)),
                    "the reference seven-argument Mythic call accepts a null trigger");
            Player player = detachedPlayer(caster.getLocation());
            evidence.put(
                    "mythicPlayerType", ((CraftEntity) player).getHandle().getClass().getName());
            evidence.put("mythicPlayerConnected", player.isOnline());
            check(
                    !fixture.ready(
                                    context(player, Map.of()),
                                    List.of("cast-skill: " + absent, "castSkill: " + SKILL))
                            .stopped(),
                    "string cast-skill/castSkill run on a real detached NMS player and ignore a missing skill result");
            later(
                    3,
                    () -> {
                        evidence.put("mythicCasterTags", List.copyOf(caster.getScoreboardTags()));
                        evidence.put("mythicTriggerTags", List.copyOf(trigger.getScoreboardTags()));
                        evidence.put(
                                "mythicDefaultTags",
                                List.copyOf(defaultBridge.getScoreboardTags()));
                        evidence.put(
                                "mythicReferenceDefaultTags",
                                List.copyOf(defaultApi.getScoreboardTags()));
                        evidence.put("mythicPlayerTags", List.copyOf(player.getScoreboardTags()));
                        check(
                                caster.getScoreboardTags().contains(SELF_TAG)
                                        && trigger.getScoreboardTags().contains(TRIGGER_TAG)
                                        && !caster.getScoreboardTags().contains(TRIGGER_TAG)
                                        && !trigger.getScoreboardTags().contains(SELF_TAG),
                                "three-argument castSkill preserves distinct caster and trigger entity targeting");
                        check(
                                defaultBridge.getScoreboardTags().contains(SELF_TAG)
                                        && defaultBridge
                                                .getScoreboardTags()
                                                .equals(defaultApi.getScoreboardTags()),
                                "two-argument castSkill has the same effects as the actual API called with trigger=null");
                        check(
                                player.getScoreboardTags().contains(SELF_TAG)
                                        && player.getScoreboardTags().contains(TRIGGER_TAG),
                                "configured player cast-skill uses the same entity for caster and trigger");
                        limits.add(
                                "Mythic entities are real NMS objects; the player is detached, with no network connection or PlayerList registration");
                        finish(null);
                    });
        }

        ArmorStand stand() {
            var world = Bukkit.getWorlds().getFirst();
            ArmorStand stand =
                    world.spawn(
                            world.getSpawnLocation().add(0.5, 3, 0.5),
                            ArmorStand.class,
                            entity -> {
                                entity.setGravity(false);
                                entity.setInvulnerable(true);
                                entity.setInvisible(true);
                                entity.setMarker(true);
                                entity.setPersistent(false);
                            });
            spawned.add(stand);
            return stand;
        }

        Player detachedPlayer(Location location) {
            ServerPlayer player =
                    new ServerPlayer(
                            ((CraftServer) Bukkit.getServer()).getServer(),
                            ((CraftWorld) location.getWorld()).getHandle(),
                            new GameProfile(UUID.randomUUID(), "IL_Probe"),
                            ClientInformation.createDefault());
            detached.add(player);
            player.setPos(location.getX(), location.getY(), location.getZ());
            // Never add this player to the world or the PlayerList, and never manufacture a
            // connection.
            return player.getBukkitEntity();
        }

        void check(boolean success, String name) {
            attempted++;
            if (!success) throw new AssertionError(name);
            verified.add(name);
        }

        <T> void after(CompletionStage<T> stage, Consumer<T> body) {
            stage.whenComplete(
                    (value, error) ->
                            later(
                                    0,
                                    () -> {
                                        if (error != null)
                                            throw new IllegalStateException(
                                                    "Integration action failed", error);
                                        body.accept(value);
                                    }));
        }

        void later(long ticks, Runnable body) {
            if (report.isDone()) return;
            controls.add(
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    plugin,
                                    () -> {
                                        if (report.isDone()) return;
                                        try {
                                            body.run();
                                        } catch (Throwable error) {
                                            finish(error);
                                        }
                                    },
                                    ticks));
        }

        void finish(Throwable failure) {
            if (report.isDone()) return;
            for (BukkitTask task : controls) task.cancel();
            if (vaultService != null) {
                try {
                    vaultService.close();
                    if (!vaultService.restored())
                        throw new AssertionError("Economy fixture leaked a service registration");
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            for (Entity entity : spawned) {
                try {
                    entity.remove();
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            for (ServerPlayer player : detached) {
                try {
                    player.getAdvancements().clearTriggers();
                    player.getTextFilter().leave();
                } catch (Throwable error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            try {
                fixture.close();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            Map<String, Object> value = new LinkedHashMap<>(evidence);
            value.put("passed", failure == null);
            value.put("checks", attempted);
            value.put("passedChecks", verified.size());
            value.put("verified", List.copyOf(verified));
            value.put("limits", List.copyOf(limits));
            value.put("server", plugin.getServer().getMinecraftVersion());
            value.put(
                    "fixtures",
                    "real optional plugin APIs; scoped dynamic Economy service; real Mythic skill fixture and NMS entities");
            if (failure != null) value.put("failure", failure.toString());
            report.complete(value);
        }
    }

    private static Plugin enabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    private record VaultCall(
            String method,
            double amount,
            UUID player,
            boolean offlineOverload,
            boolean primaryThread) {}

    /** Uses the installed Vault's interface and response classes; never supplies a fake Vault plugin. */
    private static final class VaultService implements AutoCloseable {
        final JavaPlugin owner;
        final Class<?> service;
        final Constructor<?> response;
        final Object failureType;
        final Object original;
        final Queue<VaultCall> calls = new ConcurrentLinkedQueue<>();
        final List<Object> registered = new ArrayList<>();
        Object current;

        VaultService(JavaPlugin owner, Plugin vault) throws Exception {
            this.owner = owner;
            ClassLoader loader = vault.getClass().getClassLoader();
            service = loader.loadClass("net.milkbowl.vault.economy.Economy");
            Class<?> result = loader.loadClass("net.milkbowl.vault.economy.EconomyResponse");
            Class<?> responseType =
                    loader.loadClass("net.milkbowl.vault.economy.EconomyResponse$ResponseType");
            failureType =
                    java.util.Arrays.stream(responseType.getEnumConstants())
                            .filter(value -> ((Enum<?>) value).name().equals("FAILURE"))
                            .findFirst()
                            .orElseThrow();
            response =
                    result.getConstructor(double.class, double.class, responseType, String.class);
            original = provider();
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void install(double balance) {
            Object proxy =
                    Proxy.newProxyInstance(
                            service.getClassLoader(),
                            new Class<?>[] {service},
                            (self, method, args) ->
                                    switch (method.getName()) {
                                        case "depositPlayer", "withdrawPlayer", "getBalance" -> {
                                            boolean offline =
                                                    method.getParameterTypes()[0]
                                                                    == OfflinePlayer.class
                                                            && args[0] instanceof OfflinePlayer;
                                            if (!offline)
                                                throw new AssertionError(
                                                        "Wrong economy overload: " + method);
                                            double amount =
                                                    args.length > 1
                                                            ? ((Number) args[1]).doubleValue()
                                                            : 0;
                                            calls.add(
                                                    new VaultCall(
                                                            method.getName(),
                                                            amount,
                                                            ((OfflinePlayer) args[0]).getUniqueId(),
                                                            true,
                                                            Bukkit.isPrimaryThread()));
                                            yield method.getName().equals("getBalance")
                                                    ? balance
                                                    : response.newInstance(
                                                            amount,
                                                            balance,
                                                            failureType,
                                                            "Intentional probe response");
                                        }
                                        case "isEnabled" -> true;
                                        case "getName", "toString" -> "ItemLoomProbeEconomy";
                                        case "hashCode" -> System.identityHashCode(self);
                                        case "equals" -> self == args[0];
                                        default ->
                                                throw new UnsupportedOperationException(
                                                        "Unexpected probe Economy call: " + method);
                                    });
            registered.add(proxy);
            Bukkit.getServicesManager()
                    .register((Class) service, proxy, owner, ServicePriority.Highest);
            if (provider() != proxy)
                throw new AssertionError(
                        "Probe Economy provider was not selected; refusing to call the preexisting economy");
            current = proxy;
        }

        void replace(double balance) {
            if (current != null) Bukkit.getServicesManager().unregister(service, current);
            current = null;
            install(balance);
        }

        Object provider() {
            var registration = Bukkit.getServicesManager().getRegistration(service);
            return registration == null ? null : registration.getProvider();
        }

        List<VaultCall> mutations() {
            return calls.stream().filter(call -> !call.method.equals("getBalance")).toList();
        }

        boolean restored() {
            return provider() == original;
        }

        @Override
        public void close() {
            for (Object proxy : registered) Bukkit.getServicesManager().unregister(service, proxy);
            registered.clear();
            current = null;
        }
    }
}
