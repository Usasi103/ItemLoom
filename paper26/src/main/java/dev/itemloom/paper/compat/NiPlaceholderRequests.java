package dev.itemloom.paper.compat;

import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** NI's placeholder vocabulary, independent of the optional PlaceholderAPI class loader.
 * Requests touching Bukkit or scripts must be dispatched on the server thread by the caller. */
public final class NiPlaceholderRequests {
    private record Request(UUID viewer, String parameters) {}

    private final Supplier<NiCatalog> catalog;
    private final ThreadLocal<Set<Request>> path = new ThreadLocal<>();

    public NiPlaceholderRequests(Supplier<NiCatalog> catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    public String request(OfflinePlayer viewer, String parameters) {
        // Reject before acquiring a script lock or inspecting a live player/inventory.
        ItemsService.requireThread();
        NiCatalog current = catalog.get();
        if (current == null || !current.active()) return "";
        Request request = new Request(viewer == null ? null : viewer.getUniqueId(), parameters);
        Set<Request> calls = path.get();
        if (calls == null) {
            calls = new HashSet<>();
            path.set(calls);
        }
        if (calls.size() >= 32 || !calls.add(request)) return "";
        try {
            return resolve(current, viewer, parameters);
        } finally {
            calls.remove(request);
            if (calls.isEmpty()) path.remove();
        }
    }

    private String resolve(NiCatalog current, OfflinePlayer viewer, String parameters) {
        String[] head = parameters.split("_", 2);
        String kind = head[0].toLowerCase(Locale.ROOT);
        if (kind.equals("parse")) {
            if (head.length < 2) return "";
            var context = current.actionContext(viewer, null);
            context.set(NiContextKeys.PAPI_ENVIRONMENT, null);
            return context.parse(head[1]);
        }
        if (kind.equals("amount")) {
            if (!(viewer instanceof Player player) || head.length < 2) return "0";
            int total = 0;
            for (ItemStack item : player.getInventory().getContents())
                if (head[1].equals(NiItemNodes.itemId(item))) total += item.getAmount();
            return Integer.toString(total);
        }
        if (kind.equals("count")) {
            if (!(viewer instanceof Player player) || head.length < 2) return "false";
            String[] args = head[1].split("[_\\\\]", -1);
            Map<String, Integer> needed = new HashMap<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                try {
                    int count = Integer.parseInt(args[i + 1]);
                    if (count > 0) needed.put(args[i], count);
                } catch (NumberFormatException ignored) {
                }
            }
            for (ItemStack item : player.getInventory().getContents()) {
                String id = NiItemNodes.itemId(item);
                Integer count = needed.get(id);
                if (count == null) continue;
                if (count > item.getAmount()) needed.put(id, count - item.getAmount());
                else needed.remove(id);
            }
            return Boolean.toString(needed.isEmpty());
        }
        if ((!kind.equals("data") && !kind.equals("nbt"))
                || !(viewer instanceof Player player)
                || head.length < 2) return "";
        String[] args = head[1].split("_", 3);
        if (args.length != 3) return "";
        ItemStack item = slot(player, args[1].toLowerCase(Locale.ROOT));
        if (item == null || item.isEmpty()) return "";
        String operation = args[0].toLowerCase(Locale.ROOT), content = args[2];
        if (kind.equals("data")) {
            var identity = NiItemNodes.identity(item);
            if (identity == null) return "";
            Map<String, String> data = identity.rolls();
            return switch (operation) {
                case "get" -> data.getOrDefault(content, "");
                case "has" -> Boolean.toString(data.containsKey(content));
                case "check" ->
                        Boolean.toString(
                                expected(content).entrySet().stream()
                                        .allMatch(
                                                entry ->
                                                        entry.getValue() == null
                                                                ? !data.containsKey(entry.getKey())
                                                                : entry.getValue()
                                                                        .equals(
                                                                                data.get(
                                                                                        entry
                                                                                                .getKey()))));
                case "eval" -> {
                    if (!current.input().settings().bool("Papi.enableJs", false)) yield "";
                    Map<String, Object> bindings = new HashMap<>();
                    bindings.put("itemStack", item);
                    bindings.put("itemTag", new LegacyNbtItemStack(item).getOrCreateTag());
                    bindings.put("id", identity.id());
                    bindings.put("data", new HashMap<>(data));
                    yield Objects.toString(
                            current.actionContext(player, bindings).evaluate(content), "");
                }
                default -> "";
            };
        }
        var tag = new LegacyNbtItemStack(item).getOrCreateTag();
        return switch (operation) {
            case "get" -> Objects.toString(tag.getDeepStringOrNull(content), "");
            case "has" -> Boolean.toString(tag.getDeep(content) != null);
            // NI deliberately gives JSON null the opposite meaning in data_check and nbt_check.
            case "check" ->
                    Boolean.toString(
                            expected(content).entrySet().stream()
                                    .allMatch(
                                            entry ->
                                                    entry.getValue() == null
                                                            ? tag.getDeep(entry.getKey()) != null
                                                            : entry.getValue()
                                                                    .equals(
                                                                            tag.getDeepStringOrNull(
                                                                                    entry
                                                                                            .getKey()))));
            case "eval" ->
                    current.input().settings().bool("Papi.enableJs", false)
                            ? Objects.toString(
                                    current.actionContext(
                                                    player,
                                                    Map.of("itemStack", item, "itemTag", tag))
                                            .evaluate(content),
                                    "")
                            : "";
            default -> "";
        };
    }

    private static ItemStack slot(Player player, String name) {
        var inventory = player.getInventory();
        int slot;
        switch (name) {
            case "hand":
                return inventory.getItemInMainHand();
            case "offhand":
                slot = 40;
                break;
            case "head":
                slot = 39;
                break;
            case "chest":
                slot = 38;
                break;
            case "legs":
                slot = 37;
                break;
            case "feet":
                slot = 36;
                break;
            default:
                try {
                    slot = Integer.parseInt(name);
                } catch (NumberFormatException invalid) {
                    return null;
                }
        }
        // PlayerInventory's supported numeric slots are 0..40; never leak an NMS bounds error.
        return slot < 0 || slot > 40 ? null : inventory.getItem(slot);
    }

    private static Map<String, String> expected(String json) {
        Map<String, String> result = new HashMap<>();
        JsonParser.parseString(json)
                .getAsJsonObject()
                .entrySet()
                .forEach(
                        entry -> {
                            var value = entry.getValue();
                            result.put(
                                    entry.getKey(),
                                    value.isJsonNull()
                                            ? null
                                            : value.isJsonPrimitive()
                                                    ? value.getAsString()
                                                    : value.toString());
                        });
        return result;
    }
}
