package org.json_kula.valem.core.spreadsheet;

import java.util.HashMap;
import java.util.Map;

/** A plain in-memory {@link CellGrid} test double — no POI, builds via a small fluent API. */
final class InMemoryCellGrid implements CellGrid {

    private final Map<Long, CellValue> cells = new HashMap<>();
    private int rows = 0;
    private int cols = 0;

    private static long key(int row, int col) { return (long) row * 100_000 + col; }

    InMemoryCellGrid set(int row, int col, CellValue value) {
        cells.put(key(row, col), value);
        rows = Math.max(rows, row + 1);
        cols = Math.max(cols, col + 1);
        return this;
    }

    InMemoryCellGrid str(int row, int col, String s) { return set(row, col, new CellValue.StringValue(s)); }
    InMemoryCellGrid num(int row, int col, double n) { return set(row, col, new CellValue.NumberValue(n)); }
    InMemoryCellGrid bool(int row, int col, boolean b) { return set(row, col, new CellValue.BooleanValue(b)); }
    /** Convenience for tests that don't check self-test assembly — the computed value is a dummy 0. */
    InMemoryCellGrid formula(int row, int col, String f) {
        return formula(row, col, f, new CellValue.NumberValue(0));
    }

    InMemoryCellGrid formula(int row, int col, String f, CellValue computedValue) {
        return set(row, col, new CellValue.Formula(f, computedValue));
    }

    InMemoryCellGrid formula(int row, int col, String f, double computedValue) {
        return formula(row, col, f, new CellValue.NumberValue(computedValue));
    }

    InMemoryCellGrid formula(int row, int col, String f, String computedValue) {
        return formula(row, col, f, new CellValue.StringValue(computedValue));
    }

    @Override public int rowCount() { return rows; }
    @Override public int columnCount() { return cols; }

    @Override
    public CellValue valueAt(int row, int col) {
        return cells.getOrDefault(key(row, col), CellValue.EMPTY);
    }
}
