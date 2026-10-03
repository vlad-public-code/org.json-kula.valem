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
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2", 20).formula(1, 3, "VLOOKUP(A2,A1:B1,2)", 0)
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3", 60).formula(2, 3, "VLOOKUP(A3,A1:B1,2)", 0);

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
                .formula(1, 0, "VLOOKUP(A2,A1:B1,2)", 0)
                .formula(2, 0, "VLOOKUP(A3,A1:B1,2)", 0);

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
    void duplicate_header_columns_keep_their_own_distinct_data_and_field_names() {
        // Two columns both named "Amount" -- the bug this guards against: resolving a column's
        // physical index by header-text lookup always finds the FIRST match, so the second
        // "Amount" column would silently read/seed/validate against the first one's cell data, and
        // its schema property would silently overwrite the first's under the same field name.
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Amount").str(0, 1, "Amount")
                .num(1, 0, 100).num(1, 1, 999)
                .num(2, 0, 200).num(2, 1, 888);

        CompileResult result = SpreadsheetCompiler.compile(grid, "dup-headers", MAPPER);

        assertThat(result.rejectedColumns()).isEmpty();
        // Each column's OWN values must appear, under two DIFFERENT field names.
        String seedExpr = result.spec().defaultValues().get(0).expr();
        assertThat(seedExpr).contains("100.0").contains("999.0").contains("200.0").contains("888.0");
        JsonNode itemProps = MAPPER.valueToTree(result.spec().schema())
                .path("properties").path("items").path("items").path("properties");
        assertThat(itemProps.has("amount")).isTrue();
        assertThat(itemProps.has("amount2")).isTrue();
    }

    @Test
    void a_summary_aggregate_over_only_literal_columns_still_gets_a_self_test() {
        // No per-item FORMULA column at all here -- only the bottom-row SUM makes this sheet
        // interesting. buildSeedDefaultValue's `expect` stays empty (nothing per-item to assert),
        // so `tests` must not be left empty just because there was nothing else to verify: the
        // aggregate itself still needs checking against the sheet's own computed total.
        // A second literal column is needed so the summary row (only "Amount" filled, "Label"
        // blank) is distinguishable from a real data row -- a single-column table can't tell a
        // trivially-"fully populated" totals row apart from one with actual data (table detection
        // requires every table COLUMN populated to continue the data-row span, §4).
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Label").str(0, 1, "Amount")
                .str(1, 0, "a").num(1, 1, 10)
                .str(2, 0, "b").num(2, 1, 20)
                .formula(3, 1, "SUM(B2:B3)", 30);

        CompileResult result = SpreadsheetCompiler.compile(grid, "literal-plus-total", MAPPER);

        assertThat(result.spec().tests()).hasSize(1);
        List<TestCaseRunner.TestResult> testResults = TestCaseRunner.run(result.spec(), result.spec().tests());
        assertThat(testResults).hasSize(1);
        assertThat(testResults.get(0).passed())
                .as("failures: %s", testResults.get(0).failures()).isTrue();
        assertThat(result.spec().tests().get(0).expect()).containsKey("$.sumAmount");
    }

    @Test
    void a_text_formula_column_is_typed_string_in_the_schema_and_passes_the_real_engine() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "First").str(0, 1, "Last").str(0, 2, "FullName")
                .str(1, 0, "Ada").str(1, 1, "Lovelace").formula(1, 2, "A2&\" \"&B2", "Ada Lovelace")
                .str(2, 0, "Alan").str(2, 1, "Turing").formula(2, 2, "A3&\" \"&B3", "Alan Turing");

        CompileResult result = SpreadsheetCompiler.compile(grid, "names", MAPPER);

        assertThat(result.rejectedColumns()).isEmpty();
        ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(result.spec());
        assertThat(validation.isValid()).as("validation errors: %s", validation.errors()).isTrue();

        JsonNode itemProps = MAPPER.valueToTree(result.spec().schema())
                .path("properties").path("items").path("items").path("properties");
        assertThat(itemProps.path("fullName").path("type").asText()).isEqualTo("string");

        List<TestCaseRunner.TestResult> testResults = TestCaseRunner.run(result.spec(), result.spec().tests());
        assertThat(testResults).hasSize(1);
        assertThat(testResults.get(0).passed())
                .as("failures: %s", testResults.get(0).failures()).isTrue();
    }

    @Test
    void a_percent_of_total_formula_using_a_whole_column_aggregate_passes_the_real_engine() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Price").str(0, 1, "ShareOfTotal")
                .num(1, 0, 10).formula(1, 1, "A2/SUM($A$2:$A$3)", 0.25)
                .num(2, 0, 30).formula(2, 1, "A3/SUM($A$2:$A$3)", 0.75);

        CompileResult result = SpreadsheetCompiler.compile(grid, "share-of-total", MAPPER);

        assertThat(result.rejectedColumns()).isEmpty();
        ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(result.spec());
        assertThat(validation.isValid()).as("validation errors: %s", validation.errors()).isTrue();

        List<TestCaseRunner.TestResult> testResults = TestCaseRunner.run(result.spec(), result.spec().tests());
        assertThat(testResults).hasSize(1);
        assertThat(testResults.get(0).passed())
                .as("failures: %s", testResults.get(0).failures()).isTrue();
    }

    @Test
    void a_vlookup_against_a_side_table_passes_the_real_engine() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Category").str(0, 1, "Rate")
                .str(0, 5, "A").num(0, 6, 10)
                .str(1, 5, "B").num(1, 6, 20)
                .str(1, 0, "B").formula(1, 1, "VLOOKUP(A2,$F$1:$G$2,2,FALSE)", 20)
                .str(2, 0, "A").formula(2, 1, "VLOOKUP(A3,$F$1:$G$2,2,FALSE)", 10);

        CompileResult result = SpreadsheetCompiler.compile(grid, "vlookup-rates", MAPPER);

        assertThat(result.rejectedColumns()).isEmpty();
        assertThat(result.spec().library()).isNotNull();
        assertThat(result.spec().library().ownLayer().define()).contains("$lookupF1").contains("\"lookupF1\"");

        ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(result.spec());
        assertThat(validation.isValid()).as("validation errors: %s", validation.errors()).isTrue();

        List<TestCaseRunner.TestResult> testResults = TestCaseRunner.run(result.spec(), result.spec().tests());
        assertThat(testResults).hasSize(1);
        assertThat(testResults.get(0).passed())
                .as("failures: %s", testResults.get(0).failures()).isTrue();
    }

    @Test
    void sumproduct_of_two_whole_columns_becomes_a_top_level_dot_product_derivation() {
        // The vision doc's "array formulas" non-goal, scoped to its single most common real-world
        // shape: a weighted-sum / dot-product summary cell. Not a legacy CSE {=...} array formula
        // -- SUMPRODUCT is a normal, directly-callable function -- but it IS the computation legacy
        // array formulas are most often used for, and needs no CSE entry at all.
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Qty").str(0, 1, "Price")
                .num(1, 0, 2).num(1, 1, 10)
                .num(2, 0, 3).num(2, 1, 20)
                // Qty*Price summed: 2*10 + 3*20 = 80.
                .formula(3, 1, "SUMPRODUCT($A$2:$A$3,$B$2:$B$3)", 80);

        CompileResult result = SpreadsheetCompiler.compile(grid, "weighted-total", MAPPER);

        assertThat(result.rejectedColumns()).isEmpty();
        JsonNode itemProps = MAPPER.valueToTree(result.spec().schema())
                .path("properties").path("items").path("items").path("properties");
        assertThat(itemProps.has("sumproductQtyPrice")).isFalse(); // it's a ROOT field, not per-item
        JsonNode rootProps = MAPPER.valueToTree(result.spec().schema()).path("properties");
        assertThat(rootProps.has("sumproductQtyPrice")).isTrue();

        ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(result.spec());
        assertThat(validation.isValid()).as("validation errors: %s", validation.errors()).isTrue();

        List<TestCaseRunner.TestResult> testResults = TestCaseRunner.run(result.spec(), result.spec().tests());
        assertThat(testResults).hasSize(1);
        assertThat(testResults.get(0).passed())
                .as("failures: %s", testResults.get(0).failures()).isTrue();
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
