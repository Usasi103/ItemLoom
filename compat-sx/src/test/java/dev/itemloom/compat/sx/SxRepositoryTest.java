package dev.itemloom.compat.sx;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SxRepositoryTest {
    @TempDir Path root;

    void write(String file, String text) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, text);
    }

    @Test
    void readsOriginalTextWithNestedFilesAliasesAnchorsAndDottedKeys() throws Exception {
        String source =
                "# unchanged\nBase:\n  ID: &material PAPER\n  Name: '<l:grade>'\nCopy:\n  ID: *material\nAlias: Base\nShort.ID: APPLE\nNoLoad_ignored:\n  Type: NotImplemented\n";
        write("Item/sub/items.yml", source);
        write("RandomString/random.yml", "grade: [rare, rare, common]\n");
        write("Item/NoLoad_backup/broken.yml", "[:broken");
        write("Scripts/NoLoad_bad.js", "!not-js");
        SxRepository repo = new SxRepository();
        var input = repo.read(root);
        assertEquals(4, input.items().size());
        assertEquals("Base", input.items().get("Alias").alias());
        assertEquals("PAPER", input.items().get("Copy").config().text("ID", null));
        assertEquals("APPLE", input.items().get("Short").config().text("ID", null));
        assertEquals(source, Files.readString(root.resolve("Item/sub/items.yml")));
        repo.verifyUnchanged(root, input);
        write("RandomString/random.yml", "grade: changed\n");
        assertThrows(java.io.IOException.class, () -> repo.verifyUnchanged(root, input));
    }

    @Test
    void rejectsDuplicatesBadYamlAndAddedSourceDuringPreparation() throws Exception {
        write("Item/a.yml", "A:\n  ID: PAPER\n");
        SxRepository repository = new SxRepository();
        var first = repository.read(root);
        write("Item/b.yml", "A:\n  ID: STONE\n");
        assertThrows(IllegalArgumentException.class, () -> repository.read(root));
        assertThrows(java.io.IOException.class, () -> repository.verifyUnchanged(root, first));
        write("Item/b.yml", "invalid: [\n");
        assertThrows(IllegalArgumentException.class, () -> repository.read(root));
    }

    @Test
    void scriptsRetainGlobalAndLocalScopesAndCallingConvention() {
        var input =
                new SxRepository.Input(
                        new SxConfig(Map.of()),
                        Map.of(),
                        Map.of(),
                        new java.util.LinkedHashMap<>(
                                Map.of(
                                        "Global/base.js",
                                                "var prefix = 'global'; function label() { return prefix; }",
                                        "Folder/Example.js",
                                                "function run(handler, args) { return [label(), handler.replace('<l:grade>'), args[0]]; }")),
                        Map.of());
        var handler = SxExpressionsTest.handler(Map.of("grade", "rare"), Map.of(), Map.of());
        try (var scripts = new SxScripts(input, Map.of())) {
            Object value = scripts.call("Example", "run", handler, new Object[] {"argument"});
            assertInstanceOf(javax.script.Bindings.class, value);
            assertEquals(
                    java.util.List.of("global", "rare", "argument"),
                    new java.util.ArrayList<>(((javax.script.Bindings) value).values()));
        }
    }

    @Test
    void badScriptsAndUnknownEngineFailPreparation() {
        var input =
                new SxRepository.Input(
                        new SxConfig(Map.of()),
                        Map.of(),
                        Map.of(),
                        Map.of("Bad.js", "function {"),
                        Map.of());
        assertThrows(IllegalArgumentException.class, () -> new SxScripts(input, Map.of()));
        var python =
                new SxRepository.Input(
                        new SxConfig(Map.of("ScriptEngine", "python")),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of());
        assertThrows(IllegalArgumentException.class, () -> new SxScripts(python, Map.of()));
    }

    @Test
    void explicitlyDisabledScriptsDoNotEvaluateBrokenSource() {
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        settings.put("ScriptEngine", null);
        var input =
                new SxRepository.Input(
                        new SxConfig(settings),
                        Map.of(),
                        Map.of(),
                        Map.of("Bad.js", "function {"),
                        Map.of());
        try (var scripts = new SxScripts(input, Map.of())) {
            assertNull(scripts.call("Bad", "ignored", null, null));
        }
    }
}
