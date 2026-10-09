package dev.itemloom.compat.ni;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NiConfigTest {
    @TempDir Path directory;

    @Test
    void generatedMapCoercionAndLiteralPathsDifferFromBukkitSource() {
        String yaml = "number: '17'\nenabled: 'true'\noptions: {charge: '8'}\n'a.b': yes\n";
        NiConfig source = NiYaml.read(yaml, "source"),
                generated = NiYaml.readGenerated(yaml, "expanded");
        assertEquals(0, source.integer("number", 0));
        assertEquals(17, generated.integer("number", 0));
        assertFalse(source.bool("enabled", false));
        assertTrue(generated.bool("enabled", false));
        assertEquals(8, generated.section("options").integer("charge", 0));
        assertEquals(true, generated.get("a\\.b"));
        assertNull(generated.get("a.b"));
    }

    @Test
    void generatedOptionIterationRetainsObservedLegacyAliasPrecedence() {
        NiConfig config =
                NiYaml.readGenerated("options: {durability: 5, maxdurability: 6}", "alias order");
        assertEquals(
                java.util.List.of("maxdurability", "durability"),
                new java.util.ArrayList<>(config.section("options").keys()));
    }

    private NiRepository.Input input(String items, String globals) throws Exception {
        Files.createDirectories(directory.resolve("Items"));
        Files.createDirectories(directory.resolve("GlobalSections"));
        Files.writeString(directory.resolve("Items/items.yml"), items);
        Files.writeString(directory.resolve("GlobalSections/global.yml"), globals);
        return new NiRepository().read(directory);
    }

    @Test
    void scalarAndNestedInheritancePreserveOverrideOrder() throws Exception {
        var input =
                input(
                        """
                first:
                  material: STONE
                  lore: [one, two]
                  options: {durability: 10, charge: 2}
                second:
                  material: DIAMOND_SWORD
                  options: {durability: 20}
                both:
                  inherit: [first, second]
                  lore: [override]
                partial:
                  inherit:
                    options:
                      charge: first
                      durability: second
                  material: STICK
                """,
                        "{}");
        var resolved = new NiInheritance(input).resolveAll();
        assertEquals("DIAMOND_SWORD", resolved.get("both").string("material"));
        assertEquals(java.util.List.of("override"), resolved.get("both").strings("lore"));
        assertEquals(2, resolved.get("both").integer("options.charge", 0));
        assertEquals(20, resolved.get("partial").integer("options.durability", 0));
        assertEquals(2, resolved.get("partial").integer("options.charge", 0));
        assertFalse(input.items().get("both").config().contains("material"));
    }

    @Test
    void globalImportsOverrideLocalNodesAndAreInherited() throws Exception {
        var input =
                input(
                        """
                base:
                  material: STONE
                  globalsections: [global.yml]
                  sections: {quality: local}
                child:
                  inherit: base
                  sections: {quality: child}
                """,
                        "quality: global\npool: {low: '1', high: '2'}\n");
        var child = new NiInheritance(input).resolve("child");
        assertEquals("global", child.string("sections.quality"));
        assertEquals("1", child.string("sections.pool.low"));
        assertFalse(child.contains("globalsections"));
    }

    @Test
    void reloadSnapshotIncludesScriptEditsAndNewDefinitions() throws Exception {
        NiRepository repository = new NiRepository();
        var snapshot = input("valid: {material: STONE}", "{}");
        repository.verifyUnchanged(directory, snapshot);
        Files.createDirectories(directory.resolve("Scripts"));
        Path script = directory.resolve("Scripts/new.js");
        Files.writeString(script, "function main() { return 1; }");
        assertThrows(
                java.io.IOException.class, () -> repository.verifyUnchanged(directory, snapshot));
        var withScript = repository.read(directory);
        Files.writeString(script, "function main() { return 2; }");
        assertThrows(
                java.io.IOException.class, () -> repository.verifyUnchanged(directory, withScript));
        assertEquals("function main() { return 2; }", Files.readString(script));
    }

    @Test
    void noPartialResultOrWritesOnInvalidReloadInput() throws Exception {
        input("valid: {material: STONE}", "{}");
        Path invalid = directory.resolve("Items/bad.yml");
        String contents = "broken: [\n";
        Files.writeString(invalid, contents);
        var error =
                assertThrows(
                        IllegalArgumentException.class, () -> new NiRepository().read(directory));
        assertTrue(error.getMessage().contains("Items/bad.yml"));
        assertEquals(contents, Files.readString(invalid));
        try (var files = Files.walk(directory)) {
            assertEquals(3, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void itemPreprocessingPreservesRawSnapshotAndLeavesOtherCategoriesAlone() throws Exception {
        input("valid: {material: STONE, name: '%player_name%'}\n", "name: '%player_name%'\n");
        Path item = directory.resolve("Items/items.yml");
        byte[] before = Files.readAllBytes(item);
        NiRepository repository = new NiRepository();
        var snapshot =
                repository.read(
                        directory, value -> value.replace("%player_name%", "<papi::player_name>"));
        assertEquals("<papi::player_name>", snapshot.items().get("valid").config().string("name"));
        assertEquals("%player_name%", snapshot.globalValues().get("name"));
        assertEquals(
                new String(before, java.nio.charset.StandardCharsets.UTF_8),
                snapshot.sources().get("Items/items.yml"));
        assertArrayEquals(before, Files.readAllBytes(item));
        repository.verifyUnchanged(directory, snapshot);
        Files.writeString(item, "valid: {material: DIRT}\n");
        assertThrows(
                java.io.IOException.class, () -> repository.verifyUnchanged(directory, snapshot));
    }

    @Test
    void inheritanceCyclesHaveSourceAndChain() throws Exception {
        var data = input("a: {inherit: b}\nb: {inherit: a}\n", "{}");
        var error =
                assertThrows(
                        IllegalArgumentException.class, () -> new NiInheritance(data).resolve("a"));
        assertTrue(error.getMessage().contains("Items/items.yml"));
        assertTrue(error.getMessage().contains("a -> b -> a"));
    }

    @Test
    void yamlRoundtripAfterExpansionCanProduceMultipleLoreLines() {
        var raw = NiYaml.read("material: STONE\nlore:\n- '<lines>'\n", "test");
        String serialized = NiYaml.write(raw);
        // The input language permits a node to emit YAML syntax, including list entries.
        String expanded = NiTemplate.compile(serialized).render(key -> "one\n- two");
        var parsed = NiYaml.read(expanded, "generated");
        assertEquals(java.util.List.of("one", "two"), parsed.strings("lore"));
    }

    @Test
    void realConfigurationTreeReadsAndResolvesWithoutModification() throws Exception {
        String root = System.getProperty("niConfigRoot");
        Assumptions.assumeTrue(root != null, "Explicit real configuration path is required");
        NiRepository.Input input = new NiRepository().read(Path.of(root));
        assertFalse(input.items().isEmpty());
        Map<String, NiConfig> resolved = new NiInheritance(input).resolveAll();
        assertEquals(input.items().keySet(), resolved.keySet());
        // Every byte-bearing source string is reread after the operation.
        NiRepository.Input after = new NiRepository().read(Path.of(root));
        assertEquals(input.sources(), after.sources());
    }

    @Test
    void frozenMapsAndOverlaysDoNotAliasInputs() {
        var values = new HashMap<String, Object>();
        values.put("options", new HashMap<>(Map.of("charge", 3)));
        var original = new NiConfig(values);
        var changed = original.with("options.charge", 4);
        assertEquals(3, original.integer("options.charge", 0));
        assertEquals(4, changed.integer("options.charge", 0));
        assertThrows(UnsupportedOperationException.class, () -> original.values().clear());
    }
}
