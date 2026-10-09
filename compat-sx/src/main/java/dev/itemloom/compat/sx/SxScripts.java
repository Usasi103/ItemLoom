package dev.itemloom.compat.sx;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

/** One engine per prepared revision; globals and file scopes match SX's script calling convention. */
public final class SxScripts implements AutoCloseable {
    private ScriptEngine engine;
    private final Map<String, Bindings> files = new LinkedHashMap<>();
    private boolean closed;

    public SxScripts(SxRepository.Input input, Map<String, Object> globals) {
        String choice =
                input.settings().values().containsKey("ScriptEngine")
                        ? input.settings().text("ScriptEngine", "")
                        : "js";
        if (choice.isEmpty()) return;
        if (!java.util.Set.of("js", "javascript", "nashorn")
                .contains(choice.toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException(
                    "Unsupported SX ScriptEngine: "
                            + choice
                            + "; supported: js, or null to disable");
        if (input.scripts().isEmpty()) return;
        engine =
                new NashornScriptEngineFactory()
                        .getScriptEngine(
                                new String[] {"--language=es6"}, SxScripts.class.getClassLoader());
        Bindings shared = engine.createBindings();
        shared.putAll(globals);
        engine.setBindings(shared, ScriptContext.GLOBAL_SCOPE);
        for (var file : input.scripts().entrySet())
            if (file.getKey().startsWith("Global/"))
                evaluate(file.getKey(), file.getValue(), shared);
        files.put("Global", shared);
        for (var file : input.scripts().entrySet()) {
            if (file.getKey().startsWith("Global/")) continue;
            String name =
                    file.getKey().substring(file.getKey().lastIndexOf('/') + 1).split("\\.", 2)[0];
            if (files.containsKey(name) || shared.containsKey(name))
                throw new IllegalArgumentException("Duplicate/reserved SX script name: " + name);
            Bindings scope = engine.createBindings();
            evaluate(file.getKey(), file.getValue(), scope);
            files.put(name, scope);
            shared.put(name, scope);
        }
    }

    private void evaluate(String name, String source, Bindings scope) {
        try {
            scope.put(ScriptEngine.FILENAME, name);
            ((Compilable) engine).compile(source).eval(scope);
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "SX script " + name + ": " + error.getMessage(), error);
        }
    }

    public Object call(String file, String function, SxExpressions handler, Object[] arguments) {
        if (closed) throw new IllegalStateException("SX script revision is closed");
        if (engine == null) return null;
        Bindings scope = files.get(file);
        if (scope == null) throw new IllegalArgumentException("SX script file not found: " + file);
        try {
            return ((Invocable) engine).invokeMethod(scope, function, handler, arguments);
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "SX script " + file + '.' + function + ": " + error.getMessage(), error);
        }
    }

    @Override
    public void close() {
        closed = true;
        files.clear();
        engine = null;
    }
}
