package dev.itemloom.probe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import dev.itemloom.compat.ni.*;
import dev.itemloom.compat.ni.action.*;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.core.GenerationContext;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/** Reference classes are loaded only by this optional probe, never by the production engine. */
final class ActionProbe {
    private record Outcome(
            boolean stopped,
            String label,
            int priority,
            List<String> calls,
            String globals,
            String failure) {}

    static Map<String, Object> run(Plugin reference) throws Exception {
        if (reference == null || !reference.isEnabled())
            throw new IllegalStateException("NI reference is not loaded");
        ClassLoader loader = reference.getClass().getClassLoader();
        Class<?> managerClass = loader.loadClass("pers.neige.neigeitems.manager.ActionManager");
        Class<?> baseManager = loader.loadClass("pers.neige.neigeitems.manager.BaseActionManager");
        Class<?> contextClass = loader.loadClass("pers.neige.neigeitems.action.ActionContext");
        Class<?> actionClass = loader.loadClass("pers.neige.neigeitems.action.Action");
        Class<?> resultClass = loader.loadClass("pers.neige.neigeitems.action.ActionResult");
        Object manager = managerClass.getField("INSTANCE").get(null);
        Method compile = managerClass.getMethod("compile", Object.class);
        Method eval = actionClass.getMethod("eval", contextClass);
        Method getGlobal = contextClass.getMethod("getGlobal");
        List<String> oldCalls = new ArrayList<>();
        managerClass
                .getMethod("addConsumer", String.class, BiConsumer.class)
                .invoke(
                        manager,
                        "il-probe",
                        (BiConsumer<Object, String>) (context, text) -> oldCalls.add(text));
        Host host = new Host();
        List<Object> cases = new ArrayList<>();
        cases.add(List.of("il-probe: begin", "return", "il-probe: never"));
        cases.add(
                List.of(
                        "js: var count = 3;",
                        "js: global.put('answer', ++count);",
                        "il-probe: <answer>"));
        cases.add(
                List.of(
                        "set-global: answer first",
                        "il-probe: <answer>",
                        "set-global: answer second",
                        "il-probe: <answer>",
                        "del-global: answer"));
        cases.add(
                List.of(
                        "set-global-int: x 2.5",
                        "set-global-long: y 2.5",
                        "set-global-double: z 2.5"));
        cases.add(List.of("js: false", "il-probe: never"));
        cases.add(List.of("js: 5", "il-probe: next"));
        cases.add(List.of("js: throw new Error('fixture')", "il-probe: never"));
        cases.add(Map.of("actions", List.of("il-probe: wrapped")));
        cases.add(Map.of("il-probe", 42));
        cases.add(Map.of("type", "unknown", "condition", "true", "actions", "il-probe: inferred"));
        cases.add(
                List.of(
                        "set-global-int: answer 5",
                        "il-probe: <default::answer_fallback>",
                        "del-global: answer",
                        "il-probe: <default::answer_fallback>"));
        cases.add(
                List.of(
                        """
                js: var Context = Java.type('pers.neige.neigeitems.action.ActionContext');
                var Keys = Java.type('pers.neige.neigeitems.action.ContextKeys');
                var Reader = Java.type('pers.neige.neigeitems.config.BukkitConfigReader');
                var yaml = new (Java.type('org.bukkit.configuration.file.YamlConfiguration'))();
                yaml.set('fixed', 'original');
                var child = Context.builder().with(Keys.SECTIONS, new Reader(yaml)).build();
                global.put('first', ActionManager.parseNode('<fixed>', child));
                child.getGlobal().remove('fixed');
                yaml.set('fixed', 'changed');
                global.put('second', ActionManager.parseNode('<fixed>', child));
                """,
                        "il-probe: <first>-<second>"));
        cases.add(
                "js: new (Java.type('pers.neige.neigeitems.action.result.StopResult'))('legacy', 3)");
        for (String value :
                List.of("2 label", "2.5 label", "bad label", "0 label", "-2 label", "2", ""))
            cases.add("return-weight: " + value);
        for (String condition :
                List.of("true", "false", "1", "null", "'true'", "undefined", "missing.member")) {
            cases.add(
                    Map.of(
                            "condition",
                            condition,
                            "actions",
                            "il-probe: yes",
                            "deny",
                            "il-probe: no"));
        }
        cases.add(Map.of("repeat", 3, "actions", "il-probe: <i>"));
        cases.add(
                Map.of(
                        "repeat",
                        "js: 2.9",
                        "global-id",
                        "index",
                        "actions",
                        List.of("il-probe: <index>", "return: outer")));
        cases.add(
                Map.of(
                        "label",
                        "outer",
                        "actions",
                        List.of("il-probe: before", "return: outer", "il-probe: never")));
        cases.add(
                List.of(
                        "set-global-int: i 0",
                        Map.of(
                                "while",
                                "global.i < 3",
                                "actions",
                                List.of("il-probe: <i>", "js: global.put('i', global.i + 1);"),
                                "finally",
                                "il-probe: final")));
        cases.add(
                Map.of("while", "true", "actions", "return: outer", "finally", "il-probe: final"));
        for (String kind : List.of("key", "int-tree", "double-tree"))
            for (String direction : List.of("LOWER", "FLOOR", "HIGHER", "CEILING")) {
                cases.add(
                        Map.of(
                                "type",
                                kind,
                                "key",
                                "10",
                                "action-type",
                                direction,
                                "match-action",
                                "il-probe: matched",
                                "default-action",
                                "il-probe: missed",
                                "actions",
                                Map.of(
                                        "5",
                                        "il-probe: five",
                                        "10",
                                        "il-probe: ten",
                                        "20",
                                        "il-probe: twenty")));
            }
        for (String value : List.of("a", "b"))
            cases.add(
                    Map.of(
                            "type",
                            "contains",
                            "key",
                            value,
                            "elements",
                            List.of("a"),
                            "contains-action",
                            "il-probe: contained",
                            "default-action",
                            "il-probe: missed"));
        for (String kind : List.of("weight", "condition-weight"))
            cases.add(
                    Map.of(
                            "type",
                            kind,
                            "amount",
                            10,
                            "actions",
                            List.of(
                                    Map.of(
                                            "weight",
                                            1,
                                            "actions",
                                            List.of("il-probe: first", "return-weight: 3 first")),
                                    Map.of(
                                            "weight",
                                            1,
                                            "actions",
                                            List.of("il-probe: second", "return-weight: 3 second")),
                                    Map.of("weight", 0, "actions", "il-probe: zero"))));
        for (String kind : List.of("weight", "condition-weight"))
            for (int amount : List.of(-1, 0, 1, 2)) {
                cases.add(
                        Map.of(
                                "type",
                                kind,
                                "amount",
                                amount,
                                "actions",
                                List.of(
                                        Map.of("weight", 1, "actions", "return-weight: 0 label"),
                                        Map.of("weight", 1, "actions", "return-weight: 0 label"))));
            }
        Map<String, Object> differences = new LinkedHashMap<>();
        int checked = 0;
        var bindings = new dev.itemloom.compat.ni.script.LegacyScriptBindings(new NiNodes());
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of(), bindings)) {
            NiActions compiler = new NiActions(host, scripts::validate);
            bindings.actionManager(
                    new dev.itemloom.compat.ni.script.LegacyActionManager(
                            compiler, (step, context) -> step.run(context)));
            for (Object input : cases) {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("action", input);
                String source = yaml.saveToString();
                YamlConfiguration loaded = new YamlConfiguration();
                loaded.loadFromString(source);
                Object oldContext = contextClass.getConstructor().newInstance();
                oldCalls.clear();
                Outcome expected;
                try {
                    Object action = compile.invoke(manager, loaded.get("action"));
                    CompletableFuture<?> future =
                            (CompletableFuture<?>) eval.invoke(action, oldContext);
                    if (!future.isDone())
                        throw new IllegalStateException("Unexpected async fixture");
                    Object result = future.join();
                    boolean stopped = (boolean) resultClass.getMethod("isStop").invoke(result);
                    String label =
                            stopped
                                    ? (String)
                                            result.getClass().getMethod("getLabel").invoke(result)
                                    : null;
                    expected =
                            new Outcome(
                                    stopped,
                                    label,
                                    (int) resultClass.getMethod("getPriority").invoke(result),
                                    List.copyOf(oldCalls),
                                    canonical(getGlobal.invoke(oldContext)),
                                    null);
                } catch (Throwable error) {
                    expected =
                            new Outcome(
                                    false,
                                    null,
                                    0,
                                    List.copyOf(oldCalls),
                                    canonical(getGlobal.invoke(oldContext)),
                                    failure(error));
                }
                host.calls.clear();
                NiActionContext context = context(scripts);
                Outcome actual;
                try {
                    var result =
                            compiler.compile(NiYaml.read(source, "action probe").get("action"))
                                    .run(context)
                                    .toCompletableFuture()
                                    .join();
                    actual =
                            new Outcome(
                                    result.stopped(),
                                    result.label(),
                                    result.priority(),
                                    List.copyOf(host.calls),
                                    canonical(context.getGlobal()),
                                    null);
                } catch (Throwable error) {
                    actual =
                            new Outcome(
                                    false,
                                    null,
                                    0,
                                    List.copyOf(host.calls),
                                    canonical(context.getGlobal()),
                                    failure(error));
                }
                checked++;
                if (!expected.equals(actual))
                    differences.put(
                            "action:" + checked,
                            Map.of("source", source, "expected", expected, "actual", actual));
            }
            Class<?> evaluatorClass =
                    loader.loadClass("pers.neige.neigeitems.action.evaluator.Evaluator");
            for (Class<?> type :
                    List.of(String.class, Integer.class, Long.class, Double.class, Boolean.class)) {
                for (boolean list : List.of(false, true)) {
                    Method factory =
                            evaluatorClass.getMethod(
                                    "create"
                                            + type.getSimpleName()
                                            + (list ? "List" : "")
                                            + "Evaluator",
                                    baseManager,
                                    Object.class);
                    for (Object input :
                            List.of(
                                    "1",
                                    "1.5",
                                    "9007199254740993",
                                    "99999999999999999999999999",
                                    "1e3",
                                    " 3 ",
                                    "NaN",
                                    "Infinity",
                                    "true",
                                    "FALSE",
                                    "raw: 2.5",
                                    "js: 2.5",
                                    "js: '2.5'",
                                    "js: null",
                                    "js: ({x: 1})",
                                    List.of("js: null", "raw: 3"),
                                    Map.of("condition", "true", "then", 3, "else", 4),
                                    Map.of("raw", 3),
                                    Map.of("type", "null"),
                                    java.util.Collections.singletonMap("type", null),
                                    List.of(1, List.of(2, "js: null"), Map.of("type", "null"), 3),
                                    Map.of(
                                            "type",
                                            "int-tree",
                                            "value",
                                            "10",
                                            "action-type",
                                            "FLOOR",
                                            "evaluators",
                                            Map.of("10", 3),
                                            "default-evaluator",
                                            9),
                                    Map.of(
                                            "type",
                                            "weight",
                                            "evaluators",
                                            List.of(
                                                    Map.of(
                                                            "weight",
                                                            1,
                                                            "evaluator",
                                                            List.of(2, 3)))))) {
                        Object expected = null, actual = null;
                        String oldError = null, newError = null;
                        try {
                            expected =
                                    evaluatorClass
                                            .getMethod("get", contextClass)
                                            .invoke(
                                                    factory.invoke(null, manager, input),
                                                    contextClass.getConstructor().newInstance());
                        } catch (Throwable error) {
                            oldError = failure(error);
                        }
                        try {
                            actual =
                                    list
                                            ? NiValues.compileList(input, type)
                                                    .apply(context(scripts))
                                            : NiValues.compile(input, type).apply(context(scripts));
                        } catch (Throwable error) {
                            newError = failure(error);
                        }
                        checked++;
                        if (!Objects.equals(expected, actual)
                                || !Objects.equals(oldError, newError)) {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("type", type.getSimpleName() + (list ? "List" : ""));
                            detail.put("input", input);
                            detail.put("expected", expected);
                            detail.put("actual", actual);
                            detail.put("expectedFailure", oldError);
                            detail.put("actualFailure", newError);
                            differences.put("value:" + checked, detail);
                        }
                    }
                }
            }
        }
        return Map.of("checked", checked, "differences", differences);
    }

    private static NiActionContext context(NiScripts scripts) {
        return new NiActionContext(
                new NiEvaluation(
                        new GenerationContext(Map.of(), new Random(1)),
                        null,
                        null,
                        NiEvaluation.Mode.ACTION,
                        new NiNodes(),
                        scripts,
                        null),
                null,
                Map.of(),
                () -> true);
    }

    private static String canonical(Object values) {
        return new java.util.TreeMap<>((Map<?, ?>) values).toString();
    }

    private static String failure(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getClass().getName();
    }

    private static final class Host implements NiActions.Host {
        final List<String> calls = new ArrayList<>();

        public CompletionStage<ActionFlow.Result> execute(
                String id, String content, NiActionContext context) {
            if (id.equals("il-probe")) calls.add(content);
            return CompletableFuture.completedFuture(ActionFlow.Result.CONTINUE);
        }

        public void fork(
                ActionFlow.Step<NiActionContext> action, NiActionContext context, boolean async) {
            throw new IllegalStateException("Unexpected fork fixture");
        }
    }
}
