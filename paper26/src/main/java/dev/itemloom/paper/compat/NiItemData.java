package dev.itemloom.paper.compat;

import com.google.gson.JsonObject;
import dev.itemloom.compat.ni.*;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.nms.NmsItems;
import java.time.Clock;
import java.util.*;
import net.minecraft.nbt.CompoundTag;

public final class NiItemData {
    private static final Set<String> COLORS =
            Set.of(
                    "BLACK",
                    "DARK_BLUE",
                    "DARK_GREEN",
                    "DARK_AQUA",
                    "DARK_RED",
                    "DARK_PURPLE",
                    "GOLD",
                    "GRAY",
                    "DARK_GRAY",
                    "BLUE",
                    "GREEN",
                    "AQUA",
                    "RED",
                    "LIGHT_PURPLE",
                    "YELLOW",
                    "WHITE");
    private final Clock clock;
    private final NiItemMigration migration = new NiItemMigration();

    public NiItemData(Clock clock) {
        this.clock = clock;
    }

    public CompoundTag applyGenerated(
            CompoundTag source, NiConfig config, ItemIdentity identity, int hash) {
        return migration.convert(apply(source, config, identity, hash));
    }

    public CompoundTag apply(CompoundTag source, NiConfig config, ItemIdentity identity, int hash) {
        CompoundTag result = source.copy();
        NiConfig options = config.section("options");
        boolean removed =
                options != null
                        && (options.bool("removeNBT", false) || options.bool("remove-nbt", false));
        if (!removed) {
            CompoundTag metadata = result.getCompoundOrEmpty("NeigeItems").copy();
            if (identity != null) {
                JsonObject rolls = new JsonObject();
                identity.rolls().forEach(rolls::addProperty);
                metadata.putString("id", identity.id());
                metadata.putString("data", rolls.toString());
                metadata.putInt("hashCode", hash);
            }
            if (options != null)
                for (String key : options.keys()) applyOption(metadata, options, key);
            result.put("NeigeItems", metadata);
        }
        NiConfig nbt = config.section("nbt");
        if (nbt != null) result.merge((CompoundTag) NmsItems.tag(NiTagValues.decode(nbt.values())));
        return result;
    }

    private void applyOption(CompoundTag metadata, NiConfig options, String key) {
        switch (key.toLowerCase(Locale.ROOT)) {
            case "charge", "durability" -> {
                String current = key.toLowerCase(Locale.ROOT);
                metadata.putInt(current, options.integer(key, 0));
                metadata.putInt(
                        current.equals("charge") ? "maxCharge" : "maxDurability",
                        options.integer(key, 0));
            }
            case "maxcharge", "max-charge", "maxdurability", "max-durability" -> {
                boolean charge = key.toLowerCase(Locale.ROOT).contains("charge");
                int value = options.integer(key, 0);
                metadata.putInt(charge ? "maxCharge" : "maxDurability", value);
                if (!metadata.contains(charge ? "charge" : "durability"))
                    metadata.putInt(charge ? "charge" : "durability", value);
            }
            case "itembreak", "item-break" ->
                    metadata.putBoolean("itemBreak", options.bool(key, true));
            case "hide" -> metadata.putBoolean("hide", options.bool(key, true));
            case "owner" -> metadata.putString("owner", options.string(key));
            case "dropskill", "drop-skill" -> metadata.putString("dropSkill", options.string(key));
            case "color" -> {
                String color = options.string(key, "").toUpperCase(Locale.ROOT);
                if (COLORS.contains(color)) metadata.putString("color", color);
            }
            case "itemtime", "item-time" ->
                    metadata.putLong("itemTime", clock.millis() + options.longValue(key, 0) * 1000);
            default -> {}
        }
    }
}
