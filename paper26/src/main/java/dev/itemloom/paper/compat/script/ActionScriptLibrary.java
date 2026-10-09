package dev.itemloom.paper.compat.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/** Installs the NI action helpers in each action's own JavaScript scope. */
public final class ActionScriptLibrary implements Consumer<ScriptEngine> {
    private static final String RESOURCE = "/compat-ni/action-library.js";
    private static final String SOURCE = readSource();

    // One instance belongs to a catalog revision, alongside its NiScripts. This is deliberately
    // not a static cache: a compiled script retains its engine, globals and plugin class loader.
    private final Map<ScriptEngine, CompiledScript> programs = new IdentityHashMap<>();

    /**
     * The caller installs a new ENGINE_SCOPE and its legacy namespace aliases first. Reusing
     * function objects from an earlier scope would retain that action's player and context, so
     * only the compiled program is shared. Normal script files and node transforms do not use
     * this initializer.
     */
    @Override
    public synchronized void accept(ScriptEngine engine) {
        Objects.requireNonNull(engine, "engine");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        try {
            CompiledScript program = programs.get(engine);
            if (program == null) {
                if (!(engine instanceof Compilable compilable)) {
                    throw new IllegalArgumentException(
                            "The action script engine must support compilation");
                }
                boolean hadFilename = bindings.containsKey(ScriptEngine.FILENAME);
                Object filename = bindings.put(ScriptEngine.FILENAME, RESOURCE);
                try {
                    program = compilable.compile(SOURCE);
                } finally {
                    if (hadFilename) bindings.put(ScriptEngine.FILENAME, filename);
                    else bindings.remove(ScriptEngine.FILENAME);
                }
                programs.put(engine, program);
            }
            program.eval(bindings);
        } catch (ScriptException error) {
            throw new IllegalStateException("Cannot initialize NI action script helpers", error);
        }
    }

    private static String readSource() {
        try (InputStream input = ActionScriptLibrary.class.getResourceAsStream(RESOURCE)) {
            if (input == null)
                throw new IllegalStateException("Missing action script resource: " + RESOURCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Cannot read action script resource: " + RESOURCE, error);
        }
    }
}
