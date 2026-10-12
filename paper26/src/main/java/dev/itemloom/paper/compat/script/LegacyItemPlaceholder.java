package dev.itemloom.paper.compat.script;

import dev.itemloom.paper.compat.*;
import dev.itemloom.paper.compat.nbt.*;
import java.util.*;
import java.util.function.BiFunction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.inventory.ItemStack;

public final class LegacyItemPlaceholder {
    public final LegacyItemPlaceholder INSTANCE = this;
    private final NiItemOperations owner;
    private final HashMap<String, BiFunction<ItemStack, String, String>> expansions =
            new HashMap<>();

    public LegacyItemPlaceholder(NiItemOperations owner) {
        this.owner = owner;
        expansions.put("neigeitems", this::builtin);
    }

    public HashMap<String, BiFunction<ItemStack, String, String>> getExpansions() {
        owner.ensureActive();
        return expansions;
    }

    public void addExpansion(String id, BiFunction<ItemStack, String, String> function) {
        owner.ensureActive();
        expansions.put(id.toLowerCase(Locale.getDefault()), function);
    }

    public static final class ParseResult {
        private final String text;
        private final boolean changed;

        public ParseResult(String text, boolean changed) {
            this.text = text;
            this.changed = changed;
        }

        public String getText() {
            return text;
        }

        public boolean getChanged() {
            return changed;
        }
    }

    public ParseResult parse(ItemStack item, String text) {
        owner.ensureActive();
        LegacyPercentText.Result result =
                LegacyPercentText.parse(
                        text,
                        (id, parameters) -> {
                            BiFunction<ItemStack, String, String> expansion = expansions.get(id);
                            return expansion == null ? null : expansion.apply(item, parameters);
                        });
        return new ParseResult(result.text(), result.changed());
    }

    public void itemParse(ItemStack item) {
        owner.ensureActive();
        if (!(item instanceof CraftItemStack craft) || craft.handle == null) return;
        var handle = craft.handle;
        Component name = handle.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            Component changed = component(item, name);
            if (changed != name) handle.set(DataComponents.CUSTOM_NAME, changed);
        }
        ItemLore lore = handle.get(DataComponents.LORE);
        if (lore != null) {
            List<Component> lines = components(item, lore.lines()),
                    styled = components(item, lore.styledLines());
            if (lines != lore.lines() || styled != lore.styledLines())
                handle.set(DataComponents.LORE, new ItemLore(lines, styled));
        }
    }

    private List<Component> components(ItemStack item, List<Component> source) {
        List<Component> result = null;
        for (int i = 0; i < source.size(); i++) {
            Component value = source.get(i), changed = component(item, value);
            if (result == null && changed != value) result = new ArrayList<>(source);
            if (result != null) result.set(i, changed);
        }
        return result == null ? source : List.copyOf(result);
    }

    private Component component(ItemStack item, Component component) {
        if (definitelyStatic(component, 0)) return component;
        ParseResult parsed = parse(item, CraftChatMessage.toJSON(component));
        return parsed.getChanged() ? CraftChatMessage.fromJSON(parsed.getText()) : component;
    }

    public static boolean definitelyStatic(Component component, int depth) {
        if (component == null
                || depth >= 64
                || !(component.getContents() instanceof PlainTextContents text)
                || text.text().indexOf('%') >= 0) return false;
        Style style = component.getStyle();
        if (style.getClickEvent() != null
                || style.getHoverEvent() != null
                || style.getInsertion() != null && style.getInsertion().indexOf('%') >= 0
                || !Objects.equals(style.getFont(), Style.EMPTY.getFont())) return false;
        for (Component sibling : component.getSiblings())
            if (!definitelyStatic(sibling, depth + 1)) return false;
        return true;
    }

    private String builtin(ItemStack item, String parameters) {
        String[] parts = parameters.split("_", 2);
        String kind = parts[0].toLowerCase(Locale.getDefault());
        String rest = parts.length == 2 ? parts[1] : "";
        String state =
                switch (kind) {
                    case "charge" -> "charge";
                    case "maxcharge" -> "maxCharge";
                    case "durability" -> "durability";
                    case "maxdurability" -> "maxDurability";
                    default -> null;
                };
        if (state != null) {
            Integer value = NiItemNodes.legacyInteger(item, state);
            return value == null ? null : value.toString();
        }
        if (kind.equals("itembreak")) {
            Boolean value = NiItemNodes.legacyBoolean(item, "itemBreak", true);
            if (value == null || parts.length < 2) return null;
            String[] options = rest.split("_", 2);
            int selected = value ? 1 : 0;
            return selected < options.length ? options[selected] : null;
        }
        if (!kind.equals("nbt") && !kind.equals("nbtnumber")) return null;
        LegacyNbt.Compound tag = new LegacyNbtItemStack(item).getTag();
        if (tag == null) return null;
        if (kind.equals("nbt")) {
            LegacyNbt value = tag.getDeep(rest, '`', '\\');
            return value == null ? null : value.getAsString();
        }
        String[] number = rest.split("_", 2);
        LegacyNbt value = tag.getDeep(number.length == 2 ? number[1] : "", '`', '\\');
        if (!(value instanceof LegacyNbt.Numeric numeric)) return null;
        return String.format(Locale.getDefault(), "%." + number[0] + "f", numeric.getAsDouble());
    }
}
