package dev.itemloom.paper.compat.script;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/** Installs independently implemented action helpers in each action's own JavaScript scope. */
public final class ActionScriptLibrary implements Consumer<ScriptEngine> {
    private static final String FILENAME = "itemloom:action-helper-bindings";
    private static final String BRIDGE = "__itemloom_action_helpers";
    private static final String SOURCE = bindingsSource();

    // One instance belongs to a catalog revision, alongside its NiScripts. This is deliberately
    // not a static cache: a compiled script retains its engine, globals and plugin class loader.
    private final Map<ScriptEngine, CompiledScript> programs = new IdentityHashMap<>();

    /**
     * The caller installs a new ENGINE_SCOPE and its legacy namespace aliases first. Reusing
     * function objects from an earlier scope would retain that action's player and context, so only
     * the compiled program is shared. Normal script files and node transforms do not use this
     * initializer.
     */
    @Override
    public synchronized void accept(ScriptEngine engine) {
        Objects.requireNonNull(engine, "engine");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        boolean hadBridge = bindings.containsKey(BRIDGE);
        Object previousBridge = bindings.put(BRIDGE, new ActionHelperScope(bindings));
        try {
            CompiledScript program = programs.get(engine);
            if (program == null) {
                if (!(engine instanceof Compilable compilable)) {
                    throw new IllegalArgumentException(
                            "The action script engine must support compilation");
                }
                boolean hadFilename = bindings.containsKey(ScriptEngine.FILENAME);
                Object filename = bindings.put(ScriptEngine.FILENAME, FILENAME);
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
            throw new IllegalStateException("Cannot initialize action script helpers", error);
        } finally {
            if (hadBridge) bindings.put(BRIDGE, previousBridge);
            else bindings.remove(BRIDGE);
        }
    }

    /** Only namespace declarations and a generic Java-to-JavaScript function adapter live here. */
    private static String bindingsSource() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("Calendar", "java.util.Calendar");
        aliases.put("ThreadLocalRandom", "java.util.concurrent.ThreadLocalRandom");
        for (String name : new String[] {"Bukkit", "ChatColor", "GameMode", "Material"})
            aliases.put(name, "org.bukkit." + name);
        for (String name : new String[] {"EquipmentSlot", "ItemStack"})
            aliases.put(name, "org.bukkit.inventory." + name);
        for (String name :
                new String[] {"ItemUtils", "PlayerUtils", "SectionUtils", "SchedulerUtils"})
            aliases.put(name, "pers.neige.neigeitems.utils." + name);
        aliases.put("BukkitConfigReader", "pers.neige.neigeitems.config.BukkitConfigReader");
        aliases.put("HookerManager", "pers.neige.neigeitems.manager.HookerManager");
        for (String name : new String[] {"NbtUtils", "NbtItemStack"})
            aliases.put(name, "pers.neige.neigeitems.libs.bot.inker.bukkit.nbt." + name);
        StringBuilder source = new StringBuilder();
        aliases.forEach(
                (name, type) ->
                        source.append("var ")
                                .append(name)
                                .append(" = Java.type('")
                                .append(type)
                                .append("');\n"));
        source.append(
                """
                var bukkitServer = Bukkit.getServer();
                var consoleSender = bukkitServer.getConsoleSender();
                var pluginManager = bukkitServer.getPluginManager();
                var scheduler = bukkitServer.getScheduler();
                (function(scope, helpers, convert) {
                    helpers.captureScheduler(SchedulerUtils);
                    // Let JavaScript retain its own property-access and missing-event errors.
                    helpers.captureEventGetter(function(method) { return scope.event[method](); });
                    var noResult = helpers.getNoResult();
                    function callable(name) {
                        return function() {
                            var result = helpers.call(name, convert(arguments, 'java.lang.Object[]'));
                            return result === noResult ? void 0 : result;
                        };
                    }
                    var names = helpers.getNames().iterator();
                    while (names.hasNext()) {
                        var name = String(names.next());
                        scope[name] = callable(name);
                    }
                })(this, __itemloom_action_helpers, Java.to);
                """);
        return source.toString();
    }
}
