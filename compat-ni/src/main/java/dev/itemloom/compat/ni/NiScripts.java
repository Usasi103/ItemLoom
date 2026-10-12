package dev.itemloom.compat.ni;

import java.util.*;
import java.util.function.Consumer;
import javax.script.*;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;

public final class NiScripts implements AutoCloseable {
    public static final class Scope {
        private final NiScripts owner;
        private final ScriptContext context;

        private Scope(NiScripts owner, ScriptContext context) {
            this.owner = owner;
            this.context = context;
        }

        public Map<String, Object> bindings() {
            return context.getBindings(ScriptContext.ENGINE_SCOPE);
        }
    }

    private record Program(ScriptEngine engine, ScriptObjectMirror transform) {}

    private final Map<String, Object> globals;
    private final Consumer<ScriptEngine> initializer;
    private Consumer<ScriptEngine> actionInitializer;
    private final Map<String, Program> files = new HashMap<>();
    private final LinkedHashMap<String, Program> transforms = new LinkedHashMap<>(32, .75f, true);
    private final LinkedHashMap<String, CompiledScript> expressions =
            new LinkedHashMap<>(32, .75f, true);
    private ScriptEngine compiler;
    private boolean open = true;

    public NiScripts(Map<String, String> sources, Map<String, Object> globals) {
        this(sources, globals, null);
    }

    public NiScripts(
            Map<String, String> sources,
            Map<String, Object> globals,
            Consumer<ScriptEngine> initializer) {
        this.globals = Map.copyOf(globals);
        this.initializer = initializer;
        loadSources(sources);
    }

    private void requireOpen() {
        if (!open) throw new IllegalStateException("Script revision has been closed");
    }

    private ScriptEngine bare() {
        return new NashornScriptEngineFactory()
                .getScriptEngine(new String[] {"--language=es6"}, NiScripts.class.getClassLoader());
    }

    private ScriptEngine programEngine() {
        ScriptEngine engine = bare();
        engine.getBindings(ScriptContext.ENGINE_SCOPE).putAll(globals);
        if (initializer != null) initializer.accept(engine);
        return engine;
    }

    private static String path(String path) {
        return path.replace('\\', '/');
    }

    public synchronized void loadSources(Map<String, String> sources) {
        requireOpen();
        for (var source : sources.entrySet()) {
            String name = path(source.getKey());
            try {
                ScriptEngine engine = programEngine();
                engine.put(ScriptEngine.FILENAME, name);
                engine.eval(source.getValue());
                files.put(name, new Program(engine, null));
            } catch (ScriptException | RuntimeException error) {
                throw new IllegalArgumentException(name + ": " + error.getMessage(), error);
            }
        }
    }

    public synchronized Object invoke(
            String path, String function, Map<String, Object> scope, Object... arguments) {
        requireOpen();
        Program program = files.get(path(path));
        if (program == null) throw new IllegalArgumentException("Missing NI script: " + path(path));
        try {
            Object callable = program.engine.get(function);
            if (!(callable instanceof ScriptObjectMirror fn) || !fn.isFunction())
                throw new IllegalArgumentException("Missing function " + function);
            return call(program.engine, fn, scope, arguments);
        } catch (ScriptException | RuntimeException error) {
            throw new IllegalArgumentException(function + ": " + error.getMessage(), error);
        }
    }

    private Object call(
            ScriptEngine engine,
            ScriptObjectMirror function,
            Map<String, Object> values,
            Object... args)
            throws ScriptException {
        ScriptObjectMirror receiver = (ScriptObjectMirror) engine.eval("Object.create(this)");
        values.forEach(receiver::setMember);
        Object result = function.call(receiver, args);
        return ScriptObjectMirror.isUndefined(result) ? null : result;
    }

    public synchronized boolean hasFunction(String path, String function) {
        requireOpen();
        Program program = files.get(path(path));
        return program != null
                && program.engine.get(function) instanceof ScriptObjectMirror callable
                && callable.isFunction();
    }

    public synchronized Object transform(String source, Map<String, Object> scope) {
        requireOpen();
        try {
            Program program = transforms.get(source);
            if (program == null) {
                ScriptEngine engine = programEngine();
                engine.put(ScriptEngine.FILENAME, "node-transform");
                program =
                        new Program(
                                engine,
                                (ScriptObjectMirror)
                                        engine.eval("(function(){\n" + source + "\n})"));
                transforms.put(source, program);
                trim(transforms);
            }
            return call(program.engine, program.transform, scope);
        } catch (ScriptException | RuntimeException error) {
            throw new IllegalArgumentException("node-transform: " + error.getMessage(), error);
        }
    }

    private static void trim(LinkedHashMap<?, ?> cache) {
        if (cache.size() > 256) cache.remove(cache.keySet().iterator().next());
    }

    private CompiledScript expression(String source) throws ScriptException {
        CompiledScript script = expressions.get(source);
        if (script == null) {
            script = ((Compilable) expressionEngine()).compile(source);
            expressions.put(source, script);
            trim(expressions);
        }
        return script;
    }

    private ScriptEngine expressionEngine() {
        if (compiler == null) compiler = bare();
        return compiler;
    }

    private Scope expressionScope(Map<String, Object> values, boolean action) {
        ScriptEngine engine = expressionEngine();
        SimpleScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        context.getBindings(ScriptContext.ENGINE_SCOPE).putAll(globals);
        context.getBindings(ScriptContext.ENGINE_SCOPE).putAll(values);
        ScriptContext previous = engine.getContext();
        engine.setContext(context);
        try {
            if (initializer != null) initializer.accept(engine);
            if (action && actionInitializer != null) actionInitializer.accept(engine);
        } finally {
            engine.setContext(previous);
        }
        return new Scope(this, context);
    }

    public synchronized Object evaluate(String source, Map<String, Object> values) {
        return evaluate(source, scope(values));
    }

    public synchronized Scope scope(Map<String, Object> values) {
        requireOpen();
        return expressionScope(values, false);
    }

    public synchronized void setActionInitializer(Consumer<ScriptEngine> initializer) {
        requireOpen();
        actionInitializer = initializer;
    }

    public synchronized Scope actionScope(Map<String, Object> values) {
        requireOpen();
        return expressionScope(values, true);
    }

    public synchronized Object evaluate(String source, Scope scope) {
        requireOpen();
        if (scope.owner != this)
            throw new IllegalArgumentException("Script scope belongs to another revision");
        try {
            Object result = expression(source).eval(scope.context);
            return ScriptObjectMirror.isUndefined(result) ? null : result;
        } catch (ScriptException | RuntimeException error) {
            throw new IllegalArgumentException("Expression: " + error.getMessage(), error);
        }
    }

    public synchronized void validate(String source) {
        requireOpen();
        try {
            expression(source);
        } catch (ScriptException | RuntimeException error) {
            throw new IllegalArgumentException("Expression: " + error.getMessage(), error);
        }
    }

    public synchronized boolean isOpen() {
        return open;
    }

    public synchronized void close() {
        open = false;
        files.clear();
        transforms.clear();
        expressions.clear();
        compiler = null;
        actionInitializer = null;
    }
}
