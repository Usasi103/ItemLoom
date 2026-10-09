package dev.itemloom.probe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiEvaluation;
import dev.itemloom.compat.ni.NiNodes;
import dev.itemloom.compat.ni.NiScripts;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.core.GenerationContext;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/** Differential language checks execute real NI entry points in the reference plugin loader. */
final class NodeProbe {
    private record Fixture(String text, String yaml) {}

    private record Outcome(Object value, String failure) {}

    private interface Checked {
        Object get() throws Exception;
    }

    static Map<String, Object> run(Plugin reference) throws Exception {
        if (reference == null || !reference.isEnabled())
            throw new IllegalStateException("NI reference is required");
        ClassLoader loader = reference.getClass().getClassLoader();
        Class<?> contextType = loader.loadClass("pers.neige.neigeitems.action.ActionContext");
        Class<?> contextKey = loader.loadClass("pers.neige.neigeitems.action.ContextKey");
        Class<?> keys = loader.loadClass("pers.neige.neigeitems.action.ContextKeys");
        Class<?> managerType = loader.loadClass("pers.neige.neigeitems.manager.ActionManager");
        Object manager = managerType.getField("INSTANCE").get(null);
        var modernParse = managerType.getMethod("parseNode", String.class, contextType);
        var oldParse =
                loader.loadClass("pers.neige.neigeitems.utils.SectionUtils")
                        .getMethod(
                                "parseSection",
                                String.class,
                                Map.class,
                                OfflinePlayer.class,
                                ConfigurationSection.class);
        var reader =
                loader.loadClass("pers.neige.neigeitems.config.ConfigReader")
                        .getMethod("parse", ConfigurationSection.class);
        List<Fixture> fixtures = new ArrayList<>();
        for (String text :
                List.of(
                        "<number::1_1>",
                        "<number::1.25_1.25_1_DOWN>",
                        "<number::1.25_1.25_1_0>",
                        "<number::bad_1>",
                        "<number::1>",
                        "<number::1_1_bad>",
                        "<number::__0>",
                        "<number::1_1_0_UNNECESSARY>",
                        "<gaussian::100_0_0>",
                        "<gaussian::100_0_0_2_150_200>",
                        "<gaussian::100_0_0_bad>",
                        "<gaussian::100_0_0_0_bad_200>",
                        "<chance::0_1_3>",
                        "<chance::1_1_3>",
                        "<chance::bad_1_3>",
                        "<chance::1_1_bad>",
                        "<chance::0_1_1_bad>",
                        "<calculation::2+3*4>",
                        "<calculation::1/3_3>",
                        "<calculation::2+2_0_7_9>",
                        "<calculation::missing.function()>",
                        "<calculation::4_bad>",
                        "<fastcalc::2^3^2>",
                        "<fastcalc::(2+3)*4>",
                        "<fastcalc::1+-2>",
                        "<format::1.25_0.0>",
                        "<format::2.75_0.0_DOWN>",
                        "<strings::only>",
                        "<weight::1::only>",
                        "<default::missing_fallback>",
                        "<inherit::missing>",
                        "<unknown::keep>",
                        "<#aabbcc>",
                        "<string::upper_aBc>",
                        "<string::contains_b_abc>",
                        "<string::replace_b_X_abc>",
                        "<string::substring_1_3_fallback_abcdef>",
                        "<regex::matches_ABC_[a-z]+_i>",
                        "<regex::group_a12b_([0-9]+)_1>",
                        "<gradient::FF0000_0000FF_2_ab>")) {
            fixtures.add(new Fixture(text, "{}"));
        }
        for (String yaml :
                List.of(
                        "test: {type: number, min: 1.25, max: 1.25, fixed: 1, mode: 0}",
                        "test: {type: gaussian, base: 100, spread: 0, maxSpread: 0}",
                        "test: {type: format, value: 1.25, format: '0.0'}",
                        "test: {type: join, list: [a, b], limit: 0, truncated: '...', prefix: '[', postfix: ']'}",
                        "test: {type: repeat, content: a, repeat: 2, separator: ','}",
                        "test: {type: weightjoin, list: ['1::a', '1::b'], amount: 2, order: true}",
                        "test: {type: weightdeclare, list: ['1::a'], amount: 1, key: chosen, putelse: true}",
                        "test: {type: rweightjoin, list: ['1::a', '1::a'], amount: 2}",
                        "test: {type: when, value: abc, conditions: [{condition: 'value == \"abc\"', result: yes}, no]}",
                        "test: {type: when, value: abc, conditions: [{condition: 'global.value == \"abc\"', result: yes}, no]}",
                        "test: {type: unknown, value: untouched}")) {
            fixtures.add(new Fixture("<test>", yaml));
        }
        Map<String, Object> differences = new LinkedHashMap<>();
        Map<String, Object> intentionalCorrections = new LinkedHashMap<>();
        int checked = 0;
        try (NiScripts scripts = new NiScripts(Map.of(), Map.of())) {
            for (NiEvaluation.Mode mode : NiEvaluation.Mode.values())
                for (Fixture fixture : fixtures) {
                    NiConfig config = NiYaml.read(fixture.yaml(), "node probe");
                    var section = NiYaml.toSection(config);
                    Map<String, String> oldCache = new HashMap<>();
                    Outcome expected;
                    if (mode == NiEvaluation.Mode.ACTION) {
                        Object builder = contextType.getMethod("builder").invoke(null);
                        var with = builder.getClass().getMethod("with", contextKey, Object.class);
                        with.invoke(builder, keys.getField("SECTION_CACHE").get(null), oldCache);
                        with.invoke(
                                builder,
                                keys.getField("SECTIONS").get(null),
                                reader.invoke(null, section));
                        Object context = builder.getClass().getMethod("build").invoke(builder);
                        expected =
                                outcome(() -> modernParse.invoke(manager, fixture.text(), context));
                    } else
                        expected =
                                outcome(
                                        () ->
                                                oldParse.invoke(
                                                        null,
                                                        fixture.text(),
                                                        oldCache,
                                                        null,
                                                        section));
                    var evaluation =
                            new NiEvaluation(
                                    new GenerationContext(Map.of(), new Random(1)),
                                    config,
                                    null,
                                    mode,
                                    new NiNodes(),
                                    scripts,
                                    null);
                    Outcome actual = outcome(() -> evaluation.text(fixture.text()));
                    checked++;
                    if (!Objects.equals(expected, actual)
                            || !oldCache.equals(evaluation.generation().rolls())) {
                        Map<String, Object> difference = new LinkedHashMap<>();
                        difference.put("fixture", fixture);
                        difference.put("expected", expected);
                        difference.put("actual", actual);
                        difference.put("expectedCache", oldCache);
                        difference.put("actualCache", evaluation.generation().savedRolls());
                        boolean formatFix =
                                mode == NiEvaluation.Mode.ACTION
                                        && "java.lang.IllegalArgumentException"
                                                .equals(expected.failure())
                                        && actual.failure() == null
                                        && (fixture.text().startsWith("<format::")
                                                || fixture.yaml().contains("type: format"));
                        (formatFix ? intentionalCorrections : differences)
                                .put(mode + ":" + checked, difference);
                    }
                }
        }
        return Map.of(
                "checked",
                checked,
                "differences",
                differences,
                "intentionalCorrections",
                intentionalCorrections);
    }

    private static Outcome outcome(Checked operation) {
        try {
            return new Outcome(operation.get(), null);
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            return new Outcome(null, error.getClass().getName());
        }
    }
}
