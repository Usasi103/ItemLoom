package dev.itemloom.paper.runtime;

import dev.itemloom.core.ActionFlow;
import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface FunctionAccess {
    CompletionStage<ActionFlow.Result> run(
            String id, Object caster, Map<String, Object> parameters);
}
