package dev.itemloom.paper.compat.script;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.openjdk.nashorn.api.scripting.JSObject;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;
import org.openjdk.nashorn.api.scripting.ScriptUtils;

/** Converts values at the JavaScript helper boundary without changing their object identities. */
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
        return value == null
                ? 0
                : ((Number) ScriptUtils.convert(value, double.class)).doubleValue();
    }

    static int integer(Object value) {
        return value == null ? 0 : ((Number) ScriptUtils.convert(value, int.class)).intValue();
    }

    static long longNumber(Object value) {
        return value == null ? 0 : ((Number) ScriptUtils.convert(value, long.class)).longValue();
    }

    static boolean truth(Object value) {
        if (absent(value)) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number)
            return number.doubleValue() != 0 && !Double.isNaN(number.doubleValue());
        if (value instanceof CharSequence string) return !string.isEmpty();
        return true;
    }

    static double orNumber(Object value, double fallback) {
        return truth(value) ? number(value) : fallback;
    }

    static int size(Object sequence) {
        if (sequence instanceof JSObject object) return integer(object.getMember("length"));
        if (sequence instanceof List<?> list) return list.size();
        return Array.getLength(sequence);
    }

    static Object element(Object sequence, int index) {
        if (sequence instanceof JSObject object) return object.getSlot(index);
        if (sequence instanceof List<?> list) return list.get(index);
        return Array.get(sequence, index);
    }

    static Runnable task(Object value) {
        if (value instanceof Runnable runnable) return runnable;
        if (value instanceof JSObject function && function.isFunction())
            return () -> function.call(null);
        throw new IllegalArgumentException("Action scheduling requires a callable");
    }

    static Object getter(Object target, String method) {
        if (target instanceof JSObject object) {
            Object member = object.getMember(method);
            if (member instanceof JSObject function && function.isFunction())
                return function.call(target);
            throw new IllegalArgumentException("Missing method: " + method);
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot call method: " + method, failure);
        }
    }
}
