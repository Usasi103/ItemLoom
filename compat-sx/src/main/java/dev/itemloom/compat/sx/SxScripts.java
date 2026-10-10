package dev.itemloom.compat.sx;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

/** Ordered SX script initialization with a shared global environment and isolated file scopes. */
public final class SxScripts implements AutoCloseable {
    private final Map<String, Bindings> routes = new LinkedHashMap<>();
    private ScriptEngine engine;
    private boolean closed;

    public SxScripts(SxRepository.Input input, Map<String, Object> globals) {
        Object configured = input.settings().values().getOrDefault("ScriptEngine", "js");
        if (configured == null || configured.toString().isEmpty()) return;
        String name = configured.toString().toLowerCase(Locale.ROOT);
        if (!name.equals("js") && !name.equals("javascript") && !name.equals("nashorn"))
            throw new IllegalArgumentException("Unsupported SX ScriptEngine: " + configured);
        if (input.scripts().isEmpty()) return;

        try {
            engine =
                    new NashornScriptEngineFactory()
                            .getScriptEngine(
                                    new String[] {"--language=es6"},
                                    SxScripts.class.getClassLoader());
            Bindings shared = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            shared.putAll(globals);
            engine.setBindings(shared, ScriptContext.GLOBAL_SCOPE);
            routes.put("Global", shared);
            for (var file : input.scripts().entrySet()) {
                if (file.getKey().startsWith("Global/"))
                    initialize(file.getKey(), file.getValue(), shared);
            }
            for (var file : input.scripts().entrySet()) {
                String path = file.getKey();
                if (path.startsWith("Global/")) continue;
                String route = route(path);
                if (routes.containsKey(route) || shared.containsKey(route))
                    throw new IllegalArgumentException(
                            path + ": duplicate or reserved SX script route " + route);
                Bindings scope = engine.createBindings();
                initialize(path, file.getValue(), scope);
                shared.put(route, scope);
                routes.put(route, scope);
            }
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    public Object call(String file, String function, SxExpressions handler, Object[] arguments) {
        if (closed) throw new IllegalStateException("SX scripts are closed");
        if (engine == null) return null;
        Bindings scope = routes.get(file);
        if (scope == null) throw new IllegalArgumentException("Unknown SX script file: " + file);
        try {
            return ((Invocable) engine).invokeMethod(scope, function, handler, arguments);
        } catch (ScriptException | NoSuchMethodException | RuntimeException error) {
            throw new IllegalArgumentException(
                    "SX script call failed: " + file + "." + function, error);
        }
    }

    private void initialize(String path, String source, Bindings scope) {
        try {
            scope.put(ScriptEngine.FILENAME, path);
            engine.eval(source, scope);
        } catch (ScriptException | RuntimeException error) {
            throw new IllegalArgumentException("SX script initialization failed: " + path, error);
        }
    }

    private static String route(String path) {
        String basename = path.substring(path.lastIndexOf('/') + 1);
        int dot = basename.indexOf('.');
        return dot < 0 ? basename : basename.substring(0, dot);
    }

    @Override
    public void close() {
        closed = true;
        routes.clear();
        engine = null;
    }
}
