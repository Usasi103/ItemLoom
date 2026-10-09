package dev.itemloom.paper.compat.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import dev.itemloom.compat.ni.NiTagValues;
import dev.itemloom.compat.ni.NiTemplate;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * NI editor-language contract, implemented against the current item components.
 * Behavioral reference: NeigeItems ca93bc4f ItemEditorManager/ItemManager (GPL-3.0).
 * Execute on the server thread; action hosts must dispatch before entering this adapter.
 */
@SuppressWarnings("deprecation")
public final class LegacyItemEditorManager {
    @FunctionalInterface
    public interface Editor {
        Boolean apply(Player player, ItemStack item, String content);
    }

    /** Generation is owned by the independent item service, never by this script adapter. */
    public interface Host {
        Material material(String content);

        String papi(Player player, String content);

        String section(Player player, ItemStack item, String content);

        /** amount=null skips splitting; otherwise split/give and generation belong to the host transaction. */
        boolean refresh(
                Player player,
                ItemStack item,
                List<String> remove,
                Map<String, String> overrides,
                Integer amount);

        boolean rebuild(Player player, ItemStack item, Map<String, String> overrides);
    }

    public final LegacyItemEditorManager INSTANCE = this;
    private static final Pattern GROUP_REFERENCE = Pattern.compile("\\$(\\d+)");
    private final Host host;
    private final HashMap<String, Editor> itemEditors = new HashMap<>();
    private final ArrayList<String> editorNames = new ArrayList<>();

    public LegacyItemEditorManager(Host host) {
        this.host = Objects.requireNonNull(host);
        reload();
    }

    public HashMap<String, Editor> getItemEditors() {
        return itemEditors;
    }

    public ArrayList<String> getEditorNames() {
        return editorNames;
    }

    public void runEditor(String id, String content, ItemStack item, Player player) {
        runEditorWithResult(id, content, item, player);
    }

    public Boolean runEditorWithResult(String id, String content, ItemStack item, Player player) {
        Editor editor = itemEditors.get(id.toLowerCase(Locale.getDefault()));
        if (editor == null) return null;
        mainThread();
        return editor.apply(player, item, content);
    }

    public void addItemEditor(String id, Editor function) {
        editorNames.add(id);
        itemEditors.put(
                id.toLowerCase(Locale.getDefault()),
                function == null
                        ? null
                        : (player, item, content) -> {
                            mainThread();
                            return function.apply(player, item, content);
                        });
    }

    public void reload() {
        itemEditors.clear();
        editorNames.clear();
        Map<String, Editor> basic = new HashMap<>();
        basic.put(
                "setMaterial",
                (p, item, text) -> {
                    if (empty(item)) return false;
                    Material material = host.material(text);
                    if (material == null) return false;
                    if (material.isAir()) item.setType(material);
                    else {
                        var type = CraftMagicNumbers.getItem(material);
                        if (type == null) return false;
                        // CraftItemStack.setType additionally round-trips ItemMeta. Preserve the
                        // existing patch, including components unknown to that Bukkit meta model.
                        CraftItemStack.unwrap(item).setItem(type);
                    }
                    return true;
                });
        for (String operation : List.of("set", "add", "take")) {
            basic.put(
                    operation + "Amount",
                    (p, item, text) -> {
                        Integer value = integer(text);
                        if (empty(item) || value == null) return false;
                        int amount =
                                switch (operation) {
                                    case "add" -> item.getAmount() + value;
                                    case "take" -> item.getAmount() - value;
                                    default -> value;
                                };
                        item.setAmount(
                                Math.min(Math.max(amount, 0), item.getType().getMaxStackSize()));
                        return true;
                    });
            basic.put(operation + "Damage", (p, item, text) -> editDamage(item, text, operation));
        }
        basic.put(
                "setName",
                (p, item, text) ->
                        meta(
                                item,
                                DataComponents.CUSTOM_NAME,
                                value -> value.setDisplayName(color(text))));
        basic.put(
                "addNamePrefix",
                (p, item, text) ->
                        meta(
                                item,
                                DataComponents.CUSTOM_NAME,
                                value ->
                                        value.setDisplayName(
                                                color(text) + value.getDisplayName())));
        basic.put(
                "addNamePostfix",
                (p, item, text) ->
                        meta(
                                item,
                                DataComponents.CUSTOM_NAME,
                                value ->
                                        value.setDisplayName(
                                                value.getDisplayName() + color(text))));
        basic.put(
                "setLore",
                (p, item, text) ->
                        meta(
                                item,
                                DataComponents.LORE,
                                value ->
                                        value.setLore(
                                                Arrays.asList(
                                                        color(text)
                                                                .split(
                                                                        Pattern.quote("\\n"),
                                                                        -1)))));
        basic.put(
                "addLore",
                (p, item, text) ->
                        meta(
                                item,
                                DataComponents.LORE,
                                value -> {
                                    List<String> lore = value.getLore();
                                    if (lore == null) lore = new ArrayList<>();
                                    lore.addAll(
                                            Arrays.asList(
                                                    color(text).split(Pattern.quote("\\n"), -1)));
                                    value.setLore(lore);
                                }));
        for (boolean lore : new boolean[] {false, true})
            for (boolean all : new boolean[] {false, true}) {
                String name = (all ? "replaceAll" : "replace") + (lore ? "Lore" : "Name");
                basic.put(name, (p, item, text) -> replaceLiteral(item, text, lore, all));
                for (String suffix : List.of("", "Papi", "Section"))
                    basic.put(
                            name + "Regex" + suffix,
                            (p, item, text) -> replaceRegex(p, item, text, lore, all, suffix));
            }
        basic.put(
                "setCustomModelData",
                (p, item, text) ->
                        meta(
                                item,
                                DataComponents.CUSTOM_MODEL_DATA,
                                value -> {
                                    Integer number = integer(text);
                                    if (number != null) value.setCustomModelData(number);
                                }));
        basic.put(
                "setUnbreakable",
                (p, item, text) ->
                        meta(
                                item,
                                DataComponents.UNBREAKABLE,
                                value -> {
                                    String bool = text.toLowerCase(Locale.getDefault());
                                    if (bool.equals("true") || bool.equals("false"))
                                        value.setUnbreakable(Boolean.parseBoolean(bool));
                                }));
        for (String operation :
                List.of("set", "add", "addNotCover", "remove", "levelUp", "levelDown"))
            basic.put(
                    operation + "Enchantment",
                    (p, item, text) -> enchantments(item, text, operation));
        for (String operation : List.of("set", "add", "remove"))
            basic.put(
                    operation + "ItemFlag",
                    (p, item, text) ->
                            meta(
                                    item,
                                    DataComponents.TOOLTIP_DISPLAY,
                                    value -> {
                                        if (operation.equals("set"))
                                            value.removeItemFlags(
                                                    value.getItemFlags().toArray(new ItemFlag[0]));
                                        for (String token : text.split(" ", -1))
                                            try {
                                                ItemFlag flag = ItemFlag.valueOf(token);
                                                if (operation.equals("remove"))
                                                    value.removeItemFlags(flag);
                                                else value.addItemFlags(flag);
                                            } catch (IllegalArgumentException ignored) {
                                            }
                                    }));
        basic.put("setNBT", (p, item, text) -> nbt(item, text, false));
        basic.put("setNBTWithList", (p, item, text) -> nbt(item, text, true));
        basic.put(
                "refresh",
                (p, item, text) ->
                        !empty(item)
                                && host.refresh(
                                        p, item, NiTemplate.split(text, ' ', 0), null, null));
        basic.put("rebuild", (p, item, text) -> host.rebuild(p, item, stringMap(text)));
        basic.put(
                "refreshAmount",
                (p, item, text) -> {
                    if (empty(item)) return false;
                    List<String> parts = NiTemplate.split(text, ' ', 0);
                    return host.refresh(
                            p,
                            item,
                            parts.subList(1, parts.size()),
                            null,
                            splitAmount(parts.getFirst()));
                });
        basic.put(
                "rebuildAmount",
                (p, item, text) -> {
                    if (empty(item)) return false;
                    String[] parts = text.split(" ", 2);
                    // This historical name deliberately uses refresh's custom_data-only policy.
                    return host.refresh(
                            p,
                            item,
                            null,
                            stringMap(parts.length == 1 ? "" : parts[1]),
                            splitAmount(parts[0]));
                });
        numbers(
                basic,
                "Charge",
                LegacyItemEditorManager::setCharge,
                LegacyItemEditorManager::addCharge);
        numbers(
                basic,
                "MaxCharge",
                LegacyItemEditorManager::setMaxCharge,
                LegacyItemEditorManager::addMaxCharge);
        numbers(
                basic,
                "CustomDurability",
                LegacyItemEditorManager::setCustomDurability,
                LegacyItemEditorManager::addCustomDurability);
        numbers(
                basic,
                "MaxCustomDurability",
                LegacyItemEditorManager::setMaxCustomDurability,
                LegacyItemEditorManager::addMaxCustomDurability);
        basic.forEach(
                (id, function) -> {
                    addItemEditor(id, function);
                    if (!id.contains("Regex")) {
                        addItemEditor(
                                id + "Papi",
                                (p, item, text) -> function.apply(p, item, host.papi(p, text)));
                        addItemEditor(
                                id + "Section",
                                (p, item, text) ->
                                        function.apply(p, item, host.section(p, item, text)));
                    }
                });
    }

    private static void numbers(
            Map<String, Editor> editors,
            String suffix,
            BiConsumer<ItemStack, Integer> set,
            BiConsumer<ItemStack, Integer> add) {
        editors.put(
                "set" + suffix,
                (p, item, text) -> {
                    Integer value = integer(text);
                    if (value != null) set.accept(item, value);
                    return true;
                });
        editors.put(
                "add" + suffix,
                (p, item, text) -> {
                    Integer value = integer(text);
                    if (value != null) add.accept(item, value);
                    return false;
                });
        editors.put(
                "take" + suffix,
                (p, item, text) -> {
                    Integer value = integer(text);
                    if (value != null) add.accept(item, -value);
                    return false;
                });
    }

    private static void mainThread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item editors must execute on the server thread");
    }

    private static boolean empty(ItemStack item) {
        return item.getType() == Material.AIR;
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static Integer integer(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int splitAmount(String text) {
        Integer value = integer(text);
        return value == null ? 1 : Math.max(value, 1);
    }

    private static HashMap<String, String> stringMap(String text) {
        if (text == null || text.isBlank() || text.trim().equals("null")) return null;
        var json = JsonParser.parseString(text).getAsJsonObject();
        HashMap<String, String> values = new HashMap<>();
        json.entrySet()
                .forEach(
                        entry -> {
                            JsonElement value = entry.getValue();
                            if (!value.isJsonNull()
                                    && (!value.isJsonPrimitive()
                                            || !value.getAsJsonPrimitive().isString()))
                                throw new IllegalArgumentException(
                                        "Editor value is not a string: " + entry.getKey());
                            values.put(
                                    entry.getKey(),
                                    value.isJsonNull() ? null : value.getAsString());
                        });
        return values;
    }

    /** Use Bukkit's exact legacy text/flag conversion on a detached copy, then commit only its named component. */
    private static boolean meta(
            ItemStack item, DataComponentType<?> component, Consumer<ItemMeta> edit) {
        return component(
                item,
                component,
                copy -> {
                    ItemMeta meta = copy.getItemMeta();
                    if (meta == null)
                        throw new IllegalArgumentException("Item has no editable meta");
                    edit.accept(meta);
                    copy.setItemMeta(meta);
                });
    }

    private static <T> boolean component(
            ItemStack item, DataComponentType<T> component, Consumer<ItemStack> edit) {
        if (empty(item)) return false;
        ItemStack copy = CraftItemStack.asCraftCopy(item);
        edit.accept(copy);
        T value = CraftItemStack.unwrap(copy).get(component);
        var target = CraftItemStack.unwrap(item);
        if (value == null) target.remove(component);
        else target.set(component, value);
        return true;
    }

    private static boolean editDamage(ItemStack item, String text, String operation) {
        if (empty(item)) return false;
        short value;
        try {
            value = Short.parseShort(text);
        } catch (NumberFormatException ignored) {
            return false;
        }
        short damage =
                switch (operation) {
                    case "add" -> (short) (item.getDurability() + value);
                    case "take" -> (short) (item.getDurability() - value);
                    default -> value;
                };
        short max = item.getType().getMaxDurability();
        if (max != 0 && damage > max) item.setAmount(0);
        else writeDamage(item, max == 0 ? damage : (short) Math.max(damage, 0));
        return true;
    }

    private static void writeDamage(ItemStack item, short damage) {
        component(item, DataComponents.DAMAGE, copy -> copy.setDurability(damage));
    }

    private record Match(int start, int end, String key) {}

    private static List<Match> matches(String line, List<String> keys) {
        List<Match> matches = new ArrayList<>();
        for (String key : keys) {
            if (key.isEmpty()) continue;
            for (int at = line.indexOf(key); at >= 0; at = line.indexOf(key, at + 1))
                matches.add(new Match(at, at + key.length(), key));
        }
        // StringSearcher's ignoreOverlaps selects longest matches first, then earliest
        // equal-length matches. Replacement text is never fed back into the matcher.
        matches.sort(
                Comparator.<Match>comparingInt(value -> value.end - value.start)
                        .reversed()
                        .thenComparingInt(Match::start));
        BitSet occupied = new BitSet(line.length());
        List<Match> selected = new ArrayList<>();
        for (Match match : matches) {
            int next = occupied.nextSetBit(match.start);
            if (next >= 0 && next < match.end) continue;
            selected.add(match);
            occupied.set(match.start, match.end);
        }
        selected.sort(Comparator.comparingInt(Match::start));
        return selected;
    }

    private static boolean replaceLiteral(
            ItemStack item, String content, boolean lore, boolean all) {
        return meta(
                item,
                lore ? DataComponents.LORE : DataComponents.CUSTOM_NAME,
                meta -> {
                    if (lore ? !meta.hasLore() : !meta.hasDisplayName()) return;
                    HashMap<String, String> values = stringMap(color(content));
                    if (values.isEmpty()) return;
                    List<String> keys = new ArrayList<>(values.keySet()), lines = new ArrayList<>();
                    for (String line : lore ? meta.getLore() : List.of(meta.getDisplayName())) {
                        StringBuilder result = new StringBuilder();
                        int offset = 0;
                        for (Match match : matches(line, keys)) {
                            result.append(line, offset, match.start);
                            String replacement = values.get(match.key);
                            if (replacement != null) {
                                if (lore && replacement.indexOf('\n') >= 0) {
                                    String[] parts = replacement.split("\n", -1);
                                    for (int i = 0; i < parts.length; i++) {
                                        result.append(parts[i]);
                                        if (i < parts.length - 1) {
                                            lines.add(result.toString());
                                            result.setLength(0);
                                        }
                                    }
                                } else result.append(replacement);
                            } else if (!all) result.append(match.key);
                            if (!all) values.remove(match.key);
                            offset = match.end;
                        }
                        result.append(line, offset, line.length());
                        lines.add(result.toString());
                    }
                    if (lore) meta.setLore(lines);
                    else meta.setDisplayName(lines.getFirst());
                });
    }

    private boolean replaceRegex(
            Player player,
            ItemStack item,
            String content,
            boolean lore,
            boolean all,
            String expansion) {
        return meta(
                item,
                lore ? DataComponents.LORE : DataComponents.CUSTOM_NAME,
                meta -> {
                    if (lore ? !meta.hasLore() : !meta.hasDisplayName()) return;
                    HashMap<String, String> values = stringMap(color(content));
                    if (values.isEmpty()) return;
                    if (!lore) {
                        String result = meta.getDisplayName();
                        for (var entry : values.entrySet())
                            result =
                                    replaceMatches(
                                                    result,
                                                    entry.getKey(),
                                                    entry.getValue(),
                                                    all,
                                                    expansion,
                                                    player,
                                                    item)
                                            .text;
                        meta.setDisplayName(result);
                        return;
                    }
                    List<String> lines = new ArrayList<>();
                    for (String line : meta.getLore()) {
                        String result = values.isEmpty() ? line : "";
                        Iterator<Map.Entry<String, String>> iterator = values.entrySet().iterator();
                        while (iterator.hasNext()) {
                            var entry = iterator.next();
                            Replacement replaced =
                                    replaceMatches(
                                            line,
                                            entry.getKey(),
                                            entry.getValue(),
                                            all,
                                            expansion,
                                            player,
                                            item);
                            result = replaced.text;
                            if (!all && replaced.matched) iterator.remove();
                        }
                        // NI repeats the complete multiline result rather than splitting it.
                        for (int i = 0, count = result.split("\n", -1).length; i < count; i++)
                            lines.add(result);
                    }
                    meta.setLore(lines);
                });
    }

    private record Replacement(String text, boolean matched) {}

    private Replacement replaceMatches(
            String text,
            String regex,
            String replacement,
            boolean all,
            String expansion,
            Player player,
            ItemStack item) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        StringBuilder output = new StringBuilder();
        boolean matched = false;
        while (matcher.find()) {
            if (matched && !all) break;
            Matcher groups = GROUP_REFERENCE.matcher(replacement);
            StringBuilder expanded = new StringBuilder();
            while (groups.find()) {
                int index = Integer.parseInt(groups.group(1));
                String value =
                        index <= matcher.groupCount()
                                ? Objects.toString(matcher.group(index), "")
                                : groups.group();
                groups.appendReplacement(expanded, Matcher.quoteReplacement(value));
            }
            groups.appendTail(expanded);
            String value = expanded.toString();
            if (expansion.equals("Papi")) value = host.papi(player, value);
            else if (expansion.equals("Section")) value = host.section(player, item, value);
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
            matched = true;
        }
        matcher.appendTail(output);
        return new Replacement(output.toString(), matched);
    }

    private static boolean enchantments(ItemStack item, String content, String operation) {
        return component(
                item,
                DataComponents.ENCHANTMENTS,
                copy -> {
                    if (operation.equals("set")) copy.removeEnchantments();
                    if (operation.equals("remove")) {
                        for (String name : content.split(" ", -1)) {
                            Enchantment enchantment =
                                    Enchantment.getByName(name.toUpperCase(Locale.getDefault()));
                            if (enchantment != null) copy.removeEnchantment(enchantment);
                        }
                        return;
                    }
                    HashMap<String, Integer> values = new HashMap<>();
                    JsonParser.parseString(content)
                            .getAsJsonObject()
                            .entrySet()
                            .forEach(
                                    entry -> {
                                        if (!entry.getValue().isJsonPrimitive()
                                                || !entry.getValue().getAsJsonPrimitive().isNumber()
                                                || !entry.getValue().toString().matches("-?\\d+"))
                                            throw new IllegalArgumentException(
                                                    "Enchantment level is not an integer: "
                                                            + entry.getKey());
                                        values.put(
                                                entry.getKey(),
                                                entry.getValue().getAsBigDecimal().intValueExact());
                                    });
                    values.forEach(
                            (name, amount) -> {
                                Enchantment enchantment =
                                        Enchantment.getByName(
                                                name.toUpperCase(Locale.getDefault()));
                                if (enchantment == null) return;
                                if (operation.equals("levelUp") || operation.equals("levelDown")) {
                                    if (amount == 0) return;
                                    int level =
                                            copy.getEnchantmentLevel(enchantment)
                                                    + (operation.equals("levelDown")
                                                            ? -amount
                                                            : amount);
                                    if (level <= 0) copy.removeEnchantment(enchantment);
                                    else copy.addUnsafeEnchantment(enchantment, level);
                                } else if (amount > 0
                                        && (!operation.equals("addNotCover")
                                                || !copy.containsEnchantment(enchantment)))
                                    copy.addUnsafeEnchantment(enchantment, amount);
                            });
                });
    }

    private static boolean nbt(ItemStack item, String content, boolean lists) {
        if (empty(item)) return false;
        HashMap<String, String> values = stringMap(content);
        var wrapper = new LegacyNbtItemStack(item);
        LegacyNbt.Compound original = wrapper.getTag();
        LegacyNbt.Compound candidate =
                original == null ? new LegacyNbt.Compound() : original.clone();
        values.forEach(
                (key, text) -> {
                    LegacyNbt value =
                            LegacyNbt.of(
                                    NiTagValues.decode(Objects.requireNonNull(text, "NBT value")));
                    if (lists) LegacyItemEditorManagerNbt.put(candidate, key, value);
                    else candidate.putDeep(key, value);
                });
        wrapper.setTag(candidate);
        return true;
    }

    private static LegacyNbt.Compound properties(ItemStack item) {
        mainThread();
        if (item == null || item.isEmpty()) return null;
        LegacyNbt.Compound root = new LegacyNbtItemStack(item).getTag();
        return root == null ? null : root.getCompound("NeigeItems");
    }

    public static void setCharge(ItemStack item, int amount) {
        var data = properties(item);
        if (data == null) return;
        Integer max = data.getIntOrNull("maxCharge");
        if (max != null) data.putInt("charge", Math.max(Math.min(amount, max), 0));
    }

    public static void addCharge(ItemStack item, int amount) {
        var data = properties(item);
        if (data == null) return;
        Integer current = data.getIntOrNull("charge");
        if (current == null) return;
        int next = Math.max(Math.min(current + amount, data.getInt("maxCharge")), 0);
        if (next == 0) item.setAmount(0);
        else data.putInt("charge", next);
    }

    public static void setMaxCharge(ItemStack item, int amount) {
        max(item, amount, false, false);
    }

    public static void addMaxCharge(ItemStack item, int amount) {
        max(item, amount, true, false);
    }

    public static void setCustomDurability(ItemStack item, int amount) {
        durability(item, amount, false);
    }

    public static void addCustomDurability(ItemStack item, int amount) {
        durability(item, amount, true);
    }

    public static void setMaxCustomDurability(ItemStack item, int amount) {
        max(item, amount, false, true);
    }

    public static void addMaxCustomDurability(ItemStack item, int amount) {
        max(item, amount, true, true);
    }

    private static void max(ItemStack item, int amount, boolean add, boolean durability) {
        var data = properties(item);
        if (data == null) return;
        String valueKey = durability ? "durability" : "charge",
                maxKey = durability ? "maxDurability" : "maxCharge";
        Integer required = data.getIntOrNull(add ? maxKey : valueKey);
        if (required == null) return;
        int maximum = Math.max(add ? required + amount : amount, 1);
        int current = Math.min(data.getInt(valueKey), maximum);
        LegacyNbt.Compound edits = new LegacyNbt.Compound();
        edits.putInt(valueKey, current);
        edits.putInt(maxKey, maximum);
        data.putAll(edits);
        if (durability) refreshDurability(item, current, maximum);
    }

    private static void durability(ItemStack item, int amount, boolean add) {
        var data = properties(item);
        if (data == null) return;
        Integer required = data.getIntOrNull(add ? "durability" : "maxDurability");
        if (required == null) return;
        int max = data.getInt("maxDurability");
        int current = Math.max(Math.min(add ? required + amount : amount, max), 0);
        if (current == 0 && data.getBoolean("itemBreak", true)) item.setAmount(0);
        else {
            data.putInt("durability", current);
            refreshDurability(item, current, max);
        }
    }

    public static short checkDurability(ItemStack item, int current, int max) {
        short damage =
                (short) (int) (item.getType().getMaxDurability() * (1 - (double) current / max));
        if (damage <= 0 && current < max) damage = 1;
        if (damage >= item.getType().getMaxDurability() && current > 0) damage--;
        return damage;
    }

    public static void refreshDurability(ItemStack item, int current, int max) {
        mainThread();
        writeDamage(item, checkDurability(item, current, max));
    }
}
