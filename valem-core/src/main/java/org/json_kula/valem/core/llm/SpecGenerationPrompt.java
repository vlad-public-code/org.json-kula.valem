package org.json_kula.valem.core.llm;

import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.model.DerivationSpec;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds prompt strings for LLM-based model spec generation and repair.
 *
 * <p>Usage:
 * <ol>
 *   <li>Call {@link #initialPrompt(String, String)} to ask the LLM to produce a spec.</li>
 *   <li>Parse the LLM response as JSON.</li>
 *   <li>Validate with {@link ModelSpecValidator}.</li>
 *   <li>On failure, call {@link #repairPrompt(String, String, List, boolean)} to ask the LLM
 *       to fix the errors and try again.</li>
 * </ol>
 */
public final class SpecGenerationPrompt {

    private SpecGenerationPrompt() {}

    /**
     * Supplemental context describing the {@code viewDefinition} component catalog.
     * Appended to {@link #SYSTEM_CONTEXT} when the caller requests UI generation.
     */
    public static final String SYSTEM_CONTEXT_VIEW = """

            Additional: viewDefinition (embed a declarative UI component tree in the spec)

            Top-level ViewDefinition:
            {
              "renderer":    "builtin",
              "defaultView": "<view-id>",
              "views":       [ <ViewSpec>, ... ]
            }

            ViewSpec:
            {
              "id":         "<string>",
              "label":      "<string>",
              "layout":     "vertical" | "horizontal" | "grid" | "tabs" | "wizard",
              "columns":    <int>,           // for "grid" layout only
              "components": [ <ComponentSpec>, ... ]
            }

            ComponentSpec — common fields (all types):
            {
              "id":          "<string>",     // required, unique within the view
              "type":        "<type>",       // required — see catalog below
              "label":       "<string>",
              "bind":        "<$.path>",     // JsonPath to the bound model field
              "visible":     true|false|"<JSONata>"|null,  // null → auto from $.bind#relevant meta
              "enabled":     true|false|"<JSONata>"|null,  // null → !readOnly
              "readOnly":    true|false|"<JSONata>"|null,  // null → auto from $.bind#read_only meta
              "required":    true|false|"<JSONata>"|null,  // null → auto from $.bind#required meta
              "placeholder": "<string>",
              "helperText":  "<string>",
              "tooltip":     "<string>",
              "className":   "<string>|<JSONata>",
              "onChange":    <EventHandler>,
              "onClick":     <EventHandler>
            }

            EventHandler:
            { "mutations": "<JSONata → {'$.path': value}>", "navigate": "<view-id>" }

            COMPONENT CATALOG:

            Input types (all support bind, label, visible, enabled, readOnly, required, placeholder, helperText, onChange):
              textField            single-line text
              textAreaField        multi-line; extra: rows (int)
              numericField         number; min/max from JSON Schema
              passwordField        masked text
              emailField           email with format validation
              checkboxField        boolean checkbox
              toggleField          boolean toggle/switch
              dateField            date picker
              dateTimeField        date + time picker
              countrySelector      country dropdown (no extra config needed)
              countryRegionSelector  region/state selector; extra: dependsOn ($.path of sibling countrySelector)
              phoneNumberField     phone with dial-code picker
              selectField          dropdown; extra: options [{value, label}], optionsExpr (JSONata)
              radioField           radio group; same options fields as selectField
              multiSelectField     multi-select; same options fields as selectField

            Data output:
              label          bound value or expression; extra: text (string or JSONata)
              staticText     non-reactive text block; extra: text (static string)
              badge          status chip; extra: text, variant ("primary"|"secondary"|"success"|"warning"|"danger")
              separatorLine  horizontal rule; no extra fields
              dataTable      array as table; extra: tableColumns [{field, header, format, width}], pageSize (int)
              dataChart      chart; extra: chartType ("bar"|"line"|"area"|"pie"), chartX, chartSeries [{field, label, color}]

            JSONata in view expressions — field reference syntax:
              Reference model fields by their unqualified name: age, totalTax, emissionsBand.
              NEVER use a $ prefix for field names: $age and $totalTax are undefined variables.
              $ is reserved for built-in functions ($string, $now, $sum, …) and lambda parameters.
              WRONG: "text": "$age & \\" years\\""    ← $age is an undefined variable
              RIGHT: "text": "age & \\" years\\""     ← age is the model field

            Escaping in "text" expressions (label/badge/staticText):
              When a JSONata expression contains a literal quote, use a single JSON-escaped quote:
                "text": "$& \\" €/yr\\""     ← \" is a JSON-escaped quote inside the JSONata expression
              Never backslash-escape the field name itself: "text\\":" is always a bug.

            Containers:
              group       layout box; extra: layout ("vertical"|"horizontal"|"grid"), columns, components []
              fieldSet    fieldset with legend; extra: legend (string), components []
              sectionList editable array; extra: bind ($.arrayPath), itemView (view-id), canAdd, canRemove
              sectionItem single element editor; extra: components []; bind = $.array[index]

            Actions:
              button   extra: variant ("primary"|"secondary"|"danger"|"ghost"), icon, onClick (EventHandler)
              menu     navigation; extra: menuItems [{label, targetView, icon}], orientation ("horizontal"|"vertical")

            Meta-cache inheritance: visible/readOnly/required null → evaluator reads metaDerivation values
            automatically, so metaDerivations alone can drive component visibility without view expressions.
            """;

    /** System message describing the Valem spec format to the LLM. */
    public static final String SYSTEM_CONTEXT = """
            You are an expert in Valem, a deterministic reactive computation runtime \
            for JSON data models. Your task is to produce valid Valem model specifications \
            in JSON format.

            If a web_fetch tool is available, use it to look up authoritative domain information \
            before writing derivation expressions — for example, fetch official tax tables, \
            rate schedules, or formula references. Prefer primary sources (government sites, \
            official standards). Fetch at most 2-3 relevant pages, then generate the spec.

            If an eval_jsonata tool is available, use it to TEST any non-trivial derivation or \
            constraint expression before finalizing the spec: pass the candidate expr and a small \
            sample input matching your schema. It returns the computed value or the exact compiler \
            error, so you can fix syntax (parentheses, lambda { } bodies, operators) and logic in \
            place rather than guessing. Verify your hardest expressions (schedules, chained formulas) \
            this way.

            A Valem model spec has this structure:
            {
              "id": "<string>",                      // required: unique model identifier
              "version": "<semver>",                 // optional, defaults to "1.0.0"
              "schema": { /* JSON Schema Draft 2020-12 — declare only WRITABLE input fields here.
                             A field you compute in "derivations" is read-only: either OMIT it from
                             the schema, or include it with "readOnly": true. Never leave a derived
                             field as an ordinary (writable) property — clients cannot write it. */ },
              "constants": {                         // named immutable values (any JSON type)
                "vatRate": 0.22,                     // referenced in ANY expression as $const.vatRate
                "brackets": [ { "upTo": 10000, "rate": 0.1 } ]   // arrays/objects allowed: $const.brackets
              },
              "defaultValues": [                     // seed values for newly-created containers
                {
                  "path": "<container JsonPath>",    // "$" (whole doc, seeds at creation), an object
                                                     // like "$.customer", or an element pattern
                                                     // like "$.items[*]"
                  "expr": "<JSONata object>"         // returns an object merged into the new container,
                                                     // filling ONLY fields the caller left absent.
                                                     // $self = the new container's caller-provided
                                                     // fields; $parent = its JSON parent (the array
                                                     // for an element). Use "$" to seed initial state,
                                                     // e.g. { "path": "$", "expr": "{ \\"width\\": 0 }" }.
                }
              ],
              "derivations": [                       // computed read-only fields
                {
                  "path": "<JsonPath>",              // e.g. "$.order.total" or "$.items[*].lineTotal"
                  "expr": "<JSONata expression>",    // e.g. "order.subtotal + order.tax"
                  "evaluation": "eager" | "lazy"     // optional, default "eager"
                }
              ],
              "metaDerivations": [                   // live field metadata (min/max/required etc.)
                {
                  "path": "<JsonPath>",              // e.g. "$.order.qty"
                  "property": "required" | "minimum" | "maximum" | "minLength" | "maxLength" \
            | "pattern" | "enum" | "multipleOf" | "readOnly" | "relevant",
                  "expr": "<JSONata expression>"
                }
              ],
              "constraints": [                       // invariants checked after each mutation
                {
                  "id": "<string>",
                  "expr": "<JSONata boolean expression>",  // true = constraint satisfied
                  "message": "<human-readable violation message>",
                  "policy": "rollback" | "flag"
                  // "path" is OPTIONAL. DEFAULT: omit path entirely (global constraint).
                  // Global constraints see the full document — use qualified field names.
                  //
                  // ╔══════════════════════════════════════════════════════════════╗
                  // ║  CONSTRAINT PATH RULE — violating this causes 409 at create  ║
                  // ╚══════════════════════════════════════════════════════════════╝
                  // When path IS omitted (global): expr has the FULL document as context.
                  //   CORRECT: { "expr": "vehicle.year >= 1900" }
                  //
                  // When path IS set (scalar): expr receives ONLY THE FIELD VALUE as $.
                  //   Field names are NOT available. You MUST use $ for the value.
                  //   CORRECT: { "path": "$.vehicle.year", "expr": "$ >= 1900" }
                  //   WRONG:   { "path": "$.vehicle.year", "expr": "vehicle.year >= 1900" }
                  //              ↑ vehicle.year is undefined — context is just the number
                  //
                  // RULE: DEFAULT to global constraints (no path). Only add path when you
                  // explicitly need per-field dirty tracking. If you add path, use $ in expr.
                }
              ],
              "effects": [                           // OPTIONAL: side effects run by a SHELL, not the pure core.
                {                                     //           Omit entirely unless the domain needs one.
                  "id": "<string>",
                  "executor": "caller" | "server" | "llm" | "timer",  // who runs the effect
                  "trigger": "<JSONata boolean>",     // fires once when it becomes true after a mutation
                  "dedupeKey": "<JSONata>",           // OPTIONAL edge key: re-fire only when this value changes
                  "statusPath": "$.thing.ioName",     // OPTIONAL: shell writes {phase,key,at}. Use a PLAIN name
                                                      //           (ioName), NOT "$io" — $io breaks JSONata.
                  // executor "caller" — pure; surfaced to the client in the mutation response (no I/O):
                  "emit":    "<event name>",          // caller only
                  "payload": { "<key>": "<JSONata over state>" },   // caller only
                  // executor "llm" — calls a language model, folds the JSON completion back:
                  "prompt":  "'Classify: ' & ticket.body",          // llm only: JSONata -> the prompt text
                  "responseSchema": { "type":"object", "properties": { } },  // llm OPTIONAL: structured-output shape
                  // executor "timer" — schedules the fold-back at a future time (use afterMs OR at):
                  "afterMs": "86400000",              // timer: relative delay in ms (JSONata -> number)
                  "at":      "<JSONata -> epoch millis or ISO-8601 string>",  // timer: absolute fire time
                  // executor "server" — HTTP to a spec-provided ABSOLUTE url (SSRF-guarded at runtime):
                  "request": { "method":"GET", "url":"https://api.example.com/x?q={ field }" },
                  // llm / server / timer: map the result back into writable state
                  "response": { "set": { "$.path": "$response.field" } }
                  //   $response = the LLM/HTTP JSON. For a timer, response.set values are plain JSONata
                  //   over the current state at fire time (e.g. "$.quote.status": "'expired'").
                }
              ],
              "tests": [                             // REQUIRED: 1-2 self-checks of your own math
                {
                  "description": "<what this checks>",
                  "given":  { "$.inputField": <value>, ... },   // base inputs to set
                  "expect": { "$.derivedField": <value you computed BY HAND>, ... }
                }
                // These run during generation: if a derivation does not produce the value you expect,
                // you will be asked to fix it. So compute each expected value yourself from the given
                // inputs — this verifies the FORMULAS, not just that they compile.
                // Pick SIMPLE inputs you can compute CONFIDENTLY by hand (e.g. round numbers, a zero,
                // a boundary). Prefer asserting the easiest derived field over a long chained one; do
                // NOT invent an expected value you are unsure of — a wrong expectation wastes a retry.
                //
                // ╔══════════════════════════════════════════════════════════════════════════╗
                // ║  expect ONLY scalar, deterministic values — these two rules are STRICT    ║
                // ╚══════════════════════════════════════════════════════════════════════════╝
                //  1. Assert a single SCALAR (number / boolean / string). NEVER assert an
                //     array- or object-valued derived field — you cannot hand-compute a whole
                //     computed array (e.g. an amortization "schedule") exactly, so it always
                //     mismatches. To check an array, assert ONE element's scalar instead:
                //       WRONG: "expect": { "$.schedule": [ {...}, {...}, ... ] }
                //       RIGHT: "expect": { "$.schedule[0].interest": 162.51 }
                //  2. NEVER assert a field whose formula depends on the current date/time
                //     ($now(), $millis()). Its value changes between now and runtime, so a
                //     hand-computed expectation will be wrong (e.g. an age/year coefficient).
                //     If you must test such logic, pass the reference date as a `given` input
                //     and reference that input instead of $now().
              ]
            }

            Path notation (JsonPath, RFC 9535):
            - All "path" values must start with "$." — e.g. "$.order.total", "$.items[*].price".
            - Use bracket wildcard [*] for array-scoped derivations and constraints.
            - Array indices use bracket notation: "$.items[0].qty".

            JSONata expression rules:
            - Expressions use JSONata syntax (https://docs.jsonata.org/simple).
            - Reference fields by their FULL dot-path WITHOUT the leading "$." prefix.
              A field at $.vehicle.year must be referenced as "vehicle.year" — never as just "year".
              This rule applies to ALL expression types: derivations, constraints, metaDerivations.
              WRONG: "expr": "year >= 1900"            ← bare leaf name, always undefined for nested fields
              RIGHT: "expr": "vehicle.year >= 1900"    ← full dot-path required
            - Derivation expressions may reference base fields and derived fields from prior \
            topological levels, but not derivations at the same level.
            - Derivation paths must not form cycles.
            - Current year: $substring($now(), 0, 4)~>$number()
              CRITICAL: $currentYear(), $year(), $getYear() do NOT exist in JSONata. There is no built-in
              year shortcut. Always use: $substring($now(), 0, 4)~>$number()
              Do NOT chain $toMillis() through $fromMillis() — that converts milliseconds back to a string,
              making any subsequent arithmetic produce undefined.
            - $power() does NOT exist. Use the ** operator: (1 + rate) ** n
              WRONG: $power(1 + rate, n)     RIGHT: (1 + rate) ** n
            - ?? (null coalescing operator) does NOT exist in JSONata.
              Use: (field != null ? field : 0)   or set defaults via a defaultValues rule.
            - SQL/BASIC keyword operators do NOT exist in JSONata — translate them:
              modulo:     WRONG: a mod b              RIGHT: a % b
              range test: WRONG: x between 1 and 12   RIGHT: (x >= 1 and x <= 12)
              membership: WRONG: x in [1, 2, 3]       RIGHT: (x = 1 or x = 2 or x = 3)
            - Variables are IMMUTABLE bindings. A := binding cannot be reassigned:
              WRONG: ($balance := loan; $balance := $balance - 100)  ← compile error
              RIGHT: ($balance := loan; $remaining := $balance - 100)
            - CRITICAL: Never rebind an outer-scope variable inside a lambda body.
              The JVM runtime produces "variable $X is already defined" and fails to compile:
              WRONG: ($balance := loan; $map([1..n], function($m) {$balance := $balance - p; ...}))
                     ↑ $balance is declared in the outer scope; rebinding it in the lambda = compile error.
              RIGHT: Use $reduce with an accumulator — see Pattern B in the schedules section below.

            // ╔══════════════════════════════════════════════════════════════════════════╗
            // ║  CRITICAL — multi-statement sequences REQUIRE outer parentheses ()       ║
            // ╚══════════════════════════════════════════════════════════════════════════╝
            //  The ; separator is valid ONLY inside ( ).  Without ( ), ; is a syntax error.
            //
            //  RIGHT: "expr": "($r := annualRate/1200; $n := termMonths; $r > 0 ? principal * $r * (1+$r)**$n / ((1+$r)**$n - 1) : principal/$n)"
            //  WRONG: "expr": "$r := annualRate/1200; $n := termMonths; $r > 0 ? ..."
            //                                        ↑ ; without outer () = Syntax error at ;
            //  Rule: any expression that contains ; (variable bindings OR sequenced steps) MUST
            //  be wrapped in ( ).

            // ╔══════════════════════════════════════════════════════════════════════════╗
            // ║  CRITICAL — lambda body MUST use { }, NEVER ( )                         ║
            // ╚══════════════════════════════════════════════════════════════════════════╝
            //  function($param) { body }   ← CORRECT: {} is the function body delimiter
            //  function($param) ( body )   ← WRONG: parser expects { but found (
            //
            //  Return a plain scalar:
            //    function($m) {$m * 2}
            //
            //  Return an object (no local vars):
            //    function($m) {{"month": $m, "payment": monthlyPayment}}
            //    Note double {{ }}: outer {} = function body, inner {} = object literal
            //
            //  Multiple steps / local variables — still use {}, with ; to separate steps:
            //    function($acc, $m) {$i := $acc[-1].balance * rate; $append($acc, {"balance": $acc[-1].balance - $i})}
            //                       ↑ {} is function body; ; separates steps; last expr is returned
            //
            //  WRONG: function($acc, $m) ($i := $acc[-1].balance * rate; $append(...))
            //                             ↑ () instead of {} — runtime rejects with "Expected LBRACE"

            JSONata input context per expression type (what $ and bare names resolve to):
            ($const is bound in EVERY expression below — reference named constants as $const.<name>,
            e.g. $const.vatRate, $const.brackets[0].rate. Prefer $const over hardcoded magic numbers.
            A value derived purely from constants never recomputes — reference a constant alongside an
            input field, or inline the literal.)

              Expression location          | Root context ($)          | Extra bindings
              ─────────────────────────────┼───────────────────────────┼───────────────────────────────
              derivation (non-wildcard)    | full mergedDocument       | —
              derivation (wildcard [*])    | full mergedDocument       | $parent = current array element
              metaDerivation (non-wildcard)| full mergedDocument       | —
              metaDerivation (wildcard[*]) | the array element object  | — (no root doc access)
              constraint — no path (global)| full mergedDocument       | —
              constraint — scalar path     | the field VALUE           | — (use $ for the value)
              constraint — array path [*]  | each array element        | — (use $ for element fields)
              defaultValues expr           | full document             | $self = new container fields; $parent = its JSON parent
              view text/visible/readOnly   | full mergedDocument       | —

            Generating computed arrays (schedules, time series, amortization tables):
            - Model per-period data as a SINGLE derived field that returns an array.
              Pattern A — independent rows (no carry-forward state):
                "path": "$.schedule",
                "expr": "$map([1..termMonths], function($m) {{\\"month\\": $m, \\"payment\\": monthlyPayment}})"
                Double {{ }}: outer {} = function body, inner {} = object literal being returned.
              Pattern B — amortization (each row depends on the previous balance):
                "path": "$.schedule",
                "expr": "$reduce([1..termMonths], function($acc, $m) {$i := $round($acc[-1].balance * monthlyRate, 2); $append($acc, {\\"month\\": $m, \\"interest\\": $i, \\"balance\\": $acc[-1].balance - $i})}, [{\\"balance\\": loanAmount}])"
                Lambda body uses {} even with local variable bindings; ; separates steps.
                Keep the schedule a SINGLE $reduce(...) call. Reference already-derived fields
                (e.g. monthlyPayment) DIRECTLY — do NOT re-bind them in an outer ( ... ; $reduce(...))
                sequence: that wrapper adds a closing ) that is easy to drop, producing
                "Expected RPAREN but reached end of expression". If you must pre-compute a value,
                make it its own derivation and reference it by name instead.
            - WRONG — $map with outer-scope variable reassignment (NEVER do this):
                "expr": "($balance := loan; $map([1..n], function($m) {$interest := ...; $balance := $balance - $principal; ...}))"
                ↑ $balance is bound in the outer ( ) scope; rebinding it inside the lambda causes
                  a compile error: "variable $balance is already defined". $map does NOT carry
                  state between iterations — every call to the lambda starts fresh.
                  USE $reduce (Pattern B) instead — $acc carries the running state.
            - NEVER create one derivation per period (e.g. month1Payment, month2Payment…).
              That hard-codes the term length and explodes the spec size.
            - For financial schedules, keep the expr concise — compute balance, interest,
              principal columns in a single object literal per iteration.

            COMPLETE EXAMPLE — a small, valid spec. Match this structure exactly (note: the derived
            field `area` is `readOnly` in the schema; the constraint is global and uses the bare field
            name; the test computes the expected value by hand):
            {
              "id": "rectangle",
              "version": "1.0.0",
              "schema": {
                "type": "object",
                "properties": {
                  "width":  { "type": "number" },
                  "height": { "type": "number" },
                  "area":   { "type": "number", "readOnly": true }
                }
              },
              "defaultValues": [
                { "path": "$", "expr": "{ \\"width\\": 0, \\"height\\": 0 }" }
              ],
              "derivations": [
                { "path": "$.area", "expr": "width * height" }
              ],
              "constraints": [
                { "id": "non-negative-width", "expr": "width >= 0", "message": "width must be >= 0", "policy": "flag" }
              ],
              "tests": [
                { "description": "area = width x height",
                  "given":  { "$.width": 3, "$.height": 4 },
                  "expect": { "$.area": 12 } }
              ]
            }

            JSON output (CRITICAL):
            - Output exactly one JSON object — no markdown fences, no text before or after.
            - String escaping: use \\" for a literal quote, \\\\ for a backslash. One level only —
              never backslash-escape a field name; never double-escape a string value.
            - In JSON Schema type definitions the string value MUST be closed before the object closes:
                RIGHT: {"type":"boolean"}   WRONG: {"type":"boolean}
                RIGHT: {"type":"string"}    WRONG: {"type":"string}
              A missing close-quote propagates the error through the entire remainder of the document.
            - Keep the spec concise. Do NOT repeat the same derivation pattern for every
              possible period/bucket. Use array derivations or conditional expressions instead.
            - Before finishing, verify: every opening " has a closing ", every { has a }, every [ has a ].
            """;

    /**
     * Produces the initial generation prompt for a given domain description.
     *
     * @param modelId           the desired model identifier; when {@code null}/blank the LLM is asked
     *                          to choose a concise id itself and set it as the spec's {@code id}
     * @param domainDescription free-text description of what the model should do
     * @param includeView       when {@code true}, appends the viewDefinition component catalog
     *                          so the LLM will generate a {@code viewDefinition} section
     */
    public static String initialPrompt(String modelId, String domainDescription, boolean includeView) {
        String systemContext = includeView ? SYSTEM_CONTEXT + SYSTEM_CONTEXT_VIEW : SYSTEM_CONTEXT;
        String idLine = (modelId != null && !modelId.isBlank())
                ? "Model ID: " + modelId
                : "Model ID: choose one yourself - a concise, descriptive lower-case kebab-case slug of "
                  + "2-4 words (e.g. \"mortgage-calculator\") that names this domain, and set it as the "
                  + "spec's \"id\" field.";
        return systemContext + "\n\n" + """
                Generate a Valem model spec for the following domain:

                """ + idLine + """

                Domain description:
                """ + domainDescription + shapeExemplars(domainDescription) + (includeView ? """

                Include a complete viewDefinition with a sensible UI layout for this domain.
                """ : """

                Output only the JSON spec, nothing else.
                """);
    }

    /**
     * Returns a focused, copy-this few-shot exemplar when the domain description matches a "hard
     * shape" the LLM reliably fumbles. Today that is the <b>computed array / schedule</b> shape
     * (amortization tables, per-period breakdowns), whose long {@code $reduce} expressions are where
     * paren-balance and binding mistakes cluster. Injecting a vetted, paren-balanced exemplar right
     * next to the request raises first-attempt accuracy. Returns {@code ""} for ordinary domains.
     */
    static String shapeExemplars(String domainDescription) {
        if (domainDescription == null) return "";
        String d = domainDescription.toLowerCase();
        StringBuilder out = new StringBuilder();
        if (wantsSchedule(d))       out.append(SCHEDULE_EXEMPLAR);
        if (wantsGroupBy(d))        out.append(GROUP_BY_EXEMPLAR);
        if (wantsDateMath(d))       out.append(DATE_MATH_EXEMPLAR);
        if (wantsClassification(d)) out.append(CLASSIFICATION_EXEMPLAR);
        if (wantsCurrency(d))       out.append(CURRENCY_EXEMPLAR);
        if (wantsStatus(d))         out.append(STATUS_EXEMPLAR);
        if (wantsRank(d))           out.append(RANK_EXEMPLAR);
        return out.toString();
    }

    private static boolean wantsSchedule(String d) {
        return d.contains("schedule") || d.contains("amortiz") || d.contains("amortis")
                || d.contains("installment") || d.contains("instalment") || d.contains("repayment")
                || d.contains("per month") || d.contains("per-month") || d.contains("monthly breakdown")
                || d.contains("per period") || d.contains("per-period") || d.contains("time series")
                || d.contains("timeseries") || d.contains("payment plan") || d.contains("breakdown")
                // per-period enumeration phrasing ("details on each month", "month-by-month")
                || d.contains("each month") || d.contains("every month") || d.contains("each period")
                || d.contains("every period") || d.contains("month by month") || d.contains("month-by-month");
    }

    private static boolean wantsGroupBy(String d) {
        return d.contains("group by") || d.contains("group-by") || d.contains("grouped")
                || d.contains("aggregate") || d.contains("aggregation") || d.contains("sum by")
                || d.contains("count by") || d.contains("per category") || d.contains("by category")
                || d.contains("subtotal") || d.contains("tally") || d.contains("by group");
    }

    private static boolean wantsDateMath(String d) {
        return d.contains("days between") || d.contains("days until") || d.contains("days since")
                || d.contains("date difference") || d.contains("difference between dates")
                || d.contains("duration") || d.contains("elapsed") || d.contains("months between")
                || d.contains("years between") || d.contains("time between") || d.contains("how many days")
                || d.contains("number of days") || d.contains("age in years") || d.contains("age in days");
    }

    private static boolean wantsClassification(String d) {
        return d.contains("classify") || d.contains("classification") || d.contains("risk level")
                || d.contains("priority level") || d.contains("rating band") || d.contains("tax bracket")
                || d.contains("tier") || d.contains("status based on") || d.contains("category based on")
                || d.contains("grade based on") || d.contains("assign a grade");
    }

    private static boolean wantsCurrency(String d) {
        return d.contains("exchange rate") || d.contains("currency conversion")
                || d.contains("convert currency") || d.contains("conversion rate") || d.contains("forex")
                || d.contains("fx rate") || d.contains("usd to") || d.contains("eur to") || d.contains("gbp to");
    }

    private static boolean wantsStatus(String d) {
        return d.contains("state machine") || d.contains("status transition")
                || d.contains("state transition") || d.contains("allowed transition")
                || d.contains("next state") || d.contains("next status") || d.contains("workflow")
                || d.contains("lifecycle") || d.contains("approval process") || d.contains("status changes");
    }

    private static boolean wantsRank(String d) {
        return d.contains("percentile") || d.contains("rank") || d.contains("leaderboard")
                || d.contains("quartile") || d.contains("top n") || d.contains("top-n")
                || d.contains("nth highest") || d.contains("median");
    }

    private static final String SCHEDULE_EXEMPLAR = """


            This domain needs a COMPUTED ARRAY (one row per period). Use this exact,
            paren-balanced pattern: a SINGLE $reduce(...) that references already-derived fields
            directly. Do NOT wrap it in an outer ( ...; $reduce(...) ) sequence — that extra
            closing ) is the #1 thing models drop. Count every ( ) { } [ ] and close them all.
              {
                "path": "$.schedule",
                "expr": "$reduce([1..loan.termMonths], function($acc, $m) {$interest := $round($acc[-1].balance * (loan.annualRate / 1200), 2); $principal := $round(loan.monthlyPayment - $interest, 2); $append($acc, {\\"month\\": $m, \\"payment\\": loan.monthlyPayment, \\"principal\\": $principal, \\"interest\\": $interest, \\"balance\\": $round($acc[-1].balance - $principal, 2)})}, [{\\"balance\\": loan.amount}])"
              }
            Adapt the field names to THIS domain, but keep the structure and bracket balance.""";

    private static final String GROUP_BY_EXEMPLAR = """


            This domain needs GROUP-BY / AGGREGATION. Do NOT loop manually — use JSONata aggregate
            built-ins ($sum, $count, $average, $max, $min) and the {key: agg} group operator:
              total over an array:   "$sum(items.amount)"
              count:                 "$count(items)"
              grouped totals (object keyed by category → summed amount):
                                     "items{category: $sum(amount)}"
            Each derivation returns a single value or one grouped object; keep names matching the schema.""";

    private static final String DATE_MATH_EXEMPLAR = """


            This domain needs DATE ARITHMETIC. Parse ISO date strings to epoch millis with
            $toMillis(...), subtract, then convert. NEVER chain $toMillis()~>$fromMillis() before
            arithmetic (that turns it back into a string and breaks the math):
              days between two dates: "($toMillis(endDate) - $toMillis(startDate)) / 86400000"
              age in whole years:     "$floor(($toMillis($now()) - $toMillis(birthDate)) / 31557600000)"
            86400000 ms = 1 day; 31557600000 ms ≈ 1 year. Keep field names matching the schema.""";

    private static final String CLASSIFICATION_EXEMPLAR = """


            This domain DERIVES a label / tier / status from data. Compute it as a derivation with a
            nested ternary chain — do NOT make it a writable enum the user must keep in sync:
              { "path": "$.riskLevel",
                "expr": "(score >= 80 ? \\"low\\" : score >= 50 ? \\"medium\\" : \\"high\\")" }
            Put the most selective condition first; the final branch is the default. String literals
            in JSONata use double quotes, so escape them as \\" inside the JSON expr value.""";

    private static final String CURRENCY_EXEMPLAR = """


            This domain needs CURRENCY / FX CONVERSION. Convert by multiplying by the rate and
            rounding to the target precision; keep the amount and rate as writable inputs:
              { "path": "$.convertedAmount", "expr": "$round(amount * exchangeRate, 2)" }
            Currency formatting (symbols, thousands separators) is a VIEW concern, not a derivation.""";

    private static final String STATUS_EXEMPLAR = """


            This domain has a STATUS / STATE field. Model it as a WRITABLE enum (schema
            "enum": [...]) and DERIVE flags/labels from the CURRENT status:
              { "path": "$.isEditable", "expr": "(status = \\"draft\\" or status = \\"pending\\")" }
            IMPORTANT: you CANNOT validate transitions (e.g. "only draft → submitted"). A JSONata
            expression sees only the CURRENT value, never the previous one, so transition rules cannot
            be expressed in derivations or constraints — enforce them in the calling application.""";

    private static final String RANK_EXEMPLAR = """


            This domain needs RANK / PERCENTILE over an array. Use counting, not sorting:
              rank (1 = highest):   "$count(scores[$ > currentScore]) + 1"
              percentile of v:      "$round($count(values[$ <= v]) / $count(values) * 100, 1)"
              max / min:            "$max(scores)"  /  "$min(scores)"
            $count(array[predicate]) counts the elements matching the predicate.""";

    /** Calls {@link #initialPrompt(String, String, boolean)} without view context. */
    public static String initialPrompt(String modelId, String domainDescription) {
        return initialPrompt(modelId, domainDescription, false);
    }

    /**
     * Produces a repair prompt when the LLM's previous attempt failed validation.
     *
     * @param modelId       the model identifier
     * @param previousSpec  the raw JSON the LLM produced previously
     * @param errors        validation errors to fix
     * @param includeView   when {@code true}, appends the viewDefinition component catalog
     */
    public static String repairPrompt(
            String modelId,
            String previousSpec,
            List<ModelSpecValidator.ValidationError> errors,
            boolean includeView) {

        String systemContext = includeView ? SYSTEM_CONTEXT + SYSTEM_CONTEXT_VIEW : SYSTEM_CONTEXT;
        String errorList = errors.stream()
                .map(e -> "  - [" + e.location() + "] " + e.message())
                .collect(Collectors.joining("\n"));

        return systemContext + "\n\n" + """
                Your previous model spec for '""" + modelId + """
                ' contained the following validation errors:

                """ + errorList + """

                Previous spec:
                ```json
                """ + previousSpec + """
                ```

                Fix all errors and output only the corrected JSON spec, nothing else.
                """;
    }

    /** Calls {@link #repairPrompt(String, String, List, boolean)} without view context. */
    public static String repairPrompt(
            String modelId,
            String previousSpec,
            List<ModelSpecValidator.ValidationError> errors) {
        return repairPrompt(modelId, previousSpec, errors, false);
    }

    /**
     * Produces a simplified re-generation prompt when the previous response was truncated.
     *
     * <p>Rather than sending the garbled truncated JSON back to the LLM (which wastes tokens
     * and confuses the model), this prompt asks for a minimal spec from scratch.
     *
     * @param modelId           the desired model identifier
     * @param domainDescription the original domain description
     * @param includeView       when {@code true}, appends the viewDefinition component catalog
     */
    public static String repairPromptTruncated(String modelId, String domainDescription,
                                               boolean includeView) {
        String systemContext = includeView ? SYSTEM_CONTEXT + SYSTEM_CONTEXT_VIEW : SYSTEM_CONTEXT;
        return systemContext + "\n\n" + """
                Your previous response for '""" + modelId + """
                ' was cut off before the JSON was complete — it exceeded the output token limit.

                Please generate a MUCH SHORTER spec. Strict size budget:
                - Schema: include ONLY the essential input fields; omit descriptions, readOnly flags, \
                and any derived or computed fields (those belong in derivations, not schema).
                - Derivations: at most 4, each as a single concise expression (avoid multi-line blocks).
                - Constraints: at most 2, one-liner boolean expressions only.
                - No metaDerivations, no tests.
                - defaultValues: at most one "$" seed rule with 3–5 representative fields.

                Domain:
                """ + domainDescription + """

                Output only the JSON spec, nothing else.
                """;
    }

    /** @deprecated use {@link #testRepairPrompt(String, String, List, List)} which quotes the
     *  offending derivation's expression. Kept for callers that lack the derivation list. */
    @Deprecated
    public static String testRepairPrompt(
            String modelId,
            String previousSpec,
            List<TestCaseRunner.TestResult> failedTests) {
        return testRepairPrompt(modelId, previousSpec, failedTests, List.of());
    }

    /**
     * Produces a repair prompt when the spec passed structural validation but embedded
     * test cases produced wrong output. Each failure line carries a rule-named FIX hint and, when
     * the failing path is a derivation, the exact {@code expr} the model wrote for it — so the model
     * can edit the precise expression rather than guess which derivation is wrong.
     *
     * @param modelId       the model identifier
     * @param previousSpec  the raw JSON the LLM produced
     * @param failedTests   test results that did not pass
     * @param derivations   the spec's derivations, used to quote the expr at each failing path
     */
    public static String testRepairPrompt(
            String modelId,
            String previousSpec,
            List<TestCaseRunner.TestResult> failedTests,
            List<DerivationSpec> derivations) {

        String failureList = testFailureFeedback(failedTests, derivations);

        return SYSTEM_CONTEXT + "\n\n" + """
                Your model spec for '""" + modelId + """
                ' passed structural validation but the following test cases failed:

                """ + failureList + """

                Previous spec:
                ```json
                """ + previousSpec + """
                ```

                Re-compute each failing case BY HAND from its `given` inputs. Either a derivation
                expression is wrong or the test's `expect` value is wrong — fix whichever does not
                match (you may correct the `expr` OR the `expect` value, not just the expression). \
                Output only the corrected JSON spec, nothing else.
                """;
    }

    /**
     * Formats a bulleted, rule-named feedback block for a set of failed embedded tests: one line per
     * field failure carrying the assertion message, a {@code FIX:} hint, and — when the failing path
     * is a derivation — the exact {@code expr} the model wrote there (so it edits the precise
     * expression rather than guessing). Shared by {@link #testRepairPrompt} and the evolution
     * test-repair path so both give identically actionable feedback.
     */
    static String testFailureFeedback(
            List<TestCaseRunner.TestResult> failedTests, List<DerivationSpec> derivations) {

        Map<String, String> exprByPath = derivations.stream()
                .collect(Collectors.toMap(DerivationSpec::path, DerivationSpec::expr,
                        (a, b) -> a, java.util.LinkedHashMap::new));

        return failedTests.stream()
                .flatMap(t -> t.failures().stream().map(f -> {
                    String label = t.description() != null ? "'" + t.description() + "'" : "(unnamed)";
                    String expr  = exprByPath.get(f.path());
                    String exprLine = expr != null
                            ? "\n      (derivation at " + f.path() + " is: " + expr + ")" : "";
                    return "  - Test " + label + ": " + f.message()
                            + "\n      FIX: " + testFailureHint(f) + exprLine;
                }))
                .collect(Collectors.joining("\n"));
    }

    /**
     * A rule-named, actionable hint for a single embedded-test field failure. The spec already
     * compiled, so the problem is a <em>value</em> mistake — this widens the feedback the same way
     * {@code SpecGenerator.annotateErrors} does for compile errors, pointing the model at the most
     * likely cause (null result → field-name mismatch; tiny numeric delta → missing $round; otherwise
     * a formula/precedence error).
     */
    static String testFailureHint(TestCaseRunner.FieldFailure f) {
        com.fasterxml.jackson.databind.JsonNode exp = f.expected();
        com.fasterxml.jackson.databind.JsonNode act = f.actual();
        if (act == null || act.isNull() || act.isMissingNode()) {
            return "the expression returned null/undefined — verify every field name matches the schema "
                 + "EXACTLY (full dot-path, no leading $., e.g. order.total not total) and that the "
                 + "derivation reads existing inputs from prior levels.";
        }
        if (exp != null && exp.isNumber() && act.isNumber()) {
            double e = exp.asDouble(), a = act.asDouble();
            double diff = Math.abs(e - a);
            if (diff > 0 && diff <= Math.max(1e-6, Math.abs(e) * 1e-4)) {
                return "the value is off by a tiny amount — wrap the result in $round(expr, 2) "
                     + "(or the precision this test expects).";
            }
            return "the computed number is wrong — re-check the formula and operator precedence "
                 + "(** binds LOWER than + - * /, so wrap power terms: ((1+r)**n)).";
        }
        return "the computed value is wrong — re-check the expression logic at this path.";
    }

    /**
     * Produces an incremental evolution prompt.
     *
     * @param modelId           the model to evolve
     * @param currentSpec       the current spec JSON
     * @param evolutionRequest  description of the desired changes
     * @param includeView       when {@code true}, appends the viewDefinition component catalog
     *                          and instructs the LLM to include {@code newViewDefinition} if needed
     * @param derivedPaths      paths that are already computed (read-only) in the current spec; the
     *                          model is told to upsert by the SAME path and never redeclare them as
     *                          writable schema properties. May be empty.
     */
    public static String evolutionPrompt(
            String modelId,
            String currentSpec,
            String evolutionRequest,
            boolean includeView,
            List<String> derivedPaths) {

        String systemContext = includeView ? SYSTEM_CONTEXT + SYSTEM_CONTEXT_VIEW : SYSTEM_CONTEXT;

        return systemContext + "\n\n" + """
                The current Valem model spec for '""" + modelId + """
                ' is:
                ```json
                """ + currentSpec + """
                ```
                """ + derivedPathsBlock(derivedPaths) + """

                Apply the following changes and output a SpecEvolution JSON object \
                (not a full spec — only the diff fields that change):

                """ + evolutionRequest + shapeExemplars(evolutionRequest) + (includeView ? """

                Update or replace the viewDefinition as needed to reflect the changes.
                """ : "") + """

                A SpecEvolution has these optional fields:
                """ + evolutionFields(includeView) + """

                Output only the JSON SpecEvolution, nothing else.
                """;
    }

    /** Calls {@link #evolutionPrompt(String, String, String, boolean, List)} with no derived paths. */
    public static String evolutionPrompt(
            String modelId,
            String currentSpec,
            String evolutionRequest,
            boolean includeView) {
        return evolutionPrompt(modelId, currentSpec, evolutionRequest, includeView, List.of());
    }

    /** Calls {@link #evolutionPrompt(String, String, String, boolean)} without view context. */
    public static String evolutionPrompt(
            String modelId,
            String currentSpec,
            String evolutionRequest) {
        return evolutionPrompt(modelId, currentSpec, evolutionRequest, false);
    }

    /**
     * Produces a repair prompt when a previous {@link org.json_kula.valem.core.graph.SpecEvolution}
     * attempt produced an invalid evolved spec (failed structural validation / expression compilation)
     * or whose merged spec failed embedded self-tests. Mirrors {@link #repairPrompt} for the diff path:
     * it echoes the rejected evolution, the rule-named error feedback, and the same domain context
     * (already-derived paths) so the model fixes the exact problem rather than re-emitting it.
     *
     * @param feedback rule-named error text (e.g. validation summary + a {@code FIX:} hint, or a
     *                 formatted test-failure block)
     */
    public static String evolutionRepairPrompt(
            String modelId,
            String currentSpec,
            String evolutionRequest,
            String previousEvolution,
            String feedback,
            boolean includeView,
            List<String> derivedPaths) {

        String systemContext = includeView ? SYSTEM_CONTEXT + SYSTEM_CONTEXT_VIEW : SYSTEM_CONTEXT;

        return systemContext + "\n\n" + """
                You are evolving the Valem model spec for '""" + modelId + """
                '. Your previous SpecEvolution was rejected:

                """ + feedback + """

                Previous SpecEvolution:
                ```json
                """ + previousEvolution + """
                ```

                The current (unchanged) spec is:
                ```json
                """ + currentSpec + """
                ```
                """ + derivedPathsBlock(derivedPaths) + """

                Re-apply this change request, fixing the problem above:

                """ + evolutionRequest + shapeExemplars(evolutionRequest) + """

                A SpecEvolution has these optional fields:
                """ + evolutionFields(includeView) + """

                Output only the corrected JSON SpecEvolution, nothing else.
                """;
    }

    /** The optional-field list for a SpecEvolution, with view fields only when views are on. */
    private static String evolutionFields(boolean includeView) {
        String common =
                  "  newVersion, expectedVersion, removeDerivations, upsertDerivations,\n"
                + "                  removeConstraints, upsertConstraints,\n"
                + "                  removeEffects, upsertEffects,\n"
                + "                  removeMetaDerivations, upsertMetaDerivations,\n"
                + "                  removeDefaultValues, upsertDefaultValues,\n"
                + "                  upsertConstants, removeConstants, newConstants,\n"
                + "                  upsertSchemaDefs, removeSchemaDefs, upsertSchemaNodes, removeSchemaNodes, newSchema";
        String viewFields =
                  ",\n                  newDefaultView, upsertViews, removeViews,\n"
                + "                  upsertComponents, removeComponents, newViewDefinition";
        return common + (includeView ? viewFields : "") + ".\n" + evolutionGuidance(includeView);
    }

    /** Rules that steer the model toward targeted diffs instead of wholesale section replacement. */
    private static String evolutionGuidance(boolean includeView) {
        String g = """

                PREFER TARGETED DIFFS over wholesale replacement:
                - To change ONE part of the schema, use upsertSchemaNodes (by canonical data path,
                  e.g. "$.order.items[*].qty") or upsertSchemaDefs (by $defs name). Do NOT resend the
                  whole schema via newSchema unless you are restructuring it. newSchema is mutually
                  exclusive with the schema diff fields in one evolution.
                - upsertSchemaNodes[].schema replaces that node wholesale; set "required": true/false to
                  add/remove the field from its parent's required list. A path may not traverse a $ref
                  (edit the shared definition via upsertSchemaDefs instead).
                - To change a shared shape used in many places, upsert its $defs entry once via
                  upsertSchemaDefs — it fans out to every $ref usage.
                - upsertConstants/removeConstants change named values by name. Do NOT confuse
                  upsertConstants (named VALUES, e.g. tax rates) with upsertConstraints (boolean
                  INVARIANTS). removeConstants is rejected if the constant is still referenced.
                - expectedVersion (optional) makes the evolution apply only if the model is still at that
                  version (optimistic concurrency).""";
        String v = """

                - To change ONE screen or widget, use upsertViews (whole view, by id) or upsertComponents
                  (one component, by id: {viewId, component, optional parentId/beforeId to place or move
                  it}) and removeComponents ({viewId, componentId}). Do NOT resend the whole
                  viewDefinition via newViewDefinition unless redesigning the UI.""";
        return g + (includeView ? v : "");
    }

    /**
     * A context block naming the paths already computed (read-only) in the current spec, so an
     * evolution upserts them by the SAME path instead of duplicating them or redeclaring them as
     * writable schema fields. Returns {@code ""} when there are none.
     */
    private static String derivedPathsBlock(List<String> derivedPaths) {
        if (derivedPaths == null || derivedPaths.isEmpty()) return "";
        return "\nThese paths are already DERIVED (read-only computed fields):\n  "
                + String.join(", ", derivedPaths) + "\n"
                + "To change one, upsert a derivation with the SAME path. Never redeclare a derived "
                + "field as a writable schema property, and do not add it to defaultValues/backfill.\n";
    }
}
