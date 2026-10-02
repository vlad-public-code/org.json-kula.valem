package org.json_kula.valem.core.spreadsheet;

import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.BinaryOp;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.BoolLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.CellRef;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.FuncCall;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.NumberLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.Percent;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.RangeRef;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.UnaryNeg;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelFormulaParserTest {

    @Test
    void parses_a_number() {
        assertThat(ExcelFormulaParser.parse("42")).isEqualTo(new NumberLit(42));
        assertThat(ExcelFormulaParser.parse("3.5")).isEqualTo(new NumberLit(3.5));
    }

    @Test
    void parses_booleans() {
        assertThat(ExcelFormulaParser.parse("TRUE")).isEqualTo(new BoolLit(true));
        assertThat(ExcelFormulaParser.parse("FALSE")).isEqualTo(new BoolLit(false));
    }

    @Test
    void parses_a_relative_cell_reference_zero_based() {
        // "B2" -> column index 1 ('B'), row index 1 (0-based; formula row 2 -> index 1)
        assertThat(ExcelFormulaParser.parse("B2")).isEqualTo(new CellRef(1, 1, false, false));
    }

    @Test
    void parses_absolute_and_mixed_references() {
        assertThat(ExcelFormulaParser.parse("$B2")).isEqualTo(new CellRef(1, 1, true, false));
        assertThat(ExcelFormulaParser.parse("B$2")).isEqualTo(new CellRef(1, 1, false, true));
        assertThat(ExcelFormulaParser.parse("$B$2")).isEqualTo(new CellRef(1, 1, true, true));
    }

    @Test
    void parses_multi_letter_columns() {
        assertThat(ExcelFormulaParser.parse("AA1")).isEqualTo(new CellRef(26, 0, false, false));
    }

    @Test
    void parses_a_range() {
        assertThat(ExcelFormulaParser.parse("B2:D2")).isEqualTo(
                new RangeRef(new CellRef(1, 1, false, false), new CellRef(3, 1, false, false)));
    }

    @Test
    void parses_arithmetic_with_correct_precedence() {
        // B2*C2 + D2  ==  (B2*C2) + D2, not B2*(C2+D2)
        ExcelExpr parsed = ExcelFormulaParser.parse("B2*C2+D2");
        assertThat(parsed).isInstanceOf(BinaryOp.class);
        BinaryOp top = (BinaryOp) parsed;
        assertThat(top.op()).isEqualTo("+");
        assertThat(top.left()).isInstanceOf(BinaryOp.class);
        assertThat(((BinaryOp) top.left()).op()).isEqualTo("*");
    }

    @Test
    void parentheses_override_precedence() {
        ExcelExpr parsed = ExcelFormulaParser.parse("B2*(C2+D2)");
        assertThat(parsed).isInstanceOf(BinaryOp.class);
        assertThat(((BinaryOp) parsed).op()).isEqualTo("*");
        assertThat(((BinaryOp) parsed).right()).isInstanceOf(BinaryOp.class);
    }

    @Test
    void parses_unary_minus() {
        assertThat(ExcelFormulaParser.parse("-B2")).isEqualTo(new UnaryNeg(new CellRef(1, 1, false, false)));
    }

    @Test
    void parses_percent_suffix() {
        assertThat(ExcelFormulaParser.parse("20%")).isEqualTo(new Percent(new NumberLit(20)));
    }

    @Test
    void parses_power_operator() {
        ExcelExpr parsed = ExcelFormulaParser.parse("B2^2");
        assertThat(parsed).isEqualTo(new BinaryOp("^", new CellRef(1, 1, false, false), new NumberLit(2)));
    }

    @Test
    void parses_all_comparison_operators() {
        assertThat(((BinaryOp) ExcelFormulaParser.parse("A1=B1")).op()).isEqualTo("=");
        assertThat(((BinaryOp) ExcelFormulaParser.parse("A1<>B1")).op()).isEqualTo("<>");
        assertThat(((BinaryOp) ExcelFormulaParser.parse("A1<B1")).op()).isEqualTo("<");
        assertThat(((BinaryOp) ExcelFormulaParser.parse("A1>B1")).op()).isEqualTo(">");
        assertThat(((BinaryOp) ExcelFormulaParser.parse("A1<=B1")).op()).isEqualTo("<=");
        assertThat(((BinaryOp) ExcelFormulaParser.parse("A1>=B1")).op()).isEqualTo(">=");
    }

    @Test
    void parses_a_function_call_with_multiple_args() {
        ExcelExpr parsed = ExcelFormulaParser.parse("IF(A1>0,B1,C1)");
        assertThat(parsed).isInstanceOf(FuncCall.class);
        FuncCall call = (FuncCall) parsed;
        assertThat(call.name()).isEqualTo("IF");
        assertThat(call.args()).hasSize(3);
    }

    @Test
    void parses_a_function_call_with_a_range_argument() {
        ExcelExpr parsed = ExcelFormulaParser.parse("SUM(B2:D2)");
        assertThat(parsed).isInstanceOf(FuncCall.class);
        assertThat(((FuncCall) parsed).args().get(0)).isInstanceOf(RangeRef.class);
    }

    @Test
    void function_names_are_case_insensitive_and_normalized_uppercase() {
        assertThat(((FuncCall) ExcelFormulaParser.parse("sum(A1)")).name()).isEqualTo("SUM");
        assertThat(((FuncCall) ExcelFormulaParser.parse("Sum(A1)")).name()).isEqualTo("SUM");
    }

    @Test
    void nested_function_calls_parse() {
        ExcelExpr parsed = ExcelFormulaParser.parse("ROUND(SUM(B2:D2),2)");
        assertThat(parsed).isInstanceOf(FuncCall.class);
        FuncCall outer = (FuncCall) parsed;
        assertThat(outer.name()).isEqualTo("ROUND");
        assertThat(outer.args().get(0)).isInstanceOf(FuncCall.class);
    }

    @Test
    void rejects_cross_sheet_reference_by_name() {
        assertThatThrownBy(() -> ExcelFormulaParser.parse("Sheet2!B2"))
                .isInstanceOf(UnsupportedFormulaException.class)
                .satisfies(e -> assertThat(((UnsupportedFormulaException) e).reason())
                        .isEqualTo(UnsupportedFormulaException.Reason.CROSS_SHEET_REFERENCE));
    }

    @Test
    void rejects_concatenation_operator_by_name() {
        assertThatThrownBy(() -> ExcelFormulaParser.parse("A1&B1"))
                .isInstanceOf(UnsupportedFormulaException.class)
                .satisfies(e -> assertThat(((UnsupportedFormulaException) e).reason())
                        .isEqualTo(UnsupportedFormulaException.Reason.UNSUPPORTED_OPERATOR));
    }

    @Test
    void rejects_malformed_formula_with_unbalanced_parens() {
        assertThatThrownBy(() -> ExcelFormulaParser.parse("SUM(A1,B1"))
                .isInstanceOf(UnsupportedFormulaException.class)
                .satisfies(e -> assertThat(((UnsupportedFormulaException) e).reason())
                        .isEqualTo(UnsupportedFormulaException.Reason.MALFORMED_FORMULA));
    }

    @Test
    void rejects_trailing_garbage_after_a_valid_expression() {
        assertThatThrownBy(() -> ExcelFormulaParser.parse("A1+B1)"))
                .isInstanceOf(UnsupportedFormulaException.class);
    }

    @Test
    void rejects_unrecognized_character() {
        assertThatThrownBy(() -> ExcelFormulaParser.parse("A1@B1"))
                .isInstanceOf(UnsupportedFormulaException.class)
                .satisfies(e -> assertThat(((UnsupportedFormulaException) e).reason())
                        .isEqualTo(UnsupportedFormulaException.Reason.MALFORMED_FORMULA));
    }

    @Test
    void column_letters_to_index_matches_known_values() {
        assertThat(ExcelFormulaParser.columnLettersToIndex("A")).isEqualTo(0);
        assertThat(ExcelFormulaParser.columnLettersToIndex("Z")).isEqualTo(25);
        assertThat(ExcelFormulaParser.columnLettersToIndex("AA")).isEqualTo(26);
        assertThat(ExcelFormulaParser.columnLettersToIndex("AZ")).isEqualTo(51);
    }

    @Test
    void realistic_formula_parses_end_to_end() {
        // A typical fill-down line-item formula.
        ExcelExpr parsed = ExcelFormulaParser.parse("ROUND(B2*C2*(1-D2),2)");
        assertThat(parsed).isInstanceOf(FuncCall.class);
        FuncCall call = (FuncCall) parsed;
        assertThat(call.name()).isEqualTo("ROUND");
        assertThat(call.args()).hasSize(2);
    }
}
