package dev.itemloom.paper.compat.script;

import com.google.gson.*;
import dev.itemloom.compat.ni.*;
import dev.itemloom.paper.compat.LegacyStateRules;
import dev.itemloom.paper.compat.nbt.*;
import dev.itemloom.paper.nms.NmsItems;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import net.minecraft.core.component.*;
import org.bukkit.*;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

/** Script editor vocabulary, with detached preparation for individual component changes. */
public final class LegacyItemEditorManager {
    @FunctionalInterface
    public interface Editor {
        Boolean apply(Player player, ItemStack item, String content);
    }

    public interface Host {
        Material material(String content);

        String papi(Player player, String content);

        String section(Player player, ItemStack item, String content);

        boolean refresh(
                Player player,
                ItemStack item,
                List<String> remove,
                Map<String, String> overrides,
                Integer amount);

        boolean rebuild(Player player, ItemStack item, Map<String, String> overrides);
    }

    public final LegacyItemEditorManager INSTANCE = this;
    private final Host host;
    private final HashMap<String, Editor> editors = new HashMap<>();
    private final ArrayList<String> names = new ArrayList<>();

    public LegacyItemEditorManager(Host host) {
        this.host = Objects.requireNonNull(host);
        reload();
    }

    public HashMap<String, Editor> getItemEditors() {
        return editors;
    }

    public ArrayList<String> getEditorNames() {
        return names;
    }

    public void runEditor(String id, String content, ItemStack item, Player player) {
        runEditorWithResult(id, content, item, player);
    }

    public Boolean runEditorWithResult(String id, String content, ItemStack item, Player player) {
        Editor editor = editors.get(id.toLowerCase(Locale.getDefault()));
        if (editor == null) return null;
        thread();
        return editor.apply(player, item, content);
    }

    public void addItemEditor(String id, Editor function) {
        String key = id.toLowerCase(Locale.getDefault());
        names.add(id);
        editors.put(
                key,
                function == null
                        ? null
                        : (player, item, text) -> {
                            thread();
                            return function.apply(player, item, text);
                        });
    }

    public void reload() {
        editors.clear();
        names.clear();
        Map<String, Editor> base = new HashMap<>();
        base.put("setMaterial", (player, item, text) -> material(item, text));
        for (String operation : List.of("set", "add", "take")) {
            base.put(operation + "Amount", (player, item, text) -> amount(item, text, operation));
            base.put(operation + "Damage", (player, item, text) -> damage(item, text, operation));
        }
        base.put(
                "setName",
                (player, item, text) ->
                        meta(
                                item,
                                DataComponents.CUSTOM_NAME,
                                value -> value.setDisplayName(color(text))));
        base.put(
                "addNamePrefix",
                (player, item, text) ->
                        meta(
                                item,
                                DataComponents.CUSTOM_NAME,
                                value ->
                                        value.setDisplayName(
                                                color(text) + value.getDisplayName())));
        base.put(
                "addNamePostfix",
                (player, item, text) ->
                        meta(
                                item,
                                DataComponents.CUSTOM_NAME,
                                value ->
                                        value.setDisplayName(
                                                value.getDisplayName() + color(text))));
        base.put(
                "setLore",
                (player, item, text) ->
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
        base.put(
                "addLore",
                (player, item, text) ->
                        meta(
                                item,
                                DataComponents.LORE,
                                value -> {
                                    List<String> lore =
                                            value.hasLore()
                                                    ? new ArrayList<>(value.getLore())
                                                    : new ArrayList<>();
                                    lore.addAll(
                                            Arrays.asList(
                                                    color(text).split(Pattern.quote("\\n"), -1)));
                                    value.setLore(lore);
                                }));
        for (boolean lore : List.of(false, true))
            for (boolean all : List.of(false, true)) {
                String id = (all ? "replaceAll" : "replace") + (lore ? "Lore" : "Name");
                base.put(
                        id,
                        (player, item, text) ->
                                replace(item, text, lore, all, false, UnaryOperator.identity()));
                addItemEditor(
                        id + "Regex",
                        (player, item, text) ->
                                replace(item, text, lore, all, true, UnaryOperator.identity()));
                addItemEditor(
                        id + "RegexPapi",
                        (player, item, text) ->
                                replace(
                                        item,
                                        text,
                                        lore,
                                        all,
                                        true,
                                        replacement -> host.papi(player, replacement)));
                addItemEditor(
                        id + "RegexSection",
                        (player, item, text) ->
                                replace(
                                        item,
                                        text,
                                        lore,
                                        all,
                                        true,
                                        replacement -> host.section(player, item, replacement)));
            }
        base.put(
                "setCustomModelData",
                (player, item, text) ->
                        meta(
                                item,
                                DataComponents.CUSTOM_MODEL_DATA,
                                value -> {
                                    Integer number = integer(text);
                                    if (number != null) value.setCustomModelData(number);
                                }));
        base.put(
                "setUnbreakable",
                (player, item, text) ->
                        meta(
                                item,
                                DataComponents.UNBREAKABLE,
                                value -> {
                                    String flag = text.toLowerCase(Locale.getDefault());
                                    if (flag.equals("true") || flag.equals("false"))
                                        value.setUnbreakable(flag.equals("true"));
                                }));
        for (String operation :
                List.of("set", "add", "addNotCover", "remove", "levelUp", "levelDown"))
            base.put(
                    operation + "Enchantment",
                    (player, item, text) -> enchant(item, text, operation));
        for (String operation : List.of("set", "add", "remove"))
            base.put(operation + "ItemFlag", (player, item, text) -> flags(item, text, operation));
        base.put("setNBT", (player, item, text) -> nbt(item, text, false));
        base.put("setNBTWithList", (player, item, text) -> nbt(item, text, true));
        base.put(
                "refresh",
                (player, item, text) ->
                        !air(item)
                                && host.refresh(
                                        player, item, NiTemplate.split(text, ' ', 0), null, null));
        base.put("rebuild", (player, item, text) -> host.rebuild(player, item, strings(text)));
        base.put(
                "refreshAmount",
                (player, item, text) -> {
                    if (air(item)) return false;
                    List<String> parts = NiTemplate.split(text, ' ', 0);
                    return host.refresh(
                            player,
                            item,
                            new ArrayList<>(parts.subList(1, parts.size())),
                            null,
                            requestedAmount(parts.getFirst()));
                });
        base.put(
                "rebuildAmount",
                (player, item, text) -> {
                    if (air(item)) return false;
                    int split = text.indexOf(' ');
                    String quantity = split < 0 ? text : text.substring(0, split);
                    String overrides = split < 0 ? null : text.substring(split + 1);
                    return host.refresh(
                            player, item, null, strings(overrides), requestedAmount(quantity));
                });
        stateEditors(
                base,
                "Charge",
                LegacyItemEditorManager::setCharge,
                LegacyItemEditorManager::addCharge);
        stateEditors(
                base,
                "MaxCharge",
                LegacyItemEditorManager::setMaxCharge,
                LegacyItemEditorManager::addMaxCharge);
        stateEditors(
                base,
                "CustomDurability",
                LegacyItemEditorManager::setCustomDurability,
                LegacyItemEditorManager::addCustomDurability);
        stateEditors(
                base,
                "MaxCustomDurability",
                LegacyItemEditorManager::setMaxCustomDurability,
                LegacyItemEditorManager::addMaxCustomDurability);
        base.forEach(
                (id, editor) -> {
                    addItemEditor(id, editor);
                    addItemEditor(
                            id + "Papi",
                            (player, item, text) ->
                                    editor.apply(player, item, host.papi(player, text)));
                    addItemEditor(
                            id + "Section",
                            (player, item, text) ->
                                    editor.apply(player, item, host.section(player, item, text)));
                });
    }

    private static void stateEditors(
            Map<String, Editor> into,
            String field,
            ObjIntConsumer<ItemStack> set,
            ObjIntConsumer<ItemStack> add) {
        into.put(
                "set" + field,
                (player, item, text) -> {
                    Integer amount = integer(text);
                    if (amount != null) set.accept(item, amount);
                    return true;
                });
        into.put(
                "add" + field,
                (player, item, text) -> {
                    Integer amount = integer(text);
                    if (amount != null) add.accept(item, amount);
                    return false;
                });
        into.put(
                "take" + field,
                (player, item, text) -> {
                    Integer amount = integer(text);
                    if (amount != null) add.accept(item, -amount);
                    return false;
                });
    }

    private static void thread() {
        if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item editors must execute on the server thread");
    }

    private static boolean air(ItemStack item) {
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

    private static int requestedAmount(String text) {
        Integer amount = integer(text);
        return amount == null ? 1 : Math.max(1, amount);
    }

    private boolean material(ItemStack item, String text) {
        if (air(item)) return false;
        Material type = host.material(text);
        if (type == null) return false;
        if (type.isAir()) {
            item.setType(type);
            return true;
        }
        var nativeType = CraftMagicNumbers.getItem(type);
        if (nativeType == null) return false;
        var candidate = CraftItemStack.asNMSCopy(item);
        candidate.setItem(nativeType);
        NmsItems.replace(item, CraftItemStack.asBukkitCopy(candidate));
        return true;
    }

    private static boolean amount(ItemStack item, String text, String operation) {
        Integer value = integer(text);
        if (air(item) || value == null) return false;
        int amount =
                switch (operation) {
                    case "add" -> item.getAmount() + value;
                    case "take" -> item.getAmount() - value;
                    default -> value;
                };
        item.setAmount(Math.clamp(amount, 0, item.getType().getMaxStackSize()));
        return true;
    }

    private static boolean damage(ItemStack item, String text, String operation) {
        short value;
        try {
            value = Short.parseShort(text);
        } catch (NumberFormatException ignored) {
            return false;
        }
        if (air(item)) return false;
        short current = item.getDurability();
        short damage =
                switch (operation) {
                    case "add" -> (short) (current + value);
                    case "take" -> (short) (current - value);
                    default -> value;
                };
        short maximum = item.getType().getMaxDurability();
        if (maximum != 0 && damage > maximum) item.setAmount(0);
        else
            setComponent(
                    item, DataComponents.DAMAGE, maximum == 0 ? (int) damage : Math.max(0, damage));
        return true;
    }

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

    private static boolean component(
            ItemStack item, DataComponentType<?> component, Consumer<CraftItemStack> edit) {
        if (air(item)) return false;
        CraftItemStack copy = CraftItemStack.asCraftCopy(item);
        edit.accept(copy);
        copyComponent(item, component, copy.handle);
        return true;
    }

    private static <T> void copyComponent(
            ItemStack item,
            DataComponentType<T> component,
            net.minecraft.world.item.ItemStack source) {
        setComponent(item, component, source.get(component));
    }

    private static <T> void setComponent(ItemStack item, DataComponentType<T> component, T value) {
        var candidate = CraftItemStack.asNMSCopy(item);
        if (value == null) candidate.remove(component);
        else candidate.set(component, value);
        NmsItems.replace(item, CraftItemStack.asBukkitCopy(candidate));
    }

    private static boolean replace(
            ItemStack item,
            String text,
            boolean lore,
            boolean all,
            boolean regex,
            UnaryOperator<String> transform) {
        return meta(
                item,
                lore ? DataComponents.LORE : DataComponents.CUSTOM_NAME,
                meta -> {
                    if (lore ? !meta.hasLore() : !meta.hasDisplayName()) return;
                    Map<String, String> replacements = strings(color(text));
                    if (!regex) {
                        if (lore)
                            meta.setLore(LegacyLiteralText.lore(meta.getLore(), replacements, all));
                        else
                            meta.setDisplayName(
                                    LegacyLiteralText.name(
                                            meta.getDisplayName(), replacements, all));
                    } else {
                        List<LegacyRegexText.Rule> rules = new ArrayList<>();
                        replacements.forEach(
                                (pattern, replacement) ->
                                        rules.add(new LegacyRegexText.Rule(pattern, replacement)));
                        if (lore)
                            meta.setLore(
                                    LegacyRegexText.lore(meta.getLore(), rules, all, transform));
                        else
                            meta.setDisplayName(
                                    LegacyRegexText.name(
                                            meta.getDisplayName(), rules, all, transform));
                    }
                });
    }

    private static HashMap<String, String> strings(String text) {
        if (text == null || text.isBlank() || text.trim().equals("null")) return null;
        HashMap<String, String> result = new HashMap<>();
        JsonParser.parseString(text)
                .getAsJsonObject()
                .entrySet()
                .forEach(
                        entry -> {
                            JsonElement value = entry.getValue();
                            if (!value.isJsonNull()
                                    && !(value.isJsonPrimitive()
                                            && value.getAsJsonPrimitive().isString()))
                                throw new IllegalArgumentException(
                                        "Editor value is not a string: " + entry.getKey());
                            result.put(
                                    entry.getKey(),
                                    value.isJsonNull() ? null : value.getAsString());
                        });
        return result;
    }

    private static boolean enchant(ItemStack item, String text, String operation) {
        return component(
                item,
                DataComponents.ENCHANTMENTS,
                copy -> {
                    if (operation.equals("set")) copy.removeEnchantments();
                    if (operation.equals("remove")) {
                        for (String token : text.split(" ", -1)) {
                            Enchantment enchantment =
                                    Enchantment.getByName(token.toUpperCase(Locale.getDefault()));
                            if (enchantment != null) copy.removeEnchantment(enchantment);
                        }
                        return;
                    }
                    Map<String, Integer> levels = new HashMap<>();
                    JsonParser.parseString(text)
                            .getAsJsonObject()
                            .entrySet()
                            .forEach(
                                    entry -> {
                                        JsonElement value = entry.getValue();
                                        if (!value.isJsonPrimitive()
                                                || !value.getAsJsonPrimitive().isNumber()
                                                || !value.getAsString().matches("-?\\d+"))
                                            throw new IllegalArgumentException(
                                                    "Enchantment level is not an integer: "
                                                            + entry.getKey());
                                        try {
                                            levels.put(
                                                    entry.getKey(),
                                                    Integer.valueOf(value.getAsString()));
                                        } catch (NumberFormatException error) {
                                            throw new IllegalArgumentException(
                                                    "Enchantment level is not an integer: "
                                                            + entry.getKey(),
                                                    error);
                                        }
                                    });
                    levels.forEach(
                            (name, requested) -> {
                                Enchantment enchantment =
                                        Enchantment.getByName(
                                                name.toUpperCase(Locale.getDefault()));
                                if (enchantment == null) return;
                                if (operation.equals("addNotCover")
                                        && copy.containsEnchantment(enchantment)) return;
                                if (operation.equals("levelUp") || operation.equals("levelDown")) {
                                    if (requested == 0) return;
                                    int level =
                                            operation.equals("levelUp")
                                                    ? copy.getEnchantmentLevel(enchantment)
                                                            + requested
                                                    : copy.getEnchantmentLevel(enchantment)
                                                            - requested;
                                    if (level <= 0) copy.removeEnchantment(enchantment);
                                    else copy.addUnsafeEnchantment(enchantment, level);
                                } else if (requested > 0)
                                    copy.addUnsafeEnchantment(enchantment, requested);
                            });
                });
    }

    private static boolean flags(ItemStack item, String text, String operation) {
        return meta(
                item,
                DataComponents.TOOLTIP_DISPLAY,
                meta -> {
                    if (operation.equals("set")) meta.removeItemFlags(ItemFlag.values());
                    for (String token : text.split(" ", -1)) {
                        try {
                            ItemFlag flag = ItemFlag.valueOf(token);
                            if (operation.equals("remove")) meta.removeItemFlags(flag);
                            else meta.addItemFlags(flag);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                });
    }

    private static boolean nbt(ItemStack item, String text, boolean lists) {
        if (air(item)) return false;
        LegacyNbtItemStack wrapper = new LegacyNbtItemStack(item);
        LegacyNbt.Compound previous = wrapper.getTag();
        LegacyNbt.Compound candidate =
                previous == null ? new LegacyNbt.Compound() : previous.clone();
        strings(text)
                .forEach(
                        (key, value) -> {
                            LegacyNbt tag =
                                    LegacyNbt.of(
                                            NiTagValues.decode(
                                                    Objects.requireNonNull(value, "NBT value")));
                            if (lists) LegacyItemEditorManagerNbt.put(candidate, key, tag);
                            else candidate.putDeep(key, tag);
                        });
        wrapper.setTag(candidate);
        return true;
    }

    private static LegacyNbt.Compound properties(ItemStack item) {
        thread();
        if (item == null || item.isEmpty()) return null;
        LegacyNbt.Compound tag = new LegacyNbtItemStack(item).getTag();
        return tag == null ? null : tag.getCompoundOrNull("NeigeItems");
    }

    public static void setCharge(ItemStack item, int amount) {
        LegacyNbt.Compound data = properties(item);
        if (data == null) return;
        Integer maximum = data.getIntOrNull("maxCharge");
        if (maximum != null) data.putInt("charge", Math.max(0, Math.min(amount, maximum)));
    }

    public static void addCharge(ItemStack item, int amount) {
        LegacyNbt.Compound data = properties(item);
        if (data == null) return;
        Integer old = data.getIntOrNull("charge");
        if (old == null) return;
        int current = Math.max(0, Math.min(old + amount, data.getInt("maxCharge")));
        if (current == 0) item.setAmount(0);
        else data.putInt("charge", current);
    }

    public static void setMaxCharge(ItemStack item, int amount) {
        maximum(item, amount, false, false);
    }

    public static void addMaxCharge(ItemStack item, int amount) {
        maximum(item, amount, true, false);
    }

    public static void setMaxCustomDurability(ItemStack item, int amount) {
        maximum(item, amount, false, true);
    }

    public static void addMaxCustomDurability(ItemStack item, int amount) {
        maximum(item, amount, true, true);
    }

    private static void maximum(ItemStack item, int amount, boolean add, boolean durability) {
        LegacyNbt.Compound data = properties(item);
        if (data == null) return;
        String currentKey = durability ? "durability" : "charge",
                maximumKey = durability ? "maxDurability" : "maxCharge";
        Integer required = data.getIntOrNull(add ? maximumKey : currentKey);
        if (required == null) return;
        int maximum = Math.max(1, add ? required + amount : amount);
        int current = Math.min(data.getInt(currentKey), maximum);
        data.putInt(maximumKey, maximum);
        data.putInt(currentKey, current);
        if (durability) refreshDurability(item, current, maximum);
    }

    public static void setCustomDurability(ItemStack item, int amount) {
        durability(item, amount, false);
    }

    public static void addCustomDurability(ItemStack item, int amount) {
        durability(item, amount, true);
    }

    private static void durability(ItemStack item, int amount, boolean add) {
        LegacyNbt.Compound data = properties(item);
        if (data == null) return;
        Integer required = data.getIntOrNull(add ? "durability" : "maxDurability");
        if (required == null) return;
        int maximum = data.getInt("maxDurability");
        int current = Math.max(0, Math.min(add ? required + amount : amount, maximum));
        if (current == 0 && data.getBoolean("itemBreak", true)) item.setAmount(0);
        else {
            data.putInt("durability", current);
            refreshDurability(item, current, maximum);
        }
    }

    public static short checkDurability(ItemStack item, int current, int maximum) {
        return LegacyStateRules.damage(item.getType().getMaxDurability(), current, maximum);
    }

    public static void refreshDurability(ItemStack item, int current, int maximum) {
        thread();
        setComponent(item, DataComponents.DAMAGE, (int) checkDurability(item, current, maximum));
    }
}
