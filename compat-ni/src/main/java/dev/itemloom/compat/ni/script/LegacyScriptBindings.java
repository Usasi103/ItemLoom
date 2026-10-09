package dev.itemloom.compat.ni.script;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import jdk.dynalink.beans.StaticClass;
import dev.itemloom.compat.ni.NiNodes;

/** Script namespace translation: no old-package classes are installed in any JVM class loader. */
public final class LegacyScriptBindings implements Consumer<ScriptEngine> {
    private final LegacySectionRegistry sections;
    private final Map<String, Object> registered = new HashMap<>();
    private final Map<String, Object> globals = new HashMap<>();
    private List<String> shape = List.of();
    private NamespaceNode tree;

    /** Immutable name structure only; actual adapters are taken from each invocation's aliases. */
    public static final class NamespaceNode {
        private final String qualified;
        private final Map<String, NamespaceNode> children;

        private NamespaceNode(Builder source) {
            qualified = source.qualified;
            Map<String, NamespaceNode> copy = new LinkedHashMap<>();
            source.children.forEach((name, child) -> copy.put(name, new NamespaceNode(child)));
            children = java.util.Collections.unmodifiableMap(copy);
        }

        public String getQualified() {
            return qualified;
        }

        public Map<String, NamespaceNode> getChildren() {
            return children;
        }
    }

    private static final class Builder {
        private String qualified;
        private final Map<String, Builder> children = new LinkedHashMap<>();
    }

    private NamespaceNode tree(Map<String, Object> aliases) {
        List<String> keys = new java.util.ArrayList<>(aliases.keySet());
        if (tree == null || !shape.equals(keys)) {
            Builder root = new Builder();
            for (String key : keys) {
                String qualified = String.valueOf(key);
                Builder node = root;
                for (String part : qualified.split("\\.", -1))
                    node = node.children.computeIfAbsent(part, unused -> new Builder());
                node.qualified = aliases.containsKey(qualified) ? qualified : null;
            }
            tree = new NamespaceNode(root);
            shape = java.util.Collections.unmodifiableList(keys);
        }
        return tree;
    }

    public LegacyScriptBindings(NiNodes nodes) {
        sections = new LegacySectionRegistry(nodes);
    }

    public synchronized void alias(String className, Object adapter) {
        registered.put(className, java.util.Objects.requireNonNull(adapter));
    }

    public synchronized void global(String name, Object adapter) {
        globals.put(name, java.util.Objects.requireNonNull(adapter));
    }

    public synchronized void actionManager(LegacyActionManager manager) {
        registered.put("pers.neige.neigeitems.manager.ActionManager", Map.of("INSTANCE", manager));
        globals.put("ActionManager", manager);
        globals.put("manager", manager);
    }

    @Override
    public synchronized void accept(ScriptEngine engine) {
        Map<String, Object> aliases = new HashMap<>(registered);
        aliases.put(
                "pers.neige.neigeitems.section.impl.CustomSection",
                StaticClass.forClass(LegacyCustomSection.class));
        aliases.put(
                "pers.neige.neigeitems.section.Section", StaticClass.forClass(LegacySection.class));
        aliases.put(
                "pers.neige.neigeitems.utils.SectionUtils",
                StaticClass.forClass(LegacySectionUtils.class));
        aliases.put(
                "pers.neige.neigeitems.config.ConfigReader",
                StaticClass.forClass(LegacyConfigReader.class));
        aliases.put(
                "pers.neige.neigeitems.config.BukkitConfigReader",
                StaticClass.forClass(LegacyConfigReader.BukkitReader.class));
        aliases.put(
                "pers.neige.neigeitems.config.MapConfigReader",
                StaticClass.forClass(LegacyConfigReader.MapReader.class));
        aliases.put(
                "pers.neige.neigeitems.action.ActionContext",
                StaticClass.forClass(dev.itemloom.compat.ni.action.NiActionContext.class));
        aliases.put(
                "pers.neige.neigeitems.action.ContextKey",
                StaticClass.forClass(dev.itemloom.compat.ni.action.NiContextKey.class));
        aliases.put(
                "pers.neige.neigeitems.action.ContextKeys",
                StaticClass.forClass(dev.itemloom.compat.ni.action.NiContextKeys.class));
        aliases.put(
                "pers.neige.neigeitems.action.ActionResult",
                StaticClass.forClass(LegacyActionResult.class));
        aliases.put(
                "pers.neige.neigeitems.action.ResultType",
                StaticClass.forClass(LegacyActionResult.Type.class));
        aliases.put(
                "pers.neige.neigeitems.action.result.Results",
                StaticClass.forClass(LegacyActionResult.Results.class));
        aliases.put(
                "pers.neige.neigeitems.action.result.StopResult",
                StaticClass.forClass(LegacyActionResult.Stop.class));
        aliases.put(
                "pers.neige.neigeitems.action.result.SuccessResult",
                StaticClass.forClass(LegacyActionResult.Success.class));
        engine.put("__itemloom_section_registry", sections);
        try {
            globals.forEach(engine::put);
            for (String name : java.util.List.of("ActionContext", "ContextKeys"))
                engine.put(name, aliases.get("pers.neige.neigeitems.action." + name));
            for (String name : java.util.List.of("Results", "StopResult", "SuccessResult"))
                engine.put(name, aliases.get("pers.neige.neigeitems.action.result." + name));
            aliases.put(
                    "pers.neige.neigeitems.manager.SectionManager",
                    engine.eval("({INSTANCE: __itemloom_section_registry})"));
            engine.put("__itemloom_aliases", aliases);
            engine.put("__itemloom_alias_tree", tree(aliases));
            // Keep only the immutable names. Retaining CompiledScript per engine here would
            // also retain transform engines after NiScripts evicts their bounded programs.
            engine.eval(
                    """
                    (function (hostPackages, hostJava, aliases, tree) {
                        function namespace(node, original) {
                            var members = Object.create(null);
                            var entries = node.getChildren().entrySet().iterator();
                            while (entries.hasNext()) {
                                var entry = entries.next();
                                var name = String(entry.getKey());
                                var child = entry.getValue();
                                var qualified = child.getQualified();
                                members[name] = qualified !== null ? aliases.get(qualified)
                                    : namespace(child, original[name]);
                            }
                            // A direct `new Packages.x.Type(...)` uses Nashorn's method lookup,
                            // which skips __get__ and requests __call__ on a bare JSAdapter.
                            // Real override properties preserve the actual constructor object.
                            return new JSAdapter(members, {
                                __get__: function (name) { return original[name]; }
                            });
                        }
                        Packages = namespace(tree, hostPackages);
                        Java = Object.create(hostJava);
                        Java.type = function (type) {
                            return aliases.containsKey(String(type)) ? aliases.get(String(type)) : hostJava.type(type);
                        };
                    })(Packages, Java, __itemloom_aliases, __itemloom_alias_tree);
                    """);
        } catch (ScriptException error) {
            throw new IllegalStateException(
                    "Cannot install NI script namespace compatibility", error);
        } finally {
            engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE)
                    .remove("__itemloom_section_registry");
            engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE)
                    .remove("__itemloom_aliases");
            engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE)
                    .remove("__itemloom_alias_tree");
        }
    }
}
