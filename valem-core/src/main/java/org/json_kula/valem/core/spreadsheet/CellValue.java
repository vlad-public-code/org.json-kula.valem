package org.json_kula.valem.core.spreadsheet;

/**
 * One cell's content, kind-tagged. {@code Formula} carries the raw Excel formula string (no leading
 * {@code =}), parsed lazily by {@link ExcelFormulaParser} where needed.
 */
public sealed interface CellValue permits CellValue.NumberValue, CellValue.BooleanValue,
        CellValue.StringValue, CellValue.Formula, CellValue.Empty {

    record NumberValue(double value) implements CellValue {}

    record BooleanValue(boolean value) implements CellValue {}

    record StringValue(String value) implements CellValue {}

    /**
     * A formula cell, carrying both the raw Excel formula text (translated by
     * {@code ExcelFormulaParser}/{@code ExcelFormulaTranslator}) and its <b>computed</b> value — in
     * production always read from POI's own {@code XSSFFormulaEvaluator}, never from our own
     * translation, so the self-tests {@code SpreadsheetCompiler} builds check against an oracle
     * independent of the thing under test (excel-to-spec-v1-design.md §6, vision doc AC-4).
     * {@code computedValue} is always a {@link NumberValue} or {@link BooleanValue} in v1 scope.
     */
    record Formula(String excelFormula, CellValue computedValue) implements CellValue {}

    record Empty() implements CellValue {}

    Empty EMPTY = new Empty();
}
