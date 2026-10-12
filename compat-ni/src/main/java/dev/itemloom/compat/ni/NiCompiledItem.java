package dev.itemloom.compat.ni;

public final class NiCompiledItem {
    private final String id;
    private final String diagnostic;
    private final NiConfig definition;
    private final NiTemplate output;
    private String previous;
    private NiConfig promoted;

    public NiCompiledItem(String id, String source, NiConfig definition) {
        this.id = id;
        this.definition = definition;
        diagnostic = source + " / " + id + " generated YAML";
        output =
                NiTemplate.compile(
                        NiYaml.write(
                                definition.without(
                                        "sections",
                                        "client_bound_data",
                                        "static",
                                        "options.update",
                                        "event",
                                        "options.id-section")));
    }

    public String id() {
        return id;
    }

    public NiConfig definition() {
        return definition;
    }

    public synchronized boolean reusable(NiConfig config) {
        return promoted != null && promoted == config;
    }

    public NiConfig expand(NiEvaluation evaluation) {
        String key = definition.string("options.id-section");
        if (key != null) evaluation.generation().rolls().put(key, id);
        String rendered = output.render(evaluation::value);
        synchronized (this) {
            if (rendered.length() > 16384) {
                previous = null;
                promoted = null;
                return NiYaml.readGenerated(rendered, diagnostic);
            }
            if (rendered.equals(previous) && promoted != null) return promoted;
            NiConfig parsed = NiYaml.readGenerated(rendered, diagnostic);
            promoted = rendered.equals(previous) ? parsed : null;
            previous = rendered;
            return parsed;
        }
    }
}
