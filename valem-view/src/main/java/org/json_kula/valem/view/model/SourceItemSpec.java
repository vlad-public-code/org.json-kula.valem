package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One citation of a {@code sourceList}: a named external reference behind a URL, with the date it
 * was last checked.
 *
 * <p>Unlike a {@code keyValueList} row these fields are authored literals, not paths — a source
 * block cites an official authority (a tax code, a government calculator), which is a fact about the
 * model's provenance rather than a value computed from its state. {@code date} is the E-E-A-T
 * "sources checked on" signal a page and a quality rater both look for; it is a plain string so an
 * annual refresh is a one-line edit, not a schema change.
 */
public record SourceItemSpec(
        String label,
        String url,
        String date
) {
    @JsonCreator
    public static SourceItemSpec of(
            @JsonProperty("label") String label,
            @JsonProperty("url")   String url,
            @JsonProperty("date")  String date) {
        return new SourceItemSpec(label, url, date);
    }
}
