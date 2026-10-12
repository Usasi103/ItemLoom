package dev.itemloom.compat.ni;

import java.util.*;

public final class NiInheritance {
    private final NiRepository.Input input;
    private final Map<String, NiConfig> resolved = new LinkedHashMap<>();
    private final LinkedHashSet<String> visiting = new LinkedHashSet<>();

    public NiInheritance(NiRepository.Input input) {
        this.input = input;
    }

    public Map<String, NiConfig> resolveAll() {
        Map<String, NiConfig> result = new LinkedHashMap<>();
        for (String id : input.items().keySet()) result.put(id, resolve(id));
        return Collections.unmodifiableMap(result);
    }

    public NiConfig resolve(String id) {
        if (resolved.containsKey(id)) return resolved.get(id).without("globalsections");
        NiRepository.Definition item = input.items().get(id);
        if (item == null) throw new IllegalArgumentException("Missing parent item: " + id);
        if (!visiting.add(id))
            throw new IllegalArgumentException(
                    item.source() + ": " + String.join(" -> ", visiting) + " -> " + id);
        try {
            NiConfig result = root(item.config(), item.source() + " / " + id);
            resolved.put(id, result);
            return result.without("globalsections");
        } finally {
            visiting.remove(id);
        }
    }

    public NiConfig resolveRoot(NiConfig root) {
        return root(root, "root").without("globalsections");
    }

    private NiConfig parent(String id) {
        resolve(id);
        return resolved.get(id);
    }

    private NiConfig root(NiConfig child, String source) {
        NiConfig base = new NiConfig(Map.of());
        Object parents = child.get("inherit");
        if (parents instanceof String id) base = base.overlay(parent(id));
        else if (parents instanceof List<?> list)
            for (Object parent : list) {
                if (!(parent instanceof String id))
                    throw new IllegalArgumentException(source + ".inherit must contain item ids");
                base = base.overlay(parent(id));
            }
        else if (parents instanceof Map<?, ?> map) base = selected(base, map, "");
        return imports(base.overlay(child), true, false);
    }

    private NiConfig selected(NiConfig target, Map<?, ?> paths, String prefix) {
        for (var entry : paths.entrySet()) {
            String path =
                    prefix.isEmpty()
                            ? String.valueOf(entry.getKey())
                            : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> map) target = selected(target, map, path);
            else if (entry.getValue() instanceof String id) {
                Object value = parent(id).get(path);
                if (value != null) target = target.with(path, value);
            }
        }
        return target;
    }

    private NiConfig imports(NiConfig config, boolean strict, boolean strip) {
        NiConfig result = config;
        for (String id : config.strings("globalsections")) {
            NiConfig file = input.globalFiles().get(id.replace('\\', '/'));
            if (file != null)
                for (var entry : file.values().entrySet())
                    result = result.with("sections." + entry.getKey(), entry.getValue());
            else if (input.globalValues().containsKey(id))
                result = result.with("sections." + id, input.globalValues().get(id));
            else if (strict) throw new IllegalArgumentException("Missing global section: " + id);
        }
        return strip ? result.without("globalsections") : result;
    }

    public NiConfig packGlobals(NiConfig config) {
        return imports(config, false, true);
    }
}
