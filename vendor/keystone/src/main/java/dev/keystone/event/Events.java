package dev.keystone.event;

import dev.keystone.Keystone;
import dev.keystone.log.Log;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * Lambda listeners, for handlers registered at runtime - most importantly events of optional
 * plugins, which a plain {@code @EventHandler} method cannot reference without crashing
 * registration when that plugin is missing (Bukkit resolves every handler method of a class at
 * once).
 */
public final class Events {

    private Events() {}

    /** {@link #listen(Class, EventPriority, boolean, Consumer)} at NORMAL priority. */
    public static <E extends Event> Listener listen(Class<E> type, Consumer<E> handler) {
        return listen(type, EventPriority.NORMAL, false, handler);
    }

    /** Registers {@code handler} for {@code type}; returns the listener to {@link #unregister}. */
    public static <E extends Event> Listener listen(
            Class<E> type, EventPriority priority, boolean ignoreCancelled, Consumer<E> handler) {
        Listener listener = new Listener() {};
        Bukkit.getPluginManager()
                .registerEvent(
                        type,
                        listener,
                        priority,
                        (ignored, event) -> {
                            if (type.isInstance(event)) {
                                handler.accept(type.cast(event));
                            }
                        },
                        Keystone.plugin(),
                        ignoreCancelled);
        return listener;
    }

    /** {@link #listenOptional(String, EventPriority, boolean, Consumer)} at NORMAL priority. */
    public static Listener listenOptional(String className, Consumer<Event> handler) {
        return listenOptional(className, EventPriority.NORMAL, false, handler);
    }

    /**
     * Listens to an event class of another plugin by name. Returns null (and registers nothing)
     * when the class is not loadable, i.e. the plugin is absent.
     */
    public static Listener listenOptional(
            String className,
            EventPriority priority,
            boolean ignoreCancelled,
            Consumer<Event> handler) {
        Class<?> type;
        try {
            type = Class.forName(className, true, Keystone.plugin().getClass().getClassLoader());
        } catch (Throwable t) {
            return null;
        }
        if (!Event.class.isAssignableFrom(type)) {
            Log.warn(className + " 不是事件类，已跳过监听。");
            return null;
        }
        return listen(type.asSubclass(Event.class), priority, ignoreCancelled, handler::accept);
    }

    public static void unregister(Listener listener) {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
    }
}
