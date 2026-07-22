package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.view.model.ColumnSpec;
import org.json_kula.valem.view.model.ChartSeriesSpec;
import org.json_kula.valem.view.model.EventHandler;
import org.json_kula.valem.view.model.MenuItemSpec;
import org.json_kula.valem.view.model.OptionSpec;

import java.util.List;

/**
 * Renderer-agnostic view of an evaluated component.
 * Each concrete permit carries only the fields relevant to that component type;
 * methods not overridden by a subtype return sensible defaults.
 */
public sealed interface EvaluatedComponent permits
        EvaluatedBasicInput,
        EvaluatedTextArea,
        EvaluatedSelectField,
        EvaluatedDependentSelector,
        EvaluatedSlider,
        EvaluatedDateRange,
        EvaluatedFileUpload,
        EvaluatedLabel,
        EvaluatedStaticText,
        EvaluatedBadge,
        EvaluatedImage,
        EvaluatedLink,
        EvaluatedProgressBar,
        EvaluatedDataTable,
        EvaluatedDataChart,
        EvaluatedKeyValueList,
        EvaluatedStatTile,
        EvaluatedJsonViewer,
        EvaluatedTracePanel,
        EvaluatedValidationSummary,
        EvaluatedEffectStatus,
        EvaluatedContainer,
        EvaluatedSectionList,
        EvaluatedButton,
        EvaluatedMenu,
        EvaluatedSeparatorLine {

    String id();
    String type();
    boolean visible();

    default String bind()               { return null; }
    default boolean enabled()           { return true; }
    default boolean readOnly()          { return false; }
    default boolean required()          { return false; }
    default JsonNode value()            { return null; }
    default String text()               { return null; }
    default String label()              { return null; }
    default List<OptionSpec> options()  { return null; }
    default List<EvaluatedComponent> components() { return null; }
    default Double min()                { return null; }
    default Double max()                { return null; }
    default Double step()               { return null; }
    default String accept()             { return null; }
    default Boolean multiple()          { return null; }
    default Integer minFiles()          { return null; }
    default Integer maxFiles()          { return null; }
    default Long minSize()              { return null; }
    default Long maxSize()              { return null; }
    default String allowedMediaTypes()  { return null; }
    default Boolean showValue()         { return null; }
    default String format()             { return null; }
    default String currency()           { return null; }
    default String variant()            { return null; }
    default String icon()               { return null; }
    default boolean collapsed()         { return false; }
    default Integer limit()             { return null; }
    default List<ColumnSpec> tableColumns()        { return null; }
    default List<ChartSeriesSpec> chartSeries()    { return null; }
    default List<MenuItemSpec> menuItems()         { return null; }
    default List<EvaluatedKeyValueItem> items()    { return null; }
    default EventHandler onClick()      { return null; }
    default EventHandler onChange()     { return null; }
    default EventHandler onOpen()       { return null; }
    default EventHandler onClose()      { return null; }
    default EventHandler onRetry()      { return null; }
}
