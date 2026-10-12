package dev.itemloom.compat.ni.script;

import dev.itemloom.compat.ni.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

public final class LegacyCustomSection implements NiNodes.Extension {
    @FunctionalInterface
    public interface Configured {
        String invoke(
                ConfigurationSection data,
                Map<String, String> cache,
                Object player,
                ConfigurationSection sections);
    }

    @FunctionalInterface
    public interface Inline {
        String invoke(
                List<String> args,
                Map<String, String> cache,
                Object player,
                ConfigurationSection sections);
    }

    private final String id;
    private final Configured configured;
    private final Inline inline;

    public LegacyCustomSection(String id, Configured configured, Inline inline) {
        this.id = id;
        this.configured = configured;
        this.inline = inline;
    }

    public String getId() {
        return id;
    }

    @Override
    public String configured(NiConfig config, NiEvaluation evaluation) {
        return configured == null
                ? null
                : configured.invoke(
                        NiYaml.toSection(config),
                        evaluation.legacyCache(),
                        evaluation.player(),
                        NiYaml.toSection(evaluation.sections()));
    }

    @Override
    public String inline(List<String> arguments, NiEvaluation evaluation) {
        return inline == null
                ? null
                : inline.invoke(
                        arguments,
                        evaluation.legacyCache(),
                        evaluation.player(),
                        NiYaml.toSection(evaluation.sections()));
    }
}
