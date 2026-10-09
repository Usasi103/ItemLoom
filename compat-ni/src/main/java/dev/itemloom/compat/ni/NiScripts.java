package dev.itemloom.compat.ni;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.Bindings;
import javax.script.ScriptContext;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;

/** A revision owns its script engines. Invocation scopes never leak between item requests. */
public final class NiScripts implements AutoCloseable {
    /** One action execution's globals, retained across its own conditions and delayed steps. */
    public static final class Scope {
        private final NiScripts owner;
        private final Bindings bindings;

        private Scope(NiScripts owner, Bindings bindings) {
            this.owner = owner;
            this.bindings = bindings;
        }

        public Map<String, Object> bindings() {
            return bindings;
        }
    }

    private record Program(ScriptEngine engine, ScriptObjectMirror scopeFactory) {}

    private final Map<String, Program> programs = new LinkedHashMap<>();
    private final Map<String, Program> transforms = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, CompiledScript> expressions = new LinkedHashMap<>(16, .75f, true);
    private final ScriptEngine expressionsEngine;
    private final Map<String, Object> globals;
    private final Consumer<ScriptEngine> initializer;
    private Consumer<ScriptEngine> actionInitializer = engine -> {};
    private boolean closed;

    public NiScripts(Map<String, String> sources, Map<String, Object> globals) {
        this(sources, globals, engine -> {});
    }

    public NiScripts(
            Map<String, String> sources,
            Map<String, Object> globals,
            Consumer<ScriptEngine> initializer) {
        this.globals = Map.copyOf(globals);
        this.initializer = initializer;
        expressionsEngine = engine();
        loadSources(sources);
    }

    public synchronized void loadSources(Map<String, String> sources) {
        requireOpen();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            programs.put(
                    entry.getKey().replace('\\', '/'), compile(entry.getKey(), entry.getValue()));
        }
    }

    private ScriptEngine engine() {
        ScriptEngine result =
                new NashornScriptEngineFactory()
                        .getScriptEngine(
                                new String[] {"--language=es6"}, NiScripts.class.getClassLoader());
        globals.forEach(result::put);
        initializer.accept(result);
        return result;
    }

    private Program compile(String name, String source) {
        ScriptEngine engine = engine();
        engine.put(ScriptEngine.FILENAME, name);
        try {
            ((Compilable) engine).compile(source).eval();
            ScriptObjectMirror factory =
                    (ScriptObjectMirror)
                            engine.eval(
                                    "(function(scope) { return function() { return Object.create(scope); }; })(this)");
            return new Program(engine, factory);
        } catch (ScriptException error) {
            throw new IllegalArgumentException(name + ": " + error.getMessage(), error);
        }
    }

    public synchronized Object invoke(
            String path, String function, Map<String, Object> scope, Object... arguments) {
        requireOpen();
        Program program = programs.get(path.replace('\\', '/'));
        if (program == null) throw new IllegalArgumentException("Missing NI script: " + path);
        return call(program, function, scope, arguments);
    }

    public synchronized boolean hasFunction(String path, String function) {
        requireOpen();
        Program program = programs.get(path.replace('\\', '/'));
        return program != null
                && program.engine().get(function) instanceof ScriptObjectMirror value
                && value.isFunction();
    }

    public synchronized Object transform(String source, Map<String, Object> scope) {
        requireOpen();
        Program program = transforms.get(source);
        if (program == null) {
            program = compile("node-transform", "function main() {\n" + source + "\n}");
            boundedPut(transforms, source, program);
        }
        return call(program, "main", scope);
    }

    private Object call(
            Program program, String function, Map<String, Object> values, Object... arguments) {
        ScriptObjectMirror scope = (ScriptObjectMirror) program.scopeFactory().call(null);
        values.forEach(scope::put);
        try {
            return scope.callMember(function, arguments);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "Script function " + function + ": " + error.getMessage(), error);
        }
    }

    public synchronized Object evaluate(String source, Map<String, Object> values) {
        return evaluate(source, scope(values));
    }

    public synchronized Scope scope(Map<String, Object> values) {
        return scope(values, false);
    }

    public synchronized void setActionInitializer(Consumer<ScriptEngine> initializer) {
        requireOpen();
        actionInitializer = java.util.Objects.requireNonNull(initializer);
    }

    public synchronized Scope actionScope(Map<String, Object> values) {
        return scope(values, true);
    }

    private Scope scope(Map<String, Object> values, boolean action) {
        requireOpen();
        Bindings bindings = expressionsEngine.createBindings();
        bindings.putAll(globals);
        bindings.putAll(values);
        Bindings previous = expressionsEngine.getBindings(ScriptContext.ENGINE_SCOPE);
        expressionsEngine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        try {
            initializer.accept(expressionsEngine);
            if (action) actionInitializer.accept(expressionsEngine);
        } finally {
            expressionsEngine.setBindings(previous, ScriptContext.ENGINE_SCOPE);
        }
        return new Scope(this, bindings);
    }

    public synchronized Object evaluate(String source, Scope scope) {
        requireOpen();
        if (scope.owner != this)
            throw new IllegalArgumentException("Script scope belongs to another revision");
        try {
            return expression(source).eval(scope.bindings);
        } catch (ScriptException error) {
            throw new IllegalArgumentException("Expression: " + error.getMessage(), error);
        }
    }

    public synchronized void validate(String source) {
        requireOpen();
        try {
            expression(source);
        } catch (ScriptException error) {
            throw new IllegalArgumentException("Expression: " + error.getMessage(), error);
        }
    }

    private CompiledScript expression(String source) throws ScriptException {
        CompiledScript script = expressions.get(source);
        if (script == null) {
            script = ((Compilable) expressionsEngine).compile(source);
            boundedPut(expressions, source, script);
        }
        return script;
    }

    private static <V> void boundedPut(Map<String, V> cache, String key, V value) {
        if (cache.size() >= 256) cache.remove(cache.keySet().iterator().next());
        cache.put(key, value);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Script revision has been closed");
    }

    public synchronized boolean isOpen() {
        return !closed;
    }

    @Override
    public synchronized void close() {
        closed = true;
        programs.clear();
        transforms.clear();
        expressions.clear();
    }
}
