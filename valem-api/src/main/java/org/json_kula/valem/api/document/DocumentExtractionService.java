package org.json_kula.valem.api.document;

import org.json_kula.valem.core.document.DocumentExtractionException;
import org.json_kula.valem.core.document.DocumentTextExtractor;
import org.json_kula.valem.core.document.DocxTextExtractor;
import org.json_kula.valem.core.document.ExtractedDocument;
import org.json_kula.valem.core.document.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Extracts text from an uploaded PDF/DOCX, enforcing the size/page caps that make the "potentially
 * big" document problem predictable rather than a silent hang or truncation (vision doc AC-1/AC-7).
 */
@Service
public class DocumentExtractionService {

    private final List<DocumentTextExtractor> extractors;
    private final long maxFileSizeBytes;
    private final int  maxPages;
    private final int  maxExtractedChars;

    // Explicit @Autowired: a second (package-private, test-only) constructor exists below, and
    // Spring cannot pick a constructor implicitly once there is more than one candidate.
    @Autowired
    public DocumentExtractionService(
            @Value("${valem.document.max-file-size-bytes:26214400}") long maxFileSizeBytes,
            @Value("${valem.document.max-pages:300}") int maxPages,
            @Value("${valem.document.max-extracted-chars:5000000}") int maxExtractedChars) {
        this(List.of(new PdfTextExtractor(), new DocxTextExtractor()), maxFileSizeBytes, maxPages,
                maxExtractedChars);
    }

    /** Package-private — lets tests substitute fake extractors without touching Spring config. */
    DocumentExtractionService(List<DocumentTextExtractor> extractors, long maxFileSizeBytes, int maxPages,
                              int maxExtractedChars) {
        this.extractors        = extractors;
        this.maxFileSizeBytes  = maxFileSizeBytes;
        this.maxPages          = maxPages;
        this.maxExtractedChars = maxExtractedChars;
    }

    public ExtractedDocument extract(MultipartFile file) throws DocumentExtractionException, IOException {
        if (file == null || file.isEmpty()) {
            throw new DocumentExtractionException(
                    DocumentExtractionException.Reason.EMPTY_DOCUMENT, "Uploaded file is empty");
        }

        // Enforced from getSize() BEFORE parsing — an oversized upload is rejected without ever
        // reading its bytes (vision doc AC-1: rejected immediately, not after minutes of processing).
        if (file.getSize() > maxFileSizeBytes) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.FILE_TOO_LARGE,
                    "File size " + file.getSize() + " bytes exceeds the " + maxFileSizeBytes
                    + " byte limit");
        }

        String filename = file.getOriginalFilename();
        DocumentTextExtractor extractor = extractors.stream()
                .filter(e -> e.supports(filename, file.getContentType()))
                .findFirst()
                .orElseThrow(() -> new DocumentExtractionException(
                        DocumentExtractionException.Reason.UNSUPPORTED_FORMAT,
                        "Unsupported file format: " + filename
                        + " — only text-native PDF and DOCX are supported in v1"));

        ExtractedDocument extracted;
        try (InputStream in = file.getInputStream()) {
            extracted = extractor.extract(in, filename);
        }

        if (extracted.isEmpty()) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.EMPTY_DOCUMENT,
                    "No extractable text found — the document may be a scanned image "
                    + "(OCR is not supported in v1)");
        }
        // Checked on the RESULT, independent of the page-count cap below: a decompression-ratio
        // outlier or a pathologically busy single page could otherwise produce far more in-memory
        // text than the (already-capped) upload size would suggest. Defense in depth alongside POI's
        // own ZipSecureFile zip-bomb guard for DOCX (min-inflate-ratio + max-entry-size, on by default).
        if (extracted.charCount() > maxExtractedChars) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.CONTENT_TOO_LARGE,
                    "Extracted text is " + extracted.charCount() + " characters, exceeding the "
                    + maxExtractedChars + " character limit");
        }
        if (extracted.pageCount() > maxPages) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.TOO_MANY_PAGES,
                    "Document has " + extracted.pageCount() + " pages, exceeding the " + maxPages
                    + " page limit");
        }
        return extracted;
    }
}
