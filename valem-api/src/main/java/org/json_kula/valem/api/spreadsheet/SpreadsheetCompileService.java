package org.json_kula.valem.api.spreadsheet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json_kula.valem.core.spreadsheet.SpreadsheetCompiler;
import org.json_kula.valem.core.spreadsheet.SpreadsheetExtractionException;
import org.json_kula.valem.core.spreadsheet.XssfCellGrid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;

import static org.json_kula.valem.core.spreadsheet.SpreadsheetExtractionException.Reason.EMPTY_WORKBOOK;
import static org.json_kula.valem.core.spreadsheet.SpreadsheetExtractionException.Reason.FILE_TOO_LARGE;
import static org.json_kula.valem.core.spreadsheet.SpreadsheetExtractionException.Reason.PARSE_FAILED;
import static org.json_kula.valem.core.spreadsheet.SpreadsheetExtractionException.Reason.TOO_MANY_COLUMNS;
import static org.json_kula.valem.core.spreadsheet.SpreadsheetExtractionException.Reason.TOO_MANY_ROWS;
import static org.json_kula.valem.core.spreadsheet.SpreadsheetExtractionException.Reason.UNSUPPORTED_FORMAT;

/**
 * Enforces the size caps that make a "potentially big" workbook fail predictably (mirroring
 * {@code DocumentExtractionService}'s discipline), then runs the whole deterministic
 * {@code SpreadsheetCompiler} pipeline. No LLM is involved anywhere in this feature.
 */
@Service
public class SpreadsheetCompileService {

    private final long maxFileSizeBytes;
    private final int maxRows;
    private final int maxColumns;
    private final ObjectMapper mapper;

    @Autowired
    public SpreadsheetCompileService(
            @Value("${valem.spreadsheet.max-file-size-bytes:26214400}") long maxFileSizeBytes,
            @Value("${valem.spreadsheet.max-rows:10000}") int maxRows,
            @Value("${valem.spreadsheet.max-columns:200}") int maxColumns,
            ObjectMapper mapper) {
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxRows = maxRows;
        this.maxColumns = maxColumns;
        this.mapper = mapper;
    }

    /**
     * Never actually throws a checked {@code IOException}: the try-with-resources below catches
     * everything (including any read/parse failure from opening the workbook) and rewraps it as a
     * named {@link SpreadsheetExtractionException} instead — a corrupt or unreadable upload is a
     * client error (400), not a server error, so there is no distinct "500" case for this method's
     * caller to handle.
     */
    public SpreadsheetCompiler.CompileResult compile(MultipartFile file, String modelId) {
        if (file == null || file.isEmpty()) {
            throw new SpreadsheetExtractionException(EMPTY_WORKBOOK, "Uploaded file is empty");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new SpreadsheetExtractionException(FILE_TOO_LARGE,
                    "File size " + file.getSize() + " bytes exceeds the " + maxFileSizeBytes + " byte limit");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new SpreadsheetExtractionException(UNSUPPORTED_FORMAT,
                    "Unsupported file format: " + filename + " — only .xlsx is supported in v1");
        }

        try (InputStream in = file.getInputStream(); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            XssfCellGrid grid = new XssfCellGrid(workbook);
            if (grid.rowCount() > maxRows) {
                throw new SpreadsheetExtractionException(TOO_MANY_ROWS,
                        "Workbook has " + grid.rowCount() + " rows, exceeding the " + maxRows + " row limit");
            }
            if (grid.columnCount() > maxColumns) {
                throw new SpreadsheetExtractionException(TOO_MANY_COLUMNS,
                        "Workbook has " + grid.columnCount() + " columns, exceeding the " + maxColumns
                        + " column limit");
            }
            return SpreadsheetCompiler.compile(grid, modelId, mapper);
        } catch (SpreadsheetExtractionException | org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException e) {
            throw e;
        } catch (Exception e) {
            throw new SpreadsheetExtractionException(PARSE_FAILED, "Could not parse workbook: " + e.getMessage(), e);
        }
    }
}
