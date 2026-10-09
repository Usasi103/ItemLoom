package dev.itemloom.probe;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiItemOperations;
import dev.itemloom.paper.compat.script.LegacyItemPack;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Paired pack generation in short tick batches; includes identical reflective-call overhead on both sides. */
final class PackOptimizationProbe {
    private static volatile Object sink;
    private static final int CHUNK = 16, WARMUP = 4096, ITERATIONS = 512, SAMPLES = 7;

    private record Sample(long nanosPerPack, long bytesPerPack) {}

    private record Work(Object pack, Method create, boolean unique) {
        Object run(int index) throws Exception {
            return create.invoke(
                    pack,
                    null,
                    new java.util.HashMap<>(Map.of("label", unique ? "value-" + index : "same")));
        }
    }

    private record Scenario(String name, Work before, Work after) {}

    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin, Path baseline) {
        var result = new CompletableFuture<Map<String, Object>>();
        Fixture fixture;
        try {
            fixture = new Fixture(plugin, baseline);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            return result;
        }
        try {
            var scenarios = new ArrayList<Scenario>();
            String many = "Items:\n" + "- 'STONE 1 1 true'\n".repeat(32);
            List<String> names =
                    List.of(
                            "single-static",
                            "32-static-entries",
                            "repeated-expanded",
                            "unique-expanded");
            List<String> texts =
                    List.of(
                            "Items: [STONE]\n",
                            many,
                            "Items: [STONE]\nnote: <label>\n",
                            "Items: [STONE]\nnote: <label>\n");
            for (int i = 0; i < names.size(); i++) {
                var old = fixture.before(texts.get(i), i == 3);
                var current = fixture.after(texts.get(i), i == 3);
                for (int j = 0; j < 8; j++)
                    if (!old.run(j).equals(current.run(j)))
                        throw new AssertionError("Pack output differs: " + names.get(i));
                scenarios.add(new Scenario(names.get(i), old, current));
            }
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put(
                    "baselineSha256",
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(baseline))));
            evidence.put("java", System.getProperty("java.version"));
            evidence.put("warmupPerImplementation", WARMUP);
            evidence.put("iterationsPerSample", ITERATIONS);
            evidence.put("samples", SAMPLES);
            evidence.put("chunkPerTickPerImplementation", CHUNK);
            evidence.put(
                    "boundary",
                    "Same JVM before/after pack generation; actual Paper, same catalog and inputs; no connected clients, bandwidth or whole-server capacity claim");
            var data = new LinkedHashMap<String, Object>();
            evidence.put("scenarios", data);
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            new BukkitRunnable() {
                int scenario, offset;
                long beforeTime, afterTime, beforeBytes, afterBytes, maxChunk;
                List<Sample> before = new ArrayList<>(), after = new ArrayList<>();

                @Override
                public void run() {
                    try {
                        Scenario value = scenarios.get(scenario);
                        boolean reverse = (offset / CHUNK) % 2 != 0;
                        Sample first =
                                measure(reverse ? value.after() : value.before(), bean, offset);
                        Sample second =
                                measure(reverse ? value.before() : value.after(), bean, offset);
                        Sample old = reverse ? second : first, current = reverse ? first : second;
                        maxChunk =
                                Math.max(
                                        maxChunk,
                                        (first.nanosPerPack() + second.nanosPerPack()) * CHUNK);
                        if (offset >= WARMUP) {
                            beforeTime += old.nanosPerPack() * CHUNK;
                            afterTime += current.nanosPerPack() * CHUNK;
                            beforeBytes += old.bytesPerPack() * CHUNK;
                            afterBytes += current.bytesPerPack() * CHUNK;
                        }
                        offset += CHUNK;
                        if (offset > WARMUP && (offset - WARMUP) % ITERATIONS == 0) {
                            before.add(
                                    new Sample(beforeTime / ITERATIONS, beforeBytes / ITERATIONS));
                            after.add(new Sample(afterTime / ITERATIONS, afterBytes / ITERATIONS));
                            beforeTime = afterTime = beforeBytes = afterBytes = 0;
                        }
                        if (offset == WARMUP + ITERATIONS * SAMPLES) {
                            data.put(
                                    value.name(),
                                    Map.of(
                                            "before",
                                            before,
                                            "after",
                                            after,
                                            "matchingPayloads",
                                            8,
                                            "maxPairedChunkNanosIncludingWarmup",
                                            maxChunk));
                            offset = 0;
                            maxChunk = 0;
                            before = new ArrayList<>();
                            after = new ArrayList<>();
                            if (++scenario == scenarios.size()) {
                                cancel();
                                fixture.close();
                                sink = null;
                                evidence.put("passed", true);
                                result.complete(evidence);
                            }
                        }
                    } catch (Throwable failure) {
                        cancel();
                        try {
                            fixture.close();
                        } catch (Exception closing) {
                            failure.addSuppressed(closing);
                        }
                        sink = null;
                        result.completeExceptionally(failure);
                    }
                }
            }.runTaskTimer(plugin, 1, 1);
        } catch (Throwable failure) {
            try {
                fixture.close();
            } catch (Exception closing) {
                failure.addSuppressed(closing);
            }
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static Sample measure(Work work, com.sun.management.ThreadMXBean bean, int offset)
            throws Exception {
        long thread = Thread.currentThread().threadId(),
                allocated = bean.getThreadAllocatedBytes(thread),
                start = System.nanoTime();
        for (int i = 0; i < CHUNK; i++) sink = work.run(offset + i);
        return new Sample(
                (System.nanoTime() - start) / CHUNK,
                (bean.getThreadAllocatedBytes(thread) - allocated) / CHUNK);
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("il-pack-paired-").toAbsolutePath().normalize();
        final PlayerActionState players = new PlayerActionState();
        final NiCatalog catalog;
        final URLClassLoader loader;
        final Class<?> old;

        Fixture(JavaPlugin plugin, Path baseline) throws Exception {
            catalog =
                    new NiCatalog(
                            1,
                            new NiRepository().read(root),
                            root,
                            plugin,
                            (p, text) -> null,
                            players);
            loader =
                    new URLClassLoader(
                            new java.net.URL[] {baseline.toUri().toURL()},
                            LegacyItemPack.class.getClassLoader()) {
                        @Override
                        protected Class<?> loadClass(String name, boolean resolve)
                                throws ClassNotFoundException {
                            if (!name.equals(LegacyItemPack.class.getName())
                                    && !name.startsWith(LegacyItemPack.class.getName() + "$"))
                                return super.loadClass(name, resolve);
                            synchronized (getClassLoadingLock(name)) {
                                Class<?> value = findLoadedClass(name);
                                if (value == null) value = findClass(name);
                                if (resolve) resolveClass(value);
                                return value;
                            }
                        }
                    };
            old = loader.loadClass(LegacyItemPack.class.getName());
        }

        Work before(String text, boolean unique) throws Exception {
            Object pack =
                    old.getConstructor(
                                    NiItemOperations.class,
                                    String.class,
                                    ConfigurationSection.class)
                            .newInstance(catalog.items(), "bench", yaml(text));
            return new Work(
                    pack,
                    old.getMethod("getItemStacks", org.bukkit.OfflinePlayer.class, Map.class),
                    unique);
        }

        Work after(String text, boolean unique) throws Exception {
            return new Work(
                    new LegacyItemPack(catalog.items(), "bench", yaml(text)),
                    LegacyItemPack.class.getMethod(
                            "getItemStacks", org.bukkit.OfflinePlayer.class, Map.class),
                    unique);
        }

        private static YamlConfiguration yaml(String text) throws Exception {
            var yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            return yaml;
        }

        @Override
        public void close() throws Exception {
            catalog.close();
            players.close();
            loader.close();
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root))
                        throw new IllegalStateException("Fixture cleanup escaped root");
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
