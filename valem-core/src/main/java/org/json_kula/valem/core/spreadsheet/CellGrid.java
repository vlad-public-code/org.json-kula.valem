package org.json_kula.valem.core.spreadsheet;

/**
 * A plain, POI-free view of a single worksheet's cell grid (excel-to-spec-v1-design.md §2.1) — the
 * seam that keeps table detection, classification, and translation unit-testable with an in-memory
 * double, with the real {@code XSSFWorkbook} adapter living in {@code valem-api}.
 *
 * <p>0-based row/column indices throughout, matching POI's own convention and
 * {@code org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.CellRef}.
 */
public interface CellGrid {

    /** Exclusive upper bound on row index actually populated (may be sparse below this). */
    int rowCount();

    /** Exclusive upper bound on column index actually populated (may be sparse below this). */
    int columnCount();

    /** Never null — {@link CellValue#EMPTY} for an absent/blank cell. */
    CellValue valueAt(int row, int col);
}
