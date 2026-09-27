package org.json_kula.valem.core.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentScannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ExtractedDocument doc(String... pages) {
        List<DocumentPage> list = new java.util.ArrayList<>();
        for (int i = 0; i < pages.length; i++) list.add(new DocumentPage(i + 1, pages[i]));
        return new ExtractedDocument("policy.pdf", ExtractedDocument.SourceKind.PDF, list);
    }

    @Test
    void found_response_parses_into_candidates() {
        LlmClient stub = prompt -> """
                {"found": true, "candidates": [
                  {"quote": "Coinsurance is 20% of the allowed amount.", "page": 2,
                   "sectionHint": "4.2 Coinsurance", "confidence": 0.9, "rationale": "directly answers"}
                ], "reason": ""}
                """;
        DocumentScanner scanner = new DocumentScanner(stub, MAPPER, 6, 4000, 24000);

        DocumentScanResult result = scanner.scan(doc("irrelevant page", "Coinsurance is 20% of the allowed amount."),
                "what is my coinsurance?");

        assertThat(result).isInstanceOf(DocumentScanResult.Found.class);
        List<FormulaCandidate> candidates = ((DocumentScanResult.Found) result).candidates();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).quote()).isEqualTo("Coinsurance is 20% of the allowed amount.");
        assertThat(candidates.get(0).page()).isEqualTo(2);
    }

    @Test
    void not_found_response_carries_the_reason() {
        LlmClient stub = prompt -> """
                {"found": false, "candidates": [], "reason": "No coinsurance clause in the excerpts"}
                """;
        DocumentScanner scanner = new DocumentScanner(stub, MAPPER, 6, 4000, 24000);

        DocumentScanResult result = scanner.scan(doc("some text"), "what is my coinsurance?");

        assertThat(result).isInstanceOf(DocumentScanResult.NotFound.class);
        assertThat(((DocumentScanResult.NotFound) result).reason())
                .isEqualTo("No coinsurance clause in the excerpts");
    }

    @Test
    void malformed_json_response_becomes_not_found_not_an_exception() {
        LlmClient stub = prompt -> "not json at all {{{";
        DocumentScanner scanner = new DocumentScanner(stub, MAPPER, 6, 4000, 24000);

        DocumentScanResult result = scanner.scan(doc("some text"), "anything");

        assertThat(result).isInstanceOf(DocumentScanResult.NotFound.class);
    }

    @Test
    void llm_exception_becomes_not_found_not_a_propagated_exception() {
        LlmClient stub = prompt -> { throw new LlmClient.LlmException("provider down"); };
        DocumentScanner scanner = new DocumentScanner(stub, MAPPER, 6, 4000, 24000);

        DocumentScanResult result = scanner.scan(doc("some text"), "anything");

        assertThat(result).isInstanceOf(DocumentScanResult.NotFound.class);
        assertThat(((DocumentScanResult.NotFound) result).reason()).contains("provider down");
    }

    @Test
    void empty_document_is_not_found_without_calling_the_llm() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient stub = prompt -> { calls.incrementAndGet(); return "{}"; };
        DocumentScanner scanner = new DocumentScanner(stub, MAPPER, 6, 4000, 24000);

        DocumentScanResult result = scanner.scan(doc(), "anything");

        assertThat(result).isInstanceOf(DocumentScanResult.NotFound.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void blank_ask_is_not_found_without_calling_the_llm() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient stub = prompt -> { calls.incrementAndGet(); return "{}"; };
        DocumentScanner scanner = new DocumentScanner(stub, MAPPER, 6, 4000, 24000);

        DocumentScanResult result = scanner.scan(doc("some text"), "   ");

        assertThat(result).isInstanceOf(DocumentScanResult.NotFound.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void malformed_individual_candidate_is_skipped_not_fatal() {
        LlmClient stub = prompt -> """
                {"found": true, "candidates": [
                  {"quote": "", "page": 2},
                  {"quote": "valid quote here", "page": 3}
                ]}
                """;
        DocumentScanner scanner = new DocumentScanner(stub, MAPPER, 6, 4000, 24000);

        DocumentScanResult result = scanner.scan(doc("a", "b", "c"), "anything");

        assertThat(result).isInstanceOf(DocumentScanResult.Found.class);
        assertThat(((DocumentScanResult.Found) result).candidates()).hasSize(1);
    }

    @Test
    void cost_is_bounded_regardless_of_document_length() {
        // A 200-page document must not blow up the prompt sent to the LLM: only maxTotalChars worth
        // of chunk text should ever reach it, regardless of how many pages exist.
        String[] pages = new String[200];
        for (int i = 0; i < pages.length; i++) pages[i] = "filler page " + i + " ".repeat(50);
        pages[150] = "The early termination fee is $200 per the contract.";

        StringBuilder capturedPromptLength = new StringBuilder();
        LlmClient stub = prompt -> {
            capturedPromptLength.append(prompt.length());
            return """
                    {"found": true, "candidates": [
                      {"quote": "The early termination fee is $200 per the contract.", "page": 151}
                    ]}
                    """;
        };
        DocumentScanner scanner = new DocumentScanner(stub, MAPPER, 6, 200, 1200);

        DocumentScanResult result = scanner.scan(doc(pages), "what is the early termination fee?");

        assertThat(result).isInstanceOf(DocumentScanResult.Found.class);
        int promptLen = Integer.parseInt(capturedPromptLength.toString());
        // Bounded by maxTotalChars + prompt scaffolding, independent of the 200-page source.
        assertThat(promptLen).isLessThan(5000);
    }

    @Test
    void rejects_invalid_construction_parameters() {
        assertThatThrownBy(() -> new DocumentScanner(p -> "{}", MAPPER, 0, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentScanner(p -> "{}", MAPPER, 5, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentScanner(p -> "{}", MAPPER, 5, 500, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
