package dev.itemloom.compat.ni;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves inheritance before templates execute; global imports override local nodes as NI does. */
public final class NiInheritance {
    private final NiRepository.Input input;
    private final Map<String, NiConfig> inherited = new LinkedHashMap<>();
    private final Deque<String> resolving = new ArrayDeque<>();

    public NiInheritance(NiRepository.Input input) {
        this.input = input;
    }

    public Map<String, NiConfig> resolveAll() {
        Map<String, NiConfig> result = new LinkedHashMap<>();
        input.items().keySet().forEach(id -> result.put(id, resolve(id)));
        return java.util.Collections.unmodifiableMap(result);
    }

    public NiConfig resolve(String id) {
        return globals(inherit(id), true);
    }

    /** An unregistered root inherits from the original registry, even when it has the same id. */
    public NiConfig resolveRoot(NiConfig root) {
        return globals(inheritSource(root, "dynamic item"), true);
    }

    private NiConfig inherit(String id) {
        NiConfig previous = inherited.get(id);
        if (previous != null) return previous;
        NiRepository.Definition definition = input.items().get(id);
        if (definition == null) throw new IllegalArgumentException("Missing inherited item: " + id);
        if (resolving.contains(id)) {
            throw new IllegalArgumentException(
                    definition.source()
                            + ": cyclic inheritance "
                            + String.join(" -> ", resolving)
                            + " -> "
                            + id);
        }
        resolving.addLast(id);
        try {
            NiConfig result = inheritSource(definition.config(), definition.source() + ": " + id);
            inherited.put(id, result);
            return result;
        } finally {
            resolving.removeLast();
        }
    }

    private NiConfig inheritSource(NiConfig source, String description) {
        NiConfig result = new NiConfig(Map.of());
        Object parents = source.get("inherit");
        if (parents instanceof String parent) result = parent(parent);
        else if (parents instanceof List<?> list) {
            for (Object parent : list) {
                if (!(parent instanceof String name))
                    throw new IllegalArgumentException(
                            description + ".inherit must contain item ids");
                result = result.overlay(parent(name));
            }
        } else if (parents instanceof Map<?, ?> map) {
            result = partial(result, "", map);
        }
        return result.overlay(source);
    }

    private NiConfig parent(String id) {
        return globals(inherit(id), false);
    }

    private NiConfig partial(NiConfig target, String prefix, Map<?, ?> mappings) {
        for (Map.Entry<?, ?> entry : mappings.entrySet()) {
            String path =
                    prefix.isEmpty() ? entry.getKey().toString() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof String parent) {
                Object selected = parent(parent).get(path);
                if (selected != null) target = target.with(path, selected);
            } else if (entry.getValue() instanceof Map<?, ?> nested)
                target = partial(target, path, nested);
        }
        return target;
    }

    private NiConfig globals(NiConfig config, boolean removeImports) {
        return globals(config, removeImports, true);
    }

    /** Packs import global nodes without item inheritance; NI ignores missing pack imports. */
    public NiConfig packGlobals(NiConfig config) {
        return globals(config, true, false);
    }

    private NiConfig globals(NiConfig config, boolean removeImports, boolean strict) {
        for (String id : config.strings("globalsections")) {
            NiConfig file = input.globalFiles().get(id.replace('\\', '/'));
            if (file != null) {
                for (String key : file.keys())
                    config = config.with("sections." + key, file.get(key));
            } else {
                Object value = input.globalValues().get(id);
                if (value == null) {
                    if (strict) throw new IllegalArgumentException("Missing global section: " + id);
                    continue;
                }
                config = config.with("sections." + id, value);
            }
        }
        return removeImports ? config.without("globalsections") : config;
    }
}
