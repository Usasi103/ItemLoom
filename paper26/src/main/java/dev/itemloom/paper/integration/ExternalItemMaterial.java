package dev.itemloom.paper.integration;

import java.util.Locale;
import java.util.Set;

public record ExternalItemMaterial(String provider, String id) {
    private static final String PREFIX = "itembridge:";
    private static final Set<String> LOCAL =
            Set.of("ni", "neigeitems", "sx", "sxitem", "sx-item", "itemloom");

    public ExternalItemMaterial {
        if (provider == null || provider.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("外部物品必须指定 provider 和 id");
        }
        provider = provider.strip().toLowerCase(Locale.ROOT);
        id = id.strip();
        if (provider.indexOf(':') >= 0) throw new IllegalArgumentException("provider 不能包含冒号");
        requireAllowed(provider);
    }

    public static boolean isExternal(String text) {
        return text != null && text.strip().regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    public static ExternalItemMaterial parse(String text) {
        if (!isExternal(text)) throw new IllegalArgumentException("外部材质格式: itembridge:provider:id");
        String body = text.strip().substring(PREFIX.length());
        int separator = body.indexOf(':');
        if (separator < 0) throw new IllegalArgumentException("外部材质格式: itembridge:provider:id");
        return new ExternalItemMaterial(
                body.substring(0, separator), body.substring(separator + 1));
    }

    static boolean allowed(String provider) {
        return provider != null && !LOCAL.contains(provider.strip().toLowerCase(Locale.ROOT));
    }

    static void requireAllowed(String provider) {
        if (!allowed(provider))
            throw new IllegalArgumentException("ItemBridge source is disabled: " + provider);
    }
}
