package org.json_kula.valem.core.spreadsheet;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The real {@code XSSFWorkbook}-backed {@link CellGrid} (excel-to-spec-v1-design.md §2.1) — the one
 * class in this feature that imports POI into the pipeline. A formula cell's
 * {@link CellValue.Formula#computedValue()} always comes from POI's own
 * {@link XSSFFormulaEvaluator}, never from our own translation, so {@code SpreadsheetCompiler}'s
 * self-tests check against an oracle independent of the thing under test (vision doc AC-4).
 *
 * <p>Always reads the workbook's <b>first</b> sheet (vision doc §4 non-goal: one worksheet per
 * workbook in v1).
 */
public final class XssfCellGrid implements CellGrid {

    private final Sheet sheet;
    private final XSSFFormulaEvaluator evaluator;
    private final int rowCount;
    private final int columnCount;

    public XssfCellGrid(XSSFWorkbook workbook) {
        this.sheet = workbook.getSheetAt(0);
        this.evaluator = new XSSFFormulaEvaluator(workbook);
        this.rowCount = sheet.getLastRowNum() + 1;
        int maxCol = 0;
        for (Row row : sheet) {
            maxCol = Math.max(maxCol, row.getLastCellNum()); // 1-based count, or -1 if no cells
        }
        this.columnCount = Math.max(maxCol, 0);
    }

    @Override
    public int rowCount() {
        return rowCount;
    }

    @Override
    public int columnCount() {
        return columnCount;
    }

    @Override
    public CellValue valueAt(int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return CellValue.EMPTY;
        Cell cell = row.getCell(colIdx);
        if (cell == null) return CellValue.EMPTY;

        CellType type = cell.getCellType();
        return switch (type) {
            case BLANK, _NONE -> CellValue.EMPTY;
            case STRING -> cell.getStringCellValue().isBlank()
                    ? CellValue.EMPTY : new CellValue.StringValue(cell.getStringCellValue());
            case NUMERIC -> new CellValue.NumberValue(cell.getNumericCellValue());
            case BOOLEAN -> new CellValue.BooleanValue(cell.getBooleanCellValue());
            case FORMULA -> new CellValue.Formula(cell.getCellFormula(), evaluateFormula(cell));
            case ERROR -> throw new SpreadsheetExtractionException(
                    SpreadsheetExtractionException.Reason.PARSE_FAILED,
                    "Cell " + cell.getAddress() + " has a #ERROR value in the source workbook");
        };
    }

    /**
     * A formula our translator will go on to reject anyway (an unsupported function like VLOOKUP, a
     * malformed range, …) can easily fail POI's own evaluation too — e.g. VLOOKUP over a one-cell
     * range genuinely evaluates to #N/A in real Excel. Failing hard here would abort reading the
     * <em>whole workbook</em> over one column that the classifier/translator was always going to
     * name and reject on its own with a far more specific reason (vision doc AC-3: a rejected column
     * must not fail the whole compile). So an unevaluable formula gets a placeholder computed value
     * instead of an exception — it is only ever consulted for a column that SURVIVES translation,
     * and this bounded v1 function set (§5.2) evaluates cleanly to numeric/boolean/text whenever its
     * inputs do, so a surviving column landing here would itself be the surprise worth investigating.
     */
    private CellValue evaluateFormula(Cell cell) {
        org.apache.poi.ss.usermodel.CellValue evaluated;
        try {
            evaluated = evaluator.evaluate(cell);
        } catch (RuntimeException e) {
            return new CellValue.NumberValue(0);
        }
        return switch (evaluated.getCellType()) {
            case NUMERIC -> new CellValue.NumberValue(evaluated.getNumberValue());
            case BOOLEAN -> new CellValue.BooleanValue(evaluated.getBooleanValue());
            case STRING -> new CellValue.StringValue(evaluated.getStringValue());
            default -> new CellValue.NumberValue(0);
        };
    }
}
