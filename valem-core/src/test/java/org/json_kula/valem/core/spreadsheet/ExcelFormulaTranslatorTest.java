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
    void whole_column_range_inside_a_per_row_formula_is_rejected() {
        // The range is $-locked, as a real fill-down formula would, so it stays identical on every
        // row (otherwise it wouldn't even pass the uniform-shape check).
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Price").str(0, 1, "ShareOfTotal")
                .num(1, 0, 10).formula(1, 1, "A2/SUM($A$2:$A$3)")
                .num(2, 0, 20).formula(2, 1, "A3/SUM($A$2:$A$3)");

        TranslationResult r = translate(grid);
        ColumnOutcome outcome = outcomeOf(r, "ShareOfTotal");
        assertThat(outcome).isInstanceOf(ColumnOutcome.Rejected.class);
        assertThat(((ColumnOutcome.Rejected) outcome).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.UNSUPPORTED_RANGE_SHAPE);
    }

    @Test
    void a_formula_depending_on_a_rejected_column_is_cascaded_to_rejected() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Bad").str(0, 1, "DependsOnBad")
                .formula(1, 0, "A2&\"x\"").formula(1, 1, "A2*2")
                .formula(2, 0, "A3&\"x\"").formula(2, 1, "A3*2");

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
}
