package org.json_kula.valem.core.spreadsheet;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects the one header-row table a v1 sheet is expected to contain (excel-to-spec-v1-design.md
 * §4). A mechanical, named rule — not a heuristic that tries several layouts and picks the best one:
 * row 0 must be all-string, contiguous from the first non-empty column; data rows are the contiguous
 * non-empty run immediately below it.
 */
public final class TableDetector {

    private TableDetector() {}

    public record TableBounds(int headerRow, int firstCol, int lastCol, int firstDataRow, int lastDataRow,
                              List<String> headers) {
        public int columnCount() { return lastCol - firstCol + 1; }
        public int dataRowCount() { return lastDataRow - firstDataRow + 1; }
    }

    public static TableBounds detect(CellGrid grid) {
        int headerRow = findFirstNonEmptyRow(grid);
        if (headerRow < 0) {
            throw new UnsupportedFormulaException(UnsupportedFormulaException.Reason.MALFORMED_FORMULA,
                    "Sheet is empty — no header row found");
        }

        int firstCol = findFirstNonEmptyColumnInRow(grid, headerRow);
        if (firstCol < 0) {
            throw new UnsupportedFormulaException(UnsupportedFormulaException.Reason.MALFORMED_FORMULA,
                    "Header row " + (headerRow + 1) + " is empty");
        }

        List<String> headers = new ArrayList<>();
        int col = firstCol;
        while (col < grid.columnCount()) {
            CellValue v = grid.valueAt(headerRow, col);
            if (v instanceof CellValue.Empty) break;
            if (!(v instanceof CellValue.StringValue s)) {
                throw new UnsupportedFormulaException(UnsupportedFormulaException.Reason.MALFORMED_FORMULA,
                        "Header row " + (headerRow + 1) + ", column " + columnLetter(col)
                        + " is not plain text — cannot detect a header row");
            }
            headers.add(s.value());
            col++;
        }
        int lastCol = col - 1;

        int firstDataRow = headerRow + 1;
        int lastDataRow = firstDataRow - 1; // empty by default (no data rows)
        int row = firstDataRow;
        while (row < grid.rowCount() && isRowFullyPopulated(grid, row, firstCol, lastCol)) {
            lastDataRow = row;
            row++;
        }
        if (lastDataRow < firstDataRow) {
            throw new UnsupportedFormulaException(UnsupportedFormulaException.Reason.MALFORMED_FORMULA,
                    "No data rows found below the header row " + (headerRow + 1));
        }

        return new TableBounds(headerRow, firstCol, lastCol, firstDataRow, lastDataRow, headers);
    }

    private static int findFirstNonEmptyRow(CellGrid grid) {
        for (int r = 0; r < grid.rowCount(); r++) {
            for (int c = 0; c < grid.columnCount(); c++) {
                if (!(grid.valueAt(r, c) instanceof CellValue.Empty)) return r;
            }
        }
        return -1;
    }

    private static int findFirstNonEmptyColumnInRow(CellGrid grid, int row) {
        for (int c = 0; c < grid.columnCount(); c++) {
            if (!(grid.valueAt(row, c) instanceof CellValue.Empty)) return c;
        }
        return -1;
    }

    /**
     * A data row must have every table column populated — a row where only some columns are filled
     * (e.g. a summary/totals row with a value in just one column) is not a data row and correctly
     * ends the data block, same as a fully empty row would.
     */
    private static boolean isRowFullyPopulated(CellGrid grid, int row, int firstCol, int lastCol) {
        for (int c = firstCol; c <= lastCol; c++) {
            if (grid.valueAt(row, c) instanceof CellValue.Empty) return false;
        }
        return true;
    }

    /** 0-based column index -> Excel letters ("A", "B", … "AA", …), for error messages. */
    static String columnLetter(int col) {
        StringBuilder sb = new StringBuilder();
        int n = col + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }
}
