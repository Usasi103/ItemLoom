package dev.itemloom.probe;

import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.component.CustomData;
import dev.itemloom.api.ItemLoom;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.paper.nms.NmsItems;
import dev.itemloom.paper.compat.NiItemMigration;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin. It is never included in the release JAR. */
public final class ItemsProbe extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String mode = args.length == 0 ? "corpus" : args[0];
        try {
            var reference = getServer().getPluginManager().getPlugin("NeigeItems");
            CompletionStage<Map<String, Object>> result =
                    switch (mode) {
                        case "legacy-materials" ->
                                CompletableFuture.completedFuture(
                                        LegacyMaterialsProbe.run(
                                                this, args.length > 1 ? args[1] : "bench"));
                        case "itembridge" ->
                                CompletableFuture.completedFuture(ItemBridgeProbe.run(this));
                        case "external-material" ->
                                CompletableFuture.completedFuture(ExternalMaterialProbe.run(this));
                        case "action-threads" -> ActionThreadProbe.run(this);
                        case "action-runtime" -> ActionRuntimeProbe.run(this);
                        case "triggers" -> ItemTriggerProbe.run(this);
                        case "runtime-rules" ->
                                CompletableFuture.completedFuture(RuntimeRulesProbe.run(this));
                        case "consume-commit" -> ConsumeCommitProbe.run(this);
                        case "trigger-commit" -> TriggerCommitProbe.run(this);
                        case "scheduler" -> SchedulerProbe.run(this);
                        case "builtins" -> BuiltinActionProbe.run(this);
                        case "inputs" -> InputCaptureProbe.run(this);
                        case "signs" -> SignCaptureProbe.run(this);
                        case "item-save" ->
                                CompletableFuture.completedFuture(
                                        ItemSaveProbe.run(this, reference));
                        case "registry" -> ItemRegistryProbe.run(this);
                        case "config-manager" -> ItemConfigManagerProbe.run(this);
                        case "manager-reload" ->
                                CompletableFuture.completedFuture(ItemManagerReloadProbe.run(this));
                        case "manager-reload-delayed" -> ItemManagerReloadProbe.runDelayed(this);
                        case "packs" -> ItemPackProbe.run(this, reference);
                        case "packs-paired" ->
                                PackOptimizationProbe.run(this, java.nio.file.Path.of(args[1]));
                        case "drops" -> ItemDropProbe.run(this);
                        case "drop-visibility" -> DropVisibilityProbe.run(this);
                        case "generation-plan" ->
                                CompletableFuture.completedFuture(GenerationPlanProbe.run());
                        case "generation-paired" ->
                                CompletableFuture.completedFuture(
                                        GenerationPlanProbe.paired(java.nio.file.Path.of(args[1])));
                        case "display" -> ItemDisplayProbe.run(this);
                        case "display-proofs" ->
                                CompletableFuture.completedFuture(DisplayProofProbe.run(this));
                        case "display-template" ->
                                CompletableFuture.completedFuture(DisplayTemplateProbe.run(this));
                        case "return-ledger" -> ReturnLedgerProbe.run(this);
                        case "papi-config" -> CompletableFuture.completedFuture(papiConfig());
                        case "ni-placeholders" -> NiPlaceholderProbe.run(this, reference);
                        case "ni-papi" -> niPapi();
                        case "filesave" -> ItemFileSaveProbe.run(this);
                        case "maintenance" -> ItemMaintenanceProbe.run(this);
                        case "lifecycle" -> LifecycleProbe.run(this);
                        case "identity" ->
                                CompletableFuture.completedFuture(ItemIdentityProbe.run(this));
                        case "integrations" -> OptionalIntegrationProbe.run(this);
                        case "mythic-drops" -> MythicDropProbe.run(this);
                        case "loot-bags" ->
                                CompletableFuture.completedFuture(LootBagProbe.run(this));
                        case "sx-config" ->
                                CompletableFuture.completedFuture(SxConfigProbe.run(this));
                        case "provider-admin" -> ProviderAdminProbe.run(this);
                        case "use-restrictions" ->
                                CompletableFuture.completedFuture(UseRestrictionsProbe.run(this));
                        case "generation-events" ->
                                CompletableFuture.completedFuture(
                                        ItemGenerationEventProbe.run(this));
                        case "library" ->
                                CompletableFuture.completedFuture(
                                        ActionLibraryProbe.run(this, reference));
                        case "editors" ->
                                CompletableFuture.completedFuture(
                                        ItemEditorProbe.run(this, reference));
                        case "revisions" ->
                                CompletableFuture.completedFuture(ItemRevisionProbe.run(this));
                        case "nbt" -> CompletableFuture.completedFuture(NbtProbe.run(this));
                        case "read-optimization" ->
                                CompletableFuture.completedFuture(ReadOptimizationProbe.run());
                        case "item-nodes" ->
                                CompletableFuture.completedFuture(ItemNodeProbe.run(reference));
                        case "actions" ->
                                CompletableFuture.completedFuture(ActionProbe.run(reference));
                        case "nodes" -> CompletableFuture.completedFuture(NodeProbe.run(reference));
                        case "corpus", "compare" -> null;
                        default ->
                                throw new IllegalArgumentException("Unknown probe mode: " + mode);
                    };
            if (result != null) {
                result.whenComplete(
                        (report, error) -> {
                            if (error != null)
                                getLogger()
                                        .log(
                                                java.util.logging.Level.SEVERE,
                                                "IL_" + mode + "_PROBE failed",
                                                error);
                            try {
                                Object evidence =
                                        error == null
                                                ? report
                                                : Map.of(
                                                        "passed", false, "error", error.toString());
                                Files.createDirectories(getDataFolder().toPath());
                                Files.writeString(
                                        getDataFolder().toPath().resolve(mode + ".json"),
                                        new GsonBuilder()
                                                .serializeSpecialFloatingPointValues()
                                                .setPrettyPrinting()
                                                .create()
                                                .toJson(evidence),
                                        StandardCharsets.UTF_8);
                                sender.sendMessage(
                                        "IL_"
                                                + mode.toUpperCase(java.util.Locale.ROOT)
                                                + "_PROBE "
                                                + evidence);
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
                return true;
            }
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        ItemLoom service = getServer().getServicesManager().load(ItemLoom.class);
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Object> failures = new LinkedHashMap<>(), differences = new LinkedHashMap<>();
        int generated = 0, compared = 0;
        long started = System.nanoTime();
        try {
            if (service == null) throw new IllegalStateException("ItemLoom service missing");
            Object reference = null;
            java.lang.reflect.Method referenceGenerate = null;
            if (mode.equals("compare")) {
                var plugin = getServer().getPluginManager().getPlugin("NeigeItems");
                if (plugin == null || !plugin.isEnabled())
                    throw new IllegalStateException("NI reference is not loaded");
                Class<?> type =
                        plugin.getClass()
                                .getClassLoader()
                                .loadClass("pers.neige.neigeitems.manager.ItemManager");
                reference = type.getField("INSTANCE").get(null);
                referenceGenerate =
                        type.getMethod(
                                "getItemStack", String.class, OfflinePlayer.class, Map.class);
            }
            for (String id : service.ids()) {
                try {
                    ItemStack old = null;
                    Map<String, String> rolls = Map.of();
                    if (referenceGenerate != null) {
                        old =
                                (ItemStack)
                                        referenceGenerate.invoke(
                                                reference,
                                                id,
                                                null,
                                                new java.util.HashMap<String, String>());
                        if (old == null)
                            throw new IllegalStateException("Reference returned no item");
                        rolls =
                                new NiItemMigration()
                                        .identify(old)
                                        .map(identity -> identity.rolls())
                                        .orElse(Map.of());
                    }
                    ItemStack created = service.create(id, null, rolls);
                    if (created == null) throw new AssertionError("Generation returned null");
                    generated++;
                    if (!created.isEmpty()
                            && NmsItems.customData(created)
                                    .getCompoundOrEmpty("NeigeItems")
                                    .contains("id")) {
                        throw new AssertionError("New item retained legacy identity");
                    }
                    if (old != null) {
                        compared++;
                        String expected = canonical(old), actual = canonical(created);
                        if (!expected.equals(actual))
                            differences.put(id, Map.of("reference", expected, "itemloom", actual));
                        ItemStack converted = service.migrate(old);
                        if (!canonical(converted).equals(expected))
                            throw new AssertionError("Migration changed item payload");
                    }
                } catch (Throwable error) {
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    failures.put(id, cause.getClass().getName() + ": " + cause.getMessage());
                }
            }
            report.put("definitions", service.ids().size());
            report.put(
                    "referencePluginLoaded",
                    getServer().getPluginManager().isPluginEnabled("NeigeItems"));
        } catch (Throwable error) {
            failures.put("@probe", error.toString());
        }
        report.put("generated", generated);
        report.put("compared", compared);
        report.put("failures", failures);
        report.put("differences", differences);
        report.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(
                    getDataFolder().toPath().resolve(mode + ".json"),
                    new GsonBuilder()
                            .setPrettyPrinting()
                            .disableHtmlEscaping()
                            .create()
                            .toJson(report),
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot write probe result", error);
        }
        sender.sendMessage(
                "IL_PROBE "
                        + mode
                        + " generated="
                        + generated
                        + " compared="
                        + compared
                        + " failures="
                        + failures.size()
                        + " differences="
                        + differences.size());
        return true;
    }

    @SuppressWarnings("unchecked")
    private CompletionStage<Map<String, Object>> niPapi() throws ReflectiveOperationException {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))
            throw new IllegalStateException("ni-papi requires actual PlaceholderAPI");
        Class<?> probe =
                Class.forName("dev.itemloom.probe.NiPapiProbe", true, getClass().getClassLoader());
        return (CompletionStage<Map<String, Object>>)
                probe.getDeclaredMethod("run", JavaPlugin.class).invoke(null, this);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> papiConfig() throws ReflectiveOperationException {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))
            throw new IllegalStateException("papi-config requires actual PlaceholderAPI");
        Class<?> probe =
                Class.forName(
                        "dev.itemloom.probe.PapiConfigProbe", true, getClass().getClassLoader());
        return (Map<String, Object>)
                probe.getDeclaredMethod("run", JavaPlugin.class).invoke(null, this);
    }

    /** Compare public item payload, normalizing only the explicitly changed storage envelope and creation time. */
    private String canonical(ItemStack item) {
        var copy = CraftItemStack.asNMSCopy(item);
        CompoundTag custom = new NiItemMigration().convert(NmsItems.customData(item));
        CompoundTag state = custom.getCompoundOrEmpty(ItemStateCodec.KEY);
        if (!state.isEmpty()) {
            CompoundTag properties = state.getCompoundOrEmpty("properties");
            properties.remove("hashCode");
            properties.remove("itemTime");
        }
        copy.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        var ops = CraftRegistry.getMinecraftRegistry().createSerializationContext(NbtOps.INSTANCE);
        var tag = net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops, copy).getOrThrow();
        // Compound SNBT preserves hash iteration order. Canonicalize structurally for stable
        // comparisons.
        return new GsonBuilder().disableHtmlEscaping().create().toJson(sorted(tag));
    }

    private Object sorted(net.minecraft.nbt.Tag tag) {
        if (tag instanceof CompoundTag map) {
            Map<String, Object> result = new java.util.TreeMap<>();
            map.entrySet().forEach(entry -> result.put(entry.getKey(), sorted(entry.getValue())));
            return result;
        }
        if (tag instanceof net.minecraft.nbt.ListTag list) {
            var result = new ArrayList<>();
            list.forEach(value -> result.add(sorted(value)));
            return result;
        }
        return tag.toString();
    }
}
