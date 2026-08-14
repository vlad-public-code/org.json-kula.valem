package org.json_kula.valem.core.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The document-rootedness rule for library definitions.
 *
 * <p>False <b>rejections</b> are the expensive failure here — they block valid specs — so the accept
 * cases carry more weight than the reject cases.
 */
class LibraryPurityCheckTest {

    // ── Rejected: reads the model document ────────────────────────────────────

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            // a bare field reference in a function body
            "( $f := function() { order.subtotal }; [\"f\"] )",
            // an unqualified field name
            "( $f := function() { total * 2 }; [\"f\"] )",
            // the context, with no input to read
            "( $f := function() { $.subtotal }; [\"f\"] )",
            // the document root
            "( $f := function() { $$.order.subtotal }; [\"f\"] )",
            // ... including from inside an otherwise variable-rooted map step
            "( $f := function($t) { $t.($$.rate) }; [\"f\"] )",
            // descendant and wildcard steps
            "( $f := function() { **.price }; [\"f\"] )",
            "( $f := function() { *.price }; [\"f\"] )",
            // a document read outside any lambda, at the top level of the definition
            "( $rate := order.rate; $f := function($n) { $n * $rate }; [\"f\"] )",
    })
    void rejects_document_reads(String define) {
        assertThat(LibraryPurityCheck.check(define))
                .as("should be reported as a document read")
                .isNotEmpty();
    }

    // ── Accepted: rooted at a variable, a parameter, or a mapped element ───────

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {
            // pure arithmetic on parameters
            "( $f := function($a, $b) { $a * $b }; [\"f\"] )",
            // navigation into a parameter
            "( $f := function($order) { $order.subtotal - $order.discount }; [\"f\"] )",
            // $const navigation
            "( $f := function($n) { $n * $const.vatRate }; [\"f\"] )",
            "( $f := function($n) { $n * $const.brackets[0].rate }; [\"f\"] )",
            // a predicate over a variable-rooted array: upTo/rate are fields of the element
            "( $f := function($amount) { ($const.brackets[upTo >= $amount])[0].rate }; [\"f\"] )",
            // a map block over a variable-rooted array
            "( $f := function($a) { $sum($const.brackets.( upTo - band )) }; [\"f\"] )",
            // $ as the mapped element of a variable-rooted sequence
            "( $g := function($x, $b) { $x * $b.rate }; $f := function($a) { $sum($const.brackets.$g($a, $)) }; [\"f\"] )",
            // a parameter navigated into inside a map
            "( $f := function($rows) { $sum($rows.(price * qty)) }; [\"f\"] )",
            // a block-local binding inside a parenthesised lambda body
            "( $f := function($n) { ( $half := $n / 2; $half + 1 ) }; [\"f\"] )",
            // built-ins and literals only
            "( $f := function($s) { $uppercase($s) & \"!\" }; [\"f\"] )",
            // a quoted key on a variable-rooted path
            "( $f := function($o) { $o.\"odd-name\" }; [\"f\"] )",
            // higher-order: a lambda passed to a built-in
            "( $f := function($xs) { $map($xs, function($x){ $x * 2 }) }; [\"f\"] )",
            // an exported constant computed from $const
            "( $maxRate := $max($const.brackets.rate); [\"maxRate\"] )",
    })
    void accepts_pure_definitions(String define) {
        assertThat(LibraryPurityCheck.check(define))
                .as("should be accepted as pure")
                .isEmpty();
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    @Test
    void names_the_offending_reference() {
        assertThat(LibraryPurityCheck.check("( $f := function() { order.subtotal }; [\"f\"] )"))
                .extracting(LibraryPurityCheck.Finding::reference)
                .containsExactly("order.subtotal");
    }

    @Test
    void reports_each_distinct_reference_once() {
        assertThat(LibraryPurityCheck.check(
                "( $f := function() { order.a + order.a }; [\"f\"] )"))
                .hasSize(1);
    }

    @Test
    void reports_a_dotted_path_whole_rather_than_per_segment() {
        assertThat(LibraryPurityCheck.check("( $f := function() { order.line.subtotal }; [\"f\"] )"))
                .extracting(LibraryPurityCheck.Finding::reference)
                .containsExactly("order.line.subtotal");
    }

    @Test
    void an_unparseable_definition_reports_nothing_leaving_the_parse_error_to_the_compiler() {
        assertThat(LibraryPurityCheck.check("( $f := function($x { $x }; [\"f\"] )")).isEmpty();
    }
}
