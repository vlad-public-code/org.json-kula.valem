package org.json_kula.valem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creating a model batch-compiles its view and effect {@code response.set} expressions too, so the
 * first {@code GET /view} and the first effect fold-back do not run javac on a request thread (the
 * tic-tac-toe restart on the single-carrier hosted sandbox).
 *
 * <p>Expression literals are namespaced ({@code mswt*}) because compiled expressions live in a
 * process-wide map; a literal another test compiled would look pre-warmed regardless of this change.
 */
class ModelServiceExpressionWarmTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPEC = """
            {
              "id": "warm", "version": "1.0.0",
              "schema": { "type": "object", "properties": {
                  "mswtBoard": { "type": "array", "items": { "type": "string" } },
                  "mswtTurn":  { "type": "string" } } },
              "effects": [ {
                  "id": "reply", "executor": "timer", "trigger": "mswtTurn = \\"O\\"", "afterMs": "2000",
                  "response": { "set": { "$.mswtTurn": "mswtTurn = \\"O\\" ? \\"X\\" : \\"O\\"" } }
              } ],
              "viewDefinition": {
                "renderer": "builtin", "defaultView": "main",
                "views": [ { "id": "main", "components": [
                    { "id": "status", "type": "staticText", "text": "\\"Turn: \\" & $.mswtTurn" },
                    { "id": "cell0", "type": "button", "text": "$.mswtBoard[0] ? $.mswtBoard[0] : \\" \\"",
                      "enabled": "$.mswtTurn = \\"X\\"" },
                    { "id": "note", "type": "staticText", "text": "Total: $" }
                ] } ]
              }
            }
            """;

    @Test
    void create_precompiles_view_and_response_set_expressions() throws Exception {
        ModelRegistry registry = new ModelRegistry();
        ModelService service = new ModelService(registry, new InMemoryBlobStore());

        service.createModel(MAPPER.readValue(SPEC, ModelSpec.class));

        ExpressionCache cache = registry.find("warm").orElseThrow().expressionCache();
        assertThat(cache.isCompiled("\"Turn: \" & $.mswtTurn")).isTrue();
        assertThat(cache.isCompiled("$.mswtBoard[0] ? $.mswtBoard[0] : \" \"")).isTrue();
        assertThat(cache.isCompiled("$.mswtTurn = \"X\"")).isTrue();
        assertThat(cache.isCompiled("mswtTurn = \"O\" ? \"X\" : \"O\"")).isTrue();
        // A "$"-bearing literal that is not JSONata is skipped, without costing the rest their warm-up.
        assertThat(cache.isCompiled("Total: $")).isFalse();

        // …and the view still renders, the literal kept as-is.
        assertThat(service.getEvaluatedView("warm", null).components()).hasSize(3);
    }
}
