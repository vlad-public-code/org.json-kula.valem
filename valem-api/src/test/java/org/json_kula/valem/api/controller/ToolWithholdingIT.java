package org.json_kula.valem.api.controller;

import org.json_kula.valem.core.llm.LlmProgressEvent;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.json_kula.valem.core.llm.SpecGenerator.GenerationResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification (real LLM) of the tool-withholding fix: web tools power the research phase
 * on the INITIAL attempt only, and are withheld on every repair. Repairs re-invoking the tools is
 * what let an off-topic web search (belonging to a previously generated model) surface on a repair
 * turn via provider prompt-prefix cache bleed.
 *
 * <p>Drives a domain that reliably needs more than one attempt and asserts, from the progress-event
 * stream, that no {@code ToolCalling} event occurs on any attempt after the first.
 *
 * <p>Skipped when the LLM is not configured (set the Mistral key + provider=mistral).
 */
@SpringBootTest
class ToolWithholdingIT {

    private static final Logger log = LoggerFactory.getLogger(ToolWithholdingIT.class);

    // Amortization is fiddly enough that the model reliably needs at least one repair attempt.
    private static final String DOMAIN =
            "Monthly mortgage payment from loan principal, annual interest rate percent, and term in "
            + "years, using the standard amortization formula; also derive total interest paid.";

    @Autowired(required = false)
    SpecGenerator specGenerator;

    @Test
    void web_tools_are_only_used_on_the_first_attempt() {
        Assumptions.assumeTrue(specGenerator != null,
                "Skipping: LLM not configured (set the Mistral key + provider=mistral)");

        // Record the attempt number in scope when each web-tool call fires.
        int[] currentAttempt = {0};
        List<Integer> toolCallAttempts = new ArrayList<>();

        GenerationResult result = specGenerator.generate("mortgage-payment", DOMAIN, true, ev -> {
            if (ev instanceof LlmProgressEvent.LlmRequesting r) {
                currentAttempt[0] = r.attempt();
            } else if (ev instanceof LlmProgressEvent.ToolCalling t) {
                toolCallAttempts.add(currentAttempt[0]);
                log.info("tool '{}' called on attempt {}", t.tool(), currentAttempt[0]);
            }
        });

        int attemptsUsed = result instanceof GenerationResult.Success s ? s.attemptsUsed()
                : ((GenerationResult.Failure) result).attemptsUsed();
        log.info("Result: {}, attemptsUsed: {}, tool calls per attempt: {}",
                result.getClass().getSimpleName(), attemptsUsed, toolCallAttempts);
        if (attemptsUsed == 1) {
            log.warn("Only one attempt was needed — the repair path (and thus tool-withholding on "
                    + "repair) was not exercised this run; the assertion holds vacuously.");
        }

        // The fix's contract: every web-tool call happened on the first attempt; none on a repair.
        assertThat(toolCallAttempts)
                .as("no web-tool call may occur on a repair attempt (attempt > 1); saw %s", toolCallAttempts)
                .allMatch(a -> a == 1);
    }
}
