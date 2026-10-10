package dev.itemloom.compat.sx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SxScriptBoundaryTest {
    @Test
    void globalLexicalDeclarationsStayWithinTheirEnvironment() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "Global/base.js",
                "let lexical = 2; const fixed = 3; function read() { return lexical + ':' + fixed; }");
        sources.put("Local.js", "function read() { return typeof lexical + ':' + typeof fixed; }");
        try (SxScripts scripts = new SxScripts(input(Map.of(), sources), Map.of())) {
            assertEquals("2:3", scripts.call("Global", "read", null, null));
            assertEquals("undefined:undefined", scripts.call("Local", "read", null, null));
        }
    }

    @Test
    void assigningAnInheritedScalarShadowsItWithinThatFile() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "First.js",
                "count += 1; function value() { return count; } function bump() { return ++count; }");
        sources.put("Global/base.js", "var count = 1; function value() { return count; }");
        sources.put(
                "Second.js",
                "var initial = count; function value() { return initial + ':' + count + ':' + First.value(); }");
        try (SxScripts scripts = new SxScripts(input(Map.of(), sources), Map.of())) {
            assertEquals(2, ((Number) scripts.call("First", "value", null, null)).intValue());
            assertEquals(1, ((Number) scripts.call("Global", "value", null, null)).intValue());
            assertEquals("1:1:2", scripts.call("Second", "value", null, null));
            assertEquals(3, ((Number) scripts.call("First", "bump", null, null)).intValue());
            assertEquals("1:1:3", scripts.call("Second", "value", null, null));
        }
    }

    @Test
    void globalFilesRunFirstInEncounterOrderAndLocalsImportEarlierRoutes() {
        List<String> log = new ArrayList<>();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "nested/Alpha.extra.js",
                "log.add('A'); var own = 'alpha'; function inspect() { return this.own; }");
        sources.put(
                "Global/second.js",
                "log.add('G2'); var state = { count: 1 }; function count() { return state.count; }");
        sources.put(
                "Beta.js",
                "log.add('B'); var own = 'beta'; var imported = Alpha.inspect(); state.count += 2; function inspect() { return imported + ':' + this.own + ':' + count(); }");
        sources.put("Global/first.js", "log.add('G1'); state.count += 3;");
        try (SxScripts scripts = new SxScripts(input(Map.of(), sources), Map.of("log", log))) {
            assertEquals(List.of("G2", "G1", "A", "B"), log);
            assertEquals("alpha", scripts.call("Alpha", "inspect", null, null));
            assertEquals("alpha:beta:6", scripts.call("Beta", "inspect", null, null));
            assertEquals(6, ((Number) scripts.call("Global", "count", null, null)).intValue());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scripts.call("Global", "inspect", null, null));
        }
        assertEquals(List.of("G2", "G1", "A", "B"), log);
    }

    @Test
    void callsPreserveHandlerArrayObjectsAndScopeThis() {
        SxExpressions handler = SxExpressionsTest.handler(Map.of(), Map.of(), Map.of());
        Object marker = new Object();
        Object[] arguments = {"payload", marker};
        Map<String, Object> globals =
                Map.of(
                        "expectedHandler",
                        handler,
                        "expectedArguments",
                        arguments,
                        "marker",
                        marker);
        String source =
                "var own = 'scope'; function inspect(h, a) { return arguments.length + ':' + (h === expectedHandler) + ':' + (a === expectedArguments) + ':' + a.length + ':' + a[0] + ':' + (a[1] === marker) + ':' + this.own; } function original() { return marker; } function nil(h, a) { return arguments.length === 2 && h === null && a === null; }";
        try (SxScripts scripts =
                new SxScripts(input(Map.of(), Map.of("Example.js", source)), globals)) {
            assertEquals(
                    "2:true:true:2:payload:true:scope",
                    scripts.call("Example", "inspect", handler, arguments));
            assertSame(marker, scripts.call("Example", "original", null, null));
            assertEquals(true, scripts.call("Example", "nil", null, null));
        }
    }

    @Test
    void filenameIsAvailableDuringInitializationAndEs6IsEnabled() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "Global/init.js",
                "var globalPath = this['javax.script.filename']; function path() { return globalPath; }");
        sources.put(
                "missing-directory/Example.js",
                "const prefix = 'ES6:'; let localPath = this['javax.script.filename']; var describe = () => prefix + localPath; function path() { return describe(); }");
        try (SxScripts scripts = new SxScripts(input(Map.of(), sources), Map.of())) {
            assertEquals("Global/init.js", scripts.call("Global", "path", null, null));
            assertEquals(
                    "ES6:missing-directory/Example.js",
                    scripts.call("Example", "path", null, null));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"js", "JS", "JavaScript", "JAVASCRIPT", "nashorn", "NASHORN"})
    void acceptsOnlySupportedEngineAliases(String alias) {
        try (SxScripts scripts =
                new SxScripts(
                        input(
                                Map.of("ScriptEngine", alias),
                                Map.of("Test.js", "function value() { return 7; }")),
                        Map.of())) {
            assertEquals(7, ((Number) scripts.call("Test", "value", null, null)).intValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"python", "js ", " js", "null", "graal.js"})
    void validatesEngineBeforeCheckingEmptyInputs(String alias) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SxScripts(input(Map.of("ScriptEngine", alias), Map.of()), Map.of()));
    }

    @Test
    void disabledOrEmptyRuntimesReturnNullUntilClosed() {
        for (String engine : new String[] {null, ""}) {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("ScriptEngine", engine);
            SxScripts scripts =
                    new SxScripts(input(settings, Map.of("Bad.js", "function {")), Map.of());
            assertNull(scripts.call("missing", "missing", null, null));
            scripts.close();
            scripts.close();
            assertThrows(
                    IllegalStateException.class,
                    () -> scripts.call("missing", "missing", null, null));
        }
        SxScripts empty = new SxScripts(input(Map.of(), Map.of()), Map.of());
        assertNull(empty.call("missing", "missing", null, null));
        empty.close();
        assertThrows(
                IllegalStateException.class, () -> empty.call("missing", "missing", null, null));
    }

    @Test
    void duplicateAndReservedRoutesFailBeforeTheirSourceExecutes() {
        for (String reserved : List.of("Global", "Taken", "Object", "anchor")) {
            List<String> log = new ArrayList<>();
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put("Global/base.js", "log.add('global'); var Taken = {}; ");
            sources.put(reserved + ".js", "log.add('forbidden');");
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new SxScripts(
                                            input(Map.of(), sources),
                                            Map.of("log", log, "anchor", new Object())));
            assertTrue(error.getMessage().contains(reserved + ".js"));
            assertEquals(List.of("global"), log);
        }
        List<String> log = new ArrayList<>();
        Map<String, String> duplicate = new LinkedHashMap<>();
        duplicate.put("one/Entry.js", "log.add('first');");
        duplicate.put("two/Entry.extra.js", "log.add('forbidden');");
        assertThrows(
                IllegalArgumentException.class,
                () -> new SxScripts(input(Map.of(), duplicate), Map.of("log", log)));
        assertEquals(List.of("first"), log);
    }

    @Test
    void missingRoutesFunctionsAndScriptFailuresIdentifyTheirBoundary() {
        for (String source : List.of("function {", "throw new Error('init failure');")) {
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new SxScripts(
                                            input(Map.of(), Map.of("nested/Bad.js", source)),
                                            Map.of()));
            assertTrue(error.getMessage().contains("nested/Bad.js"));
            assertNotNull(error.getCause());
        }
        SxScripts scripts =
                new SxScripts(
                        input(
                                Map.of(),
                                Map.of(
                                        "Test.js",
                                        "function fail() { throw new Error('call failure'); }")),
                        Map.of());
        IllegalArgumentException missing =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> scripts.call("Missing", "run", null, null));
        assertTrue(missing.getMessage().contains("Missing"));
        for (String name : List.of("fail", "missing")) {
            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> scripts.call("Test", name, null, null));
            assertTrue(error.getMessage().contains("Test." + name));
            assertNotNull(error.getCause());
        }
        scripts.close();
        scripts.close();
        assertThrows(IllegalStateException.class, () -> scripts.call("Test", "fail", null, null));
    }

    private static SxRepository.Input input(
            Map<String, Object> settings, Map<String, String> sources) {
        return new SxRepository.Input(
                new SxConfig(settings), Map.of(), Map.of(), sources, Map.of());
    }
}
