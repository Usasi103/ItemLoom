package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Random;
import dev.itemloom.compat.ni.script.LegacyScriptBindings;
import dev.itemloom.core.GenerationContext;
import org.junit.jupiter.api.Test;

class LegacyScriptBindingsTest {
    @Test
    void indexedNamespacesObserveAliasChangesAndKeepPreviousScopeValues() {
        LegacyScriptBindings bindings = new LegacyScriptBindings(new NiNodes());
        bindings.alias("fixture.audit.Type", Map.of("value", "before"));
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of(), bindings)) {
            var old = scripts.scope(Map.of());
            assertEquals(
                    "before",
                    scripts.evaluate("Java.type('fixture.audit.Type').get('value')", old));
            bindings.alias("fixture.audit.Type", Map.of("value", "after"));
            bindings.alias("fixture.audit.Added", Map.of("value", "added"));
            var next = scripts.scope(Map.of());
            assertEquals(
                    "after/added",
                    scripts.evaluate(
                            "Packages.fixture.audit.Type.get('value') + '/' + Packages.fixture.audit.Added.get('value')",
                            next));
            assertEquals(
                    "before", scripts.evaluate("Packages.fixture.audit.Type.get('value')", old));
            assertFalse(next.bindings().containsKey("__itemloom_alias_tree"));
            // An exact alias continues to take precedence over deeper names at that path.
            bindings.alias("fixture.audit.Type.Hidden", Map.of("value", "child"));
            assertEquals(
                    "after",
                    scripts.evaluate("Packages.fixture.audit.Type.get('value')", Map.of()));
        }
    }

    @Test
    void directPackagesConstructorsPreserveStaticClassesAndRevisionFactories() {
        LegacyScriptBindings bindings = new LegacyScriptBindings(new NiNodes());
        bindings.alias(
                "fixture.compat.NativeList",
                jdk.dynalink.beans.StaticClass.forClass(java.util.ArrayList.class));
        bindings.alias(
                "fixture.compat.FactoryList",
                new org.openjdk.nashorn.api.scripting.AbstractJSObject() {
                    @Override
                    public boolean isFunction() {
                        return true;
                    }

                    @Override
                    public Object newObject(Object... arguments) {
                        return new java.util.ArrayList<>(java.util.Arrays.asList(arguments));
                    }

                    @Override
                    public boolean isInstance(Object instance) {
                        return instance instanceof java.util.ArrayList<?>;
                    }

                    @Override
                    public boolean hasMember(String name) {
                        return "class".equals(name);
                    }

                    @Override
                    public Object getMember(String name) {
                        return "class".equals(name) ? java.util.ArrayList.class : null;
                    }
                });
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of(), bindings)) {
            assertEquals(
                    "factory/7/native/typed",
                    scripts.evaluate(
                            """
                    var made = new Packages.fixture.compat.FactoryList('factory', 7);
                    var hostList = new Packages.fixture.compat.NativeList();
                    hostList.add('native');
                    var Factory = Java.type('fixture.compat.FactoryList');
                    var typed = new Factory('typed');
                    String(made.get(0)) + '/' + made.get(1) + '/' + hostList.get(0) + '/' + typed.get(0);
                    """,
                            Map.of()));
            assertEquals(
                    true,
                    scripts.evaluate(
                            """
                    var made = new Packages.fixture.compat.FactoryList('value');
                    var hostList = new Packages.fixture.compat.NativeList();
                    made instanceof Packages.fixture.compat.FactoryList
                        && hostList instanceof Packages.fixture.compat.NativeList
                        && Packages.fixture.compat.FactoryList.class.isInstance(made)
                        && Packages.fixture.compat.NativeList.class.isInstance(hostList)
                        && Packages.fixture.compat.FactoryList === Java.type('fixture.compat.FactoryList')
                        && Packages.fixture.compat.NativeList === Java.type('fixture.compat.NativeList');
                    """,
                            Map.of()));
            assertEquals(
                    "normal Java",
                    scripts.evaluate(
                            "new Packages.java.lang.StringBuilder('normal').append(' Java').toString()",
                            Map.of()));
        }
    }

    @Test
    void actionEvalClonesThreadIntentButAsyncSafeRetainsSuppliedContext() {
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            var compiler =
                    new dev.itemloom.compat.ni.action.NiActions(
                            new dev.itemloom.compat.ni.action.NiActions.Host() {
                                public java.util.concurrent.CompletionStage<
                                                dev.itemloom.core.ActionFlow.Result>
                                        execute(
                                                String id,
                                                String content,
                                                dev.itemloom.compat.ni.action.NiActionContext
                                                        context) {
                                    return java.util.concurrent.CompletableFuture.completedFuture(
                                            dev.itemloom.core.ActionFlow.Result.CONTINUE);
                                }

                                public void fork(
                                        dev.itemloom.core.ActionFlow.Step<
                                                        dev.itemloom.compat.ni.action
                                                                .NiActionContext>
                                                action,
                                        dev.itemloom.compat.ni.action.NiActionContext context,
                                        boolean asynchronous) {}
                            });
            var observed =
                    new java.util.concurrent.atomic.AtomicReference<
                            dev.itemloom.compat.ni.action.NiActionContext>();
            var manager =
                    new dev.itemloom.compat.ni.script.LegacyActionManager(
                            compiler,
                            (step, context) -> {
                                observed.set(context);
                                return step.run(context);
                            });
            var evaluation =
                    new NiEvaluation(
                            new GenerationContext(Map.of(), new Random(0)),
                            null,
                            null,
                            NiEvaluation.Mode.ACTION,
                            new NiNodes(),
                            scripts,
                            null);
            var context =
                    new dev.itemloom.compat.ni.action.NiActionContext(
                            evaluation, null, null, () -> true);
            context.setSync(false);
            var action = manager.compile((Object) null);
            action.eval(context).join();
            assertNotSame(context, observed.get());
            assertTrue(observed.get().isSync());
            assertFalse(context.isSync());
            assertSame(context.getGlobal(), observed.get().getGlobal());
            action.evalAsyncSafe(context).join();
            assertSame(context, observed.get());
            assertFalse(observed.get().isSync());
        }
    }

    @Test
    void actionHelpersUseTheirOwnScopeWithoutPollutingFilesOrTransforms() {
        try (NiScripts scripts =
                new NiScripts(
                        Map.of("file.js", "function value(){return typeof helper;}"), Map.of())) {
            scripts.setActionInitializer(
                    engine -> {
                        try {
                            engine.eval("const helper = function(){return player;};");
                        } catch (javax.script.ScriptException error) {
                            throw new IllegalStateException(error);
                        }
                    });
            var first = scripts.actionScope(Map.of("player", "first"));
            var second = scripts.actionScope(Map.of("player", "second"));
            assertEquals("first", scripts.evaluate("helper()", first));
            assertEquals("second", scripts.evaluate("helper()", second));
            assertEquals("first", scripts.evaluate("helper()", first));
            assertEquals("undefined", scripts.evaluate("typeof helper", Map.of()));
            assertEquals("undefined", scripts.invoke("file.js", "value", Map.of()));
            assertEquals("undefined", scripts.transform("return typeof helper;", Map.of()));
        }
    }

    @Test
    void originalQualifiedImportsRegisterAndRunWithoutNiClasses() {
        NiNodes nodes = new NiNodes();
        String extension =
                """
                function enable() {
                    const SectionManager = Packages.pers.neige.neigeitems.manager.SectionManager.INSTANCE;
                    const CustomSection = Packages.pers.neige.neigeitems.section.impl.CustomSection;
                    const SectionUtils = Packages.pers.neige.neigeitems.utils.SectionUtils;
                    SectionManager.loadParser(new CustomSection('legacy-test',
                        function(data, cache, player, sections) {
                            return SectionUtils.parseSection(data.getString('value'), cache, player, sections);
                        },
                        function(args, cache, player, sections) {
                            return SectionUtils.parseSection(String(args.get(0)), cache, player, sections);
                        }
                    ));
                }
                function normalJava() { return Packages.java.lang.Integer.parseInt('42'); }
                function aliasedType() { return Java.type('pers.neige.neigeitems.section.impl.CustomSection').class.getName(); }
                """;
        try (NiScripts scripts =
                new NiScripts(
                        Map.of("extension.js", extension),
                        Map.of(),
                        new LegacyScriptBindings(nodes))) {
            scripts.invoke("extension.js", "enable", Map.of());
            NiEvaluation evaluation =
                    new NiEvaluation(
                            new GenerationContext(Map.of("saved", "19"), new Random(0)),
                            NiYaml.read(
                                    "custom: {type: legacy-test, value: '<saved>'}\n",
                                    "old-script"),
                            null,
                            NiEvaluation.Mode.ACTION,
                            nodes,
                            scripts,
                            null);
            assertEquals("19", evaluation.value("custom"));
            assertEquals("19", evaluation.text("<legacy-test::<saved>>"));
            assertEquals(
                    42,
                    ((Number) scripts.invoke("extension.js", "normalJava", Map.of())).intValue());
            assertEquals(
                    "dev.itemloom.compat.ni.script.LegacyCustomSection",
                    scripts.invoke("extension.js", "aliasedType", Map.of()));
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("pers.neige.neigeitems.manager.SectionManager"));
            assertThrows(IllegalStateException.class, NiEvaluation::current);
        }
    }

    @Test
    void scriptCallsDoNotLeakPlayerOrVarsAcrossRequests() {
        String source =
                "function read(){return typeof this.player === 'undefined' ? 'none' : this.player;}";
        try (NiScripts scripts = new NiScripts(Map.of("scope.js", source), Map.of())) {
            assertEquals("first", scripts.invoke("scope.js", "read", Map.of("player", "first")));
            assertEquals("none", scripts.invoke("scope.js", "read", Map.of()));
        }
    }

    @Test
    void expressionImportsWorkWithoutLeakingAssignments() {
        try (NiScripts scripts =
                new NiScripts(Map.of(), Map.of(), new LegacyScriptBindings(new NiNodes()))) {
            assertEquals(
                    "dev.itemloom.compat.ni.script.LegacySection",
                    scripts.evaluate(
                            "Java.type('pers.neige.neigeitems.section.Section').class.getName()",
                            Map.of()));
            scripts.evaluate("var leaked = 19; this.otherLeak = 7;", Map.of());
            assertEquals(
                    "undefined:undefined",
                    scripts.evaluate("typeof leaked + ':' + typeof otherLeak", Map.of()));
        }
    }

    @Test
    void explicitlyCachelessLegacyCallsRerollNamedNodes() {
        NiNodes nodes = new NiNodes();
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        nodes.register(
                "counter",
                new NiNodes.Extension() {
                    public String configured(NiConfig config, NiEvaluation evaluation) {
                        assertNull(evaluation.legacyCache());
                        return String.valueOf(calls.incrementAndGet());
                    }

                    public String inline(java.util.List<String> args, NiEvaluation evaluation) {
                        return null;
                    }
                });
        NiEvaluation root =
                new NiEvaluation(
                        new GenerationContext(Map.of(), new Random(0)),
                        NiYaml.read("value: {type: counter}", "cacheless"),
                        null,
                        NiEvaluation.Mode.ACTION,
                        nodes,
                        null,
                        null);
        NiEvaluation child = root.legacyContext(null, null, root.sections());
        assertEquals("1/2", child.text("<value>/<value>"));
        assertTrue(child.generation().rolls().isEmpty());
        assertEquals("<unknown::x>", child.text("<unknown::x>"));
    }

    @Test
    void actionScopesRetainTheirOwnScriptVariablesAcrossSteps() {
        try (NiScripts scripts =
                new NiScripts(Map.of(), Map.of(), new LegacyScriptBindings(new NiNodes()))) {
            var first = scripts.scope(Map.of("initial", 3));
            var second = scripts.scope(Map.of("initial", 100));
            scripts.evaluate("var counter = initial;", first);
            assertEquals(4, ((Number) scripts.evaluate("++counter", first)).intValue());
            assertEquals("undefined", scripts.evaluate("typeof counter", second));
            assertEquals(
                    "dev.itemloom.compat.ni.script.LegacySection",
                    scripts.evaluate(
                            "Java.type('pers.neige.neigeitems.section.Section').class.getName()",
                            first));
        }
    }
}
