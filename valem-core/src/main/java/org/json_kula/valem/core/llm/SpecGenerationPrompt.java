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
              "label":       "<plain text>", // PLAIN literal — shown verbatim, NEVER quote or JSONata
              "bind":        "<$.path>",     // PATH to the bound model field (e.g. "$.bmi")
              "visible":     true|false|"<JSONata>"|null,  // null → auto from $.bind#relevant meta
              "enabled":     true|false|"<JSONata>"|null,  // null → !readOnly
              "readOnly":    true|false|"<JSONata>"|null,  // null → auto from $.bind#read_only meta
              "required":    true|false|"<JSONata>"|null,  // null → auto from $.bind#required meta
              "placeholder": "<plain text>", // PLAIN literal — never quote or JSONata
              "helperText":  "<plain text>", // PLAIN literal — never quote or JSONata
              "tooltip":     "<plain text>", // PLAIN literal — never quote or JSONata
              "onChange":    <EventHandler>,
              "onClick":     <EventHandler>
            }

            EventHandler:
            { "mutations": "<JSONata → {'$.path': value}>", "navigate": "<view-id>" }

            *** FIELD VALUE KINDS — the #1 source of broken views. Every component field is ONE of
            three kinds, and putting the wrong kind of value in one is the most common mistake: ***

            1. PLAIN-TEXT fields — shown to the user exactly as written, never evaluated:
               label, placeholder, helperText, tooltip, legend, addLabel, removeLabel, fromLabel,
               toLabel, alt, and every options[].label / menuItems[].label / tableColumns[].header /
               keyValueList items[].label.
               Write the human text directly — no surrounding quotes, no JSONata:
                 RIGHT: "label": "Weight (kg)"
                 WRONG: "label": "\\"Weight (kg)\\""   ← renders with the literal quote characters
                 WRONG: "label": "weight & \\" kg\\""   ← JSONata is ignored here; shown verbatim

            2. PATH (bind) fields — a "$.path" address the component READS its value from:
               bind, bindFrom, bindTo, dependsOn, keyValueList items[].bind.
               RULE OF THUMB: to show one stored or derived value, prefer "bind": "$.path" over any
               expression — it is what statTile, progressBar, gauge and label exist for:
                 RIGHT: { "type": "statTile", "label": "Your BMI", "bind": "$.bmi", "format": "number" }
               EXCEPTION — tableColumns[].field, chartX and chartSeries[].field are NOT "$." paths.
               The table/chart binds the ARRAY; these name a field INSIDE one of its items, written
               bare, relative to the row:
                 RIGHT: "bind": "$.entries", "chartX": "date"
                 WRONG: "bind": "$.entries", "chartX": "$.entries.date"   ← plots nothing

            3. EXPRESSION fields — JSONata evaluated against the model: text (label/badge/staticText/
               link), value (statTile), delta, caption, trend, plus the boolean dynamics
               visible / enabled / readOnly / required.
               Reference model fields by their UNQUALIFIED name (age, totalTax) — never "$age", which
               is an undefined variable. "$" is for built-in functions and lambda parameters.
               CRITICAL SERVER RULE: text/value/delta/caption/trend are only evaluated when the string
               CONTAINS a "$"; with no "$" it is shown as literal text.
                 "text": "bmiCategory"                → shows the literal word (BUG)
                 "text": "$string(bmiCategory)"       → shows the value  ✓
                 "text": "$string(age) & \\" yrs\\""      → a literal segment spliced into a $-expression
               So reference a field through a "$" function, or — better — give the component a "bind".
               A FIXED literal needs no quotes and no "$", and falls through as-is:
                 RIGHT: "caption": "kg/m2"
                 WRONG: "caption": "\\"kg/m2\\""   ← the quote characters are shown to the user
               visible/enabled/readOnly/required do NOT need a "$" — they are always evaluated, so
               "visible": "bmi != null" is correct as written.

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

            Data output (each row notes WHERE its value comes from — a PATH bind or an EXPRESSION):
              label          shows one value; value from "bind" ($.path) — or "text" (JSONata, needs "$") when unbound; extra: format ("currency"|"percent"|"number"|"integer"), currency
              statTile       one headline number as a card; value from "bind" ($.path) — or "value" (JSONata, needs "$") if computed inline; extra: format, currency, delta (JSONata), trend ("up"|"down"|"flat"), caption (plain or JSONata), icon
              progressBar    numeric value as a bar; value from "bind" ($.path to a number); extra: min, max, showValue, format
              gauge          same value as a 180-degree arc; value from "bind" ($.path); extra: min, max, showValue, format
              keyValueList   read-only summary of labelled values; extra: items [{label (plain text), bind ($.path) OR text (JSONata, needs "$"), format, currency}]
              staticText     non-reactive text block; "text" = the literal text (write it plainly, no quotes)
              badge          status chip; "text" = value to show — badge has NO bind, so to show a field use "$string(field)"; extra: variant ("primary"|"secondary"|"success"|"warning"|"danger")
              separatorLine  horizontal rule; no extra fields
              dataTable      array as table; "bind" = $.arrayPath; extra: tableColumns [{field, header, format, width}], pageSize (int)
              dataChart      chart; "bind" = $.arrayPath; extra: chartType ("bar"|"line"|"area"|"pie"), chartX, chartSeries [{field, label, color}]

            *** A GRAPH IS A COMPONENT, NOT A DERIVATION. *** When the description asks for a graph,
            chart, trend, curve, plot, history or anything "over time", the viewDefinition MUST contain
            a dataChart — a table of the same numbers does not answer it. A chart plots an ARRAY, so:
            bind the array that holds the series, name its x-axis field, and list one entry per plotted
            series. If the model has no such array yet, ADD one (the log/history of entries) and derive
            the plotted value per item — never drop the chart because the shape was inconvenient.
              { "id": "consumptionChart", "type": "dataChart", "label": "Consumption over time",
                "bind": "$.entries", "chartType": "line", "chartX": "date",
                "chartSeries": [ { "field": "consumption", "label": "l/100km" } ] }
            Pick chartType by intent: "line"/"area" for a value over time, "bar" for per-category
            comparison, "pie" for shares of a whole.

            Formatting numbers: on EVERY numeric output — label, statTile, keyValueList row and dataTable
            column — set "format": "currency" (with a "currency" ISO code), "percent" (appends a % sign;
            does NOT multiply by 100), "number", or "integer". Never render a raw derived number. Present
            the one-to-three headline results a model computes as statTile cards grouped horizontally,
            not plain labels.

            Containers:
              group       layout box; extra: layout ("vertical"|"horizontal"|"grid"), columns, components []
              fieldSet    fieldset with legend; extra: legend (string), components []
              sectionList editable array with add/remove; extra: bind ($.arrayPath), components [] (the
                          element editor — see below), canAdd, canRemove, addLabel, removeLabel
              sectionItem single element editor; extra: components []

            *** A LIST OF ITEMS IS EDITED INLINE, IN ONE VIEW. *** When the user adds, edits and removes
            items (debts, expenses, passengers, line items), put the element's fields in the sectionList's
            OWN "components" and bind each one through the array wildcard [*]:
              { "id": "debtsList", "type": "sectionList", "label": "Your debts", "bind": "$.debts",
                "addLabel": "Add a debt", "components": [
                  { "id": "debtName", "type": "textField", "label": "Name", "bind": "$.debts[*].name" },
                  { "id": "debtBalance", "type": "numericField", "label": "Balance", "bind": "$.debts[*].balance" } ] }
            The renderer repeats that editor per row and replaces [*] with the row's index, so Add opens a
            new row in place and the list never leaves the screen. Do NOT move the fields to a second view
            unless an item has many of them — a separate view is a whole screen the user has to find their
            way back from. If you do use one, put "itemView": "<view-id>" on the sectionList INSTEAD of
            "components", bind that view's fields through [*] exactly the same way (never $.debts[0] — that
            edits the first row from every row), and give that view a button that navigates back to the list.

            Actions:
              button   extra: variant ("primary"|"secondary"|"danger"|"ghost"), icon, onClick (EventHandler:
                       {"mutations": <JSONata producing {"$.path": value}>, "navigate": "<view-id>"})
              menu     navigation; extra: menuItems [{label, targetView, icon}], orientation ("horizontal"|"vertical")

            Every view other than the defaultView must be REACHABLE (some button "navigate", menu/stepper
            targetView, or sectionList itemView leads to it) and must LEAD BACK (its own navigate/menuItem):
            a view with no way out strands the user, and one nothing points at is never seen.

            Meta-cache inheritance: visible/readOnly/required null → evaluator reads metaDerivation values
            automatically, so metaDerivations alone can drive component visibility without view expressions.

            GOLDEN EXAMPLE — a correct viewDefinition (note: plain labels with NO quotes; every
            displayed value comes from a "bind" path; a fixed caption written plainly; a badge whose
            dynamic text uses a "$" function so it evaluates server-side):
            {
              "defaultView": "main",
              "views": [ {
                "id": "main", "label": "BMI Calculator", "layout": "vertical",
                "components": [
                  { "id": "weight", "type": "numericField", "label": "Weight (kg)", "bind": "$.weight",
                    "placeholder": "e.g. 70", "helperText": "Your weight in kilograms" },
                  { "id": "height", "type": "numericField", "label": "Height (m)", "bind": "$.height" },
                  { "id": "bmiTile", "type": "statTile", "label": "Your BMI", "bind": "$.bmi",
                    "format": "number", "caption": "kg/m2" },
                  { "id": "category", "type": "badge", "text": "$string(bmiCategory)",
                    "variant": "$boolean(healthy) ? 'success' : 'warning'" }
                ]
              } ]
            }
            """;

    /** System message describing the Valem spec format to the LLM. */
    public static final String SYSTEM_CONTEXT = """
            You are an expert in Valem, a deterministic reactive computation runtime \
            for JSON data models. Your task is to produce valid Valem model specifications \
            in JSON format.

            If a get_domain_guidance tool is available, CALL IT FIRST, before writing anything. Read \
            the domain description — in ANY language — decide which of the tool's listed topics it \
            matches (the topic ids are fixed English keys; map the meaning, not the words), and call it \
            with that set of topic ids. It returns vetted, copy-this instructions for the hard shapes \
            those topics cover; follow them. Call it whenever a topic plausibly applies (you may pass \
            several); skip it only when none do. This is how you get shape-specific guidance now — do \
            not rely on keywords in the prompt.

            If a web_search tool is available, SEARCH FIRST to find the authoritative page's real URL — \
            never guess or hand-construct URLs (fabricated paths 404 and silently burn your fetch \
            budget). Then, if a web_fetch tool is available, fetch the 2-3 most authoritative results \
            (prefer primary sources: the government / tax-authority site or the statute) and read the \
            numbers before writing expressions.
            DOMAIN-DATA HONESTY (critical): rate tables are often published only as images, PDFs or             interactive calculators, so a fetch may return prose WITHOUT the numbers. Never invent             precise-looking figures to fill the gap. If you cannot confirm a rate or bracket: get the             STRUCTURE right first (the components and formula shape matter more than exact constants),             and use clearly-rounded placeholders — a structurally faithful calculator with honest,             editable placeholders beats a confident-looking one built on fabricated rules. Flag a             placeholder in the DERIVATION's or FIELD's "description"; do NOT wrap the constant in a             {"value": ..., "description": ...} object — a constant must stay a RAW value, because             $const.<name> returns it as-is and a wrapper makes every reference a broken object.
            If an eval_jsonata tool is available, TEST any non-trivial expression before finalizing: \
            pass the candidate expr and a small sample input; it returns the value or the exact \
            compiler error, so you fix syntax and logic in place rather than guessing.

            Your output SHAPE is enforced by a response JSON Schema (structured output) — the section
            names, per-record fields and enums are already guaranteed. So what follows is only what a
            schema cannot say: what each section MEANS, and how it goes wrong.

            "id" / "version" — an id you choose; version defaults to "1.0.0".

            "schema" — JSON Schema Draft 2020-12 declaring only WRITABLE input fields. A field you
            compute in "derivations" is read-only: OMIT it here, or include it with "readOnly": true.
            Never leave a derived field an ordinary writable property — clients cannot write it.

            "constants" — named immutable values of any JSON type, read in ANY expression as
            $const.<name> ($const.vatRate, $const.brackets[0].rate). Prefer them over magic numbers.
            A value derived PURELY from constants never recomputes — reference an input field
            alongside it, or inline the literal.

            "library" — OPTIONAL named JSONata functions, callable from every expression as $name(args).
            "define" is a JSONata expression that binds functions and RETURNS THE LIST OF NAMES TO
            EXPORT as its last value; names it binds but does not export stay internal helpers.
              Use one ONLY when the same calculation shape appears in 3+ expressions (a bracket walk,
              a proration, a rounding convention). "$total()" is worse than "price * qty".
              THE HARD RULE: a library function CANNOT read the document. Field names inside a
              function body always evaluate to NOTHING — it sees only its arguments and $const.
                WRONG: "$net := function() { order.subtotal - order.discount }"  ← returns nothing
                RIGHT: "$net := function($sub, $disc) { $sub - $disc }", called from the derivation
                       as "$net(order.subtotal, order.discount)" — field names live in the CALLER.
              A multi-statement body must parenthesise: "function($x) { ( $a := 1; $a ) }".
              $const works inside a library; $now/$millis/$random do NOT (it is evaluated once, at
              compile time). Never export a name that shadows a built-in ($sum, $round, $map, …).

            "defaultValues" — seeds for newly-created containers. "path" is "$" (the whole document,
            seeded once at creation), an object like "$.customer", or an element pattern like
            "$.items[*]"; "expr" returns an object merged in, filling ONLY fields the caller left
            absent. $self = the new container's caller-provided fields, $parent = its JSON parent.
            The "$" rule IS the initial state, and that state must satisfy every rollback constraint
            below — see the INITIAL-STATE RULE.

            "derivations" — computed read-only fields, e.g.
            { "path": "$.order.total", "expr": "order.subtotal + order.tax" }.

            "metaDerivations" — live field metadata. "property" is one of: required, minimum, maximum,
            minLength, maxLength, pattern, enum, multipleOf, readOnly, relevant.

            "constraints" — invariants checked after every mutation; expr is true when SATISFIED.
              "path" is OPTIONAL and you should DEFAULT to omitting it, because the context differs
              and getting it wrong causes a 409 at create:
                no path (global) → expr sees the FULL document, so use qualified names:
                  RIGHT { "expr": "vehicle.year >= 1900" }
                path set (scalar) → expr sees ONLY THE FIELD VALUE as $; field names are unavailable:
                  RIGHT { "path": "$.vehicle.year", "expr": "$ >= 1900" }
                  WRONG { "path": "$.vehicle.year", "expr": "vehicle.year >= 1900" }  ← undefined
              Add a path only when you explicitly need per-field dirty tracking.
              INITIAL-STATE RULE: rollback constraints are evaluated against the freshly-created state
              (after the "$" defaultValues), so that state MUST satisfy every one. If a rollback
              constraint requires a field positive / non-empty / in-range, SEED it so — an unseeded
              number is 0 and an unseeded string/array is absent, and both fail such constraints:
                constraint { "expr": "floorArea > 0" }
                REQUIRES   defaultValues [{ "path": "$", "expr": "{ \\"floorArea\\": 50 }" }]
              #1 CAUSE OF 409 is a "$" seed that zero-initializes EVERY numeric field. Seed 0 only for
              fields no positive-rollback constraint covers; seed realistic values for the rest.
              Prefer policy "flag" for invariants a user is expected to fix by editing; reserve
              "rollback" for hard invariants.

            "effects" — OPTIONAL side effects run by a SHELL, not the pure core. Omit unless needed.
            "trigger" is a JSONata boolean that fires once when it becomes true; "statusPath" is a
            PLAIN name like "$.thing.ioName" (never "$io", which breaks JSONata).
            "executor" is one of caller | server | llm | timer, and decides the other fields:
              caller — pure, no I/O, surfaced in the mutation response: "emit", "payload":{k:JSONata}
              llm    — "prompt" (JSONata → text), optional "responseSchema"; folds the JSON back
              timer  — "afterMs" (JSONata → ms) OR "at" (JSONata → epoch ms / ISO-8601)
              server — "request": {method,url} to an ABSOLUTE url (SSRF-guarded at runtime)
              llm/server/timer also take "response": {"set": {"$.path": "$response.field"}}, mapping
              the result into writable state ($response = the returned JSON; timer set values are
              JSONata over state).

            "tests" — REQUIRED: 1-2 self-checks of your own math. They RUN during generation, so a
            derivation that misses your expectation comes back to you as a repair, so
            compute each expected value yourself, BY HAND, from the given inputs — this verifies the FORMULAS, not just that
            they compile — and pick inputs you can be confident about (round numbers, a zero, a
            boundary). Prefer the easiest derived field over a long chained one; a wrong expectation
            wastes a retry. expect ONLY scalar, deterministic values — two STRICT rules:
              1. Assert a single SCALAR (number / boolean / string). NEVER assert an array- or
                 object-valued derived field — you cannot hand-compute a whole computed array (an
                 amortization "schedule") exactly, so it always mismatches. Assert one element:
                   WRONG "expect": { "$.schedule": [ {...}, {...}, ... ] }
                   RIGHT "expect": { "$.schedule[0].interest": 162.51 }
              2. NEVER assert a field whose formula depends on the current date/time ($now(),
                 $millis()) — its value changes between now and runtime. Pass the reference date in
                 as a `given` input and reference that instead.

            Units and dimensional consistency (a frequent source of wrong-but-compiling math):
            - Pick ONE canonical unit per quantity and state it in the schema field's "description"
              AND its view "label" (e.g. height in metres, weight in kilograms). Never mix cm and m.
            - Keep every formula dimensionally consistent with those units. If an input is in cm but a
              formula needs m, convert explicitly (height/100) — do not assume the unit.
            - Make at least one self-test pin a value a unit slip would break. E.g. for BMI, given
              weight 70 (kg) and height 1.75 (m), expect bmi 22.86 — a value that comes out ~100x
              wrong if height is treated as cm, so the test catches the mistake during generation.

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
            - CONTROL FLOW from other languages does NOT exist. JSONata has ONLY the ternary, and a
              ( ; ) block whose LAST expression is its value — no if/then/else, no let, no return:
                WRONG: if (d = 0) then 0 else v / d      RIGHT: d = 0 ? 0 : v / d
                WRONG: ( let $p := E in $p + 1 )         RIGHT: ( $p := E; $p + 1 )
                WRONG: ( $p := E return $p + 1 )         RIGHT: ( $p := E; $p + 1 )
            - JavaScript ARRAY METHODS do NOT exist: no .every(), .some(), .filter(), .map(),
              .includes(), .find(), .length. This one is dangerous because it does not fail loudly —
              "entries.every(function($e) { $e.liters > 0 })" parses, evaluates to NOTHING, and a
              rollback constraint written that way reports itself violated on a perfectly good state.
              Use a filter predicate [ ] and $count instead:
              all rows satisfy P: WRONG: rows.every(function($r) { $r.qty > 0 })
                                  RIGHT: $count(rows[qty <= 0]) = 0     ← count the VIOLATIONS
              any row satisfies P: WRONG: rows.some(function($r) { $r.qty > 0 })
                                  RIGHT: $count(rows[qty > 0]) > 0
              select rows:        WRONG: rows.filter(...)   RIGHT: rows[qty > 0]
              transform rows:     WRONG: rows.map(...)      RIGHT: $map(rows, function($r) { ... })
              count rows:         WRONG: rows.length        RIGHT: $count(rows)
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

            *** WILDCARD [*] DERIVATIONS — the silent empty-column trap. *** The root context of a
            [*] derivation is the WHOLE document, not the row, so a bare field name resolves at the
            root and yields NOTHING. There is no error — just an empty column in every row:
                WRONG: "path": "$.entries[*].cost", "expr": "liters * pricePerLiter"
                RIGHT: "path": "$.entries[*].cost", "expr": "$parent.liters * $parent.pricePerLiter"
            Read EVERY field of the current row through $parent — including one an earlier [*]
            derivation computed ($parent.cost). Inside a path PREDICATE the context is the element, so
            bare names are correct there: entries[liters > 0].

            PREVIOUS ROW (refuelling logs, meter readings, running deltas): there is no row-index
            binding anywhere — not in a derivation, not in a constraint — and $index/$all/$position do
            not exist. Identify the previous row by VALUE, as the largest reading below this one:
                "path": "$.entries[*].distance",
                "expr": "$parent.odometer - $max(entries[odometer < $parent.odometer].odometer)"
            The first row has no predecessor, so $max(...) is nothing and the result is nothing; where
            a number is required, guard it: "$parent.distance > 0 ? $parent.liters * 100 / $parent.distance : 0".

            *** NEVER DERIVE AN ARRAY FROM ITSELF. *** A user-editable list (the log, the entries, the
            line items) is a BASE field the user writes. A derivation whose path IS that array depends
            on itself and the spec is rejected outright:
                WRONG: "path": "$.entries", "expr": "$map(entries, function($e) { ... })"
                       → "Cyclic dependency detected among nodes: [$.entries]"
            Do not try to escape it with a shadow input either ($.entriesInput, $._entries_raw): that
            is the same cycle with an extra field, and it makes the user edit the wrong one. Instead:
              - per-row values → a [*] derivation ON that array ("$.entries[*].consumption");
              - whole-list results → a separate SCALAR derivation that reads it
                ("$.averageConsumption": "$average(entries.consumption)").
            Deriving a whole array is right only when the array itself is computed and the user never
            edits it — an amortization schedule built from term and rate (see the patterns below).

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
                """ + domainDescription + domainAnchoringHint(domainDescription)
                + (includeView ? """

                Include a complete viewDefinition with a sensible UI layout for this domain.
                """ : """

                Output only the JSON spec, nothing else.
                """);
        return new PromptParts(systemContext(includeView), user);
    }

    /**
     * When the domain description is terse (a bare noun phrase like "Body Mass Index"), an LLM can
     * latch onto an unrelated interpretation — we saw a three-word prompt drift into a payroll model.
     * For a short description, anchor the model to the domain's standard, widely-accepted definition
     * before it starts inventing. Returns {@code ""} for a description detailed enough to stand on
     * its own, so ordinary requests are unaffected.
     */
    static String domainAnchoringHint(String domainDescription) {
        if (domainDescription == null) return "";
        int words = domainDescription.trim().isEmpty() ? 0 : domainDescription.trim().split("\\s+").length;
        if (words > 8) return "";
        return """


                This description is brief. First establish the STANDARD, widely-accepted definition of
                this domain — its usual inputs (each with its canonical unit), its outputs, the exact
                formulas, and any standard categories/bands — from your own knowledge (use web_fetch to
                confirm figures if available). Model THAT standard definition faithfully; do not
                substitute a loosely-related domain.""";
    }


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

                """ + evolutionRequest + (includeView ? """

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

                """ + evolutionRequest + """

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
                + "                  newLibrary (replaces the model's own library layer wholesale -\n"
                + "                    there is no per-function diff; resend the whole define),\n"
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
