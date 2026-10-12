package dev.itemloom.paper.compat.script;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.paper.compat.*;
import java.io.File;
import java.util.*;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public final class LegacyItemGenerator {
    private final NiItemOperations operations;
    private final LegacyItemConfig origin;
    private final NiPaperRecipe recipe;
    private final ActionFlow.Step<NiActionContext> post;

    public LegacyItemGenerator(NiItemOperations operations, String id) {
        this.operations = Objects.requireNonNull(operations);
        operations.ensureActive();
        LegacyItemGenerator registered = operations.catalog().registry().generators().get(id);
        if (registered == null) throw new IllegalArgumentException("Unknown item: " + id);
        origin = registered.origin;
        recipe = registered.recipe;
        post = registered.post;
    }

    public LegacyItemGenerator(
            NiItemOperations operations,
            LegacyItemConfig itemConfig,
            NiPaperRecipe compiled,
            ActionFlow.Step<NiActionContext> postGenerate) {
        this.operations = Objects.requireNonNull(operations);
        origin = Objects.requireNonNull(itemConfig);
        recipe = Objects.requireNonNull(compiled);
        post = postGenerate;
    }

    public boolean ownedBy(NiItemOperations owner) {
        return owner != null && owner.catalog() == operations.catalog();
    }

    public NiPaperRecipe compiledRecipe() {
        operations.ensureActive();
        return recipe;
    }

    public ActionFlow.Step<NiActionContext> postGenerate() {
        operations.ensureActive();
        return post;
    }

    public LegacyItemConfig getItemConfig() {
        return origin;
    }

    public File getFile() {
        return origin.getFile();
    }

    public String getId() {
        return origin.getId();
    }

    public ConfigurationSection getConfigSection() {
        return compiledRecipe().legacyConfigSection();
    }

    public ConfigurationSection getSections() {
        return compiledRecipe().legacySections();
    }

    private NiConfig definition() {
        return compiledRecipe().definition().definition();
    }

    public boolean getUpdate() {
        return definition().bool("options.update.enable", false);
    }

    public boolean getProtectDamage() {
        return getUpdate() && definition().bool("options.update.protect-damage", false);
    }

    public List<String> getProtectNBT() {
        return getUpdate() ? definition().strings("options.update.protect") : List.of();
    }

    public List<String> getProtectComponents() {
        return getUpdate() ? definition().strings("options.update.protect-components") : List.of();
    }

    public List<String> getRefreshData() {
        return getUpdate() ? definition().strings("options.update.refresh") : List.of();
    }

    public HashMap<String, String> getRebuildData() {
        if (!getUpdate()) return null;
        NiConfig values = definition().section("options.update.rebuild");
        if (values == null) return null;
        HashMap<String, String> result = new HashMap<>();
        NiYaml.toSection(values)
                .getValues(true)
                .forEach(
                        (key, value) -> {
                            if (value instanceof String text) result.put(key, text);
                        });
        return result;
    }

    public String getIdSection() {
        return definition().string("options.id-section");
    }

    public LegacyConfigReader getStatic() {
        return LegacyConfigReader.parse(getConfigSection().getConfigurationSection("static"));
    }

    public int getHashCode() {
        return compiledRecipe().definitionHash();
    }

    public ItemStack getItemStack(OfflinePlayer player, String data) {
        return getItemStack(player, LegacyItemManager.parseData(data));
    }

    public ItemStack getItemStack(OfflinePlayer player, Map<String, String> data) {
        return operations.create(this, player, data);
    }
}
