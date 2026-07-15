package org.json_kula.valem.service;

public record ModelInfo(
        String id,
        String version,
        int derivationCount,
        int metaDerivationCount,
        int constraintCount,
        int effectCount
) {}
