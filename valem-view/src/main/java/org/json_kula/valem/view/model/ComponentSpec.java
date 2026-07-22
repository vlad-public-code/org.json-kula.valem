package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One component in a {@link ViewSpec}'s component tree.
 *
 * <p>{@code type} is the discriminator; each permitted record carries only the fields the
 * component types it covers actually use, so illegal combinations (a {@code badge} with a
 * {@code pageSize}) are not representable. Records are grouped by field shape rather than one
 * per type, mirroring {@code EvaluatedComponent} on the output side.
 *
 * <p>A {@code type} Valem does not know binds to {@link UnknownComponentSpec}, which keeps
 * every property it was given — including ones no built-in component type declares.
 *
 * <p>This interface declares only what the generic evaluation pipeline reads. Everything else
 * is reachable only by pattern-matching the concrete record, which is what makes
 * {@code ViewEvaluator}'s switch exhaustive. {@code bind} is common to every type because it
 * anchors the meta-driven {@code visible}/{@code readOnly}/{@code required} inheritance
 * (see {@code $.bind#relevant} and friends), not only because it locates a value.
 *
 * <p>Dynamic fields ({@code visible}, {@code enabled}, {@code readOnly}, {@code required},
 * {@code canAdd}, {@code canRemove}, {@code text}) accept either a JSON boolean or a JSONata
 * expression string, which is why they are typed as {@link JsonNode}.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true,
        defaultImpl = UnknownComponentSpec.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = BasicInputSpec.class, names = {
                "textField", "numericField", "passwordField", "emailField", "phoneNumberField",
                "checkboxField", "toggleField", "dateField", "dateTimeField", "timeField",
                "countrySelector"}),
        @JsonSubTypes.Type(value = TextAreaSpec.class,          names = "textAreaField"),
        @JsonSubTypes.Type(value = ChoiceInputSpec.class,       names = {
                "selectField", "radioField", "multiSelectField"}),
        @JsonSubTypes.Type(value = DependentSelectorSpec.class, names = "countryRegionSelector"),
        @JsonSubTypes.Type(value = SliderSpec.class,            names = "sliderField"),
        @JsonSubTypes.Type(value = FileUploadSpec.class,        names = "fileUploadField"),
        @JsonSubTypes.Type(value = LabelSpec.class,             names = "label"),
        @JsonSubTypes.Type(value = StaticTextSpec.class,        names = "staticText"),
        @JsonSubTypes.Type(value = BadgeSpec.class,             names = "badge"),
        @JsonSubTypes.Type(value = SeparatorLineSpec.class,     names = "separatorLine"),
        @JsonSubTypes.Type(value = ProgressBarSpec.class,       names = "progressBar"),
        @JsonSubTypes.Type(value = DataTableSpec.class,         names = "dataTable"),
        @JsonSubTypes.Type(value = DataChartSpec.class,         names = "dataChart"),
        @JsonSubTypes.Type(value = ContainerSpec.class,         names = {
                "group", "fieldSet", "sectionItem"}),
        @JsonSubTypes.Type(value = SectionListSpec.class,       names = "sectionList"),
        @JsonSubTypes.Type(value = ButtonSpec.class,            names = "button"),
        @JsonSubTypes.Type(value = MenuSpec.class,              names = "menu")
})
public sealed interface ComponentSpec permits
        BasicInputSpec,
        TextAreaSpec,
        ChoiceInputSpec,
        DependentSelectorSpec,
        SliderSpec,
        FileUploadSpec,
        LabelSpec,
        StaticTextSpec,
        BadgeSpec,
        SeparatorLineSpec,
        ProgressBarSpec,
        DataTableSpec,
        DataChartSpec,
        ContainerSpec,
        SectionListSpec,
        ButtonSpec,
        MenuSpec,
        UnknownComponentSpec {

    String id();

    String type();

    /** JsonPath to the bound model field; also the meta-inheritance anchor. */
    default String bind() { return null; }

    /** Boolean | JSONata String | null -> inherit {@code $.bind#relevant}. */
    default JsonNode visible() { return null; }

    /** Boolean | JSONata String | null -> {@code !readOnly}. */
    default JsonNode enabled() { return null; }

    /** Boolean | JSONata String | null -> inherit {@code $.bind#read_only}. */
    default JsonNode readOnly() { return null; }

    /** Boolean | JSONata String | null -> inherit {@code $.bind#required}. */
    default JsonNode required() { return null; }

    /**
     * Enforces the two fields every component must carry. Called from each record's compact
     * constructor so a spec missing them fails at parse time (a 422 at write) rather than
     * rendering as a nameless component.
     */
    static void requireIdentity(String id, String type) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ComponentSpec: 'id' is required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                    "ComponentSpec: 'type' is required (component '" + id + "')");
        }
    }
}
