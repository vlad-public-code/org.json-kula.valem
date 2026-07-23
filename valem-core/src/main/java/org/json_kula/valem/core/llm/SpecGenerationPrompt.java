package org.json_kula.valem.core.llm;

import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.model.DerivationSpec;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
     * A prompt split into three cache tiers:
     * <ul>
     *   <li>{@code system} — the spec-format instructions (and, optionally, the view catalog). Stable
     *       across every call in every session with the same view mode → the primary prompt-cache
     *       prefix (breakpoint 1).</li>
     *   <li>{@code sessionContext} — content that is stable within one generation/evolution
     *       <em>session</em> but varies between sessions: on the evolution path, the full current spec
     *       JSON and its derived-paths block, re-sent identically on every retry. Carrying it behind a
     *       <em>second</em> cache breakpoint lets those (often large) tokens be re-read at ~10% price
     *       across the retry loop instead of billed in full each attempt. Empty ({@code ""}) on paths
     *       with no session-stable content (initial generation, validation/test repair).</li>
     *   <li>{@code user} — the volatile per-attempt text (task, error/test feedback, exemplars, the
     *       rejected previous output). This is the only tier that carries user-controlled free text, so
     *       keeping it distinct also preserves the prompt-injection boundary.</li>
     * </ul>
     * Provider clients send {@code system}+{@code sessionContext} as cacheable prefix blocks and
     * {@code user} as the volatile message. {@link #concatenated()} reproduces the legacy single string
     * for the default {@link LlmClient} path and the UI preview endpoint.
     */
    public record PromptParts(String system, String sessionContext, String user) {
        /** Legacy two-tier prompt (no session-stable middle segment). */
        public PromptParts(String system, String user) {
            this(system, "", user);
        }

        public String concatenated() {
            return sessionContext == null || sessionContext.isBlank()
                    ? system + "\n\n" + user
                    : system + "\n\n" + sessionContext + "\n\n" + user;
        }

        /** True when there is a session-stable middle segment to cache behind its own breakpoint. */
        public boolean hasSessionContext() {
            return sessionContext != null && !sessionContext.isBlank();
        }
    }

    /** The system context for the given view mode: spec-format instructions, plus the view catalog. */
    private static String systemContext(boolean includeView) {
        return includeView ? SYSTEM_CONTEXT + SYSTEM_CONTEXT_VIEW : SYSTEM_CONTEXT;
    }

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

            If a web_fetch tool is available, use it to look up authoritative domain info (official \
            tax tables, rate schedules, formulas) before writing expressions — prefer primary \
            sources; fetch 2-3 pages, then generate.
            If an eval_jsonata tool is available, TEST any non-trivial expression before finalizing: \
            pass the candidate expr and a small sample input; it returns the value or the exact \
            compiler error, so you fix syntax and logic in place rather than guessing.

            Your output shape is enforced by a response JSON Schema (structured output). This section \
            gives the FIELD SEMANTICS the schema cannot express — read it for meaning, not field names.
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
                                                     // IMPORTANT: the "$" seed must make the initial
                                                     // state satisfy every rollback constraint (see the
                                                     // INITIAL-STATE RULE under "constraints" below) —
                                                     // seed positive/non-empty values for any field a
                                                     // rollback constraint requires to be so.
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
                  // CONSTRAINT PATH RULE — violating this causes 409 at create:
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
                  //
                  // INITIAL-STATE RULE: rollback constraints are evaluated against the freshly-
                  // created state (after "$" defaultValues). A rollback constraint that fails on the
                  // initial state causes 409 at create — the initial state MUST satisfy EVERY one.
                  // If a rollback constraint requires a field to be positive / non-empty /
                  // in-range (e.g. floorArea > 0, quantity >= 1, name != ""), you MUST seed
                  // that field with a satisfying value via a "$" defaultValues rule — an
                  // unseeded number defaults to 0 and an unseeded string/array to absent,
                  // which fails such constraints and rejects creation.
                  //   constraint  { "expr": "floorArea > 0" }
                  //   REQUIRES     defaultValues [{ "path": "$", "expr": "{ \\"floorArea\\": 50 }" }]
                  //
                  // #1 CAUSE OF 409: a "$" seed that zero-initializes EVERY numeric field.
                  // NEVER seed 0 for a field a rollback constraint requires to be positive —
                  // seed a realistic positive value instead. It is fine to seed 0 ONLY for
                  // fields with no positive-rollback constraint.
                  //   WRONG: "$" seed { "floorArea": 0,  "wallHeight": 0, ... }  ← 409, floorArea>0 fails
                  //   RIGHT: "$" seed { "floorArea": 50, "wallHeight": 2.5, ... } ← registers cleanly
                  // Prefer policy "flag" for invariants a user is expected to fix by editing;
                  // reserve "rollback" for hard invariants, and ALWAYS seed defaults that
                  // satisfy every "rollback" constraint so the model registers cleanly.
                }
              ],
              "effects": [   // OPTIONAL — side effects run by a SHELL, not the pure core. Omit unless needed.
                {            // Common: id; executor ("caller"|"server"|"llm"|"timer"); trigger (JSONata bool,
                             // fires once when it becomes true); optional dedupeKey; optional statusPath
                             // ("$.thing.ioName" — a PLAIN name, NOT "$io" which breaks JSONata).
                  //  caller — pure; no I/O; surfaced in the mutation response:  "emit", "payload":{k:JSONata}
                  //  llm    — "prompt": JSONata->text; optional "responseSchema"; folds JSON completion back
                  //  timer  — "afterMs" (JSONata->ms) OR "at" (JSONata->epoch ms / ISO-8601)
                  //  server — "request": {method,url} to an ABSOLUTE url (SSRF-guarded at runtime)
                  //  llm/server/timer: "response": {"set": {"$.path": "$response.field"}} maps result to
                  //  writable state ($response = the LLM/HTTP JSON; timer set values are JSONata over state).
                  "id": "<string>", "executor": "...", "trigger": "<JSONata boolean>"
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
                // expect ONLY scalar, deterministic values — these two rules are STRICT:
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

            CRITICAL — multi-statement sequences REQUIRE outer parentheses ():
              The ; separator is valid ONLY inside ( ). Any expression containing ; (bindings OR
              sequenced steps) MUST be wrapped in ( ), else ; is a syntax error.
              RIGHT: "($r := annualRate/1200; $n := termMonths; $r > 0 ? principal * $r * (1+$r)**$n / ((1+$r)**$n - 1) : principal/$n)"
              WRONG: "$r := annualRate/1200; $n := termMonths; ..."   (no outer () = Syntax error at ;)

            CRITICAL — lambda body MUST use { }, NEVER ( ) (runtime rejects () with "Expected LBRACE"):
              function($m) {$m * 2}                                   (scalar return)
              function($m) {{"month": $m, "payment": monthlyPayment}} (object return; double {{ }} = body + object literal)
              function($acc, $m) {$i := $acc[-1].balance * rate; $append($acc, {"balance": $acc[-1].balance - $i})}
                                                                      (multi-step: {} body, ; separates steps, last expr returned)

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
            - Model per-period data as a SINGLE derived field returning an array — NEVER one
              derivation per period (month1Payment, month2Payment…): that hard-codes the term and
              explodes the spec.
              Pattern A — independent rows:
                "$map([1..termMonths], function($m) {{\\"month\\": $m, \\"payment\\": monthlyPayment}})"
              Pattern B — carry-forward (amortization; each row needs the previous balance):
                "$reduce([1..termMonths], function($acc, $m) {$i := $round($acc[-1].balance * monthlyRate, 2); $append($acc, {\\"month\\": $m, \\"interest\\": $i, \\"balance\\": $acc[-1].balance - $i})}, [{\\"balance\\": loanAmount}])"
              Keep it a SINGLE $reduce; reference already-derived fields (monthlyPayment) directly — do
              NOT wrap in an outer ( …; $reduce(…) ) sequence (a dropped closing ) causes "Expected
              RPAREN"). $map does NOT carry state between iterations — use $reduce ($acc) for
              carry-forward; never reassign an outer-scope variable inside a lambda ("already defined").

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
              never backslash-escape a field name; never double-escape a string value. Close every
              string value before its object closes ({"type":"string"} not {"type":"string}); a
              missing close-quote corrupts the rest of the document.
            - Keep the spec concise (array derivations / conditionals, not one entry per period), and
              verify before finishing: every " { [ has its closing " } ].
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
        return initialPromptParts(modelId, domainDescription, includeView).concatenated();
    }

    /** {@link #initialPrompt(String, String, boolean)} split into {@link PromptParts}. */
    public static PromptParts initialPromptParts(String modelId, String domainDescription,
                                                 boolean includeView) {
        String idLine = (modelId != null && !modelId.isBlank())
                ? "Model ID: " + modelId
                : "Model ID: choose one yourself - a concise, descriptive lower-case kebab-case slug of "
                  + "2-4 words (e.g. \"mortgage-calculator\") that names this domain, and set it as the "
                  + "spec's \"id\" field.";
        String user = """
                Generate a Valem model spec for the following domain:

                """ + idLine + """

                Domain description:
                """ + domainDescription + shapeExemplars(domainDescription) + (includeView ? """

                Include a complete viewDefinition with a sensible UI layout for this domain.
                """ : """

                Output only the JSON spec, nothing else.
                """);
        return new PromptParts(systemContext(includeView), user);
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

    // Word-boundary keyword matchers per exemplar group. Bare String.contains fired on substrings —
    // "rank" on Frankfurt/franking, "tier" on frontier, "tally" on totally — and a bare "breakdown"
    // dragged the schedule exemplar into group-by domains ("cost breakdown by category"). Each group is
    // one precompiled alternation; keep this table next to the *_EXEMPLAR strings it selects. A comment
    // marks each non-obvious inclusion.
    private static final Pattern SCHEDULE_KEYS = Pattern.compile(
            "\\b(schedule|amorti[sz]\\w*|insta(l|ll)ment|repayment|time\\s*series|payment\\s+plan"
          + "|per[-\\s]month|per[-\\s]period|each\\s+(month|period)|every\\s+(month|period)"
          + "|month[-\\s]by[-\\s]month"
          // "breakdown" ONLY when qualified as a per-period one (not a generic cost/category breakdown)
          + "|(monthly|per[-\\s]period|month[-\\s]by[-\\s]month|payment)\\s+breakdown)\\b");

    private static final Pattern GROUP_BY_KEYS = Pattern.compile(
            "\\b(group(ed)?[-\\s]?by|grouped|aggregat(e|ion)|sum\\s+by|count\\s+by"
          + "|per\\s+category|by\\s+category|subtotal|tally|by\\s+group)\\b");

    private static final Pattern DATE_MATH_KEYS = Pattern.compile(
            "\\b(days\\s+(between|until|since)|date\\s+difference|difference\\s+between\\s+dates"
          + "|duration|elapsed|(months|years|time)\\s+between|how\\s+many\\s+days|number\\s+of\\s+days"
          + "|age\\s+in\\s+(years|days))\\b");

    private static final Pattern CLASSIFICATION_KEYS = Pattern.compile(
            "\\b(classif(y|ication)|risk\\s+level|priority\\s+level|rating\\s+band|tax\\s+bracket"
          + "|tiers?|status\\s+based\\s+on|category\\s+based\\s+on|grade\\s+based\\s+on|assign\\s+a\\s+grade)\\b");

    private static final Pattern CURRENCY_KEYS = Pattern.compile(
            "\\b(exchange\\s+rate|currency\\s+conversion|convert\\s+currency|conversion\\s+rate|forex"
          + "|fx\\s+rate|usd\\s+to|eur\\s+to|gbp\\s+to)\\b");

    private static final Pattern STATUS_KEYS = Pattern.compile(
            "\\b(state\\s+machine|status\\s+transition|state\\s+transition|allowed\\s+transition"
          + "|next\\s+state|next\\s+status|workflow|lifecycle|approval\\s+process|status\\s+changes)\\b");

    private static final Pattern RANK_KEYS = Pattern.compile(
            "\\b(percentile|rank(s|ed|ing)?|leaderboard|quartile|top[-\\s]n|nth\\s+highest|median)\\b");

    private static boolean wantsSchedule(String d)       { return SCHEDULE_KEYS.matcher(d).find(); }
    private static boolean wantsGroupBy(String d)        { return GROUP_BY_KEYS.matcher(d).find(); }
    private static boolean wantsDateMath(String d)       { return DATE_MATH_KEYS.matcher(d).find(); }
    private static boolean wantsClassification(String d) { return CLASSIFICATION_KEYS.matcher(d).find(); }
    private static boolean wantsCurrency(String d)       { return CURRENCY_KEYS.matcher(d).find(); }
    private static boolean wantsStatus(String d)         { return STATUS_KEYS.matcher(d).find(); }
    private static boolean wantsRank(String d)           { return RANK_KEYS.matcher(d).find(); }

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
        return repairPromptParts(modelId, previousSpec, errors, includeView).concatenated();
    }

    /** {@link #repairPrompt(String, String, List, boolean)} split into {@link PromptParts}. */
    public static PromptParts repairPromptParts(
            String modelId,
            String previousSpec,
            List<ModelSpecValidator.ValidationError> errors,
            boolean includeView) {

        String errorList = errors.stream()
                .map(e -> "  - [" + e.location() + "] " + e.message())
                .collect(Collectors.joining("\n"));

        String user = """
                Your previous model spec for '""" + modelId + """
                ' contained the following validation errors:

                """ + errorList + """

                Previous spec:
                ```json
                """ + previousSpec + """
                ```

                Fix all errors and output only the corrected JSON spec, nothing else.
                """;
        return new PromptParts(systemContext(includeView), user);
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
        return repairPromptTruncatedParts(modelId, domainDescription, includeView).concatenated();
    }

    /** {@link #repairPromptTruncated(String, String, boolean)} split into {@link PromptParts}. */
    public static PromptParts repairPromptTruncatedParts(String modelId, String domainDescription,
                                                         boolean includeView) {
        String user = """
                Your previous response for '""" + modelId + """
                ' was cut off before the JSON was complete — it exceeded the output token limit.

                Generate a MUCH SHORTER spec. Budget: schema = essential input fields only (no
                descriptions/readOnly/derived fields); <=4 single-line derivations; <=2 one-line
                constraints; no metaDerivations or tests; one "$" defaultValues seed (3-5 fields).

                Domain:
                """ + domainDescription + """

                Output only the JSON spec, nothing else.
                """;
        return new PromptParts(systemContext(includeView), user);
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
        return testRepairPromptParts(modelId, previousSpec, failedTests, derivations, false)
                .concatenated();
    }

    /**
     * View-aware {@link PromptParts} variant of {@link #testRepairPrompt}. When {@code includeView}
     * is {@code true} the system context includes the ViewDefinition catalog, so a spec carrying a
     * {@code viewDefinition} is repaired with the view documentation in scope rather than blind.
     */
    public static PromptParts testRepairPromptParts(
            String modelId,
            String previousSpec,
            List<TestCaseRunner.TestResult> failedTests,
            List<DerivationSpec> derivations,
            boolean includeView) {

        String failureList = testFailureFeedback(failedTests, derivations);

        String user = """
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
        return new PromptParts(systemContext(includeView), user);
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
        return evolutionPromptParts(modelId, currentSpec, evolutionRequest, includeView, derivedPaths)
                .concatenated();
    }

    /** {@link #evolutionPrompt(String, String, String, boolean, List)} split into {@link PromptParts}. */
    public static PromptParts evolutionPromptParts(
            String modelId,
            String currentSpec,
            String evolutionRequest,
            boolean includeView,
            List<String> derivedPaths) {

        String user = """
                Apply the following changes to the current spec (shown above) and output a \
                SpecEvolution JSON object (not a full spec — only the diff fields that change):

                """ + evolutionRequest + shapeExemplars(evolutionRequest) + (includeView ? """

                Update or replace the viewDefinition as needed to reflect the changes.
                """ : "") + """

                A SpecEvolution has these optional fields:
                """ + evolutionFields(includeView) + """

                Output only the JSON SpecEvolution, nothing else.
                """;
        return new PromptParts(systemContext(includeView),
                currentSpecContext(modelId, currentSpec, derivedPaths), user);
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
        return evolutionRepairPromptParts(modelId, currentSpec, evolutionRequest, previousEvolution,
                feedback, includeView, derivedPaths).concatenated();
    }

    /** {@link #evolutionRepairPrompt} split into {@link PromptParts}. */
    public static PromptParts evolutionRepairPromptParts(
            String modelId,
            String currentSpec,
            String evolutionRequest,
            String previousEvolution,
            String feedback,
            boolean includeView,
            List<String> derivedPaths) {

        String user = """
                You are evolving the Valem model spec for '""" + modelId + """
                ' (shown above). Your previous SpecEvolution was rejected:

                """ + feedback + """

                Previous SpecEvolution:
                ```json
                """ + previousEvolution + """
                ```

                Re-apply this change request against the current (unchanged) spec, fixing the problem above:

                """ + evolutionRequest + shapeExemplars(evolutionRequest) + """

                A SpecEvolution has these optional fields:
                """ + evolutionFields(includeView) + """

                Output only the corrected JSON SpecEvolution, nothing else.
                """;
        return new PromptParts(systemContext(includeView),
                currentSpecContext(modelId, currentSpec, derivedPaths), user);
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
    /**
     * The session-stable evolution context: the full current spec JSON plus its derived-paths block.
     * Identical on every attempt — and byte-for-byte identical between the evolution and
     * evolution-repair prompts — of one evolution session, so a provider can cache it behind a second
     * breakpoint and re-read the (often large) spec at ~10% price across the whole retry loop instead
     * of re-billing it in full each attempt. Both evolution prompts share this method so the cached
     * prefix matches exactly.
     */
    private static String currentSpecContext(String modelId, String currentSpec,
                                             List<String> derivedPaths) {
        return "The current Valem model spec for '" + modelId + "' is:\n```json\n"
                + currentSpec + "\n```\n" + derivedPathsBlock(derivedPaths);
    }

    private static String derivedPathsBlock(List<String> derivedPaths) {
        if (derivedPaths == null || derivedPaths.isEmpty()) return "";
        return "\nThese paths are already DERIVED (read-only computed fields):\n  "
                + String.join(", ", derivedPaths) + "\n"
                + "To change one, upsert a derivation with the SAME path. Never redeclare a derived "
                + "field as a writable schema property, and do not add it to defaultValues/backfill.\n";
    }
}
