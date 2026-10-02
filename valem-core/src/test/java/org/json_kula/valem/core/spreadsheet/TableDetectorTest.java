package org.json_kula.valem.core.spreadsheet;

import org.json_kula.valem.core.spreadsheet.TableDetector.TableBounds;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableDetectorTest {

    @Test
    void detects_header_row_and_contiguous_data_rows() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "Quantity").str(0, 1, "Price")
                .num(1, 0, 2).num(1, 1, 10)
                .num(2, 0, 3).num(2, 1, 20)
                .num(3, 0, 1).num(3, 1, 5);

        TableBounds bounds = TableDetector.detect(grid);

        assertThat(bounds.headerRow()).isEqualTo(0);
        assertThat(bounds.firstCol()).isEqualTo(0);
        assertThat(bounds.lastCol()).isEqualTo(1);
        assertThat(bounds.firstDataRow()).isEqualTo(1);
        assertThat(bounds.lastDataRow()).isEqualTo(3);
        assertThat(bounds.headers()).containsExactly("Quantity", "Price");
    }

    @Test
    void stops_data_rows_at_the_first_fully_empty_row() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "A")
                .num(1, 0, 1)
                .num(2, 0, 2);
        // row 3 intentionally left empty, row 4 populated but must NOT be included
        grid.num(4, 0, 99);

        TableBounds bounds = TableDetector.detect(grid);

        assertThat(bounds.lastDataRow()).isEqualTo(2);
    }

    @Test
    void header_columns_stop_at_the_first_gap() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "A").str(0, 1, "B")
                // column 2 intentionally left blank in the header row
                .str(0, 3, "D")
                .num(1, 0, 1).num(1, 1, 2);

        TableBounds bounds = TableDetector.detect(grid);

        assertThat(bounds.lastCol()).isEqualTo(1);
        assertThat(bounds.headers()).containsExactly("A", "B");
    }

    @Test
    void rejects_a_non_string_header_cell() {
        InMemoryCellGrid grid = new InMemoryCellGrid()
                .str(0, 0, "A").num(0, 1, 42) // header row has a NUMBER, not text
                .num(1, 0, 1).num(1, 1, 2);

        assertThatThrownBy(() -> TableDetector.detect(grid)).isInstanceOf(UnsupportedFormulaException.class);
    }

    @Test
    void rejects_an_empty_sheet() {
        assertThatThrownBy(() -> TableDetector.detect(new InMemoryCellGrid()))
                .isInstanceOf(UnsupportedFormulaException.class);
    }

    @Test
    void rejects_a_header_row_with_no_data_rows_below_it() {
        InMemoryCellGrid grid = new InMemoryCellGrid().str(0, 0, "A").str(0, 1, "B");
        assertThatThrownBy(() -> TableDetector.detect(grid)).isInstanceOf(UnsupportedFormulaException.class);
    }

    @Test
    void column_letter_renders_known_values() {
        assertThat(TableDetector.columnLetter(0)).isEqualTo("A");
        assertThat(TableDetector.columnLetter(25)).isEqualTo("Z");
        assertThat(TableDetector.columnLetter(26)).isEqualTo("AA");
    }
}
