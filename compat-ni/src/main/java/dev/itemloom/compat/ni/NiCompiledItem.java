package dev.itemloom.compat.ni;

/** Immutable generation input plus a bounded cache of detached expanded YAML documents. */
public final class NiCompiledItem {
    private static final int MAX_CACHE_CHARACTERS = 16 * 1024;

    private record Expanded(String source, NiConfig config) {}

    private final String id;
    private final String source;
    private final NiConfig definition;
    private final NiTemplate template;
    private volatile Expanded expanded;
    private volatile String candidate;

    public NiCompiledItem(String id, String source, NiConfig definition) {
        this.id = id;
        this.source = source;
        this.definition = definition;
        NiConfig dynamic =
                definition.without(
                        "sections",
                        "client_bound_data",
                        "static",
                        "options.update",
                        "event",
                        "options.id-section");
        template = NiTemplate.compile(NiYaml.write(dynamic));
    }

    public String id() {
        return id;
    }

    public NiConfig definition() {
        return definition;
    }

    /** Only a repeated, bounded document is eligible for a platform's compiled appearance cache. */
    public boolean reusable(NiConfig config) {
        Expanded current = expanded;
        return current != null && current.config() == config;
    }

    public NiConfig expand(NiEvaluation evaluation) {
        String idSection = definition.string("options.id-section");
        if (idSection != null) evaluation.generation().rolls().put(idSection, id);
        String yaml = template.render(evaluation::value);
        Expanded cached = expanded;
        if (cached != null && cached.source().equals(yaml)) return cached.config();
        NiConfig result = NiYaml.readGenerated(yaml, source + " / " + id + " generated YAML");
        // Keep at most one small document after a repeated input. Randomized equipment does
        // not accumulate 32 detached trees per definition; node evaluation always runs first.
        if (yaml.length() <= MAX_CACHE_CHARACTERS) {
            String previous = candidate;
            candidate = yaml;
            expanded = yaml.equals(previous) ? new Expanded(yaml, result) : null;
        } else {
            candidate = null;
            expanded = null;
        }
        return result;
    }
}
