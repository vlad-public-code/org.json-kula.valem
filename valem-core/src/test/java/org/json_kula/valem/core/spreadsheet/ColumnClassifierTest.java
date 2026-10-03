package org.json_kula.valem.core.spreadsheet;

import org.json_kula.valem.core.spreadsheet.ColumnClassification.FormulaColumn;
import org.json_kula.valem.core.spreadsheet.ColumnClassification.Literal;
import org.json_kula.valem.core.spreadsheet.ColumnClassification.LiteralKind;
import org.json_kula.valem.core.spreadsheet.ColumnClassification.Rejected;
import org.json_kula.valem.core.spreadsheet.TableDetector.TableBounds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnClassifierTest {

    private static InMemoryCellGrid baseGrid() {
        return new InMemoryCellGrid()
                .str(0, 0, "Quantity").str(0, 1, "Price").str(0, 2, "Total");
    }

    @Test
    void all_numeric_column_is_literal_number() {
        InMemoryCellGrid grid = baseGrid()
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2")
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3");
        TableBounds bounds = TableDetector.detect(grid);

        List<ColumnClassification> result = ColumnClassifier.classify(grid, bounds);

        assertThat(result.get(0)).isInstanceOf(Literal.class);
        assertThat(((Literal) result.get(0)).kind()).isEqualTo(LiteralKind.NUMBER);
    }

    @Test
    void uniform_per_row_formula_column_is_classified_as_formula() {
        InMemoryCellGrid grid = baseGrid()
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2")
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3")
                .num(3, 0, 1).num(3, 1, 5).formula(3, 2, "A4*B4");
        TableBounds bounds = TableDetector.detect(grid);

        List<ColumnClassification> result = ColumnClassifier.classify(grid, bounds);

        assertThat(result.get(2)).isInstanceOf(FormulaColumn.class);
        assertThat(((FormulaColumn) result.get(2)).header()).isEqualTo("Total");
    }

    @Test
    void non_uniform_formula_shapes_are_rejected_by_name() {
        InMemoryCellGrid grid = baseGrid()
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2")
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3+B3"); // different operator -> different shape
        TableBounds bounds = TableDetector.detect(grid);

        List<ColumnClassification> result = ColumnClassifier.classify(grid, bounds);

        assertThat(result.get(2)).isInstanceOf(Rejected.class);
        assertThat(((Rejected) result.get(2)).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.NON_UNIFORM_FORMULA);
    }

    @Test
    void mixed_literal_and_formula_in_one_column_is_rejected() {
        InMemoryCellGrid grid = baseGrid()
                .num(1, 0, 2).num(1, 1, 10).num(1, 2, 999) // literal, not a formula
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3");
        TableBounds bounds = TableDetector.detect(grid);

        List<ColumnClassification> result = ColumnClassifier.classify(grid, bounds);

        assertThat(result.get(2)).isInstanceOf(Rejected.class);
        assertThat(((Rejected) result.get(2)).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.MIXED_CONTENT);
    }

    @Test
    void mixed_literal_kinds_in_one_column_is_rejected() {
        InMemoryCellGrid grid = baseGrid()
                .num(1, 0, 2).num(1, 1, 10).num(1, 2, 20)
                .num(2, 0, 3).str(2, 1, "not a number").num(2, 2, 60); // Price column: number then text
        TableBounds bounds = TableDetector.detect(grid);

        List<ColumnClassification> result = ColumnClassifier.classify(grid, bounds);

        assertThat(result.get(1)).isInstanceOf(Rejected.class);
        assertThat(((Rejected) result.get(1)).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.MIXED_CONTENT);
    }

    @Test
    void an_unparseable_formula_rejects_the_column_with_the_parser_reason() {
        InMemoryCellGrid grid = baseGrid()
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "Sheet2!B2") // cross-sheet ref unsupported
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "Sheet2!B3");
        TableBounds bounds = TableDetector.detect(grid);

        List<ColumnClassification> result = ColumnClassifier.classify(grid, bounds);

        assertThat(result.get(2)).isInstanceOf(Rejected.class);
        assertThat(((Rejected) result.get(2)).reason())
                .isEqualTo(UnsupportedFormulaException.Reason.CROSS_SHEET_REFERENCE);
    }

    @Test
    void boolean_literal_column_is_classified_correctly() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Active")
                .bool(1, 0, true)
                .bool(2, 0, false);
        TableBounds bounds = TableDetector.detect(grid);

        List<ColumnClassification> result = ColumnClassifier.classify(grid, bounds);

        assertThat(result.get(0)).isInstanceOf(Literal.class);
        assertThat(((Literal) result.get(0)).kind()).isEqualTo(LiteralKind.BOOLEAN);
    }

    @Test
    void a_formula_referencing_a_fixed_absolute_cell_is_still_uniform() {
        // Every row multiplies by the SAME absolute-referenced tax rate cell -> still one shape.
        InMemoryCellGrid grid = baseGrid()
                .num(1, 0, 2).num(1, 1, 10).formula(1, 2, "A2*B2*$D$1")
                .num(2, 0, 3).num(2, 1, 20).formula(2, 2, "A3*B3*$D$1");
        TableBounds bounds = TableDetector.detect(grid);

        List<ColumnClassification> result = ColumnClassifier.classify(grid, bounds);

        assertThat(result.get(2)).isInstanceOf(FormulaColumn.class);
    }
}
