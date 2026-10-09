package dev.itemloom.paper.compat.script;

import java.io.File;
import java.util.Set;
import dev.itemloom.paper.compat.NiItemOperations;
import org.bukkit.configuration.ConfigurationSection;
import org.openjdk.nashorn.api.scripting.AbstractJSObject;

/** Public old constructor names capture their importing revision, never a global active one. */
public final class LegacyItemConstructors {
    private LegacyItemConstructors() {}

    public static AbstractJSObject pack(NiItemOperations owner) {
        var info = packItemInfo(owner);
        return new Constructor(LegacyItemPack.class) {
            @Override
            public Object newObject(Object... args) {
                owner.ensureActive();
                if (args.length != 2
                        || !(args[0] instanceof CharSequence)
                        || !(args[1] instanceof ConfigurationSection config))
                    throw new IllegalArgumentException(
                            "ItemPack requires id and ConfigurationSection");
                return new LegacyItemPack(owner, args[0].toString(), config);
            }

            @Override
            public boolean hasMember(String name) {
                return name.equals("ItemInfo") || super.hasMember(name);
            }

            @Override
            public Object getMember(String name) {
                return name.equals("ItemInfo") ? info : super.getMember(name);
            }
        };
    }

    public static AbstractJSObject packItemInfo(NiItemOperations owner) {
        return new Constructor(LegacyItemPack.ItemInfo.class) {
            @Override
            public Object newObject(Object... args) {
                owner.ensureActive();
                if (args.length != 1 || !(args[0] instanceof CharSequence))
                    throw new IllegalArgumentException("ItemPack.ItemInfo requires one string");
                return new LegacyItemPack.ItemInfo(owner, args[0].toString());
            }
        };
    }

    public static AbstractJSObject config(NiItemOperations owner) {
        return new Constructor(LegacyItemConfig.class) {
            @Override
            public Object newObject(Object... args) {
                owner.ensureActive();
                if ((args.length != 2 && args.length != 3)
                        || !(args[0] instanceof CharSequence)
                        || args[1] != null && !(args[1] instanceof File))
                    throw new IllegalArgumentException(
                            "ItemConfig requires id, File and optional containing ConfigurationSection");
                if (args.length == 2)
                    return new LegacyItemConfig(args[0].toString(), (File) args[1]);
                if (!(args[2] instanceof ConfigurationSection config))
                    throw new IllegalArgumentException(
                            "ItemConfig third argument must be a containing ConfigurationSection");
                return new LegacyItemConfig(args[0].toString(), (File) args[1], config);
            }
        };
    }

    public static AbstractJSObject generator(NiItemOperations owner) {
        return new Constructor(LegacyItemGenerator.class) {
            @Override
            public Object newObject(Object... args) {
                owner.ensureActive();
                if (args.length != 1 || !(args[0] instanceof LegacyItemConfig config))
                    throw new IllegalArgumentException("ItemGenerator requires one ItemConfig");
                return owner.catalog().compile(config);
            }
        };
    }

    public static AbstractJSObject configManager(NiItemOperations owner) {
        return new Constructor(LegacyItemConfigManager.class) {
            @Override
            public Object newObject(Object... args) {
                owner.ensureActive();
                var plugin = owner.catalog().plugin();
                var root = owner.catalog().inputRoot();
                String dir = "Items";
                if (args.length == 1 && args[0] instanceof CharSequence text) dir = text.toString();
                else if (args.length >= 1
                        && args.length <= 2
                        && args[0] instanceof org.bukkit.plugin.java.JavaPlugin explicit) {
                    plugin = explicit;
                    root = explicit.getDataFolder().toPath();
                    if (args.length == 2) {
                        if (!(args[1] instanceof CharSequence))
                            throw new IllegalArgumentException(
                                    "ItemConfigManager directory must be a string");
                        dir = args[1].toString();
                    }
                } else if (args.length != 0) {
                    throw new IllegalArgumentException(
                            "ItemConfigManager requires optional JavaPlugin and/or directory");
                }
                return new LegacyItemConfigManager(owner, plugin, root, dir);
            }
        };
    }

    private abstract static class Constructor extends AbstractJSObject {
        private final Class<?> type;

        Constructor(Class<?> type) {
            this.type = type;
        }

        @Override
        public boolean isFunction() {
            return true;
        }

        @Override
        public boolean isInstance(Object instance) {
            return type.isInstance(instance);
        }

        @Override
        public boolean hasMember(String name) {
            return "class".equals(name);
        }

        @Override
        public Object getMember(String name) {
            return "class".equals(name) ? type : null;
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
}
