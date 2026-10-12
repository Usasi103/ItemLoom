package dev.itemloom.paper.compat.script;

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

/** Reuses compiled installation code, while every action scope receives fresh JavaScript functions. */
public final class ActionScriptLibrary implements Consumer<ScriptEngine> {
    private static final String HOST = "__itemloom_action_helpers";
    private static final String PROGRAM =
            """
            (function(scope, helpers) {
                scope.Calendar = Java.type('java.util.Calendar');
                scope.ThreadLocalRandom = Java.type('java.util.concurrent.ThreadLocalRandom');
                scope.Bukkit = Java.type('org.bukkit.Bukkit');
                scope.ChatColor = Java.type('org.bukkit.ChatColor');
                scope.GameMode = Java.type('org.bukkit.GameMode');
                scope.Material = Java.type('org.bukkit.Material');
                scope.EquipmentSlot = Java.type('org.bukkit.inventory.EquipmentSlot');
                scope.ItemStack = Java.type('org.bukkit.inventory.ItemStack');
                scope.ItemUtils = Java.type('pers.neige.neigeitems.utils.ItemUtils');
                scope.PlayerUtils = Java.type('pers.neige.neigeitems.utils.PlayerUtils');
                scope.SectionUtils = Java.type('pers.neige.neigeitems.utils.SectionUtils');
                scope.SchedulerUtils = Java.type('pers.neige.neigeitems.utils.SchedulerUtils');
                scope.BukkitConfigReader = Java.type('pers.neige.neigeitems.config.BukkitConfigReader');
                scope.HookerManager = Java.type('pers.neige.neigeitems.manager.HookerManager');
                scope.NbtUtils = Java.type('pers.neige.neigeitems.libs.bot.inker.bukkit.nbt.NbtUtils');
                scope.NbtItemStack = Java.type('pers.neige.neigeitems.libs.bot.inker.bukkit.nbt.NbtItemStack');
                scope.bukkitServer = scope.Bukkit.getServer();
                scope.consoleSender = scope.bukkitServer.getConsoleSender();
                scope.pluginManager = scope.bukkitServer.getPluginManager();
                scope.scheduler = scope.bukkitServer.getScheduler();
                helpers.captureScheduler(scope.SchedulerUtils);
                helpers.captureEventGetter(function(method) { return scope.event[method](); });
                var absent = helpers.getNoResult();
                var iterator = helpers.getNames().iterator();
                while (iterator.hasNext()) {
                    (function(name) {
                        scope[name] = function() {
                            var args = Java.to(Array.prototype.slice.call(arguments), 'java.lang.Object[]');
                            var result = helpers.call(name, args);
                            return result === absent ? undefined : result;
                        };
                    })(String(iterator.next()));
                }
            })(this, __itemloom_action_helpers);
            """;
    private final Map<ScriptEngine, CompiledScript> programs = new IdentityHashMap<>();

    @Override
    public synchronized void accept(ScriptEngine engine) {
        Objects.requireNonNull(engine, "engine");
        if (!(engine instanceof Compilable compiler)) {
            throw new IllegalArgumentException("Action helpers require a compilable script engine");
        }
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        boolean hadFilename = bindings.containsKey(ScriptEngine.FILENAME);
        Object filename = bindings.get(ScriptEngine.FILENAME);
        boolean hadHost = bindings.containsKey(HOST);
        Object previous = bindings.get(HOST);
        try {
            bindings.put(ScriptEngine.FILENAME, "itemloom:action-helper-bindings");
            bindings.put(HOST, new ActionHelperScope(bindings));
            CompiledScript program = programs.get(engine);
            if (program == null) {
                program = compiler.compile(PROGRAM);
                programs.put(engine, program);
            }
            program.eval(engine.getContext());
        } catch (ScriptException failure) {
            throw new IllegalStateException("Cannot initialize action script helpers", failure);
        } finally {
            restore(bindings, HOST, hadHost, previous);
            restore(bindings, ScriptEngine.FILENAME, hadFilename, filename);
        }
    }

    private static void restore(Bindings bindings, String key, boolean present, Object value) {
        if (present) bindings.put(key, value);
        else bindings.remove(key);
    }
}
