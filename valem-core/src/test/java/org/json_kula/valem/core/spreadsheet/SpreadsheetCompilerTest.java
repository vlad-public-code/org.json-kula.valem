package org.json_kula.valem.core.spreadsheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.spreadsheet.SpreadsheetCompiler.CompileResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full pipeline on an in-memory {@link CellGrid} (no POI) — proves the assembled {@link ModelSpec}
 * is valid per {@link ModelSpecValidator} and that its own self-test (built from the sheet's
 * "computed" values, §6/AC-4) actually passes through the real engine, not just that the JSON shape
 * looks right.
 */
class SpreadsheetCompilerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void compiles_a_line_items_sheet_with_a_formula_and_a_total_into_a_passing_model() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Quantity").str(0, 1, "Price").str(0, 2, "Total")
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2", 20)
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3", 60)
                // summary row right below the data: a whole-column SUM of the Total column
                .formula(3, 2, "SUM(C2:C3)", 80);

        CompileResult result = SpreadsheetCompiler.compile(grid, "order-line-items", MAPPER);

        assertThat(result.rejectedColumns()).isEmpty();
        ModelSpec spec = result.spec();
        assertThat(spec.id()).isEqualTo("order-line-items");

        ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(spec);
        assertThat(validation.isValid()).as("validation errors: %s", validation.errors()).isTrue();

        // Run the spec's OWN embedded self-test through the real engine (not just assert JSON shape).
        List<TestCaseRunner.TestResult> testResults = TestCaseRunner.run(spec, spec.tests());
        assertThat(testResults).hasSize(1);
        assertThat(testResults.get(0).passed())
                .as("failures: %s", testResults.get(0).failures()).isTrue();
    }

    @Test
    void a_rejected_column_does_not_fail_the_whole_compile() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Quantity").str(0, 1, "Price").str(0, 2, "Total").str(0, 3, "Bad")
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2", 20).formula(1, 3, "A2&\"x\"", 0)
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3", 60).formula(2, 3, "A3&\"x\"", 0);

        CompileResult result = SpreadsheetCompiler.compile(grid, "partial", MAPPER);

        assertThat(result.rejectedColumns()).hasSize(1);
        assertThat(result.rejectedColumns().get(0).header()).isEqualTo("Bad");
        // The surviving Total column still made it into the schema.
        JsonNode props = MAPPER.valueToTree(result.spec().schema())
                .path("properties").path("items").path("items").path("properties");
        assertThat(props.has("total")).isTrue();
        assertThat(props.has("bad")).isFalse();
    }

    @Test
    void every_column_rejected_throws_rather_than_producing_an_empty_spec() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Bad")
                .formula(1, 0, "A2&\"x\"", 0)
                .formula(2, 0, "A3&\"x\"", 0);

        assertThatThrownBy(() -> SpreadsheetCompiler.compile(grid, "all-bad", MAPPER))
                .isInstanceOf(UnsupportedFormulaException.class);
    }

    @Test
    void literal_columns_become_writable_schema_fields_and_formula_columns_readOnly() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Quantity").str(0, 1, "Price").str(0, 2, "Total")
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2", 20)
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3", 60);

        CompileResult result = SpreadsheetCompiler.compile(grid, "m", MAPPER);

        JsonNode itemProps = MAPPER.valueToTree(result.spec().schema())
                .path("properties").path("items").path("items").path("properties");
        assertThat(itemProps.path("quantity").has("readOnly")).isFalse();
        assertThat(itemProps.path("total").path("readOnly").asBoolean()).isTrue();
    }

    @Test
    void seed_default_value_carries_the_sheets_own_literal_rows() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Quantity").str(0, 1, "Price").str(0, 2, "Total")
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2", 20)
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3", 60);

        CompileResult result = SpreadsheetCompiler.compile(grid, "m", MAPPER);

        assertThat(result.spec().defaultValues()).hasSize(1);
        assertThat(result.spec().defaultValues().get(0).path()).isEqualTo("$");
        assertThat(result.spec().defaultValues().get(0).expr())
                .contains("\"quantity\":2.0").contains("\"price\":10.0");
    }

    @Test
    void no_constraints_are_invented() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Quantity")
                .num(1, 0, 2)
                .num(2, 0, 3);

        CompileResult result = SpreadsheetCompiler.compile(grid, "m", MAPPER);

        assertThat(result.spec().constraints()).isEmpty();
    }
}
