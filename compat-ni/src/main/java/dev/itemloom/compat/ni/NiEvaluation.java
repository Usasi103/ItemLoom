package dev.itemloom.compat.ni;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import dev.itemloom.core.GenerationContext;

/** Per-call language context; reusable definitions and engines hold none of this mutable state. */
public final class NiEvaluation {
    private static final ThreadLocal<NiEvaluation> CURRENT = new ThreadLocal<>();

    public enum Mode {
        ACTION,
        SECTION
    }

    public interface Host {
        String placeholder(Object player, String parameters);

        String itemValue(String key, String parameters);

        default String legacyItemValue(
                String key,
                String parameters,
                org.bukkit.inventory.ItemStack item,
                Object nbt,
                Map<String, String> rolls) {
            throw new UnsupportedOperationException("This evaluation has no item host");
        }

        void check(Object actions, NiEvaluation evaluation, String value);

        default void warning(String message) {}

        default boolean papiJavascript() {
            return false;
        }

        default boolean papiRegex() {
            return false;
        }
    }

    private final GenerationContext generation;
    private final NiConfig sections;
    private final Object player;
    private final Mode mode;
    private final NiNodes nodes;
    private final NiScripts scripts;
    private final Host host;
    private final boolean cacheEnabled;
    private Map<String, Object> actionCache;

    public NiEvaluation(
            GenerationContext generation,
            NiConfig sections,
            Object player,
            Mode mode,
            NiNodes nodes,
            NiScripts scripts,
            Host host) {
        this(generation, sections, player, mode, nodes, scripts, host, true);
    }

    private NiEvaluation(
            GenerationContext generation,
            NiConfig sections,
            Object player,
            Mode mode,
            NiNodes nodes,
            NiScripts scripts,
            Host host,
            boolean cacheEnabled) {
        this.generation = generation;
        this.sections = sections == null ? new NiConfig(Map.of()) : sections;
        this.player = player;
        this.mode = mode;
        this.nodes = nodes;
        this.scripts = scripts;
        this.host = host;
        this.cacheEnabled = cacheEnabled;
    }

    public GenerationContext generation() {
        return generation;
    }

    public NiConfig sections() {
        return sections;
    }

    public Object player() {
        return player;
    }

    public Mode mode() {
        return mode;
    }

    public NiScripts scripts() {
        return scripts;
    }

    public Host host() {
        return host;
    }

    public void warning(String message) {
        if (host != null) host.warning(message);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, String> legacyCache() {
        return cacheEnabled ? (Map) cache() : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, Object> cache() {
        return actionCache == null ? (Map) generation.rolls() : actionCache;
    }

    public String text(String value) {
        return value == null ? null : nodes.template(value).render(this::value);
    }

    public String value(String expression) {
        return scoped(() -> resolve(expression));
    }

    private String resolve(String expression) {
        int delimiter = expression.indexOf("::");
        if (delimiter >= 0)
            return nodes.inline(
                    expression.substring(0, delimiter), expression.substring(delimiter + 2), this);
        if (actionCache != null && actionCache.containsKey(expression))
            return String.valueOf(actionCache.get(expression));
        if (actionCache == null && generation.rolls().containsKey(expression))
            return String.valueOf(generation.rolls().get(expression));
        if (sections.contains(expression)) {
            String result =
                    generation.resolve(
                            expression,
                            () -> uncached(expression),
                            cacheEnabled && actionCache == null);
            if (actionCache != null && result != null) actionCache.put(expression, result);
            return result;
        }
        if (expression.startsWith("#")) {
            try {
                int rgb = Integer.parseInt(expression.substring(1), 16);
                return NiTextNodes.color(rgb);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static NiEvaluation current() {
        NiEvaluation value = CURRENT.get();
        if (value == null)
            throw new IllegalStateException(
                    "Legacy script node call requires an active item evaluation");
        return value;
    }

    public <T> T scoped(Supplier<T> operation) {
        NiEvaluation previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return operation.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public NiEvaluation legacyContext(Map<String, String> cache, Object player, NiConfig sections) {
        GenerationContext child =
                cache == generation.rolls()
                        ? generation
                        : new GenerationContext(
                                cache == null ? Map.of() : cache, generation.random());
        return new NiEvaluation(
                child, sections, player, Mode.SECTION, nodes, scripts, host, cache != null);
    }

    public String configured(NiConfig config) {
        return scoped(() -> nodes.configured(config, this));
    }

    public String uncached(String name) {
        NiConfig node = sections.section(name);
        return node == null ? text(sections.string(name)) : nodes.configured(node, this);
    }

    public NiEvaluation withMode(Mode next) {
        NiEvaluation result =
                new NiEvaluation(
                        generation, sections, player, next, nodes, scripts, host, cacheEnabled);
        result.actionCache = actionCache;
        return result;
    }

    public NiEvaluation withActionCache(Map<String, Object> cache) {
        NiEvaluation result = withMode(Mode.ACTION);
        result.actionCache = cache;
        return result;
    }

    public NiEvaluation withSections(NiConfig sections) {
        NiEvaluation result =
                new NiEvaluation(
                        generation, sections, player, mode, nodes, scripts, host, cacheEnabled);
        result.actionCache = actionCache;
        return result;
    }

    public NiEvaluation fresh() {
        return new NiEvaluation(
                new GenerationContext(Map.of(), generation.random()),
                sections,
                player,
                mode,
                nodes,
                scripts,
                host);
    }

    public NiEvaluation action(Object caster) {
        return new NiEvaluation(
                new GenerationContext(Map.of(), generation.random()),
                null,
                caster,
                Mode.ACTION,
                nodes,
                scripts,
                host);
    }

    public Map<String, Object> bindings() {
        Map<String, Object> values = new HashMap<>();
        if (player != null) {
            values.put("player", player);
            values.put("papi", (Function<String, String>) input -> host.placeholder(player, input));
        }
        values.put("vars", (Function<String, String>) this::text);
        return values;
    }
}
