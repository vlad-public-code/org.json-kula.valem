package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Flat discriminated-union record for all component types.
 * The {@code type} field is the discriminator; all other fields are nullable
 * and interpreted only for the component types that use them.
 *
 * Dynamic fields (visible, enabled, readOnly, required, text, className) accept
 * either a JSON boolean or a JSONata expression string.
 */
public record ComponentSpec(
        String id,
        String type,

        // common display
        String label,
        JsonNode visible,
        JsonNode enabled,
        JsonNode readOnly,
        JsonNode required,
        String bind,
        String placeholder,
        String helperText,
        String tooltip,
        JsonNode className,

        // options for select/radio/multiSelect
        List<OptionSpec> options,
        String optionsExpr,
        String optionsUrl,
        String optionsPath,

        // countryRegionSelector
        String dependsOn,

        // textAreaField
        Integer rows,

        // aggregate containers
        List<ComponentSpec> components,
        String layout,
        Integer columns,

        // fieldSet
        String legend,

        // sectionList
        String itemView,
        JsonNode canAdd,
        JsonNode canRemove,
        String addLabel,
        String removeLabel,

        // dataTable
        List<ColumnSpec> tableColumns,
        Integer pageSize,

        // dataChart
        String chartType,
        String chartX,
        List<ChartSeriesSpec> chartSeries,

        // label / staticText
        JsonNode text,

        // menu
        List<MenuItemSpec> menuItems,
        String orientation,

        // button
        String variant,
        String icon,

        // sliderField
        Double min,
        Double max,
        Double step,

        // fileUploadField
        String accept,
        Boolean multiple,
        Integer minFiles,
        Integer maxFiles,
        Long minSize,
        Long maxSize,
        String allowedMediaTypes,

        // progressBar
        Boolean showValue,
        String format,

        // event handlers
        EventHandler onClick,
        EventHandler onChange,
        EventHandler onOpen,
        EventHandler onClose
) {
    @JsonCreator
    public static ComponentSpec of(
            @JsonProperty(value = "id",   required = true) String id,
            @JsonProperty(value = "type", required = true) String type,
            @JsonProperty("label")        String label,
            @JsonProperty("visible")      JsonNode visible,
            @JsonProperty("enabled")      JsonNode enabled,
            @JsonProperty("readOnly")     JsonNode readOnly,
            @JsonProperty("required")     JsonNode required,
            @JsonProperty("bind")         String bind,
            @JsonProperty("placeholder")  String placeholder,
            @JsonProperty("helperText")   String helperText,
            @JsonProperty("tooltip")      String tooltip,
            @JsonProperty("className")    JsonNode className,
            @JsonProperty("options")      List<OptionSpec> options,
            @JsonProperty("optionsExpr")  String optionsExpr,
            @JsonProperty("optionsUrl")   String optionsUrl,
            @JsonProperty("optionsPath")  String optionsPath,
            @JsonProperty("dependsOn")    String dependsOn,
            @JsonProperty("rows")         Integer rows,
            @JsonProperty("components")   List<ComponentSpec> components,
            @JsonProperty("layout")       String layout,
            @JsonProperty("columns")      Integer columns,
            @JsonProperty("legend")       String legend,
            @JsonProperty("itemView")     String itemView,
            @JsonProperty("canAdd")       JsonNode canAdd,
            @JsonProperty("canRemove")    JsonNode canRemove,
            @JsonProperty("addLabel")     String addLabel,
            @JsonProperty("removeLabel")  String removeLabel,
            @JsonProperty("tableColumns") List<ColumnSpec> tableColumns,
            @JsonProperty("pageSize")     Integer pageSize,
            @JsonProperty("chartType")    String chartType,
            @JsonProperty("chartX")       String chartX,
            @JsonProperty("chartSeries")  List<ChartSeriesSpec> chartSeries,
            @JsonProperty("text")         JsonNode text,
            @JsonProperty("menuItems")    List<MenuItemSpec> menuItems,
            @JsonProperty("orientation")  String orientation,
            @JsonProperty("variant")      String variant,
            @JsonProperty("icon")         String icon,
            @JsonProperty("min")          Double min,
            @JsonProperty("max")          Double max,
            @JsonProperty("step")         Double step,
            @JsonProperty("accept")              String accept,
            @JsonProperty("multiple")            Boolean multiple,
            @JsonProperty("minFiles")            Integer minFiles,
            @JsonProperty("maxFiles")            Integer maxFiles,
            @JsonProperty("minSize")             Long minSize,
            @JsonProperty("maxSize")             Long maxSize,
            @JsonProperty("allowedMediaTypes")   String allowedMediaTypes,
            @JsonProperty("showValue")           Boolean showValue,
            @JsonProperty("format")              String format,
            @JsonProperty("onClick")      EventHandler onClick,
            @JsonProperty("onChange")     EventHandler onChange,
            @JsonProperty("onOpen")       EventHandler onOpen,
            @JsonProperty("onClose")      EventHandler onClose
    ) {
        return new ComponentSpec(
                id, type, label, visible, enabled, readOnly, required,
                bind, placeholder, helperText, tooltip, className,
                options      != null ? List.copyOf(options)      : null,
                optionsExpr, optionsUrl, optionsPath,
                dependsOn, rows,
                components   != null ? List.copyOf(components)   : null,
                layout, columns, legend, itemView,
                canAdd, canRemove, addLabel, removeLabel,
                tableColumns != null ? List.copyOf(tableColumns) : null,
                pageSize,
                chartType, chartX,
                chartSeries  != null ? List.copyOf(chartSeries)  : null,
                text,
                menuItems    != null ? List.copyOf(menuItems)    : null,
                orientation, variant, icon,
                min, max, step, accept, multiple, minFiles, maxFiles, minSize, maxSize, allowedMediaTypes, showValue, format,
                onClick, onChange, onOpen, onClose
        );
    }
}
