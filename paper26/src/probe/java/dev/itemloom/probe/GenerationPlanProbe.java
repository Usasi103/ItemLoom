package dev.itemloom.probe;

import java.lang.management.ManagementFactory;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import dev.itemloom.compat.ni.NiCompiledItem;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.core.GenerationContext;
import dev.itemloom.core.ItemRecipe;
import dev.itemloom.paper.compat.NiPaperRecipe;
import dev.itemloom.paper.nms.ItemStateCodec;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Same-process paired recipe microbenchmark; never presented as a whole-server TPS test. */
final class GenerationPlanProbe {
    private static volatile Object sink;

    static Map<String, Object> run() {
        List<String> checks = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        try (Fixture f = new Fixture()) {
            checks(f, checks);
            result.put("passed", true);
        } catch (Throwable failure) {
            result.put("passed", false);
            result.put("failure", failure.toString());
        }
        result.put("checks", checks.size());
        result.put("assertions", checks);
        result.put(
                "boundary",
                "Main-thread recipe generation; no packet serialization, client, scripts or whole-server load benchmark");
        result.put("java", System.getProperty("java.version"));
        return result;
    }

    private static void checks(Fixture f, List<String> c) throws Exception {
        NiPaperRecipe recipe =
                f.recipe(
                        "cached",
                        """
                material: DIAMOND_SWORD
                name: '&a<papi::same>'
                lore: ['&7Fixed lore', '&bSecond line']
                options: {durability: 10, item-time: 60}
                static: {unbreakable: true, nbt: {external: {keep: yes}}}
                components: {enchantment_glint_override: true}
                """);
        ItemStack first = null;
        for (int i = 0; i < 4; i++) first = f.create(recipe, Map.of("seed", "old"));
        Object cached = cached(recipe);
        check(c, cached != null, "repeated small expansion produces one appearance plan");
        check(
                c,
                f.evaluations.get() == 4,
                "node side effects execute on every request despite identical output");
        var firstState = new ItemStateCodec();
        check(
                c,
                firstState.properties(first).getLong("itemTime").orElseThrow() == 61_000,
                "initial lifetime uses current request clock");
        var handle = CraftItemStack.unwrap(first);
        handle.set(DataComponents.CUSTOM_NAME, Component.literal("poison"));
        handle.setCount(42);
        handle.remove(DataComponents.UNBREAKABLE);
        f.clock.time = 9_000;
        var rolls = new LinkedHashMap<String, String>();
        rolls.put("seed", "new");
        rolls.put("null", null);
        ItemStack second = f.create(recipe, rolls);
        check(
                c,
                cached(recipe) == cached && f.evaluations.get() == 5,
                "cached appearance reused only after fresh evaluation");
        var fresh = CraftItemStack.unwrap(second);
        check(
                c,
                fresh.getCount() == 1
                        && fresh.get(DataComponents.CUSTOM_NAME).getString().equals("same")
                        && fresh.has(DataComponents.UNBREAKABLE),
                "returned stack mutation cannot poison cached appearance");
        check(
                c,
                fresh.get(DataComponents.LORE).lines().size() == 2
                        && fresh.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE),
                "lore and explicit components survive compiled plan");
        check(
                c,
                firstState.properties(second).getLong("itemTime").orElseThrow() == 69_000,
                "cached appearance never freezes item lifetime");
        var identity = firstState.read(second).orElseThrow();
        check(
                c,
                identity.rolls().get("seed").equals("new")
                        && identity.rolls().containsKey("null")
                        && identity.rolls().get("null") == null,
                "per-request saved rolls including nulls remain independent");

        NiPaperRecipe dynamic =
                f.recipe(
                        "dynamic",
                        "material: '<kind>'\nname: '<label>'\noptions: {owner: '<owner>'}\n");
        var a = Map.of("kind", "STONE", "label", "one", "owner", "Alice");
        for (int i = 0; i < 4; i++) f.create(dynamic, a);
        check(
                c,
                cached(dynamic) != null,
                "repeated dynamic result is eligible for the bounded cache");
        var b = Map.of("kind", "DIRT", "label", "two", "owner", "Bob");
        ItemStack changed = f.create(dynamic, b);
        check(
                c,
                cached(dynamic) == null
                        && changed.getType() == Material.DIRT
                        && CraftItemStack.unwrap(changed)
                                .get(DataComponents.CUSTOM_NAME)
                                .getString()
                                .equals("two")
                        && firstState
                                .properties(changed)
                                .getString("owner")
                                .orElseThrow()
                                .equals("Bob"),
                "changed expansion immediately evicts old material, name and owner");
        for (int i = 0; i < 4; i++) f.create(dynamic, b);
        check(
                c,
                cached(dynamic) != null,
                "replacement repeated expansion establishes its own plan");
        for (int i = 0; i < 12; i++)
            f.create(dynamic, Map.of("kind", "STONE", "label", "unique" + i, "owner", "Alice"));
        check(
                c,
                cached(dynamic) == null,
                "randomized unique results do not accumulate appearance entries");
        NiPaperRecipe huge =
                f.recipe("huge", "material: STONE\nname: '" + "x".repeat(17_000) + "'\n");
        for (int i = 0; i < 4; i++) f.create(huge, Map.of());
        check(c, cached(huge) == null, "oversized expansion stays uncached");
        NiPaperRecipe mutable =
                f.recipe(
                        "mutable", "material: STONE\nname: '<label>'\nsections: {label: before}\n");
        for (int i = 0; i < 4; i++) f.create(mutable, Map.of());
        mutable.legacySections().set("label", "after");
        check(
                c,
                CraftItemStack.unwrap(f.create(mutable, Map.of()))
                        .get(DataComponents.CUSTOM_NAME)
                        .getString()
                        .equals("after"),
                "legacy mutable sections invalidate by rendered value without replaying old nodes");
        NiPaperRecipe overlay =
                f.recipe(
                        "overlay",
                        """
                material: STONE
                static: {nbt: {external: {keep: value}}}
                options: {durability: 7}
                nbt: {NeigeItems: {durability: '(Int) 3'}, external: {added: value}}
                """);
        for (int i = 0; i < 4; i++) first = f.create(overlay, Map.of());
        check(
                c,
                firstState.properties(first).getInt("durability").orElseThrow() == 3,
                "legacy NBT overlay still overrides fresh options after cached appearance");
        var custom = dev.itemloom.paper.nms.NmsItems.customData(first);
        check(
                c,
                custom.getCompoundOrEmpty("external").contains("keep")
                        && custom.getCompoundOrEmpty("external").contains("added")
                        && !custom.contains("NeigeItems"),
                "foreign custom data survives while final identity remains independent");
        NiPaperRecipe plain = f.recipe("removed", "material: STONE\noptions: {removeNBT: true}\n");
        for (int i = 0; i < 4; i++) first = f.create(plain, Map.of());
        check(c, firstState.read(first).isEmpty(), "removeNBT remains untagged through cache hits");
    }

    static Map<String, Object> paired(Path baseline) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Fixture f = new Fixture();
                var loader = new BaselineLoader(baseline)) {
            var oldType = loader.loadClass(NiPaperRecipe.class.getName());
            var constructor =
                    oldType.getConstructor(
                            NiCompiledItem.class,
                            NiNodes.class,
                            NiScripts.class,
                            NiEvaluation.Host.class,
                            Clock.class,
                            java.util.function.Consumer.class);
            result.put("baselineJar", baseline.toAbsolutePath().toString());
            result.put(
                    "baselineSha256",
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(java.nio.file.Files.readAllBytes(baseline))));
            result.put("scenarios", bench(f, constructor));
            result.put("passed", true);
        } catch (Throwable failure) {
            result.put("passed", false);
            result.put("failure", failure.toString());
        }
        result.put(
                "boundary",
                "Before/after recipe classes in one JVM; alternating order; same inputs, frontend and clock; excludes events, networking and whole-server TPS");
        result.put("java", System.getProperty("java.version"));
        return result;
    }

    private static Map<String, Object> bench(Fixture f, java.lang.reflect.Constructor<?> baseline)
            throws Exception {
        var scenarios = new LinkedHashMap<String, Object>();
        var rich = new StringBuilder("material: DIAMOND_SWORD\nname: '&6Fixed equipment'\nlore:\n");
        for (int i = 0; i < 32; i++)
            rich.append("- '&7Attribute ")
                    .append(i)
                    .append(": &b+123.45 &8(stored equipment text)'\n");
        rich.append(
                "components: {enchantment_glint_override: true, rarity: epic}\noptions: {durability: 100}\n");
        List<String> names =
                List.of("plain", "rich-32-lines", "repeated-dynamic", "unique-dynamic");
        List<String> definitions =
                List.of(
                        "material: STONE\n",
                        rich.toString(),
                        "material: STONE\nname: '<label>'\n",
                        "material: STONE\nname: '<label>'\n");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int scenario = 0; scenario < names.size(); scenario++) {
            String id = names.get(scenario), yaml = definitions.get(scenario);
            NiPaperRecipe recipe = f.recipe(id, yaml);
            @SuppressWarnings("unchecked")
            ItemRecipe<ItemStack> before =
                    (ItemRecipe<ItemStack>)
                            baseline.newInstance(
                                    new NiCompiledItem(
                                            id, "memory/" + id, NiYaml.read(yaml, "memory/" + id)),
                                    f.nodes,
                                    f.scripts,
                                    f.host,
                                    f.clock,
                                    (java.util.function.Consumer<String>)
                                            message -> {
                                                throw new AssertionError(message);
                                            });
            boolean unique = scenario == 3;
            // Repeated calls admit the cache before comparing full components and custom data.
            for (int i = 0; i < 8; i++) {
                var input = Map.of("label", unique ? "compare-" + i : "same");
                if (!net.minecraft.world.item.ItemStack.matches(
                        CraftItemStack.asNMSCopy(f.create(before, input)),
                        CraftItemStack.asNMSCopy(f.create(recipe, input))))
                    throw new AssertionError(id + ": baseline payload differs");
            }
            int warmup = 5_000;
            for (int i = 0; i < warmup; i++) {
                var input = Map.of("label", unique ? "warmup-" + i : "same");
                sink = f.create(before, input);
                sink = f.create(recipe, input);
            }
            var oldSamples = new ArrayList<Sample>();
            var newSamples = new ArrayList<Sample>();
            int iterations = 5_000;
            for (int sample = 0; sample < 7; sample++) {
                if (sample % 2 == 0) {
                    oldSamples.add(measure(f, before, bean, thread, unique, sample, iterations));
                    newSamples.add(measure(f, recipe, bean, thread, unique, sample, iterations));
                } else {
                    newSamples.add(measure(f, recipe, bean, thread, unique, sample, iterations));
                    oldSamples.add(measure(f, before, bean, thread, unique, sample, iterations));
                }
            }
            scenarios.put(
                    id,
                    Map.of(
                            "warmupPerImplementation",
                            warmup,
                            "iterationsPerSample",
                            iterations,
                            "samplesPerImplementation",
                            7,
                            "matchingPayloads",
                            8,
                            "before",
                            oldSamples,
                            "after",
                            newSamples));
        }
        sink = null;
        return scenarios;
    }

    private record Sample(long nsPerItem, long allocatedBytesPerItem) {}

    private static Sample measure(
            Fixture f,
            ItemRecipe<ItemStack> recipe,
            com.sun.management.ThreadMXBean bean,
            long thread,
            boolean unique,
            int sample,
            int iterations) {
        long allocated = bean.getThreadAllocatedBytes(thread), started = System.nanoTime();
        for (int i = 0; i < iterations; i++)
            sink =
                    f.create(
                            recipe,
                            Map.of("label", unique ? "sample-" + sample + "-" + i : "same"));
        return new Sample(
                (System.nanoTime() - started) / iterations,
                (bean.getThreadAllocatedBytes(thread) - allocated) / iterations);
    }

    /** Override just the old recipe and its nested types; both paths share actual runtime dependencies. */
    private static final class BaselineLoader extends URLClassLoader {
        BaselineLoader(Path path) throws java.net.MalformedURLException {
            super(new java.net.URL[] {path.toUri().toURL()}, NiPaperRecipe.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(NiPaperRecipe.class.getName())
                    && !name.startsWith(NiPaperRecipe.class.getName() + "$"))
                return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> value = findLoadedClass(name);
                if (value == null) value = findClass(name);
                if (resolve) resolveClass(value);
                return value;
            }
        }
    }

    private static Object cached(NiPaperRecipe recipe) throws Exception {
        var field = NiPaperRecipe.class.getDeclaredField("appearance");
        field.setAccessible(true);
        return field.get(recipe);
    }

    private static void check(List<String> c, boolean valid, String text) {
        if (!valid) throw new AssertionError(text);
        c.add(text);
    }

    private static final class MutableClock extends Clock {
        long time = 1_000;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(time);
        }

        @Override
        public long millis() {
            return time;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final NiScripts scripts = new NiScripts(Map.of(), Map.of());
        final NiNodes nodes = new NiNodes();
        final MutableClock clock = new MutableClock();
        final AtomicInteger evaluations = new AtomicInteger();
        final NiEvaluation.Host host =
                new NiEvaluation.Host() {
                    @Override
                    public String placeholder(Object viewer, String text) {
                        evaluations.incrementAndGet();
                        return text;
                    }

                    @Override
                    public String itemValue(String key, String parameters) {
                        return null;
                    }

                    @Override
                    public void check(Object actions, NiEvaluation evaluation, String value) {}
                };

        NiPaperRecipe recipe(String id, String yaml) {
            NiConfig config = NiYaml.read(yaml, "memory/" + id);
            return new NiPaperRecipe(
                    new NiCompiledItem(id, "memory/" + id, config),
                    nodes,
                    scripts,
                    host,
                    clock,
                    message -> {
                        throw new AssertionError("Unexpected compilation warning: " + message);
                    });
        }

        ItemStack create(ItemRecipe<ItemStack> recipe, Map<String, String> rolls) {
            return recipe.create(
                    new GenerationContext(rolls, java.util.concurrent.ThreadLocalRandom.current()));
        }

        @Override
        public void close() {
            scripts.close();
        }
    }
}
