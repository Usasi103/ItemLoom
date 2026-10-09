package dev.itemloom.compat.ni.script;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.itemloom.compat.ni.NiNodes;

/** Each configuration revision receives its own legacy registration target. */
public final class LegacySectionRegistry {
    private final NiNodes nodes;
    private final Map<String, LegacyCustomSection> registered = new LinkedHashMap<>();

    public LegacySectionRegistry(NiNodes nodes) {
        this.nodes = nodes;
    }

    public void loadParser(LegacyCustomSection parser) {
        registered.put(parser.getId(), parser);
        nodes.register(parser.getId(), parser);
    }

    public Map<String, LegacyCustomSection> getSectionParsers() {
        return Collections.unmodifiableMap(registered);
    }
}
