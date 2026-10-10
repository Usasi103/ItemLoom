package dev.itemloom.probe;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.material.MaterialData;
import org.bukkit.plugin.java.JavaPlugin;

/** Run bench in a fresh JVM. Export/verify deliberately initializes Paper's legacy oracle afterward. */
@SuppressWarnings({"deprecation", "removal"})
final class LegacyMaterialsProbe {
    private static volatile int sink;

    static Map<String, Object> run(JavaPlugin owner, String mode) throws Exception {
        ClassLoader loader =
                Bukkit.getPluginManager().getPlugin("ItemLoom").getClass().getClassLoader();
        Class<?> resolver = Class.forName("dev.itemloom.paper.sx.SxMaterials", false, loader);
        Method resolve = resolver.getDeclaredMethod("resolve", String.class);
        resolve.setAccessible(true);
        Class<?> resolved =
                Class.forName("dev.itemloom.paper.sx.SxMaterials$Resolved", false, loader);
        Method material = resolved.getDeclaredMethod("material");
        material.setAccessible(true);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("server", Bukkit.getVersion());
        report.put(
                "plugin",
                Bukkit.getPluginManager().getPlugin("ItemLoom").getPluginMeta().getVersion());
        report.put("primaryThread", Bukkit.isPrimaryThread());
        var cpu = ManagementFactory.getThreadMXBean();
        var allocation = (com.sun.management.ThreadMXBean) cpu;
        long thread = Thread.currentThread().threadId();
        long allocated = allocation.getThreadAllocatedBytes(thread),
                cpuStart = cpu.getCurrentThreadCpuTime();
        long started = System.nanoTime();
        Object first = resolve.invoke(null, "35:14");
        report.put("firstWallNanos", System.nanoTime() - started);
        report.put("firstCpuNanos", cpu.getCurrentThreadCpuTime() - cpuStart);
        report.put("firstAllocatedBytes", allocation.getThreadAllocatedBytes(thread) - allocated);
        if (material.invoke(first) != Material.RED_WOOL) throw new AssertionError("red wool");
        report.put(
                "warmModern",
                measure(resolve, new String[] {"PAPER", "DIAMOND_SWORD:80%", "minecraft:stone"}));
        report.put(
                "warmLegacy",
                measure(
                        resolve,
                        new String[] {"35:14", "359", "5:2", "WOOD_SWORD", "351:4", "397:3"}));
        int assertions = 1;
        if (mode.equals("export") || mode.equals("verify")) {
            var legacy =
                    Arrays.stream(Material.class.getEnumConstants())
                            .filter(Material::isLegacy)
                            .sorted(Comparator.comparingInt(Material::getId))
                            .toList();
            StringBuilder table =
                    new StringBuilder(
                            "# ItemLoom legacy material observations; Paper 26.2 build 123\n"
                                    + "# id\tlegacy-name\tstart=modern-material (or - for rejected), repeated at value changes\n");
            int pairs = 0;
            for (Material old : legacy) {
                table.append(old.getId()).append('\t').append(old.name().substring(7));
                String previous = null;
                for (int data = 0; data < 256; data++) {
                    Material expected =
                            Bukkit.getUnsafe().fromLegacy(new MaterialData(old, (byte) data), true);
                    if (expected == null || expected.isAir() || !expected.isItem()) expected = null;
                    String value = expected == null ? "-" : expected.name();
                    if (!value.equals(previous))
                        table.append('\t').append(data).append('=').append(value);
                    previous = value;
                    if (mode.equals("verify")) {
                        check(resolve, material, old.getId() + ":" + data, expected);
                        assertions++;
                    }
                    pairs++;
                }
                if (mode.equals("verify")) {
                    String name = old.name().substring(7);
                    Material exact = Material.matchMaterial(name);
                    Material expected =
                            exact != null && !exact.isLegacy() && exact.isItem() && !exact.isAir()
                                    ? exact
                                    : Bukkit.getUnsafe().fromLegacy(new MaterialData(old), true);
                    if (expected == null || expected.isAir() || !expected.isItem()) expected = null;
                    check(resolve, material, name, expected);
                    assertions++;
                }
                table.append('\n');
            }
            Files.createDirectories(owner.getDataFolder().toPath());
            Files.writeString(
                    owner.getDataFolder().toPath().resolve("legacy-materials-26.2.tsv"),
                    table,
                    StandardCharsets.UTF_8);
            report.put("oraclePairs", pairs);
            report.put("oracleMaterials", legacy.size());
        }
        if (mode.equals("verify")) {
            for (String text :
                    List.of(
                            "35:256",
                            "35:9999999999999999999",
                            "99999999",
                            "UNKNOWN",
                            "LEGACY_WOOL",
                            "0",
                            "-1",
                            "",
                            "35:1.5",
                            "35:abc")) {
                check(resolve, material, text, null);
                assertions++;
            }
            for (String text : List.of("35:+14", "35:-14", "35:80%", "35:<14")) {
                check(resolve, material, text, Material.WHITE_WOOL);
                assertions++;
            }
            check(resolve, material, "00035:014", Material.RED_WOOL);
            check(resolve, material, "STONE:1", Material.STONE);
            check(resolve, material, "minecraft:diamond_sword:80%", Material.DIAMOND_SWORD);
            assertions += 3;
        }
        report.put("checks", assertions);
        report.put("passed", true);
        report.put(
                "limits",
                "Single-thread resolver microbenchmark with reflection overhead; no clients or TPS/load claim. Oracle export/verify intentionally initializes CraftLegacy after measured calls.");
        return report;
    }

    private static Map<String, Object> measure(Method method, String[] values) throws Exception {
        for (int i = 0; i < 10_000; i++)
            sink ^= method.invoke(null, values[i % values.length]).hashCode();
        List<Double> batches = new ArrayList<>();
        long allocated = 0;
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int batch = 0; batch < 7; batch++) {
            long before = mx.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long start = System.nanoTime();
            for (int i = 0; i < 20_000; i++)
                sink ^= method.invoke(null, values[i % values.length]).hashCode();
            batches.add((System.nanoTime() - start) / 20_000.0);
            allocated += mx.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        }
        List<Double> sorted = batches.stream().sorted().toList();
        return Map.of(
                "batchMeanNsPerOp",
                batches,
                "medianBatchNsPerOp",
                sorted.get(3),
                "allocatedBytesPerOp",
                allocated / 140_000.0,
                "measuredOperations",
                140_000);
    }

    private static void check(Method resolver, Method accessor, String text, Material expected)
            throws Exception {
        Material actual;
        try {
            actual = (Material) accessor.invoke(resolver.invoke(null, text));
        } catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof IllegalArgumentException)) throw error;
            actual = null;
        }
        if (actual != expected)
            throw new AssertionError(text + ": expected " + expected + ", got " + actual);
    }
}
