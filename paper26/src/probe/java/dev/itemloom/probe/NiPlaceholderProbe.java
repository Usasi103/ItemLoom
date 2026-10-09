package dev.itemloom.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiItemMigration;
import dev.itemloom.paper.compat.NiPlaceholderRequests;
import dev.itemloom.paper.nms.NmsItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Real NMS items and script engines; player inventory is a deliberate synthetic fixture. */
final class NiPlaceholderProbe {
    private record Case(String request, String expected) {}

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin, Plugin reference)
            throws Exception {
        Path root =
                Files.createTempDirectory("itemloom-ni-placeholder-probe-")
                        .toAbsolutePath()
                        .normalize();
        var checks = new ArrayList<String>();
        Map<String, Object> failures = new LinkedHashMap<>();
        Map<String, Object> corrections = new LinkedHashMap<>();
        var players = new PlayerActionState();
        var active = new AtomicReference<NiCatalog>();
        var requests = new NiPlaceholderRequests(active::get);
        var input =
                new NiRepository.Input(
                        new NiConfig(Map.of("Papi", Map.of("enableJs", true, "enableRegex", true))),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of());
        NiCatalog catalog =
                new NiCatalog(
                        1,
                        input,
                        root,
                        plugin,
                        (viewer, value) ->
                                switch (value) {
                                    case "probe_cycle" ->
                                            requests.request(
                                                    (OfflinePlayer) viewer,
                                                    "parse_<papi::probe_cycle>");
                                    case "probe_inner" ->
                                            requests.request(
                                                    (OfflinePlayer) viewer, "amount_fixture");
                                    default -> null;
                                },
                        players);
        active.set(catalog);
        Player player = ProbePlayer.create("PlaceholderProbe");
        CompoundTag old = new CompoundTag();
        old.putString("id", "fixture");
        old.putString("data", "{\"saved\":\"roll\",\"number\":\"17\",\"empty\":null}");
        CompoundTag custom = new CompoundTag();
        custom.put("NeigeItems", old);
        custom.putInt("integer", 17);
        custom.putString("a.b", "literal-dot");
        ItemStack original = NmsItems.withCustomData(new ItemStack(Material.STONE, 3), custom);
        player.getInventory().setItemInMainHand(new NiItemMigration().convert(original));
        for (int slot = 36; slot <= 40; slot++)
            player.getInventory().setItem(slot, new NiItemMigration().convert(original));
        List<Case> cases =
                List.of(
                        new Case("", ""),
                        new Case("unknown", ""),
                        new Case("parse", ""),
                        new Case("parse_plain_with_underscore", "plain_with_underscore"),
                        new Case("parse_<fastcalc::2+3>", "5"),
                        new Case("parse_<calculation::3*4>", "12"),
                        new Case("parse_<regex::matches_abc_a.*>", "true"),
                        new Case("amount_fixture", "18"),
                        new Case("AMOUNT_fixture", "18"),
                        new Case("amount_other", "0"),
                        new Case("amount", "0"),
                        new Case("count", "false"),
                        new Case("count_", "true"),
                        new Case("count_fixture_18", "true"),
                        new Case("count_fixture_19", "false"),
                        new Case("count_fixture_99_fixture_18", "true"),
                        new Case("count_fixture\\18", "true"),
                        new Case("count_other_bad_fixture_-1", "true"),
                        new Case("count_fixture_18_other_1", "false"),
                        new Case("data_get_hand_saved", "roll"),
                        new Case("DATA_GET_HAND_saved", "roll"),
                        new Case("data_get_hand_missing", ""),
                        new Case("data_get_hand_empty", null),
                        new Case("data_has_hand_empty", "true"),
                        new Case("data_has_hand_missing", "false"),
                        new Case("data_check_hand_{\"saved\":\"roll\",\"missing\":null}", "true"),
                        new Case("data_check_hand_{\"empty\":null}", "false"),
                        new Case("data_check_hand_{\"number\":17}", "true"),
                        new Case("data_check_hand_{\"number\":18}", "false"),
                        new Case("data_check_hand_{}", "true"),
                        new Case("nbt_get_hand_NeigeItems.id", "fixture"),
                        new Case("nbt_get_hand_integer", "17"),
                        new Case("nbt_get_hand_a\\.b", "literal-dot"),
                        new Case("nbt_has_hand_missing", "false"),
                        new Case("nbt_check_hand_{\"integer\":null}", "true"),
                        new Case("nbt_check_hand_{\"missing\":null}", "false"),
                        new Case("nbt_check_hand_{\"integer\":17}", "true"),
                        new Case("nbt_check_hand_{}", "true"),
                        new Case(
                                "data_eval_hand_id + ':' + data.get('saved') + ':' + player.getName()",
                                "fixture:roll:PlaceholderProbe"),
                        new Case(
                                "nbt_eval_hand_itemTag.getDeepString('NeigeItems.id') + ':' + itemStack.getAmount()",
                                "fixture:3"),
                        new Case("data_eval_hand_var requestLocal = 9; requestLocal", "9"),
                        new Case("data_eval_hand_typeof requestLocal", "undefined"),
                        new Case("nbt_eval_hand_null", ""),
                        new Case("data_get_offhand_saved", "roll"),
                        new Case("data_get_head_saved", "roll"),
                        new Case("data_get_chest_saved", "roll"),
                        new Case("data_get_legs_saved", "roll"),
                        new Case("data_get_feet_saved", "roll"),
                        new Case("data_get_0_saved", "roll"),
                        new Case("data_get_1_saved", ""),
                        new Case("data_bad_hand_saved", ""),
                        new Case("nbt_get_invalid_integer", ""));
        Object manager = null;
        boolean oldJs = false, oldRegex = false;
        java.lang.reflect.Method referenceRequest = null;
        try {
            if (reference != null && reference.isEnabled()) {
                var loader = reference.getClass().getClassLoader();
                var configType = loader.loadClass("pers.neige.neigeitems.manager.ConfigManager");
                manager = configType.getField("INSTANCE").get(null);
                oldJs = (boolean) configType.getMethod("getEnableJsPapi").invoke(manager);
                oldRegex = (boolean) configType.getMethod("getEnableRegexPapi").invoke(manager);
                configType.getMethod("setEnableJsPapi", boolean.class).invoke(manager, true);
                configType.getMethod("setEnableRegexPapi", boolean.class).invoke(manager, true);
                referenceRequest =
                        loader.loadClass("pers.neige.neigeitems.papi.PapiExpansion")
                                .getDeclaredMethod("request", OfflinePlayer.class, String.class);
                referenceRequest.setAccessible(true);
            }
            for (Case entry : cases) {
                String actual = requests.request(player, entry.request());
                check(
                        checks,
                        failures,
                        entry.request(),
                        Objects.equals(entry.expected(), actual),
                        "expected=" + entry.expected() + ", actual=" + actual);
                if (referenceRequest != null) {
                    Player referencePlayer = ProbePlayer.create("PlaceholderProbe");
                    referencePlayer.getInventory().setItemInMainHand(original.clone());
                    for (int slot = 36; slot <= 40; slot++)
                        referencePlayer.getInventory().setItem(slot, original.clone());
                    Object expected =
                            referenceRequest.invoke(null, referencePlayer, entry.request());
                    if (entry.request().equals("data_eval_hand_typeof requestLocal")
                            && Objects.equals(expected, "number")
                            && Objects.equals(actual, "undefined")) {
                        corrections.put(
                                entry.request(),
                                Map.of(
                                        "NI",
                                        expected,
                                        "ItemLoom",
                                        actual,
                                        "reason",
                                        "NI's shared engine leaks a prior request's var; IL retains request-local script scope"));
                        checks.add(
                                "known correction: PAPI request variables do not leak between requests");
                    } else
                        check(
                                checks,
                                failures,
                                "reference: " + entry.request(),
                                Objects.equals(expected, actual),
                                "NI=" + expected + ", IL=" + actual);
                }
            }
            check(
                    checks,
                    failures,
                    "numeric slot bounds",
                    requests.request(player, "nbt_get_-1_integer").isEmpty()
                            && requests.request(player, "nbt_get_41_integer").isEmpty(),
                    "bounds must be handled");
            check(
                    checks,
                    failures,
                    "null/offline defaults",
                    requests.request(null, "amount_fixture").equals("0")
                            && requests.request(null, "count_fixture_1").equals("false")
                            && requests.request(null, "data_get_hand_saved").isEmpty(),
                    "offline fallback");
            check(
                    checks,
                    failures,
                    "cycles terminate and allow later requests",
                    requests.request(player, "parse_<papi::probe_cycle>").isEmpty()
                            && requests.request(player, "parse_<papi::probe_inner>").equals("18"),
                    "cycle path must be removed");
            check(
                    checks,
                    failures,
                    "read-only queries preserve independent item identity",
                    NmsItems.customData(player.getInventory().getItemInMainHand())
                            .equals(NmsItems.customData(new NiItemMigration().convert(original))),
                    "NBT changed");
            var disabledInput =
                    new NiRepository.Input(
                            new NiConfig(Map.of()),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of());
            try (var disabled =
                    new NiCatalog(
                            2, disabledInput, root, plugin, (viewer, value) -> null, players)) {
                active.set(disabled);
                check(
                        checks,
                        failures,
                        "PAPI JS and regex default disabled",
                        requests.request(player, "parse_<calculation::2+3>")
                                        .equals("<calculation::2+3>")
                                && requests.request(player, "parse_<regex::matches_abc_a.*>")
                                        .equals("<regex::matches_abc_a.*>")
                                && requests.request(player, "data_eval_hand_3+4").isEmpty()
                                && requests.request(player, "nbt_eval_hand_3+4").isEmpty(),
                        "Papi gate");
                check(
                        checks,
                        failures,
                        "ordinary calculations and regex remain enabled",
                        disabled.actionContext(player, null)
                                .parse("<calculation::2+3>|<regex::matches_abc_a.*>")
                                .equals("5|true"),
                        "non-PAPI gate leaked");
                var context = disabled.actionContext(player, null);
                context.set(NiContextKeys.PAPI_ENVIRONMENT, null);
                context.set(
                        NiContextKeys.SECTIONS,
                        new NiConfig(
                                Map.of(
                                        "configured",
                                        Map.of(
                                                "type", "regex", "mode", "matches", "value", "abc",
                                                "pattern", "a.*"))));
                check(
                        checks,
                        failures,
                        "configured regex retains NI semantics",
                        context.parse("<configured>").equals("true"),
                        "inline-only gate");
            }
        } catch (Throwable error) {
            failures.put("probe", error.toString());
        } finally {
            active.set(catalog);
            if (manager != null) {
                manager.getClass()
                        .getMethod("setEnableJsPapi", boolean.class)
                        .invoke(manager, oldJs);
                manager.getClass()
                        .getMethod("setEnableRegexPapi", boolean.class)
                        .invoke(manager, oldRegex);
            }
        }
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                requests.request(player, "data_get_hand_saved");
                                return false;
                            } catch (IllegalStateException expected) {
                                return true;
                            }
                        })
                .whenComplete(
                        (rejected, error) ->
                                Bukkit.getScheduler()
                                        .runTask(
                                                plugin,
                                                () -> {
                                                    check(
                                                            checks,
                                                            failures,
                                                            "raw backend rejects worker before inventory/script access",
                                                            error == null
                                                                    && Boolean.TRUE.equals(
                                                                            rejected),
                                                            String.valueOf(error));
                                                    catalog.close();
                                                    players.close();
                                                    check(
                                                            checks,
                                                            failures,
                                                            "closed catalog returns empty",
                                                            requests.request(
                                                                            player,
                                                                            "amount_fixture")
                                                                    .isEmpty(),
                                                            "closed catalog");
                                                    try {
                                                        Files.delete(root);
                                                    } catch (Exception failure) {
                                                        failures.put("cleanup", failure.toString());
                                                    }
                                                    result.complete(
                                                            Map.of(
                                                                    "passed",
                                                                    failures.isEmpty(),
                                                                    "checks",
                                                                    checks.size() + failures.size(),
                                                                    "assertions",
                                                                    checks,
                                                                    "failures",
                                                                    failures,
                                                                    "referenceLoaded",
                                                                    reference != null
                                                                            && reference
                                                                                    .isEnabled(),
                                                                    "realClient",
                                                                    false,
                                                                    "intentionalCorrections",
                                                                    corrections,
                                                                    "boundary",
                                                                    "request backend only; public PAPI registration and async dispatch tested separately"));
                                                }));
        return result;
    }

    private static void check(
            List<String> checks,
            Map<String, Object> failures,
            String name,
            boolean valid,
            String detail) {
        if (valid) checks.add(name);
        else failures.put(name, detail);
    }
}
