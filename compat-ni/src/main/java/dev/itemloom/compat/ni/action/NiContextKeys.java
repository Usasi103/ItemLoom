package dev.itemloom.compat.ni.action;

import java.util.Map;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;

public final class NiContextKeys {
    public static final NiContextKey<ItemStack> ITEM_STACK = new NiContextKey<>("itemStack");
    public static final NiContextKey<Object> NBT = new NiContextKey<>("nbt", "itemTag");
    public static final NiContextKey<Object> ITEM_INFO = new NiContextKey<>("itemInfo");
    public static final NiContextKey<Map<String, String>> DATA = new NiContextKey<>("data");
    public static final NiContextKey<Event> EVENT = new NiContextKey<>("event");
    public static final NiContextKey<Map<String, Object>> SECTION_CACHE =
            new NiContextKey<>("cache");
    public static final NiContextKey<Object> SECTIONS = new NiContextKey<>("sections");
    public static final NiContextKey<Void> PAPI_ENVIRONMENT =
            new NiContextKey<>("papi-environment");

    private NiContextKeys() {}
}
