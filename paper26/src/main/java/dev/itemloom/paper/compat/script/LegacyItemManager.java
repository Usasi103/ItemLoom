package dev.itemloom.paper.compat.script;

import com.google.gson.*;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.paper.compat.*;
import java.io.File;
import java.util.*;
import jdk.dynalink.beans.StaticClass;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class LegacyItemManager {
    public enum SaveResult {
        SUCCESS,
        AIR,
        CONFLICT
    }

    public final StaticClass SaveResult = StaticClass.forClass(LegacyItemManager.SaveResult.class);
    public final LegacyItemManager INSTANCE = this;
    private final NiItemOperations operations;

    public LegacyItemManager(NiItemOperations operations) {
        this.operations = Objects.requireNonNull(operations);
    }

    public JavaPlugin getPlugin() {
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

    public Map<String, LegacyItemGenerator> getItems() {
        return operations.catalog().registry().generators();
    }

    public Map<String, LegacyItemConfig> getItemConfigs() {
        return operations.catalog().registry().configs();
    }

    public ArrayList<String> getItemIdsRaw() {
        return new ArrayList<>(getItemConfigs().keySet());
    }

    public ArrayList<String> getItemIds() {
        ArrayList<String> ids = getItemIdsRaw();
        ids.sort(String::compareTo);
        return ids;
    }

    public int getItemAmount() {
        return getItemConfigs().size();
    }

    public boolean hasItem(String id) {
        return getItems().containsKey(id);
    }

    public LegacyItemGenerator getItem(String id) {
        return getItems().get(id);
    }

    public ConfigurationSection getOriginConfig(String id) {
        ConfigurationSection original = getRealOriginConfig(id);
        return original == null ? null : NiYaml.toSection(NiYaml.fromSection(original));
    }

    public ConfigurationSection getRealOriginConfig(String id) {
        LegacyItemConfig original = getItemConfigs().get(id);
        return original == null ? null : original.getConfigSection();
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
        if (result != null && parseData) result.getData();
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
        HashMap<String, String> result = new HashMap<>();
        if (source == null || source.isBlank()) return result;
        JsonElement root = JsonParser.parseString(source);
        if (root.isJsonNull()) return result;
        if (!root.isJsonObject())
            throw new IllegalArgumentException("Item data must be a JSON object");
        root.getAsJsonObject()
                .entrySet()
                .forEach(
                        entry -> {
                            JsonElement value = entry.getValue();
                            if (!value.isJsonNull() && !value.isJsonPrimitive())
                                throw new IllegalArgumentException(
                                        "Item data must contain scalar values: " + entry.getKey());
                            result.put(
                                    entry.getKey(),
                                    value.isJsonNull() ? null : value.getAsString());
                        });
        return result;
    }
}
