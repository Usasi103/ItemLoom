package dev.itemloom.compat.ni.script;

import dev.itemloom.core.ActionFlow;

/** Old script return objects are translated at the language boundary, not installed as NI classes. */
public class LegacyActionResult implements Comparable<LegacyActionResult> {
    public enum Type {
        SUCCESS,
        STOP
    }

    private final ActionFlow.Result value;

    public LegacyActionResult(ActionFlow.Result value) {
        this.value = value;
    }

    public ActionFlow.Result value() {
        return value;
    }

    public Type getType() {
        return value.stopped() ? Type.STOP : Type.SUCCESS;
    }

    public boolean isStop() {
        return value.stopped();
    }

    public String getLabel() {
        return value.label();
    }

    public int getPriority() {
        return value.priority();
    }

    @Override
    public int compareTo(LegacyActionResult other) {
        return Integer.compare(getPriority(), other.getPriority());
    }

    public static final class Success extends LegacyActionResult {
        public Success() {
            super(ActionFlow.Result.CONTINUE);
        }
    }

    public static final class Stop extends LegacyActionResult {
        public Stop() {
            this(null, 1);
        }

        public Stop(int priority) {
            this(null, priority);
        }

        public Stop(String label) {
            this(label, 1);
        }

        public Stop(String label, int priority) {
            super(new ActionFlow.Result(true, label, priority));
        }
    }

    public static final class Results {
        public static final LegacyActionResult SUCCESS = new Success();
        public static final LegacyActionResult STOP = new Stop();

        private Results() {}

        public static LegacyActionResult fromBoolean(boolean value) {
            return value ? SUCCESS : STOP;
        }
    }
}
