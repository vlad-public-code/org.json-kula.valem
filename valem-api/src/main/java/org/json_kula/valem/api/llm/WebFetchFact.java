package org.json_kula.valem.api.llm;

public record WebFetchFact(
        String url,
        int    responseCode,
        String mediaType,
        int    rawLength,
        int    extractedLength
) {}
