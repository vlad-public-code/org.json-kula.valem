package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link DefaultValueApplier} through {@link ModelRuntime#mutate} and
 * {@link ModelRuntime#initialize}, which is how defaults actually fire in production.
 */
class DefaultValueApplierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    // ── New array element ───────────────────────────────────────────────────────

    @Test
    void new_array_element_is_filled_with_defaults() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "{ \\"status\\": \\"pending\\", \\"qty\\": 1 }" }
              ] }
            """);

        rt.mutate(Map.of("$.items[0].sku", F.textNode("A")));

        assertThat(rt.getValue("$.items[0].sku").asText()).isEqualTo("A");
        assertThat(rt.getValue("$.items[0].status").asText()).isEqualTo("pending");
        assertThat(rt.getValue("$.items[0].qty").asInt()).isEqualTo(1);
    }

    @Test
    void caller_provided_field_is_not_overwritten() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "{ \\"qty\\": 1 }" }
              ] }
            """);

        rt.mutate(Map.of(
                "$.items[0].sku", F.textNode("A"),
                "$.items[0].qty", F.numberNode(9)));

        assertThat(rt.getValue("$.items[0].qty").asInt()).isEqualTo(9);
    }

    @Test
    void existing_element_update_does_not_reapply_defaults() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "{ \\"status\\": \\"pending\\" }" }
              ] }
            """);

        rt.mutate(Map.of("$.items[0].sku", F.textNode("A")));   // status defaulted to "pending"
        rt.mutate(Map.of("$.items[0].status", F.textNode("shipped")));
        rt.mutate(Map.of("$.items[0].sku", F.textNode("B")));   // existing element, not new

        assertThat(rt.getValue("$.items[0].status").asText()).isEqualTo("shipped");
    }

    @Test
    void parent_binding_is_the_container_array() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "{ \\"seq\\": $count($parent) }" }
              ] }
            """);

        rt.mutate(Map.of("$.items[0].sku", F.textNode("A")));
        rt.mutate(Map.of("$.items[1].sku", F.textNode("B")));

        assertThat(rt.getValue("$.items[0].seq").asInt()).isEqualTo(1);
        assertThat(rt.getValue("$.items[1].seq").asInt()).isEqualTo(2);
    }

    @Test
    void self_binding_sees_caller_provided_fields() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "{ \\"lineTotal\\": $self.qty * $self.price }" }
              ] }
            """);

        rt.mutate(Map.of(
                "$.items[0].qty",   F.numberNode(2),
                "$.items[0].price", F.numberNode(10)));

        assertThat(rt.getValue("$.items[0].lineTotal").asInt()).isEqualTo(20);
    }

    @Test
    void nested_default_object_fills_nested_absent_leaf() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "{ \\"meta\\": { \\"flag\\": true } }" }
              ] }
            """);

        rt.mutate(Map.of("$.items[0].sku", F.textNode("A")));

        assertThat(rt.getValue("$.items[0].meta.flag").asBoolean()).isTrue();
    }

    @Test
    void non_object_expression_result_is_ignored() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "\\"just a string\\"" }
              ] }
            """);

        rt.mutate(Map.of("$.items[0].sku", F.textNode("A")));

        assertThat(rt.getValue("$.items[0].sku").asText()).isEqualTo("A");
        assertThat(rt.getValue("$.items[0]").size()).isEqualTo(1); // only sku, nothing merged
    }

    // ── New object ──────────────────────────────────────────────────────────────

    @Test
    void newly_created_object_is_filled() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.customer", "expr": "{ \\"country\\": \\"US\\" }" }
              ] }
            """);

        rt.mutate(Map.of("$.customer.name", F.textNode("Ada")));

        assertThat(rt.getValue("$.customer.name").asText()).isEqualTo("Ada");
        assertThat(rt.getValue("$.customer.country").asText()).isEqualTo("US");
    }

    // ── Defaults are visible to derivations in the same cycle ────────────────────

    @Test
    void derivation_sees_defaulted_value_in_same_cycle() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "{ \\"qty\\": 2 }" }
              ],
              "derivations": [
                { "path": "$.items[*].dbl", "expr": "$parent.qty * 2" }
              ] }
            """);

        rt.mutate(Map.of("$.items[0].sku", F.textNode("A")));

        assertThat(rt.getValue("$.items[0].qty").asInt()).isEqualTo(2);
        assertThat(rt.getValue("$.items[0].dbl").asInt()).isEqualTo(4);
    }

    @Test
    void mutated_paths_include_applied_defaults() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "{ \\"qty\\": 1 }" }
              ] }
            """);

        var result = rt.mutate(Map.of("$.items[0].sku", F.textNode("A")));

        assertThat(result.mutatedPaths()).contains("$.items[0].sku", "$.items[0].qty");
    }

    // ── Root "$" seeding at creation (replaces initialState) ─────────────────────

    @Test
    void initialize_seeds_root_from_dollar_rule() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$", "expr": "{ \\"width\\": 0, \\"height\\": 0 }" }
              ],
              "derivations": [
                { "path": "$.area", "expr": "width * height" }
              ] }
            """);

        rt.initialize();

        assertThat(rt.getValue("$.width").asInt()).isEqualTo(0);
        assertThat(rt.getValue("$.height").asInt()).isEqualTo(0);
        assertThat(rt.getValue("$.area").asInt()).isEqualTo(0);
    }

    @Test
    void initialize_is_a_noop_without_a_dollar_rule() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "defaultValues": [
                { "path": "$.items[*]", "expr": "{ \\"qty\\": 1 }" }
              ] }
            """);

        var result = rt.initialize();

        assertThat(result.mutatedPaths()).isEmpty();
        assertThat(rt.getValue("$.items").isMissingNode()).isTrue();
    }

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
