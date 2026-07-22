package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code dataChart} — a bar/line/pie/area chart over a bound array — and {@code sparkline}, the
 * same series stripped of axes, legend and grid so it can sit inline beside a number.
 */
public record DataChartSpec(
        String id,
        String type,
        String label,
        JsonNode visible,
        String bind,
        String chartType,
        String chartX,
        List<ChartSeriesSpec> chartSeries
) implements ComponentSpec {
    public DataChartSpec {
        ComponentSpec.requireIdentity(id, type);
        chartSeries = chartSeries != null ? List.copyOf(chartSeries) : null;
    }
}
