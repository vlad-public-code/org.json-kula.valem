# Valem — AI / LLM Prompt Reference

This document describes what is sent to the LLM on every call.
Source: `valem-core/.../llm/SpecGenerationPrompt.java`, `SpecGenerator.java`,
`valem-api/.../config/LlmConfig.java`.

---

## Structure of every prompt

Every call is a single string composed as:

```
<system context>

<user prompt>
```

There is no separate system/user message split at the `LlmClient` interface level — the
system context is concatenated into the prompt string before it is sent. When tools are
available (see [Tool use](#tool-use) below), the provider's native tool-calling protocol
layers on top of this single string.

---

## System context (always first)

`SpecGenerationPrompt.SYSTEM_CONTEXT` (~290 lines) is prepended to every prompt. It contains:

1. A description of what Valem is and the full JSON structure of a `ModelSpec` with
   field-level comments (including `constants`/`$const`, `defaultValues`, `effects` —
   `actions` is not mentioned as current syntax).
2. A per-expression-type context-binding table: what `$`, `$parent`, `$self`, and `$const`
   resolve to for each of `derivations` / `metaDerivations` / `constraints` /
   `defaultValues` / view expressions.
3. A JSONata gotcha cheat-sheet the model reliably gets wrong otherwise: no `mod`/`between`/
   `in`/`??`/`$power`; lambda bodies must use `{}` not `()`; a multi-statement `;` sequence
   needs an outer `()`; `:=` bindings are immutable; computed arrays go through `$reduce`;
   the current-year idiom; JSON-escaping rules for embedding the spec in the response.
4. A full worked example spec.
5. Validation rules, including: all expressions use JSONata syntax; derivation paths must
   not form cycles; derivation expressions may reference base fields and other derived
   fields; constraint expressions must evaluate to a boolean (`true` = passes); output must
   be valid JSON only (no markdown fences, no prose). There is **no** rule about "action
   trigger expressions" — `actions` was removed from the spec format and effects (`caller`/
   `server`/`llm`/`timer`) replaced it; `SYSTEM_CONTEXT` does not use the word "action"
   except as an unrelated UI-button label in the view catalog.

When `includeView=true`, `SpecGenerationPrompt.SYSTEM_CONTEXT_VIEW` (~90 lines — the
`ViewDefinition`/`ComponentSpec` catalog) is appended after `SYSTEM_CONTEXT`, and the caller's
prompt is told to include (or update) a `viewDefinition`.

**Shape exemplars.** Every prompt that carries free-text (`domainDescription` on generation,
`evolutionRequest` on evolution) is scanned by `shapeExemplars()` for seven "hard shape"
keyword groups the model reliably fumbles — schedule/amortization, group-by, date-math,
classification, currency conversion, state-machine/status, and rank/percentile — and a vetted,
paren-balanced few-shot exemplar for each matched group is appended verbatim. Ordinary
domains get none of these.

---

## Prompt types

### 1. Initial generation

Triggered by `SpecGenerator.generate(modelId, domainDescription, includeView)`.

**Assembled prompt:**
```
<system context>[+ view catalog if includeView]

Generate a Valem model spec for the following domain:

Model ID: <modelId>

Domain description:
<domainDescription>[+ matched shape exemplars]

[Include a complete viewDefinition with a sensible UI layout for this domain.
 | Output only the JSON spec, nothing else.]
```

### 2. Repair — validation errors

Triggered automatically when the LLM response fails `ModelSpecValidator`. Each error is
annotated by `annotateErrors()` with a rule-named `— FIX: …` hint for known compiler-error
patterns before being echoed back, and the previous spec is included so the model edits
rather than re-derives from scratch.

**Assembled prompt:**
```
<system context>

Your previous model spec for '<modelId>' contained the following validation errors:

  - [<location>] <message> — FIX: <hint, when a known pattern matched>
  ...

Previous spec:
```json
<previousSpec>
```

Fix all errors and output only the corrected JSON spec, nothing else.
```

**Truncated-response variant.** If the raw response looks cut off mid-JSON
(`isLikelyTruncated()`), `repairPromptTruncated()` is used instead: a materially shorter
prompt that asks for a smaller/simpler spec rather than echoing the garbled truncated JSON
back at the model.

### 3. Repair — test failures

Triggered automatically when validation passes but embedded `tests` entries fail
(`TestCaseRunner`). Each failure line carries a rule-named `FIX:` hint (null-result,
tiny-delta-needs-`$round`, wrong-formula/precedence, …) plus the exact failing derivation's
`expr` quoted inline, not just the test description and message.

**Assembled prompt:**
```
<system context>

Your model spec for '<modelId>' passed structural validation but the following
test cases failed:

  - Test '<description>' (expr: <failing expression>): <failure message> — FIX: <hint>
  ...

Previous spec:
```json
<previousSpec>
```

Fix the expressions so all test cases pass. Output only the corrected JSON spec, nothing else.
```

### 4. Evolution

Triggered by `SpecGenerator.generateEvolution(currentSpec, evolutionRequest, includeView)`.
The prompt also lists the paths already `DERIVED` in the current spec (`derivedPathsBlock()`)
so the model upserts the same path instead of duplicating or redeclaring it as writable.

**Assembled prompt:**
```
<system context>[+ view catalog if includeView]

The current Valem model spec for '<modelId>' is:
```json
<currentSpec>
```
[These paths are already DERIVED (read-only computed fields): <derivedPaths>. To change one,
 upsert a derivation with the SAME path.]

Apply the following changes and output a SpecEvolution JSON object
(not a full spec — only the diff fields that change):

<evolutionRequest>[+ matched shape exemplars]
[Update or replace the viewDefinition as needed to reflect the changes.]

A SpecEvolution has these optional fields:
  newVersion, removeDerivations, upsertDerivations,
  removeConstraints, upsertConstraints,
  removeActions, upsertActions,
  removeMetaDerivations, upsertMetaDerivations,
  removeDefaultValues, upsertDefaultValues, newConstants,
  newSchema[, newViewDefinition when includeView].

Output only the JSON SpecEvolution, nothing else.
```

> **Known bug, faithfully reproduced here:** the prompt text says `removeActions`/
> `upsertActions`, but `SpecEvolution`'s actual JSON property names are `removeEffects`/
> `upsertEffects` (`SpecEvolution.java`). This is a real mismatch in the prompt code itself
> (`SpecGenerationPrompt.evolutionFields()`), not a doc error — the deterministic repair
> pipeline (`fixExpressions`/JSON repair) does not currently rename these keys, so an LLM
> that emits `removeActions`/`upsertActions` literally will fail to apply that part of the
> diff. Structured-output mode's schema (`SpecGenerationSchema.java`) uses the correct
> `removeEffects`/`upsertEffects` names, so providers with `structured-output.enabled=true`
> are less likely to hit this.

### 5. Evolution repair

Triggered when a generated `SpecEvolution` fails validation or its embedded tests, mirroring
prompt type 2/3 for the diff path: it echoes the rejected `SpecEvolution`, a rule-named
`FIX:`-hinted feedback block, the unchanged current spec, and the derived-paths block, then
asks the model to re-apply the same change request fixing the specific problem.

---

## Tool use

When a `WebTool` bean is configured (`valem.llm.web-fetch.enabled`), every call in a
`generate()`/`generateEvolution()` session is made via `completeWithTools`, offering:

- **`web_fetch`** — fetch a URL for authoritative domain info (SSRF-guarded; see
  [security-model.md](security-model.md)); budget `valem.llm.web-fetch.max-calls`
  (default 5), `max-chars` (default 8000) per fetch.
- **`web_search`** — keyless DuckDuckGo search so the model finds URLs instead of guessing
  them; budget `valem.llm.web-search.max-calls` (default 3), `max-results` (default 5).
  Gated by `web-fetch.enabled`.
- **`eval_jsonata`** — evaluate a candidate JSONata expression against a sample input
  locally (no network) so the model can verify/fix an expression before committing it;
  budget `valem.llm.eval-tool.max-calls` (default 25).

Tool budgets are shared across all retries within one `generate()`/`generateEvolution()`
session, not reset per attempt.

---

## Retry / feedback loop

`SpecGenerator` uses two retry budgets, not one: `maxRetries` (base, default **3**, always
attempted) and `maxRetriesHard` (ceiling, default **6** via `valem.llm.max-retries-hard`
— extra attempts past the base budget are granted only while the validation-error count
keeps strictly dropping between attempts; see `stopAfterBaseBudget`).

Before every parse attempt, the raw LLM response runs through a deterministic auto-repair
pipeline: `extractJson` → `repairJson` → `collapseStringNewlines` → `fixExpressions` (a
cascade of ~10 sub-passes — lambda-body braces, function-body/binding commas, `mod`/
`between`/`in`/`!==`/`==`/`$power` rewrites, paren-balancing, `$currentYear()`/`$toInteger`
normalization, …) → `repairConstraintPolicy` (fills a missing constraint `"policy"` with
`"rollback"`). Every successful spec/evolution also passes through
`markDerivedFieldsReadOnly()` so the schema stays consistent with the derivations.

```
attempt 1..maxRetriesHard:
    rawResponse = llm.completeWithTools(prompt, tools?)   // temperature: generationTemperature
                                                           // on attempt 1, repairTemperature after
    repairedJson = repairConstraintPolicy(fixExpressions(repairJson(
                       collapseStringNewlines(extractJson(rawResponse)))))

    [generation path]
    spec = JSON.parse(repairedJson)
    if parse fails            → prompt = repairPromptTruncated or repairPrompt(errors) → next
    if spec invalid           → prompt = repairPrompt(annotateErrors(validator errors)) → next
    if tests fail             → prompt = testRepairPrompt(failures)                    → next
    return GenerationResult.Success(markDerivedFieldsReadOnly(spec), attemptsUsed)

    [evolution path]
    evolution = JSON.parse(repairedJson)
    if parse fails                     → next attempt (same evolutionRepairPrompt shape)
    merged = evolution.applyTo(currentSpec)     // validates + runs embedded tests
    if invalid/tests fail               → prompt = evolutionRepairPrompt(feedback) → next
    return markDerivedFieldsReadOnly(merged)

attempt i+1 < maxRetries          → always continue on failure
attempt i+1 >= maxRetries         → continue only while the error count is still converging,
                                     up to maxRetriesHard
exhaust retries → GenerationResult.Failure / LlmException
```

**Temperature:** the first attempt uses `valem.llm.generation-temperature` (default
`0.0`, deterministic); every repair attempt uses `valem.llm.repair-temperature` (default
`0.2`).

**Structured output:** when `valem.llm.structured-output.enabled=true` (the default),
the `ModelSpec`/`SpecEvolution` JSON Schema is sent as the provider's `response_format`
(OpenAI-compatible providers only, non-strict mode; Anthropic ignores this and relies on the
prompt alone).

---

## Summary table

| Prompt type | Caller supplies | System adds |
|---|---|---|
| Initial generation | `domainDescription`, `includeView` | System context (+ view catalog), model ID, shape exemplars, output instruction |
| Repair (validation) | nothing (auto retry) | System context, FIX-hinted error list, previous spec JSON (or the truncated-response variant) |
| Repair (test failures) | nothing (auto retry) | System context, FIX-hinted failure list (with failing `expr`), previous spec JSON |
| Evolution | `evolutionRequest`, `includeView` | System context (+ view catalog), current spec JSON, derived-paths block, shape exemplars, SpecEvolution field docs |
| Evolution repair | nothing (auto retry) | System context, rejected evolution, FIX-hinted feedback, current spec, derived-paths block |

---

## See also

See [configuration.md](configuration.md) for the full `valem.llm.*` property list
(8 providers, per-provider model/base-URL defaults, tool budgets, retry/temperature knobs).
