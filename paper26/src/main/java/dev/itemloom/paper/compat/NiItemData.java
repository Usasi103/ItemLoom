package dev.itemloom.paper.compat;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiTagValues;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;

/** Temporary NI data view for applying source-defined options/NBT; never part of the core model. */
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
    private final ItemStateCodec codec = new ItemStateCodec();
    private final NiItemMigration migration = new NiItemMigration();

    public NiItemData(Clock clock) {
        this.clock = clock;
    }

    /** Encode fresh generated state directly; overlays and existing envelopes retain the old translation path. */
    public CompoundTag applyGenerated(
            CompoundTag source, NiConfig config, ItemIdentity identity, int definitionHash) {
        if (identity == null
                || source.contains("NeigeItems")
                || source.contains(ItemStateCodec.KEY)
                || config.section("nbt") != null
                || config.bool("options.removeNBT", false)
                || config.bool("options.remove-nbt", false)) {
            return migration.convert(apply(source, config, identity, definitionHash));
        }
        CompoundTag result = source.copy(), properties = new CompoundTag();
        properties.putInt("hashCode", definitionHash);
        applyOptions(properties, config.section("options"));
        result.put(ItemStateCodec.KEY, codec.encode(identity, properties));
        return result;
    }

    public CompoundTag apply(
            CompoundTag source, NiConfig config, ItemIdentity identity, int definitionHash) {
        CompoundTag result = source.copy();
        if (!config.bool("options.removeNBT", false) && !config.bool("options.remove-nbt", false)) {
            CompoundTag old = result.getCompoundOrEmpty("NeigeItems").copy();
            if (identity != null) {
                old.putString("id", identity.id());
                JsonObject rolls = new JsonObject();
                identity.rolls()
                        .forEach(
                                (key, value) -> {
                                    if (value == null) rolls.add(key, JsonNull.INSTANCE);
                                    else rolls.addProperty(key, value);
                                });
                old.putString("data", rolls.toString());
                old.putInt("hashCode", definitionHash);
            }
            applyOptions(old, config.section("options"));
            result.put("NeigeItems", old);
        }
        NiConfig overlay = config.section("nbt");
        if (overlay != null)
            result.merge((CompoundTag) NmsItems.tag(NiTagValues.decode(overlay.values())));
        return result;
    }

    private void applyOptions(CompoundTag values, NiConfig options) {
        if (options == null) return;
        for (String key : options.keys()) {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "charge", "durability" -> {
                    String field = key.toLowerCase(Locale.ROOT);
                    int value = options.integer(key, 0);
                    values.putInt(field, value);
                    values.putInt(field.equals("charge") ? "maxCharge" : "maxDurability", value);
                }
                case "maxcharge", "max-charge", "maxdurability", "max-durability" -> {
                    boolean charge = key.toLowerCase(Locale.ROOT).contains("charge");
                    String field = charge ? "charge" : "durability";
                    int value = options.integer(key, 0);
                    if (!values.contains(field)) values.putInt(field, value);
                    values.putInt(charge ? "maxCharge" : "maxDurability", value);
                }
                case "itembreak", "item-break" ->
                        values.putBoolean("itemBreak", options.bool(key, true));
                case "hide" -> values.putBoolean("hide", options.bool(key, true));
                case "owner" -> values.putString("owner", options.string(key, ""));
                case "color" -> {
                    String color = options.string(key, "").toUpperCase(Locale.ROOT);
                    if (COLORS.contains(color)) values.putString("color", color);
                }
                case "dropskill", "drop-skill" ->
                        values.putString("dropSkill", options.string(key, ""));
                case "itemtime", "item-time" ->
                        values.putLong(
                                "itemTime", clock.millis() + options.longValue(key, 0) * 1000);
                default -> {
                    /* update, ID injection and removal are handled by their owning stage. */
                }
            }
        }
    }
}
