package dev.itemloom.paper.compat;

import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import dev.itemloom.compat.ni.NiCompiledItem;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.core.ItemRecipe;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import dev.itemloom.api.ItemContext;

/** The frontend compiles an NI definition into the independent engine's recipe contract. */
public final class NiPaperRecipe implements ItemRecipe<ItemStack> {
    /** Actual results of one expansion; compatibility hooks must never expand a second time. */
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

    private final NiCompiledItem definition;
    private final NiDisplayTemplate display;
    private final net.minecraft.world.item.ItemStack prototype;
    private final boolean staticMaterial;
    private final NiNodes nodes;
    private final NiScripts scripts;
    private final NiEvaluation.Host host;
    private final NiItemData data;
    private final int definitionHash;
    private final Consumer<String> warning;

    private record LegacyView(ConfigurationSection config, ConfigurationSection sections) {}

    private volatile LegacyView legacyView;

    private record Appearance(NiConfig config, net.minecraft.world.item.ItemStack prototype) {}

    private Appearance appearance;

    public NiPaperRecipe(
            NiCompiledItem definition,
            NiNodes nodes,
            NiScripts scripts,
            NiEvaluation.Host host,
            Clock clock,
            Consumer<String> warning) {
        this.definition = definition;
        NiConfig displayConfig = definition.definition().section("client_bound_data");
        display = displayConfig == null ? null : new NiDisplayTemplate(displayConfig);
        this.nodes = nodes;
        this.scripts = scripts;
        this.host = host;
        this.warning = warning;
        data = new NiItemData(clock);
        NiConfig fixed = definition.definition().section("static");
        staticMaterial =
                fixed != null && NiItemAppearance.material(fixed.string("material")) != null;
        prototype =
                new NiItemAppearance(fixed, warning)
                        .apply(new net.minecraft.world.item.ItemStack(Items.STONE));
        definitionHash =
                NiYaml.write(
                                new NiConfig(
                                        Map.of(definition.id(), definition.definition().values())))
                        .hashCode();
        if (fixed != null && !prototype.isEmpty()) {
            prototype.set(
                    DataComponents.CUSTOM_DATA,
                    CustomData.of(data.apply(customData(prototype), fixed, null, definitionHash)));
        }
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

    /** NI holds a live sections reference while its template, hash and other compiled fields stay fixed. */
    public ConfigurationSection legacyConfigSection() {
        return legacyView().config();
    }

    public ConfigurationSection legacySections() {
        return legacyView().sections();
    }

    /** Atomic publication protects lazy reads; the returned Bukkit views still require serialized mutation. */
    private LegacyView legacyView() {
        LegacyView current = legacyView;
        if (current == null) {
            synchronized (this) {
                current = legacyView;
                if (current == null) {
                    ConfigurationSection config = NiConfigViews.section(definition.definition());
                    current = new LegacyView(config, config.getConfigurationSection("sections"));
                    legacyView = current;
                }
            }
        }
        return current;
    }

    @Override
    public ItemStack create(GenerationContext context) {
        return createGenerated(context).item();
    }

    public Generated createGenerated(GenerationContext context) {
        LegacyView view = legacyView;
        NiConfig sections =
                view == null
                        ? definition.definition().section("sections")
                        : view.sections() == null ? null : NiYaml.fromSection(view.sections());
        NiEvaluation evaluation =
                new NiEvaluation(
                        context,
                        sections,
                        context.get(ItemContext.VIEWER),
                        NiEvaluation.Mode.ACTION,
                        nodes,
                        scripts,
                        host);
        NiConfig expanded = definition.expand(evaluation);
        Material material = NiItemAppearance.material(expanded.string("material"));
        if (material == null && !staticMaterial)
            throw new InvalidMaterialException(
                    definition.id() + ": invalid material: " + expanded.string("material"));
        if (prototype.isEmpty())
            return new Generated(
                    CraftItemStack.asCraftMirror(prototype.copy()),
                    expanded,
                    this::legacySections,
                    false);
        var result = appearance(expanded);
        if (result.isEmpty())
            return new Generated(
                    CraftItemStack.asCraftMirror(result), expanded, this::legacySections, false);
        CompoundTag custom =
                data.applyGenerated(
                        customData(result),
                        expanded,
                        new ItemIdentity(definition.id(), context.savedRolls()),
                        definitionHash);
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        result.setCount(1);
        return new Generated(
                CraftItemStack.asCraftMirror(result), expanded, this::legacySections, true);
    }

    /**
     * Keep one prepared appearance only for the frontend's small repeated document. Node
     * evaluation always precedes this cache; rolls, timestamps, options/NBT and generation
     * events remain per request. Randomized or oversized expansions evict the old plan.
     */
    private net.minecraft.world.item.ItemStack appearance(NiConfig expanded) {
        if (!definition.reusable(expanded)) {
            appearance = null;
            return new NiItemAppearance(expanded, warning).apply(prototype);
        }
        Appearance cached = appearance;
        if (cached == null || cached.config() != expanded) {
            cached =
                    new Appearance(
                            expanded, new NiItemAppearance(expanded, warning).apply(prototype));
            appearance = cached;
        }
        return cached.prototype().copy();
    }

    private static CompoundTag customData(net.minecraft.world.item.ItemStack item) {
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        // NiItemData always copies before applying options or overlays; this borrowed tree stays
        // private.
        return data == null ? new CompoundTag() : data.getUnsafe();
    }
}
