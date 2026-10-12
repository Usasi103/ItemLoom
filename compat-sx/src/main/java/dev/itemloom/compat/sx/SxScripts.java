package dev.itemloom.compat.sx;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import org.openjdk.nashorn.api.scripting.JSObject;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

/** One shared JS environment and independent file globals with shadowable shared bindings. */
public final class SxScripts implements AutoCloseable {
    private ScriptEngine engine;
    private final Map<String, JSObject> routes = new LinkedHashMap<>();
    private boolean closed;

    public SxScripts(SxRepository.Input input, Map<String, Object> globals) {
        Object configured =
                input.settings().values().containsKey("ScriptEngine")
                        ? input.settings().get("ScriptEngine")
                        : "js";
        if (configured == null || configured.toString().isEmpty()) {
            return;
        }
        String name = configured.toString();
        if (!name.equalsIgnoreCase("js")
                && !name.equalsIgnoreCase("javascript")
                && !name.equalsIgnoreCase("nashorn")) {
            throw new IllegalArgumentException("Unsupported SX ScriptEngine: " + name);
        }
        if (input.scripts().isEmpty()) {
            return;
        }
        engine =
                new NashornScriptEngineFactory()
                        .getScriptEngine(
                                new String[] {"--language=es6"}, SxScripts.class.getClassLoader());
        Bindings shared = engine.createBindings();
        shared.putAll(globals);
        JSObject global = scope(shared, null);
        routes.put("Global", global);
        for (Map.Entry<String, String> script : input.scripts().entrySet()) {
            if (script.getKey().startsWith("Global/")) {
                initialize(shared, script.getKey(), script.getValue());
            }
        }
        for (Map.Entry<String, String> script : input.scripts().entrySet()) {
            String path = script.getKey();
            if (path.startsWith("Global/")) {
                continue;
            }
            String basename = path.substring(path.lastIndexOf('/') + 1);
            String route = basename.split("\\.", 2)[0];
            if (routes.containsKey(route)
                    || globals.containsKey(route)
                    || global.hasMember(route)) {
                throw new IllegalArgumentException(
                        "Reserved or duplicate SX script route in " + path + ": " + route);
            }
            Bindings local = engine.createBindings();
            JSObject fileScope = scope(local, global);
            initialize(local, path, script.getValue());
            routes.put(route, fileScope);
            global.setMember(route, fileScope);
        }
    }

    private JSObject scope(Bindings bindings, JSObject parent) {
        try {
            JSObject scope = (JSObject) engine.eval("this", context(bindings));
            if (parent != null) {
                // Nashorn does not permit a global from another engine scope as a prototype.
                // Forward object properties through accessors instead; lexical declarations
                // never enter keySet, and a local assignment replaces only its own accessor.
                JSObject inherit =
                        (JSObject)
                                engine.eval(
                                        """
                        (function(read, names) {
                            var target = this;
                            for (var i = 0; i < names.length; i++) {
                                (function(name) {
                                    Object.defineProperty(target, name, {
                                        get: function() { return read.apply(name); },
                                        set: function(value) {
                                            Object.defineProperty(target, name, {
                                                value: value, writable: true,
                                                enumerable: true, configurable: true
                                            });
                                        },
                                        enumerable: true, configurable: true
                                    });
                                })(names[i]);
                            }
                        })
                        """,
                                        context(bindings));
                inherit.call(
                        scope,
                        (Function<String, Object>) parent::getMember,
                        parent.keySet().toArray(String[]::new));
            }
            return scope;
        } catch (ScriptException error) {
            throw new IllegalArgumentException("Cannot initialize SX script scope", error);
        }
    }

    private static ScriptContext context(Bindings bindings) {
        SimpleScriptContext context = new SimpleScriptContext();
        context.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        return context;
    }

    private void initialize(Bindings scope, String path, String source) {
        scope.put(ScriptEngine.FILENAME, path);
        try {
            engine.eval(source, context(scope));
        } catch (ScriptException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot initialize SX script " + path, error);
        }
    }

    public Object call(String file, String function, SxExpressions handler, Object[] arguments) {
        if (closed) {
            throw new IllegalStateException("SX scripts are closed");
        }
        if (engine == null) {
            return null;
        }
        JSObject scope = routes.get(file);
        if (scope == null) {
            throw new IllegalArgumentException("Unknown SX script route: " + file);
        }
        try {
            Object member = scope.getMember(function);
            if (!(member instanceof JSObject callable) || !callable.isFunction()) {
                throw new NoSuchMethodException(function);
            }
            return callable.call(scope, handler, arguments);
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "SX script call failed: " + file + "." + function, error);
        }
    }

    @Override
    public void close() {
        closed = true;
        routes.clear();
        engine = null;
    }
}
