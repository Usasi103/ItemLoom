package dev.itemloom.compat.ni.script;

import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.action.*;
import java.util.*;
import java.util.function.Consumer;
import javax.script.*;
import jdk.dynalink.beans.StaticClass;

public final class LegacyScriptBindings implements Consumer<ScriptEngine> {
    private static final String PREFIX = "pers.neige.neigeitems.";
    private final LegacySectionRegistry registry;
    private final Map<String, Object> aliases = new LinkedHashMap<>();
    private final Map<String, Object> globals = new LinkedHashMap<>();

    public LegacyScriptBindings(NiNodes nodes) {
        registry = new LegacySectionRegistry(nodes);
    }

    public synchronized void alias(String className, Object adapter) {
        aliases.put(className, Objects.requireNonNull(adapter));
    }

    public synchronized void global(String name, Object adapter) {
        globals.put(name, Objects.requireNonNull(adapter));
    }

    public synchronized void actionManager(LegacyActionManager manager) {
        alias(PREFIX + "manager.ActionManager", Map.of("INSTANCE", manager));
        global("ActionManager", manager);
        global("manager", manager);
    }

    public static final class NamespaceNode {
        private final String qualified;
        private final Map<String, NamespaceNode> children;

        private NamespaceNode(String qualified, Map<String, NamespaceNode> children) {
            this.qualified = qualified;
            this.children = Collections.unmodifiableMap(new LinkedHashMap<>(children));
        }

        public String getQualified() {
            return qualified;
        }

        public Map<String, NamespaceNode> getChildren() {
            return children;
        }
    }

    private static final class Names {
        final String path;
        final Map<String, Names> children = new LinkedHashMap<>();

        Names(String path) {
            this.path = path;
        }

        NamespaceNode freeze() {
            Map<String, NamespaceNode> result = new LinkedHashMap<>();
            children.forEach((name, child) -> result.put(name, child.freeze()));
            return new NamespaceNode(path, result);
        }
    }

    private static NamespaceNode tree(Set<String> names) {
        Names root = new Names("");
        for (String qualified : names) {
            Names cursor = root;
            for (String segment : qualified.split("\\.")) {
                String path = cursor.path.isEmpty() ? segment : cursor.path + "." + segment;
                cursor = cursor.children.computeIfAbsent(segment, ignored -> new Names(path));
            }
        }
        return root.freeze();
    }

    private static void builtin(Map<String, Object> target, String name, Class<?> type) {
        target.put(PREFIX + name, StaticClass.forClass(type));
    }

    @Override
    public synchronized void accept(ScriptEngine engine) {
        Map<String, Object> names = new LinkedHashMap<>(aliases);
        builtin(names, "section.impl.CustomSection", LegacyCustomSection.class);
        builtin(names, "section.Section", LegacySection.class);
        builtin(names, "utils.SectionUtils", LegacySectionUtils.class);
        builtin(names, "config.ConfigReader", LegacyConfigReader.class);
        builtin(names, "config.BukkitConfigReader", LegacyConfigReader.BukkitReader.class);
        builtin(names, "config.MapConfigReader", LegacyConfigReader.MapReader.class);
        builtin(names, "action.ActionContext", NiActionContext.class);
        builtin(names, "action.ContextKey", NiContextKey.class);
        builtin(names, "action.ContextKeys", NiContextKeys.class);
        builtin(names, "action.ActionResult", LegacyActionResult.class);
        builtin(names, "action.ResultType", LegacyActionResult.Type.class);
        builtin(names, "action.result.Results", LegacyActionResult.Results.class);
        builtin(names, "action.result.StopResult", LegacyActionResult.Stop.class);
        builtin(names, "action.result.SuccessResult", LegacyActionResult.Success.class);
        engine.getBindings(ScriptContext.ENGINE_SCOPE).putAll(globals);
        engine.put("ActionContext", StaticClass.forClass(NiActionContext.class));
        engine.put("ContextKeys", StaticClass.forClass(NiContextKeys.class));
        engine.put("Results", StaticClass.forClass(LegacyActionResult.Results.class));
        engine.put("StopResult", StaticClass.forClass(LegacyActionResult.Stop.class));
        engine.put("SuccessResult", StaticClass.forClass(LegacyActionResult.Success.class));
        try {
            engine.put("__itemloom_section_registry", registry);
            names.put(
                    PREFIX + "manager.SectionManager",
                    engine.eval("({ INSTANCE: __itemloom_section_registry })"));
            engine.put("__itemloom_aliases", names);
            engine.put("__itemloom_alias_tree", tree(names.keySet()));
            engine.eval(
                    """
                    (function(aliases, tree, nativePackages, nativeType) {
                        function packageView(node, nativePackage) {
                            var namespace = {};
                            var entries = node.getChildren().entrySet().iterator();
                            while (entries.hasNext()) {
                                var entry = entries.next();
                                var child = entry.getValue();
                                var qualified = child.getQualified();
                                Object.defineProperty(namespace, entry.getKey(), {
                                    value: aliases.containsKey(qualified)
                                        ? aliases.get(qualified)
                                        : packageView(child, nativePackage[entry.getKey()]),
                                    enumerable: true
                                });
                            }
                            Object.defineProperty(namespace, '__noSuchProperty__', {
                                value: function(name) { return nativePackage[name]; }
                            });
                            return namespace;
                        }
                        Java.type = function(name) {
                            var key = String(name);
                            return aliases.containsKey(key) ? aliases.get(key) : nativeType(key);
                        };
                        Packages = packageView(tree, nativePackages);
                    })(__itemloom_aliases, __itemloom_alias_tree, Packages, Java.type);
                    """);
        } catch (ScriptException | RuntimeException error) {
            throw new IllegalStateException(
                    "Script namespace compatibility: " + error.getMessage(), error);
        } finally {
            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            bindings.remove("__itemloom_section_registry");
            bindings.remove("__itemloom_aliases");
            bindings.remove("__itemloom_alias_tree");
        }
    }
}
