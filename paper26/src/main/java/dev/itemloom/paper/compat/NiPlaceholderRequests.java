package dev.itemloom.paper.compat;

import com.google.gson.*;
import dev.itemloom.compat.ni.action.*;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import java.util.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class NiPlaceholderRequests {
    private record Request(UUID player, String parameters) {}

    private final Supplier<NiCatalog> catalog;
    private final ThreadLocal<Set<Request>> active = ThreadLocal.withInitial(HashSet::new);

    public NiPlaceholderRequests(Supplier<NiCatalog> catalog) {
        this.catalog = catalog;
    }

    public String request(OfflinePlayer player, String parameters) {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread())
            throw new IllegalStateException("NI placeholders require the main thread");
        NiCatalog owner = catalog.get();
        if (owner == null || !owner.active()) return "";
        Request request = new Request(player == null ? null : player.getUniqueId(), parameters);
        Set<Request> path = active.get();
        if (path.size() >= 32 || !path.add(request)) return "";
        try {
            return resolve(owner, player, parameters);
        } finally {
            path.remove(request);
            if (path.isEmpty()) active.remove();
        }
    }

    private String resolve(NiCatalog owner, OfflinePlayer offline, String parameters) {
        int split = parameters.indexOf('_');
        String kind =
                (split < 0 ? parameters : parameters.substring(0, split)).toLowerCase(Locale.ROOT);
        String content = split < 0 ? null : parameters.substring(split + 1);
        Player player = offline == null ? null : offline.getPlayer();
        if (kind.equals("parse")) {
            if (content == null) return "";
            NiActionContext context = owner.actionContext(offline, null);
            context.set(NiContextKeys.PAPI_ENVIRONMENT, null);
            return context.parse(content);
        }
        if (kind.equals("amount")) {
            int total = 0;
            if (player != null && content != null)
                for (ItemStack item : player.getInventory().getContents())
                    if (content.equals(NiItemNodes.itemId(item))) total += item.getAmount();
            return String.valueOf(total);
        }
        if (kind.equals("count"))
            return String.valueOf(
                    player != null
                            && content != null
                            && InventoryRequirements.fulfilled(
                                    content,
                                    Arrays.asList(player.getInventory().getContents()),
                                    NiItemNodes::itemId,
                                    ItemStack::getAmount));
        if (player == null || content == null || !Set.of("data", "nbt").contains(kind)) return "";
        String[] parts = content.split("_", 3);
        if (parts.length != 3 || parts[1].isEmpty()) return "";
        ItemStack item = slot(player, parts[1]);
        if (item == null || item.isEmpty()) return "";
        String operation = parts[0].toLowerCase(Locale.ROOT), value = parts[2];
        if (kind.equals("data")) {
            ItemIdentity identity = NiItemNodes.identity(item);
            if (identity == null) return "";
            Map<String, String> rolls = identity.rolls();
            return switch (operation) {
                case "get" -> rolls.getOrDefault(value, "");
                case "has" -> String.valueOf(rolls.containsKey(value));
                case "check" ->
                        String.valueOf(
                                expected(value).entrySet().stream()
                                        .allMatch(
                                                entry ->
                                                        entry.getValue() == null
                                                                ? !rolls.containsKey(entry.getKey())
                                                                : entry.getValue()
                                                                        .equals(
                                                                                rolls.get(
                                                                                        entry
                                                                                                .getKey()))));
                case "eval" -> evaluate(owner, player, item, identity, value);
                default -> "";
            };
        }
        LegacyNbt.Compound nbt = new LegacyNbtItemStack(item).getOrCreateTag();
        return switch (operation) {
            case "get" -> Objects.toString(nbt.getDeepStringOrNull(value), "");
            case "has" -> String.valueOf(nbt.getDeep(value) != null);
            case "check" ->
                    String.valueOf(
                            expected(value).entrySet().stream()
                                    .allMatch(
                                            entry ->
                                                    entry.getValue() == null
                                                            ? nbt.getDeep(entry.getKey()) != null
                                                            : entry.getValue()
                                                                    .equals(
                                                                            nbt.getDeepStringOrNull(
                                                                                    entry
                                                                                            .getKey()))));
            case "eval" -> evaluate(owner, player, item, null, value);
            default -> "";
        };
    }

    private static String evaluate(
            NiCatalog owner, Player player, ItemStack item, ItemIdentity identity, String source) {
        if (!owner.input().settings().bool("Papi.enableJs", false)) return "";
        Map<String, Object> values = new HashMap<>();
        values.put("itemStack", item);
        values.put("itemTag", new LegacyNbtItemStack(item).getOrCreateTag());
        if (identity != null) {
            values.put("id", identity.id());
            values.put("data", new HashMap<>(identity.rolls()));
        }
        Object result = owner.actionContext(player, values).evaluate(source);
        return result == null ? "" : result.toString();
    }

    private static ItemStack slot(Player player, String name) {
        name = name.toLowerCase(Locale.ROOT);
        if (name.equals("hand")) return player.getInventory().getItemInMainHand();
        int slot;
        switch (name) {
            case "offhand" -> slot = 40;
            case "head" -> slot = 39;
            case "chest" -> slot = 38;
            case "legs" -> slot = 37;
            case "feet" -> slot = 36;
            default -> {
                try {
                    slot = Integer.parseInt(name);
                } catch (NumberFormatException error) {
                    return null;
                }
            }
        }
        return slot < 0 || slot > 40 ? null : player.getInventory().getItem(slot);
    }

    private static Map<String, String> expected(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonParser.parseString(json)
                .getAsJsonObject()
                .entrySet()
                .forEach(
                        entry -> {
                            JsonElement value = entry.getValue();
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
