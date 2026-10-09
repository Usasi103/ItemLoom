package dev.itemloom.paper.compat.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.compat.NiItemFiles;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Script-only manager facade owned by one catalog revision; no NI classes are loaded. */
public final class LegacyItemManager {
    public enum SaveResult {
        SUCCESS,
        AIR,
        CONFLICT
    }

    public final jdk.dynalink.beans.StaticClass SaveResult =
            jdk.dynalink.beans.StaticClass.forClass(SaveResult.class);
    public final LegacyItemManager INSTANCE = this;
    private final NiItemOperations operations;

    public LegacyItemManager(NiItemOperations operations) {
        this.operations = Objects.requireNonNull(operations);
    }

    public org.bukkit.plugin.java.JavaPlugin getPlugin() {
        operations.ensureActive();
        return operations.catalog().plugin();
    }

    public String getDir() {
        operations.ensureActive();
        return "Items";
    }

    public ArrayList<File> getFiles() {
        return operations.catalog().registry().files();
    }

    public void reloadItemConfigs() {
        operations.catalog().reloadItems(false);
    }

    public void reload() {
        operations.catalog().reloadItems(true);
    }

    public SaveResult saveItem(ItemStack item, String id, boolean cover) {
        return saveItem(item, id, id + ".yml", cover);
    }

    public SaveResult saveItem(ItemStack item, String id, String path, boolean cover) {
        return NiItemFiles.save(operations, item, id, path, cover);
    }

    public SaveResult saveItem(
            ItemStack item, String id, File file, YamlConfiguration config, boolean cover) {
        return NiItemFiles.save(operations, item, id, file, config, cover);
    }

    /** Live registry; mutations are independent of the original-configuration registry. */
    public Map<String, LegacyItemGenerator> getItems() {
        return operations.catalog().registry().generators();
    }

    public Map<String, LegacyItemConfig> getItemConfigs() {
        return operations.catalog().registry().configs();
    }

    public ArrayList<String> getItemIds() {
        ArrayList<String> result = getItemIdsRaw();
        Collections.sort(result);
        return result;
    }

    public ArrayList<String> getItemIdsRaw() {
        return new ArrayList<>(getItemConfigs().keySet());
    }

    public int getItemAmount() {
        return getItemIdsRaw().size();
    }

    public boolean hasItem(String id) {
        return getItems().containsKey(id);
    }

    public LegacyItemGenerator getItem(String id) {
        return getItems().get(id);
    }

    public ConfigurationSection getOriginConfig(String id) {
        ConfigurationSection origin = getRealOriginConfig(id);
        return origin == null ? null : NiYaml.toSection(NiYaml.fromSection(origin));
    }

    /** Stable script mirror; editing this view does not change the compiled recipe or input files. */
    public ConfigurationSection getRealOriginConfig(String id) {
        LegacyItemConfig origin = getItemConfigs().get(id);
        return origin == null ? null : origin.getConfigSection();
    }

    public ItemStack getItemStack(String id) {
        return getItemStack(id, null, (Map<String, String>) null);
    }

    public ItemStack getItemStack(String id, OfflinePlayer player) {
        return getItemStack(id, player, (Map<String, String>) null);
    }

    public ItemStack getItemStack(String id, String data) {
        return getItemStack(id, null, data);
    }

    public ItemStack getItemStack(String id, Map<String, String> data) {
        return getItemStack(id, null, data);
    }

    public ItemStack getItemStack(String id, OfflinePlayer player, String data) {
        LegacyItemGenerator generator = getItem(id);
        return generator == null ? null : generator.getItemStack(player, data);
    }

    public ItemStack getItemStack(String id, OfflinePlayer player, Map<String, String> data) {
        LegacyItemGenerator generator = getItem(id);
        return generator == null ? null : generator.getItemStack(player, data);
    }

    public LegacyItemInfo isNiItem(ItemStack item) {
        return isNiItem(item, false);
    }

    public LegacyItemInfo isNiItem(ItemStack item, boolean parseData) {
        operations.ensureActive();
        LegacyItemInfo result = LegacyItemInfo.inspect(item);
        if (parseData && result != null) result.getData();
        return result;
    }

    public String getItemId(ItemStack item) {
        operations.ensureActive();
        return NiItemNodes.itemId(item);
    }

    public boolean rebuild(ItemStack item, OfflinePlayer player, Map<String, String> sections) {
        return rebuild(item, player, sections, null);
    }

    public boolean rebuild(
            ItemStack item,
            OfflinePlayer player,
            Map<String, String> sections,
            List<String> protectNbt) {
        return operations.rebuild(item, player, sections, protectNbt);
    }

    public boolean rebuild(
            ItemStack item,
            OfflinePlayer player,
            List<String> protectSections,
            List<String> protectNbt) {
        return operations.rebuild(item, player, protectSections, protectNbt);
    }

    public void update(Player player, ItemStack item) {
        update(player, item, false, false);
    }

    public void update(Player player, ItemStack item, boolean force) {
        update(player, item, force, false);
    }

    public void update(Player player, ItemStack item, boolean force, boolean sendMessage) {
        operations.update(player, item, force, sendMessage);
    }

    public void setCharge(ItemStack item, int value) {
        operations.ensureActive();
        LegacyItemEditorManager.setCharge(item, value);
    }

    public void addCharge(ItemStack item, int value) {
        operations.ensureActive();
        LegacyItemEditorManager.addCharge(item, value);
    }

    public void setMaxCharge(ItemStack item, int value) {
        operations.ensureActive();
        LegacyItemEditorManager.setMaxCharge(item, value);
    }

    public void addMaxCharge(ItemStack item, int value) {
        operations.ensureActive();
        LegacyItemEditorManager.addMaxCharge(item, value);
    }

    public void setCustomDurability(ItemStack item, int value) {
        operations.ensureActive();
        LegacyItemEditorManager.setCustomDurability(item, value);
    }

    public void addCustomDurability(ItemStack item, int value) {
        operations.ensureActive();
        LegacyItemEditorManager.addCustomDurability(item, value);
    }

    public void setMaxCustomDurability(ItemStack item, int value) {
        operations.ensureActive();
        LegacyItemEditorManager.setMaxCustomDurability(item, value);
    }

    public void addMaxCustomDurability(ItemStack item, int value) {
        operations.ensureActive();
        LegacyItemEditorManager.addMaxCustomDurability(item, value);
    }

    public short checkDurability(ItemStack item, int current, int maximum) {
        operations.ensureActive();
        return LegacyItemEditorManager.checkDurability(item, current, maximum);
    }

    public void refreshDurability(ItemStack item, int current, int maximum) {
        operations.ensureActive();
        LegacyItemEditorManager.refreshDurability(item, current, maximum);
    }

    static Map<String, String> parseData(String source) {
        Map<String, String> result = new HashMap<>();
        if (source == null || source.isBlank()) return result;
        JsonElement parsed = JsonParser.parseString(source);
        if (parsed.isJsonNull()) return result;
        if (!parsed.isJsonObject())
            throw new IllegalArgumentException("Item data must be a JSON object");
        parsed.getAsJsonObject()
                .entrySet()
                .forEach(
                        entry -> {
                            JsonElement value = entry.getValue();
                            if (value.isJsonNull()) result.put(entry.getKey(), null);
                            else if (value.isJsonPrimitive())
                                result.put(entry.getKey(), value.getAsString());
                            else
                                throw new IllegalArgumentException(
                                        "Item data must contain scalar values: " + entry.getKey());
                        });
        return result;
    }
}
