package dev.itemloom.paper.compat.script;

import dev.itemloom.paper.compat.NiItemOperations;
import java.io.File;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.openjdk.nashorn.api.scripting.AbstractJSObject;

public final class LegacyItemConstructors {
    private LegacyItemConstructors() {}

    private static final class Constructor extends AbstractJSObject {
        private final NiItemOperations owner;
        private final Class<?> type;
        private final Function<Object[], Object> create;
        private final AbstractJSObject itemInfo;

        Constructor(
                NiItemOperations owner,
                Class<?> type,
                Function<Object[], Object> create,
                AbstractJSObject itemInfo) {
            this.owner = owner;
            this.type = type;
            this.create = create;
            this.itemInfo = itemInfo;
        }

        @Override
        public Object newObject(Object... args) {
            owner.ensureActive();
            return create.apply(args);
        }

        @Override
        public boolean isFunction() {
            return true;
        }

        @Override
        public boolean isInstance(Object value) {
            return type.isInstance(value);
        }

        @Override
        public boolean hasMember(String name) {
            return name.equals("class") || itemInfo != null && name.equals("ItemInfo");
        }

        @Override
        public Object getMember(String name) {
            return name.equals("class")
                    ? type
                    : itemInfo != null && name.equals("ItemInfo") ? itemInfo : null;
        }

        @Override
        public Set<String> keySet() {
            return Set.of("class");
        }

        @Override
        public String getClassName() {
            return type.getSimpleName();
        }
    }

    public static AbstractJSObject pack(NiItemOperations owner) {
        return new Constructor(
                owner,
                LegacyItemPack.class,
                args -> {
                    if (args.length != 2
                            || !(args[0] instanceof CharSequence id)
                            || !(args[1] instanceof ConfigurationSection config))
                        throw new IllegalArgumentException(
                                "ItemPack requires (String id, ConfigurationSection config)");
                    return new LegacyItemPack(owner, id.toString(), config);
                },
                packItemInfo(owner));
    }

    public static AbstractJSObject packItemInfo(NiItemOperations owner) {
        return new Constructor(
                owner,
                LegacyItemPack.ItemInfo.class,
                args -> {
                    if (args.length != 1 || !(args[0] instanceof CharSequence text))
                        throw new IllegalArgumentException("ItemInfo requires (String info)");
                    return new LegacyItemPack.ItemInfo(owner, text.toString());
                },
                null);
    }

    public static AbstractJSObject config(NiItemOperations owner) {
        return new Constructor(
                owner,
                LegacyItemConfig.class,
                args -> {
                    if ((args.length != 2 && args.length != 3)
                            || !(args[0] instanceof CharSequence)
                            || args[1] != null && !(args[1] instanceof File)
                            || args.length == 3 && !(args[2] instanceof ConfigurationSection))
                        throw new IllegalArgumentException(
                                "ItemConfig requires (String id, File file[, ConfigurationSection config])");
                    String id = args[0].toString();
                    File file = (File) args[1];
                    return args.length == 2
                            ? new LegacyItemConfig(id, file)
                            : new LegacyItemConfig(id, file, (ConfigurationSection) args[2]);
                },
                null);
    }

    public static AbstractJSObject generator(NiItemOperations owner) {
        return new Constructor(
                owner,
                LegacyItemGenerator.class,
                args -> {
                    if (args.length != 1 || !(args[0] instanceof LegacyItemConfig config))
                        throw new IllegalArgumentException(
                                "ItemGenerator requires (ItemConfig config)");
                    return owner.catalog().compile(config);
                },
                null);
    }

    public static AbstractJSObject configManager(NiItemOperations owner) {
        return new Constructor(
                owner,
                LegacyItemConfigManager.class,
                args -> {
                    JavaPlugin plugin = owner.catalog().plugin();
                    var root = owner.catalog().inputRoot();
                    String dir = "Items";
                    if (args.length == 1 && args[0] instanceof CharSequence text)
                        dir = text.toString();
                    else if (args.length >= 1
                            && args.length <= 2
                            && args[0] instanceof JavaPlugin explicit
                            && (args.length == 1 || args[1] instanceof CharSequence)) {
                        plugin = explicit;
                        root = explicit.getDataFolder().toPath();
                        if (args.length == 2) dir = args[1].toString();
                    } else if (args.length != 0)
                        throw new IllegalArgumentException(
                                "ItemConfigManager requires ([String dir]) or (JavaPlugin plugin[, String dir])");
                    return new LegacyItemConfigManager(owner, plugin, root, dir);
                },
                null);
    }
}
