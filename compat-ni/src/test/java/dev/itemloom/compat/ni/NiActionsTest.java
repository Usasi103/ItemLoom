package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiActions;
import dev.itemloom.compat.ni.action.NiValues;
import dev.itemloom.core.ActionFlow;
import dev.itemloom.core.GenerationContext;
import org.junit.jupiter.api.Test;

class NiActionsTest {
    @Test
    void qualifiedNamesResolveRegisteredBasicsAndExtensionsButNotJsOrEditors() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            Host host = new Host();
            NiActions compiler = new NiActions(host, scripts::validate);
            var context = context(scripts, new AtomicBoolean(true));
            var delayedRegistration = compiler.compile("NeigeItems.custom: extension");
            compiler.register("custom", true, (call, text) -> call.getGlobal().put("custom", text));
            delayedRegistration.run(context).toCompletableFuture().join();
            var result =
                    compiler.compile(
                                    List.of(
                                            "NeigeItems.set-global-int: answer 42",
                                            "NEIGEITEMS.tell: <answer>",
                                            "NeigeItems.js: broken(",
                                            "NeigeItems.set-name: unchanged",
                                            "NeigeItems.return-weight: 7 done",
                                            "tell: never"))
                            .run(context)
                            .toCompletableFuture()
                            .join();
            assertEquals("extension", context.getGlobal().get("custom"));
            assertEquals(42, context.getGlobal().get("answer"));
            assertEquals(new ActionFlow.Result(true, "done", 7), result);
            assertEquals(
                    List.of("tell:42", "neigeitems.js:broken(", "neigeitems.set-name:unchanged"),
                    host.calls);
            compiler.register(
                    "tell",
                    true,
                    (call, text) -> {
                        call.getGlobal().put("override", text);
                        return true;
                    });
            compiler.compile("NeigeItems.tell: overridden")
                    .run(context)
                    .toCompletableFuture()
                    .join();
            assertEquals("overridden", context.getGlobal().get("override"));
        }
    }

    @Test
    void legacyActionImportsAndRegistrationUseTheIndependentContext() {
        NiNodes nodes = new NiNodes();
        var bindings = new dev.itemloom.compat.ni.script.LegacyScriptBindings(nodes);
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of(), bindings)) {
            Host host = new Host();
            NiActions compiler = new NiActions(host, scripts::validate);
            bindings.actionManager(
                    new dev.itemloom.compat.ni.script.LegacyActionManager(
                            compiler, (step, context) -> step.run(context)));
            var context =
                    new NiActionContext(
                            new NiEvaluation(
                                    new GenerationContext(Map.of(), new Random(1)),
                                    null,
                                    null,
                                    NiEvaluation.Mode.ACTION,
                                    nodes,
                                    scripts,
                                    null),
                            null,
                            Map.of(),
                            () -> true);
            scripts.loadSources(
                    Map.of(
                            "extension.js",
                            """
                    var ActionManager = Java.type('pers.neige.neigeitems.manager.ActionManager').INSTANCE;
                    function enable() {
                        ActionManager.addConsumer('custom', function(context, text) {
                            context.getGlobal().put('custom', text);
                        });
                    }
                    """));
            context.invoke(() -> scripts.invoke("extension.js", "enable", Map.of()));
            var result =
                    compiler.compile(
                                    List.of(
                                            "custom: hello",
                                            "js: new (Java.type('pers.neige.neigeitems.action.result.StopResult'))('outer', 7)"))
                            .run(context)
                            .toCompletableFuture()
                            .join();
            assertEquals("hello", context.getGlobal().get("custom"));
            assertEquals(new ActionFlow.Result(true, "outer", 7), result);
            Object parsed =
                    context.evaluate(
                            """
                    var Context = Java.type('pers.neige.neigeitems.action.ActionContext');
                    var Keys = Packages.pers.neige.neigeitems.action.ContextKeys;
                    var cache = new java.util.HashMap(); cache.put('value', 'legacy');
                    var child = Context.builder().with(Keys.SECTION_CACHE, cache).build();
                    ActionManager.parseNode('<value>', child);
                    """);
            assertEquals("legacy", parsed);
            assertEquals(
                    "changed",
                    context.evaluate(
                            """
                    var yaml = new (Java.type('org.bukkit.configuration.file.YamlConfiguration'))();
                    yaml.set('fixed', 'original');
                    var Reader = Java.type('pers.neige.neigeitems.config.BukkitConfigReader');
                    var cfg = new Reader(yaml);
                    var scoped = Context.builder().with(Keys.SECTIONS, cfg).build();
                    ActionManager.parseNode('<fixed>', scoped);
                    scoped.getGlobal().remove('fixed');
                    yaml.set('fixed', 'changed');
                    ActionManager.parseNode('<fixed>', scoped);
                    """));
            assertEquals(
                    "7",
                    context.evaluate(
                            """
                    context.getGlobal().put('number', 7);
                    ActionManager.parseNode('<default::number_fallback>', context);
                    """));
            assertTrue(context.condition("Results.SUCCESS"));
            assertFalse(context.condition("new StopResult('outer')"));
        }
    }

    private static final class Host implements NiActions.Host {
        final List<String> calls = new ArrayList<>();
        final CompletableFuture<ActionFlow.Result> delay = new CompletableFuture<>();

        public CompletionStage<ActionFlow.Result> execute(
                String id, String content, NiActionContext context) {
            if (id.equals("delay")) return delay;
            calls.add(id + ":" + content);
            return CompletableFuture.completedFuture(ActionFlow.Result.CONTINUE);
        }

        public void fork(
                ActionFlow.Step<NiActionContext> action, NiActionContext context, boolean async) {
            action.run(context);
        }
    }

    private NiActionContext context(NiScripts scripts, AtomicBoolean active) {
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
                active::get);
    }

    @Test
    void actionVariablesPersistAndNestedInvalidScriptsFailBeforeEffects() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            Host host = new Host();
            NiActions compiler = new NiActions(host, scripts::validate);
            var context = context(scripts, new AtomicBoolean(true));
            compiler.compile(
                            List.of(
                                    "js: var count = 4;",
                                    "js: global.put('answer', ++count);",
                                    "tell: <answer>"))
                    .run(context)
                    .toCompletableFuture()
                    .join();
            assertEquals(List.of("tell:5.0"), host.calls);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            compiler.compile(
                                    Map.of("repeat", "js: } broken", "actions", "tell: never")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            compiler.compile(
                                    Map.of("condition", "broken(", "actions", "tell: never")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            NiValues.compileList(
                                    Map.of("condition", "true", "then", List.of("js: broken(")),
                                    Integer.class,
                                    scripts::validate));
            var list =
                    NiValues.compileList(
                            List.of(1, List.of(2, "js: null"), Map.of("type", "null"), 3),
                            Integer.class);
            assertEquals(java.util.Arrays.asList(1, 2, null, 3), list.apply(context));
        }
    }

    @Test
    void delayedSequenceSharesItsOwnVariablesAndClosedRevisionCannotResumeEffects() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            Host host = new Host();
            AtomicBoolean active = new AtomicBoolean(true);
            NiActionContext context = context(scripts, active);
            var sequence =
                    new NiActions(host)
                            .compile(
                                    List.of(
                                            "js: var count = 4;",
                                            "delay: 2",
                                            "js: global.put('count', ++count);",
                                            "tell: after"));
            var result = sequence.run(context).toCompletableFuture();
            assertFalse(result.isDone());
            active.set(false);
            host.delay.complete(ActionFlow.Result.CONTINUE);
            assertTrue(result.join().stopped());
            assertTrue(host.calls.isEmpty());
            assertFalse(context.getGlobal().containsKey("count"));
        }
    }

    @Test
    void repeatLabelAndWhileFinallyPreserveStopBoundaries() {
        String yaml =
                """
                actions:
                - label: outer
                  actions:
                    repeat: 100
                    actions:
                    - condition: global.i == 2
                      actions: 'return: outer'
                    - tell: tick
                - while: 'true'
                  actions: 'return: ignored'
                  finally: 'tell: final'
                - tell: after
                """;
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            Host host = new Host();
            var context = context(scripts, new AtomicBoolean(true));
            var action = new NiActions(host).compile(NiYaml.read(yaml, "flow").get("actions"));
            assertFalse(action.run(context).toCompletableFuture().join().stopped());
            assertEquals(List.of("tell:tick", "tell:tick", "tell:final", "tell:after"), host.calls);
            assertEquals(2, context.getGlobal().get("i"));
        }
    }

    @Test
    void keyAndNumericBranchesStoreTheRequestedKeyAndRunMatchBeforeTarget() {
        String yaml =
                """
                type: int-tree
                key: 'js: 10'
                action-type: FLOOR
                match-action: 'tell: matched'
                default-action: 'tell: missed'
                actions:
                  '5': 'tell: lower'
                  '10': 'tell: exact'
                """;
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            Host host = new Host();
            var context = context(scripts, new AtomicBoolean(true));
            new NiActions(host)
                    .compile(NiYaml.read(yaml, "tree"))
                    .run(context)
                    .toCompletableFuture()
                    .join();
            assertEquals(List.of("tell:matched", "tell:exact"), host.calls);
            assertEquals(10, context.getGlobal().get("key"));
            var value = NiValues.compile(List.of("raw: invalid", "js: 9.5"), Integer.class);
            assertEquals(9, value.apply(context));
        }
    }

    @Test
    void weightedBranchesAllRunAndHighestPriorityStopWins() {
        String yaml =
                """
                type: condition-weight
                amount: 9
                actions:
                - condition: 'true'
                  weight: 2
                  actions: ['tell: one', 'return-weight: 3 outer']
                - condition: 'false'
                  actions: 'tell: never'
                - weight: 1
                  actions: 'tell: two'
                """;
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            Host host = new Host();
            var result =
                    new NiActions(host)
                            .compile(NiYaml.read(yaml, "weight"))
                            .run(context(scripts, new AtomicBoolean(true)))
                            .toCompletableFuture()
                            .join();
            assertEquals(new ActionFlow.Result(true, "outer", 3), result);
            assertEquals(List.of("tell:one", "tell:two"), host.calls);
        }
    }
}
