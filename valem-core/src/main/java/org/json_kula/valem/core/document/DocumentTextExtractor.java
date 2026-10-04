package org.json_kula.valem.core.document;

import java.io.IOException;
import java.io.InputStream;

/**
 * One file-format-specific text extractor (PDF, DOCX, …). A caller (the REST
 * {@code DocumentExtractionService} in {@code valem-api}, or the MCP {@code search_document} tool)
 * picks the first extractor whose {@link #supports} matches, enforces its own size/page caps, and
 * calls {@link #extract}.
 */
public interface DocumentTextExtractor {

    /** Whether this extractor handles the given filename (extension-based; content-type is advisory). */
    boolean supports(String filename, String contentType);

    /**
     * Extracts text, page by page (or pseudo-page — see {@code DocxTextExtractor}).
     *
     * @throws DocumentExtractionException with {@code Reason.PARSE_FAILED} if the file cannot be parsed
     * @throws IOException on a stream read failure
     */
    ExtractedDocument extract(InputStream in, String filename) throws DocumentExtractionException, IOException;
}
