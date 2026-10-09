package dev.itemloom.paper.compat.script;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.compat.NiPaperRecipe;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/** A revision-bound script view of an independent compiled recipe. */
public final class LegacyItemGenerator {
    private final NiItemOperations operations;
    private final String id;
    private final LegacyItemConfig itemConfig;
    private final NiPaperRecipe compiled;
    private final ActionFlow.Step<NiActionContext> postGenerate;

    public LegacyItemGenerator(NiItemOperations operations, String id) {
        this(operations.catalog().registry().generators().get(id));
    }

    private LegacyItemGenerator(LegacyItemGenerator source) {
        this(
                Objects.requireNonNull(source, "Unknown item").operations,
                source.itemConfig,
                source.compiled,
                source.postGenerate);
    }

    public LegacyItemGenerator(
            NiItemOperations operations,
            LegacyItemConfig itemConfig,
            NiPaperRecipe compiled,
            ActionFlow.Step<NiActionContext> postGenerate) {
        this.operations = Objects.requireNonNull(operations);
        this.itemConfig = Objects.requireNonNull(itemConfig);
        this.id = itemConfig.getId();
        this.compiled = Objects.requireNonNull(compiled);
        this.postGenerate = postGenerate;
    }

    private NiPaperRecipe recipe() {
        operations.ensureActive();
        return compiled;
    }

    public boolean ownedBy(NiItemOperations owner) {
        return operations.catalog() == owner.catalog();
    }

    public NiPaperRecipe compiledRecipe() {
        return recipe();
    }

    public ActionFlow.Step<NiActionContext> postGenerate() {
        operations.ensureActive();
        return postGenerate;
    }

    public LegacyItemConfig getItemConfig() {
        return itemConfig;
    }

    public File getFile() {
        return itemConfig.getFile();
    }

    private NiConfig definition() {
        return recipe().definition().definition();
    }

    public String getId() {
        return id;
    }

    /** Recipe-owned mirror: live sections affect future generation, compiled fields remain fixed. */
    public ConfigurationSection getConfigSection() {
        return recipe().legacyConfigSection();
    }

    public ConfigurationSection getSections() {
        return recipe().legacySections();
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
        NiConfig source = getUpdate() ? definition().section("options.update.rebuild") : null;
        if (source == null) return null;
        HashMap<String, String> result = new HashMap<>();
        ConfigurationSection section = NiYaml.toSection(source);
        for (String key : section.getKeys(true)) {
            if (section.get(key) instanceof String value) result.put(key, value);
        }
        return result;
    }

    public String getIdSection() {
        return definition().string("options.id-section");
    }

    public LegacyConfigReader getStatic() {
        return LegacyConfigReader.parse(getConfigSection().getConfigurationSection("static"));
    }

    public int getHashCode() {
        return recipe().definitionHash();
    }

    public ItemStack getItemStack(OfflinePlayer player, String data) {
        return getItemStack(player, LegacyItemManager.parseData(data));
    }

    public ItemStack getItemStack(OfflinePlayer player, Map<String, String> data) {
        return operations.create(this, player, data);
    }
}
