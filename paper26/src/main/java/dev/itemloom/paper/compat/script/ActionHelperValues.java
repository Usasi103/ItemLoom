package dev.itemloom.paper.compat.script;

import org.openjdk.nashorn.api.scripting.JSObject;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;
import org.openjdk.nashorn.api.scripting.ScriptUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/** Argument conversion at the script boundary; business helpers use ordinary Java values. */
final class ActionHelperValues {
    private ActionHelperValues() {}

    static Object argument(Object[] values, int index) {
        return index < values.length ? values[index] : null;
    }

    static boolean absent(Object value) {
        return value == null || ScriptObjectMirror.isUndefined(value);
    }

    static String text(Object value) {
        return absent(value) ? null : (String) ScriptUtils.convert(value, String.class);
    }

    static double number(Object value) {
        return value == null ? 0 : (double) ScriptUtils.convert(value, double.class);
    }

    static int integer(Object value) {
        return value == null ? 0 : (int) ScriptUtils.convert(value, int.class);
    }

    static long longNumber(Object value) {
        return value == null ? 0 : (long) ScriptUtils.convert(value, long.class);
    }

    static boolean truth(Object value) {
        if (absent(value)) return false;
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) {
            double converted = number.doubleValue();
            return converted != 0 && !Double.isNaN(converted);
        }
        if (value instanceof CharSequence text) return !text.isEmpty();
        return true;
    }

    static double orNumber(Object value, double fallback) {
        return truth(value) ? number(value) : fallback;
    }

    static int size(Object sequence) {
        if (sequence instanceof JSObject object) return integer(object.getMember("length"));
        if (sequence instanceof List<?> list) return list.size();
        return java.lang.reflect.Array.getLength(sequence);
    }

    static Object element(Object sequence, int index) {
        if (sequence instanceof JSObject object) return object.getSlot(index);
        if (sequence instanceof List<?> list) return list.get(index);
        return java.lang.reflect.Array.get(sequence, index);
    }

    static Runnable task(Object value) {
        if (value instanceof Runnable runnable) return runnable;
        if (value instanceof JSObject function && function.isFunction())
            return () -> function.call(null);
        throw new IllegalArgumentException("Action scheduling requires a callable");
    }

    static Object getter(Object target, String method) {
        if (target instanceof JSObject object) {
            if (object.getMember(method) instanceof JSObject function && function.isFunction())
                return function.call(target);
            throw new IllegalArgumentException("Missing event method: " + method);
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException error) throw error;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Event method failed: " + method, cause);
        } catch (ReflectiveOperationException failed) {
            throw new IllegalArgumentException("Missing event method: " + method, failed);
        }
    }
}
