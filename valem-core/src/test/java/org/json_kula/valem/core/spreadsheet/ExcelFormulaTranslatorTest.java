package org.json_kula.valem.core.spreadsheet;

import org.json_kula.valem.core.spreadsheet.ExcelFormulaTranslator.ColumnOutcome;
import org.json_kula.valem.core.spreadsheet.ExcelFormulaTranslator.ColumnResult;
import org.json_kula.valem.core.spreadsheet.ExcelFormulaTranslator.TranslationResult;
import org.json_kula.valem.core.spreadsheet.TableDetector.TableBounds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelFormulaTranslatorTest {

    private static TranslationResult translate(InMemoryCellGrid grid) {
        TableBounds bounds = TableDetector.detect(grid);
        List<ColumnClassification> classes = ColumnClassifier.classify(grid, bounds);
        return new ExcelFormulaTranslator(grid, bounds).translate(classes);
    }

    private static ColumnOutcome outcomeOf(TranslationResult r, String header) {
        return r.columns().stream().filter(c -> c.header().equals(header)).findFirst().orElseThrow().outcome();
    }

    @Test
    void translates_a_simple_multiplication_formula() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Quantity").str(0, 1, "Price").str(0, 2, "Total")
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2")
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3");

        TranslationResult r = translate(grid);

        ColumnOutcome outcome = outcomeOf(r, "Total");
        assertThat(outcome).isInstanceOf(ColumnOutcome.PerItemDerivation.class);
        assertThat(((ColumnOutcome.PerItemDerivation) outcome).jsonataExpr()).isEqualTo("($parent.quantity * $parent.price)");
    }

    @Test
    void field_names_are_lower_camel_case_of_the_header() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Unit Price")
                .num(1, 0, 10)
                .num(2, 0, 20);
        TranslationResult r = translate(grid);
        assertThat(r.columns().get(0).fieldName()).isEqualTo("unitPrice");
    }

    @Test
    void if_function_translates_to_ternary() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Qty").str(0, 1, "Flag")
                .num(1, 0, 2).formula(1, 1, "IF(A2>1,1,0)")
                .num(2, 0, 3).formula(2, 1, "IF(A3>1,1,0)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Flag");
        assertThat(outcome).isInstanceOf(ColumnOutcome.PerItemDerivation.class);
        assertThat(((ColumnOutcome.PerItemDerivation) outcome).jsonataExpr()).isEqualTo("(($parent.qty > 1) ? 1 : 0)");
    }

    @Test
    void absolute_reference_becomes_a_named_constant_from_the_left_label() {
        // Header row 0; data rows 1-2. A constant cell sits at row 1, column E (outside the table's
        // column span), labelled "Tax Rate" in the column immediately to its left (column D).
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Price").str(0, 1, "Total")
                .str(1, 3, "Tax Rate").num(1, 4, 0.2)
                .num(1, 0, 10).formula(1, 1, "A2*$E$2")
                .num(2, 0, 20).formula(2, 1, "A3*$E$2");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Total");
        assertThat(outcome).isInstanceOf(ColumnOutcome.PerItemDerivation.class);
        assertThat(((ColumnOutcome.PerItemDerivation) outcome).jsonataExpr()).isEqualTo("($parent.price * $const.taxRate)");
        assertThat(r.constants()).containsEntry("taxRate", 0.2);
    }

    @Test
    void row_local_sum_range_unrolls_to_addition() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "A").str(0, 1, "B").str(0, 2, "C").str(0, 3, "Total")
                .num(1, 0, 1).num(1, 1, 2).num(1, 2, 3).formula(1, 3, "SUM(A2:C2)")
                .num(2, 0, 4).num(2, 1, 5).num(2, 2, 6).formula(2, 3, "SUM(A3:C3)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Total");
        assertThat(((ColumnOutcome.PerItemDerivation) outcome).jsonataExpr()).isEqualTo("($parent.a + $parent.b + $parent.c)");
    }

    @Test
    void whole_column_aggregate_over_a_literal_column_inside_a_per_row_formula_translates() {
        // The range is $-locked, as a real fill-down formula would, so it stays identical on every
        // row (otherwise it wouldn't even pass the uniform-shape check), and spans the table's full
        // data-row span -- the "percent of total" pattern: $$ reliably resolves to the document root
        // even from inside this wildcard derivation's own per-item context (measured empirically
        // against the real engine, unlike a bare sibling field name).
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Price").str(0, 1, "ShareOfTotal")
                .num(1, 0, 10).formula(1, 1, "A2/SUM($A$2:$A$3)")
                .num(2, 0, 20).formula(2, 1, "A3/SUM($A$2:$A$3)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "ShareOfTotal");
        assertThat(outcome).isInstanceOf(ColumnOutcome.PerItemDerivation.class);
        assertThat(((ColumnOutcome.PerItemDerivation) outcome).jsonataExpr())
                .isEqualTo("($parent.price / $sum($$.items.price))");
    }

    @Test
    void whole_column_aggregate_over_a_formula_column_inside_a_per_row_formula_is_rejected() {
        // Same shape as above, but the aggregated column ("Total") is itself a FORMULA column, not
        // a literal -- a materially bigger case (recomputing a formula's own expression INSIDE
        // another per-row formula), deliberately deferred rather than guessed.
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Qty").str(0, 1, "Total").str(0, 2, "ShareOfTotal")
                .num(1, 0, 2).formula(1, 1, "A2*2").formula(1, 2, "B2/SUM($B$2:$B$3)")
                .num(2, 0, 3).formula(2, 1, "A3*2").formula(2, 2, "B3/SUM($B$2:$B$3)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "ShareOfTotal");
        assertThat(outcome).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) outcome).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.UNSUPPORTED_RANGE_SHAPE);
    }

    @Test
    void a_whole_column_range_not_spanning_the_full_data_block_is_rejected() {
        // $A$2:$A$2 only covers the first data row, not the table's full data span -- not the
        // "percent of total" shape, and not a same-row range either.
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Price").str(0, 1, "Ratio")
                .num(1, 0, 10).formula(1, 1, "A2/SUM($A$2:$A$2)")
                .num(2, 0, 20).formula(2, 1, "A3/SUM($A$2:$A$2)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Ratio");
        assertThat(outcome).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) outcome).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.UNSUPPORTED_RANGE_SHAPE);
    }

    @Test
    void a_formula_depending_on_a_rejected_column_is_cascaded_to_rejected() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Bad").str(0, 1, "DependsOnBad")
                .formula(1, 0, "VLOOKUP(A2,A1:B1,2)").formula(1, 1, "A2*2")
                .formula(2, 0, "VLOOKUP(A3,A1:B1,2)").formula(2, 1, "A3*2");

        TranslationResult r = translate(grid);
        ColumnOutcome bad = outcomeOf(r, "Bad");
        ColumnOutcome depends = outcomeOf(r, "DependsOnBad");
        assertThat(bad).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(depends).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) depends).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.DEPENDS_ON_REJECTED_COLUMN);
    }

    @Test
    void cross_row_reference_is_rejected_by_name() {
        // Both rows' formulas reference the row above them (a running delta) -- same shape across
        // rows (so it survives classification), but each reference is to a DIFFERENT row than its
        // own, which v1 does not support (vision doc non-goals: running totals / prior-row deltas).
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Value").str(0, 1, "Delta")
                .num(1, 0, 10).formula(1, 1, "A2-A1")
                .num(2, 0, 15).formula(2, 1, "A3-A2");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Delta");
        assertThat(outcome).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) outcome).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.CROSS_ROW_REFERENCE);
    }

    @Test
    void and_function_translates() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "A").str(0, 1, "B").str(0, 2, "Both")
                .num(1, 0, 1).num(1, 1, 1).formula(1, 2, "AND(A2>0,B2>0)")
                .num(2, 0, 1).num(2, 1, 1).formula(2, 2, "AND(A3>0,B3>0)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Both");
        assertThat(((ColumnOutcome.PerItemDerivation) outcome).jsonataExpr())
                .isEqualTo("(($parent.a > 0) and ($parent.b > 0))");
    }

    @Test
    void round_function_translates() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Value").str(0, 1, "Rounded")
                .num(1, 0, 1.2345).formula(1, 1, "ROUND(A2,2)")
                .num(2, 0, 2.3456).formula(2, 1, "ROUND(A3,2)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Rounded");
        assertThat(((ColumnOutcome.PerItemDerivation) outcome).jsonataExpr()).isEqualTo("$round($parent.value, 2)");
    }

    @Test
    void unsupported_function_is_rejected_by_name() {
        // The lookup-table range is $-locked, as a real VLOOKUP fill-down would be.
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Key").str(0, 1, "Looked")
                .num(1, 0, 1).formula(1, 1, "VLOOKUP(A2,$A$1:$A$1,1)")
                .num(2, 0, 2).formula(2, 1, "VLOOKUP(A3,$A$1:$A$1,1)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Looked");
        assertThat(outcome).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) outcome).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.UNSUPPORTED_FUNCTION);
    }

    @Test
    void percent_and_power_operators_translate() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Base").str(0, 1, "Grown")
                .num(1, 0, 100).formula(1, 1, "A2*(1+20%)")
                .num(2, 0, 200).formula(2, 1, "A3*(1+20%)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Grown");
        assertThat(((ColumnOutcome.PerItemDerivation) outcome).jsonataExpr())
                .isEqualTo("($parent.base * (1 + ((20)/100)))");
    }

    // ── Text functions, &, string literals, result-type inference ─────────────

    @Test
    void concatenation_operator_translates_to_jsonata_concat() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "First").str(0, 1, "Last").str(0, 2, "Full")
                .str(1, 0, "Ada").str(1, 1, "Lovelace").formula(1, 2, "A2&\" \"&B2")
                .str(2, 0, "Alan").str(2, 1, "Turing").formula(2, 2, "A3&\" \"&B3");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Full");
        assertThat(outcome).isInstanceOf(ColumnOutcome.PerItemDerivation.class);
        ColumnOutcome.PerItemDerivation d = (ColumnOutcome.PerItemDerivation) outcome;
        assertThat(d.jsonataExpr()).isEqualTo("(($parent.first & \" \") & $parent.last)");
        assertThat(d.resultType()).isEqualTo(ColumnClassification.LiteralKind.STRING);
    }

    @Test
    void concatenate_function_chains_with_the_concat_operator() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "First").str(0, 1, "Last").str(0, 2, "Full")
                .str(1, 0, "Ada").str(1, 1, "Lovelace").formula(1, 2, "CONCATENATE(A2,\" \",B2)")
                .str(2, 0, "Alan").str(2, 1, "Turing").formula(2, 2, "CONCATENATE(A3,\" \",B3)");

        TranslationResult r = translate(grid);
        ColumnOutcome.PerItemDerivation d = (ColumnOutcome.PerItemDerivation) outcomeOf(r, "Full");
        assertThat(d.jsonataExpr()).isEqualTo("($parent.first & \" \" & $parent.last)");
        assertThat(d.resultType()).isEqualTo(ColumnClassification.LiteralKind.STRING);
    }

    @Test
    void left_right_mid_len_functions_translate_to_substring_and_length() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Code").str(0, 1, "Head").str(0, 2, "Tail").str(0, 3, "Mid").str(0, 4, "Size")
                .str(1, 0, "ABCDEF").formula(1, 1, "LEFT(A2,2)").formula(1, 2, "RIGHT(A2,2)")
                .formula(1, 3, "MID(A2,2,3)").formula(1, 4, "LEN(A2)")
                .str(2, 0, "GHIJKL").formula(2, 1, "LEFT(A3,2)").formula(2, 2, "RIGHT(A3,2)")
                .formula(2, 3, "MID(A3,2,3)").formula(2, 4, "LEN(A3)");

        TranslationResult r = translate(grid);
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Head")).jsonataExpr())
                .isEqualTo("$substring($parent.code, 0, 2)");
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Tail")).jsonataExpr())
                .isEqualTo("$substring($parent.code, $length($parent.code) - (2), 2)");
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Mid")).jsonataExpr())
                .isEqualTo("$substring($parent.code, (2) - 1, 3)");
        ColumnOutcome.PerItemDerivation size = (ColumnOutcome.PerItemDerivation) outcomeOf(r, "Size");
        assertThat(size.jsonataExpr()).isEqualTo("$length($parent.code)");
        assertThat(size.resultType()).isEqualTo(ColumnClassification.LiteralKind.NUMBER);
    }

    @Test
    void upper_lower_trim_functions_translate() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Raw").str(0, 1, "Upper").str(0, 2, "Lower").str(0, 3, "Trimmed")
                .str(1, 0, " Ada ").formula(1, 1, "UPPER(A2)").formula(1, 2, "LOWER(A2)").formula(1, 3, "TRIM(A2)")
                .str(2, 0, " Alan ").formula(2, 1, "UPPER(A3)").formula(2, 2, "LOWER(A3)").formula(2, 3, "TRIM(A3)");

        TranslationResult r = translate(grid);
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Upper")).jsonataExpr())
                .isEqualTo("$uppercase($parent.raw)");
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Lower")).jsonataExpr())
                .isEqualTo("$lowercase($parent.raw)");
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Trimmed")).jsonataExpr())
                .isEqualTo("$trim($parent.raw)");
    }

    @Test
    void string_literal_emits_as_an_escaped_jsonata_string() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Qty").str(0, 1, "Label")
                .num(1, 0, 2).formula(1, 1, "IF(A2>1,\"Say \"\"hi\"\"\",\"no\")")
                .num(2, 0, 0).formula(2, 1, "IF(A3>1,\"Say \"\"hi\"\"\",\"no\")");

        TranslationResult r = translate(grid);
        ColumnOutcome.PerItemDerivation d = (ColumnOutcome.PerItemDerivation) outcomeOf(r, "Label");
        assertThat(d.jsonataExpr()).isEqualTo("(($parent.qty > 1) ? \"Say \\\"hi\\\"\" : \"no\")");
        assertThat(d.resultType()).isEqualTo(ColumnClassification.LiteralKind.STRING);
    }

    @Test
    void an_if_branch_that_is_a_reference_into_a_literal_string_column_infers_string() {
        // IF's own result type takes the type of its branch (here a bare CellRef), which in turn is
        // resolved from the REFERENCED column's own literal kind (here Name, a literal string
        // column) -- exercising the CellRef-into-literal-column leg of inferResultType, distinct
        // from the always-STRING `&`/CONCATENATE/etc. operators tested above.
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Name").str(0, 1, "Qty").str(0, 2, "Label")
                .str(1, 0, "Ada").num(1, 1, 2).formula(1, 2, "IF(B2>1,A2,A2)")
                .str(2, 0, "Alan").num(2, 1, 0).formula(2, 2, "IF(B3>1,A3,A3)");

        TranslationResult r = translate(grid);
        ColumnOutcome.PerItemDerivation d = (ColumnOutcome.PerItemDerivation) outcomeOf(r, "Label");
        assertThat(d.resultType()).isEqualTo(ColumnClassification.LiteralKind.STRING);
    }

    @Test
    void numeric_and_boolean_formula_columns_still_infer_their_own_result_type() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Qty").str(0, 1, "Total").str(0, 2, "IsPositive")
                .num(1, 0, 2).formula(1, 1, "A2*2").formula(1, 2, "A2>0")
                .num(2, 0, 3).formula(2, 1, "A3*2").formula(2, 2, "A3>0");

        TranslationResult r = translate(grid);
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Total")).resultType())
                .isEqualTo(ColumnClassification.LiteralKind.NUMBER);
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "IsPositive")).resultType())
                .isEqualTo(ColumnClassification.LiteralKind.BOOLEAN);
    }

    // ── VLOOKUP ─────────────────────────────────────────────────────────────

    /** A small $-locked side lookup table at F1:G3 (well clear of the main table's columns, which
     *  stay within A:C in every test below, leaving D:E as an empty gap so TableDetector's header
     *  scan can never run into it): F1="A",G1=10 / F2="B",G2=20 / F3="C",G3=30. */
    private static InMemoryCellGrid gridWithSideLookupTable() {
        return new InMemoryCellGrid()
                .str(0, 0, "Category").str(0, 1, "Rate")
                .str(0, 5, "A").num(0, 6, 10)
                .str(1, 5, "B").num(1, 6, 20)
                .str(2, 5, "C").num(2, 6, 30);
    }

    @Test
    void vlookup_against_a_dollar_locked_side_table_translates_and_reads_the_table_verbatim() {
        InMemoryCellGrid grid = gridWithSideLookupTable()
                .str(1, 0, "B").formula(1, 1, "VLOOKUP(A2,$F$1:$G$3,2,FALSE)")
                .str(2, 0, "A").formula(2, 1, "VLOOKUP(A3,$F$1:$G$3,2,FALSE)");

        TranslationResult r = translate(grid);
        ColumnOutcome.PerItemDerivation d = (ColumnOutcome.PerItemDerivation) outcomeOf(r, "Rate");
        assertThat(d.jsonataExpr()).isEqualTo("($lookupF1[c1 = ($parent.category)].c2)[0]");
        assertThat(d.resultType()).isEqualTo(ColumnClassification.LiteralKind.NUMBER);

        assertThat(r.lookupTables()).containsOnlyKeys("lookupF1");
        List<List<CellValue>> table = r.lookupTables().get("lookupF1");
        assertThat(table).hasSize(3);
        assertThat(table.get(0)).containsExactly(
                new CellValue.StringValue("A"), new CellValue.NumberValue(10));
        assertThat(table.get(1)).containsExactly(
                new CellValue.StringValue("B"), new CellValue.NumberValue(20));
    }

    @Test
    void two_vlookups_against_the_same_table_array_share_one_library_table() {
        InMemoryCellGrid grid = gridWithSideLookupTable()
                .str(0, 2, "Rate2")
                .str(1, 0, "B").formula(1, 1, "VLOOKUP(A2,$F$1:$G$3,2,FALSE)").formula(1, 2, "VLOOKUP(A2,$F$1:$G$3,2,FALSE)")
                .str(2, 0, "A").formula(2, 1, "VLOOKUP(A3,$F$1:$G$3,2,FALSE)").formula(2, 2, "VLOOKUP(A3,$F$1:$G$3,2,FALSE)");

        TranslationResult r = translate(grid);
        assertThat(r.lookupTables()).hasSize(1);
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Rate")).jsonataExpr())
                .isEqualTo(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Rate2")).jsonataExpr());
    }

    @Test
    void vlookup_with_approximate_match_is_rejected_by_name() {
        InMemoryCellGrid grid = gridWithSideLookupTable()
                .str(1, 0, "B").formula(1, 1, "VLOOKUP(A2,$F$1:$G$3,2,TRUE)")
                .str(2, 0, "A").formula(2, 1, "VLOOKUP(A3,$F$1:$G$3,2,TRUE)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Rate");
        assertThat(outcome).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) outcome).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.UNSUPPORTED_LOOKUP_MODE);
    }

    @Test
    void vlookup_with_a_non_dollar_locked_table_array_is_rejected_by_name() {
        // A single data row, deliberately: with two rows a non-$-locked range's row offsets would
        // differ row to row and get caught earlier by the uniform-shape check (NON_UNIFORM_FORMULA)
        // rather than exercising this specific VLOOKUP validation.
        InMemoryCellGrid grid = gridWithSideLookupTable()
                .str(1, 0, "B").formula(1, 1, "VLOOKUP(A2,F1:G3,2,FALSE)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Rate");
        assertThat(outcome).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) outcome).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.UNSUPPORTED_RANGE_SHAPE);
    }

    @Test
    void vlookup_with_an_out_of_range_col_index_is_rejected_by_name() {
        InMemoryCellGrid grid = gridWithSideLookupTable()
                .str(1, 0, "B").formula(1, 1, "VLOOKUP(A2,$F$1:$G$3,3,FALSE)")
                .str(2, 0, "A").formula(2, 1, "VLOOKUP(A3,$F$1:$G$3,3,FALSE)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Rate");
        assertThat(outcome).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) outcome).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.UNSUPPORTED_RANGE_SHAPE);
    }

    @Test
    void vlookup_against_a_table_containing_a_formula_cell_is_rejected_by_name() {
        InMemoryCellGrid grid = gridWithSideLookupTable()
                .formula(0, 6, "10+0") // F1:G3's G1 is now a formula, not a literal
                .str(1, 0, "B").formula(1, 1, "VLOOKUP(A2,$F$1:$G$3,2,FALSE)")
                .str(2, 0, "A").formula(2, 1, "VLOOKUP(A3,$F$1:$G$3,2,FALSE)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "Rate");
        assertThat(outcome).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) outcome).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.LOOKUP_TABLE_CONTAINS_FORMULA);
    }

    @Test
    void vlookup_returning_a_string_column_infers_string_result_type() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Code").str(0, 1, "Label")
                .str(0, 5, "A").str(0, 6, "Alpha")
                .str(1, 5, "B").str(1, 6, "Bravo")
                .str(1, 0, "B").formula(1, 1, "VLOOKUP(A2,$F$1:$G$2,2,FALSE)")
                .str(2, 0, "A").formula(2, 1, "VLOOKUP(A3,$F$1:$G$2,2,FALSE)");

        TranslationResult r = translate(grid);
        assertThat(((ColumnOutcome.PerItemDerivation) outcomeOf(r, "Label")).resultType())
                .isEqualTo(ColumnClassification.LiteralKind.STRING);
    }
}
