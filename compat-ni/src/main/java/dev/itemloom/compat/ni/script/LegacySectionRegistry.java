package dev.itemloom.compat.ni.script;

import dev.itemloom.compat.ni.NiNodes;
import java.util.*;

public final class LegacySectionRegistry {
    private final NiNodes nodes;
    private final Map<String, LegacyCustomSection> parsers = new LinkedHashMap<>();
    private final Map<String, LegacyCustomSection> view = Collections.unmodifiableMap(parsers);

    public LegacySectionRegistry(NiNodes nodes) {
        this.nodes = nodes;
    }

    public void loadParser(LegacyCustomSection parser) {
        parsers.put(parser.getId(), parser);
        nodes.register(parser.getId(), parser);
    }

    public Map<String, LegacyCustomSection> getSectionParsers() {
        return view;
    }
}
