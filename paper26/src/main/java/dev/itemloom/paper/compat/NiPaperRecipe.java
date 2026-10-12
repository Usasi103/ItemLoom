package dev.itemloom.paper.compat;

import dev.itemloom.api.ItemContext;
import dev.itemloom.compat.ni.*;
import dev.itemloom.core.*;
import dev.itemloom.paper.display.ProofItemCopies;
import dev.itemloom.paper.integration.*;
import dev.itemloom.paper.nms.ItemStateCodec;
import java.time.Clock;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

public final class NiPaperRecipe implements ItemRecipe<ItemStack> {
    private static final int APPEARANCE_KEY_LIMIT = 16_384;

    public record Generated(
            ItemStack item,
            NiConfig expanded,
            Supplier<ConfigurationSection> sections,
            boolean postGenerate) {}

    public static final class InvalidMaterialException extends IllegalArgumentException {
        public InvalidMaterialException(String message) {
            super(message);
        }
    }

    private record LegacyView(ConfigurationSection config, ConfigurationSection sections) {}

    private final NiCompiledItem definition;
    private final NiDisplayTemplate display;
    private final NiNodes nodes;
    private final NiScripts scripts;
    private final NiEvaluation.Host host;
    private final NiItemData data;
    private final Consumer<String> warning;
    private final OptionalItemSources sources;
    private final NiConfig fixed;
    private final NiItemAppearance fixedAppearance;
    private final String staticExternal;
    private final boolean hasStaticMaterial;
    private final int definitionHash;
    private final net.minecraft.world.item.ItemStack prototype;
    private volatile LegacyView legacy;
    private String previousAppearanceKey;
    private boolean previousExternal;
    private NiItemAppearance appearance;

    public NiPaperRecipe(
            NiCompiledItem definition,
            NiNodes nodes,
            NiScripts scripts,
            NiEvaluation.Host host,
            Clock clock,
            Consumer<String> warning) {
        this(definition, nodes, scripts, host, clock, warning, null);
    }

    public NiPaperRecipe(
            NiCompiledItem definition,
            NiNodes nodes,
            NiScripts scripts,
            NiEvaluation.Host host,
            Clock clock,
            Consumer<String> warning,
            OptionalItemSources sources) {
        this.definition = definition;
        this.nodes = nodes;
        this.scripts = scripts;
        this.host = host;
        this.warning = warning;
        this.sources = sources;
        data = new NiItemData(clock);
        NiConfig view = definition.definition();
        definitionHash =
                NiYaml.write(new NiConfig(Collections.singletonMap(definition.id(), view.values())))
                        .hashCode();
        NiConfig displayConfig = view.section("client_bound_data");
        display = displayConfig == null ? null : new NiDisplayTemplate(displayConfig);
        NiConfig staticConfig = view.section("static");
        fixed = staticConfig == null ? new NiConfig(Map.of()) : staticConfig;
        fixedAppearance = new NiItemAppearance(fixed, warning);
        String requested = fixed.string("material");
        staticExternal = ExternalItemMaterial.isExternal(requested) ? requested : null;
        if (staticExternal != null) ExternalItemMaterial.parse(staticExternal);
        hasStaticMaterial = fixedAppearance.material() != null || staticExternal != null;
        prototype = fixedAppearance.apply(new net.minecraft.world.item.ItemStack(Items.STONE));
        if (!prototype.isEmpty())
            prototype.set(
                    DataComponents.CUSTOM_DATA,
                    CustomData.of(data.apply(custom(prototype), fixed, null, definitionHash)));
    }

    public NiCompiledItem definition() {
        return definition;
    }

    public NiDisplayTemplate display() {
        return display;
    }

    public int definitionHash() {
        return definitionHash;
    }

    private LegacyView legacy() {
        LegacyView current = legacy;
        if (current != null) return current;
        synchronized (this) {
            if (legacy == null) {
                ConfigurationSection config = NiConfigViews.section(definition.definition());
                legacy = new LegacyView(config, config.getConfigurationSection("sections"));
            }
            return legacy;
        }
    }

    public ConfigurationSection legacyConfigSection() {
        return legacy().config;
    }

    public ConfigurationSection legacySections() {
        return legacy().sections;
    }

    @Override
    public ItemStack create(GenerationContext context) {
        return createGenerated(context).item();
    }

    public Generated createGenerated(GenerationContext context) {
        OfflinePlayer viewer = context.get(ItemContext.VIEWER);
        LegacyView current = legacy;
        NiConfig sections =
                current == null
                        ? definition.definition().section("sections")
                        : current.sections == null ? null : NiYaml.fromSection(current.sections);
        NiEvaluation evaluation =
                new NiEvaluation(
                        context, sections, viewer, NiEvaluation.Mode.ACTION, nodes, scripts, host);
        NiConfig expanded = definition.expand(evaluation);
        String requested = expanded.string("material");
        String external = ExternalItemMaterial.isExternal(requested) ? requested : staticExternal;
        NiItemAppearance appearance = selectAppearance(expanded, external != null);
        if (appearance.material() == null && external == null && !hasStaticMaterial)
            throw new InvalidMaterialException(
                    "Invalid material for " + definition.id() + ": " + requested);
        net.minecraft.world.item.ItemStack item;
        if (external != null) {
            if (sources == null)
                throw new InvalidMaterialException(
                        "No external item source for " + definition.id() + ": " + external);
            ItemStack supplied =
                    sources.material(
                            external, viewer == null ? null : viewer.getPlayer(), Map.of());
            if (supplied == null)
                throw new InvalidMaterialException(
                        "Invalid external material for " + definition.id() + ": " + external);
            item = CraftItemStack.asNMSCopy(supplied);
            CompoundTag custom = custom(item);
            custom.remove(ItemStateCodec.KEY);
            if (!item.isEmpty()) {
                item.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
                item = new NiItemAppearance(fixed, warning, true).apply(item, false);
                item.set(
                        DataComponents.CUSTOM_DATA,
                        CustomData.of(data.apply(custom(item), fixed, null, definitionHash)));
            }
            item = appearance.apply(item);
        } else {
            item = prototype.copy();
            if (!item.isEmpty())
                item.set(
                        DataComponents.CUSTOM_DATA,
                        CustomData.of(data.apply(custom(item), fixed, null, definitionHash)));
            item = appearance.apply(item);
            if (!item.isEmpty()) item.setCount(1);
        }
        if (item.isEmpty())
            return new Generated(
                    CraftItemStack.asBukkitCopy(item), expanded, this::legacySections, false);
        CompoundTag custom =
                data.applyGenerated(
                        custom(item),
                        expanded,
                        new ItemIdentity(definition.id(), context.savedRolls()),
                        definitionHash);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        // Native copies can share mutable components with the plan or the static prototype.
        return new Generated(
                CraftItemStack.asBukkitCopy(ProofItemCopies.copy(item)),
                expanded,
                this::legacySections,
                true);
    }

    /** Admit one appearance only after a repeated expansion; evaluation always precedes this call. */
    private synchronized NiItemAppearance selectAppearance(NiConfig expanded, boolean external) {
        StringBuilder encoded = new StringBuilder();
        String key = appearanceKey(encoded, expanded.values()) ? encoded.toString() : null;
        boolean repeated =
                key != null && key.equals(previousAppearanceKey) && external == previousExternal;
        if (!repeated) {
            appearance = null;
            previousAppearanceKey = key;
            previousExternal = external;
        }
        if (appearance != null) return appearance;
        NiItemAppearance parsed = new NiItemAppearance(expanded, warning, external);
        if (repeated) appearance = parsed;
        return parsed;
    }

    /** Length-prefixed scalar tokens preserve map order, scalar types and unambiguous boundaries. */
    private static boolean appearanceKey(StringBuilder output, Object value) {
        if (value == null) return keyToken(output, '0');
        if (value instanceof String text) return keyScalar(output, 'S', text);
        if (value instanceof Boolean flag) return keyToken(output, flag ? 'T' : 'F');
        if (value instanceof Map<?, ?> map) {
            if (!keyToken(output, '{')) return false;
            for (var entry : map.entrySet()) {
                if (!keyScalar(output, 'K', entry.getKey().toString())
                        || !appearanceKey(output, entry.getValue())) return false;
            }
            return keyToken(output, '}');
        }
        if (value instanceof List<?> list) {
            if (!keyToken(output, '[')) return false;
            for (Object entry : list) if (!appearanceKey(output, entry)) return false;
            return keyToken(output, ']');
        }
        return keyScalar(output, 'N', value.getClass().getName())
                && keyScalar(output, 'V', value.toString());
    }

    private static boolean keyToken(StringBuilder output, char token) {
        if (output.length() >= APPEARANCE_KEY_LIMIT) return false;
        output.append(token);
        return true;
    }

    private static boolean keyScalar(StringBuilder output, char token, String value) {
        String length = Integer.toString(value.length());
        if ((long) output.length() + value.length() + length.length() + 2 > APPEARANCE_KEY_LIMIT)
            return false;
        output.append(token).append(length).append(':').append(value);
        return true;
    }

    private static CompoundTag custom(net.minecraft.world.item.ItemStack item) {
        CustomData custom = item.get(DataComponents.CUSTOM_DATA);
        return custom == null ? new CompoundTag() : custom.copyTag();
    }
}
