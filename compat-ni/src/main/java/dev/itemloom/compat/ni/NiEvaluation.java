package dev.itemloom.compat.ni;

import dev.itemloom.core.GenerationContext;
import java.util.*;
import java.util.function.*;
import org.bukkit.inventory.ItemStack;

public final class NiEvaluation {
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
                ItemStack item,
                Object nbt,
                Map<String, String> rolls) {
            throw new UnsupportedOperationException();
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

    private static final ThreadLocal<NiEvaluation> CURRENT = new ThreadLocal<>();
    private final GenerationContext generation;
    private final NiConfig sections;
    private final Object player;
    private final Mode mode;
    private final NiNodes nodes;
    private final NiScripts scripts;
    private final Host host;
    private final Map<String, Object> actionCache;
    private final boolean caching;

    public NiEvaluation(
            GenerationContext generation,
            NiConfig sections,
            Object player,
            Mode mode,
            NiNodes nodes,
            NiScripts scripts,
            Host host) {
        this(generation, sections, player, mode, nodes, scripts, host, null, true);
    }

    private NiEvaluation(
            GenerationContext generation,
            NiConfig sections,
            Object player,
            Mode mode,
            NiNodes nodes,
            NiScripts scripts,
            Host host,
            Map<String, Object> actionCache,
            boolean caching) {
        this.generation = generation;
        this.sections = sections == null ? new NiConfig(Map.of()) : sections;
        this.player = player;
        this.mode = mode;
        this.nodes = nodes;
        this.scripts = scripts;
        this.host = host;
        this.actionCache = actionCache;
        this.caching = caching;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<String, String> legacyCache() {
        return caching ? (Map) cache() : null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
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
        int separator = expression.indexOf("::");
        if (separator >= 0)
            return nodes.inline(
                    expression.substring(0, separator), expression.substring(separator + 2), this);
        if (cache().containsKey(expression)) return String.valueOf(cache().get(expression));
        if (sections.contains(expression)) {
            String result =
                    generation.resolve(
                            expression, () -> uncached(expression), caching && actionCache == null);
            if (actionCache != null && result != null) actionCache.put(expression, result);
            return result;
        }
        if (expression.startsWith("#")) {
            try {
                return NiTextNodes.color(Integer.parseInt(expression.substring(1), 16));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public static NiEvaluation current() {
        NiEvaluation value = CURRENT.get();
        if (value == null) throw new IllegalStateException("No active evaluation");
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
        GenerationContext rolls =
                cache == generation.rolls()
                        ? generation
                        : new GenerationContext(
                                cache == null ? Map.of() : cache, generation.random());
        return new NiEvaluation(
                rolls, sections, player, Mode.SECTION, nodes, scripts, host, null, cache != null);
    }

    public String configured(NiConfig config) {
        return scoped(() -> nodes.configured(config, this));
    }

    public String uncached(String name) {
        NiConfig config = sections.section(name);
        return config == null ? text(sections.string(name)) : configured(config);
    }

    public NiEvaluation withMode(Mode next) {
        return new NiEvaluation(
                generation, sections, player, next, nodes, scripts, host, actionCache, caching);
    }

    public NiEvaluation withActionCache(Map<String, Object> cache) {
        return new NiEvaluation(
                generation, sections, player, Mode.ACTION, nodes, scripts, host, cache, caching);
    }

    public NiEvaluation withSections(NiConfig sections) {
        return new NiEvaluation(
                generation, sections, player, mode, nodes, scripts, host, actionCache, caching);
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
        Map<String, Object> result = new HashMap<>();
        result.put("vars", (Function<String, String>) this::text);
        if (player != null) {
            result.put("player", player);
            result.put("papi", (Function<String, String>) value -> host.placeholder(player, value));
        }
        return result;
    }
}
