package dev.itemloom.paper.compat.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.item.component.ItemLore;
import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import dev.itemloom.paper.compat.nbt.LegacyNbtItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.inventory.ItemStack;

/** Percent placeholder and JSON replacement contract from NI, owned by one catalog revision. */
public final class LegacyItemPlaceholder {
    private static final Pattern COLORS = Pattern.compile("§+[a-z0-9]");
    public final LegacyItemPlaceholder INSTANCE = this;
    private final NiItemOperations owner;
    private final HashMap<String, BiFunction<ItemStack, String, String>> expansions =
            new HashMap<>();

    public LegacyItemPlaceholder(NiItemOperations owner) {
        this.owner = owner;
        expansions.put("neigeitems", this::builtIn);
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
        int cursor = text.indexOf('%'), copied = 0;
        StringBuilder output = null;
        while (cursor >= 0 && cursor + 1 < text.length()) {
            int separator = -1, end = cursor + 1;
            for (; end < text.length(); end++) {
                char value = text.charAt(end);
                if (value == '%' || value == ' ' && separator < 0) break;
                if (value == '_' && separator < 0) separator = end;
            }
            if (end < text.length() && text.charAt(end) == '%') {
                String id =
                        text.substring(cursor + 1, separator < 0 ? end : separator)
                                .toLowerCase(Locale.getDefault());
                if (id.indexOf('§') >= 0) id = COLORS.matcher(id).replaceAll("");
                var expansion = expansions.get(id);
                if (expansion != null) {
                    String replacement =
                            expansion.apply(
                                    item, separator < 0 ? "" : text.substring(separator + 1, end));
                    if (replacement != null) {
                        if (output == null) output = new StringBuilder(text.length());
                        output.append(text, copied, cursor).append(replacement);
                        copied = end + 1;
                    }
                }
            }
            cursor = text.indexOf('%', end + 1);
        }
        return output == null
                ? new ParseResult(text, false)
                : new ParseResult(output.append(text, copied, text.length()).toString(), true);
    }

    public void itemParse(ItemStack item) {
        owner.ensureActive();
        if (!(item instanceof CraftItemStack)) return;
        var handle = CraftItemStack.unwrap(item);
        Component name = handle.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            Component edited = component(item, name);
            if (edited != name) handle.set(DataComponents.CUSTOM_NAME, edited);
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
            Component before = source.get(i), after = component(item, before);
            if (before != after) {
                if (result == null) result = new ArrayList<>(source);
                result.set(i, after);
            }
        }
        return result == null ? source : List.copyOf(result);
    }

    private Component component(ItemStack item, Component source) {
        if (source == null || definitelyStatic(source, 0)) return source;
        // NI parses the entire component JSON, including translation/style strings. Keep
        // that contract rather than flattening styled/interactive components to plain text.
        ParseResult result = parse(item, CraftChatMessage.toJSON(source));
        return result.getChanged() ? CraftChatMessage.fromJSON(result.getText()) : source;
    }

    public static boolean definitelyStatic(Component component, int depth) {
        if (component == null
                || depth >= 64
                || !(component.getContents() instanceof PlainTextContents literal)
                || literal.text().indexOf('%') >= 0) return false;
        Style style = component.getStyle();
        if (style.getClickEvent() != null
                || style.getHoverEvent() != null
                || style.getInsertion() != null && style.getInsertion().indexOf('%') >= 0
                || !java.util.Objects.equals(style.getFont(), Style.EMPTY.getFont())) return false;
        for (Component child : component.getSiblings())
            if (!definitelyStatic(child, depth + 1)) return false;
        return true;
    }

    private String builtIn(ItemStack stack, String parameters) {
        String[] args = parameters.split("_", 2);
        String kind = args[0].toLowerCase(Locale.getDefault());
        String field =
                switch (kind) {
                    case "charge" -> "charge";
                    case "maxcharge" -> "maxCharge";
                    case "durability" -> "durability";
                    case "maxdurability" -> "maxDurability";
                    default -> null;
                };
        if (field != null) {
            Integer value = NiItemNodes.legacyInteger(stack, field);
            return value == null ? null : value.toString();
        }
        if (kind.equals("itembreak")) {
            Boolean value = NiItemNodes.legacyBoolean(stack, "itemBreak", true);
            if (value == null) return null;
            String[] values = args.length > 1 ? args[1].split("_", 2) : new String[0];
            int index = value ? 1 : 0;
            return values.length > index ? values[index] : null;
        }
        LegacyNbt.Compound tag = new LegacyNbtItemStack(stack).getTag();
        if (tag == null) return null;
        return switch (kind) {
            case "nbt" -> {
                LegacyNbt value = tag.getDeep(args.length > 1 ? args[1] : "", '`', '\\');
                yield value == null ? null : value.getAsString();
            }
            case "nbtnumber" -> {
                String[] values = args.length > 1 ? args[1].split("_", 2) : new String[0];
                LegacyNbt value = tag.getDeep(values.length > 1 ? values[1] : "", '`', '\\');
                yield value instanceof LegacyNbt.Numeric number
                        ? String.format(
                                Locale.getDefault(),
                                "%." + (values.length > 0 ? values[0] : null) + "f",
                                number.getAsDouble())
                        : null;
            }
            default -> null;
        };
    }
}
