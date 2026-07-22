---
title: LLM prompts
parent: Reference
nav_order: 4
description: "Exactly what is sent to the LLM on every spec-generation call."
---

# LLM prompts
{: .no_toc }

This document describes what is sent to the LLM on every call.
Source: `valem-core/.../llm/SpecGenerationPrompt.java`, `SpecGenerator.java`,
`valem-api/.../config/LlmConfig.java`.

---

1. TOC
{:toc}

## Structure of every prompt

Every prompt is built as two parts — a `SpecGenerationPrompt.PromptParts(system, user)`:

```
system: <system context>          (stable across a session — spec rules, + view catalog)
user:   <task, spec JSON, error feedback, exemplars>   (per-attempt)
```

The provider clients send these as **separate roles**: Anthropic as a `system` field and a
lone `user` message; OpenAI-compatible providers as a leading `system` message. This improves
instruction adherence, separates the trusted rules from user-controlled text (a
prompt-injection boundary), and — on Anthropic — lets the stable `tools`+`system` prefix be
prompt-cached (see [Prompt caching](#prompt-caching)). The legacy single-string form is still
available as `parts.concatenated()` (`system + "\n\n" + user`) — used by the UI preview
endpoint and the default `LlmClient` path. When tools are available (see [Tool use](#tool-use)
below), the provider's native tool-calling protocol layers on top.

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

When `modelId` is `null`/blank, the `Model ID:` line instead asks the model to choose a
concise lower-case kebab-case slug of 2–4 words itself and set it as the spec's `id`.

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
(`isLikelyTruncated()`), the loop first retries the **same** prompt with a raised token budget
(`min(2 × valem.llm.max-tokens, valem.llm.max-tokens-hard)`) — a truncation is usually a
transient budget problem, so this keeps the intended spec instead of downgrading it. Only on a
**second** truncation does it fall back to `repairPromptTruncated()`, a materially shorter
prompt that asks for a smaller/simpler spec.

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

  - Test '<description>': <failure message>
      FIX: <hint>
      (derivation at <path> is: <failing expression>)
  ...

Previous spec:
```json
<previousSpec>
```

Re-compute each failing case BY HAND from its `given` inputs. Either a derivation
expression is wrong or the test's `expect` value is wrong — fix whichever does not
match (you may correct the `expr` OR the `expect` value, not just the expression).
Output only the corrected JSON spec, nothing else.
```

Note: the test-repair prompt takes an `includeView` flag (`testRepairPromptParts(...,
includeView)`), threaded from `generate()`. When the spec being repaired carries a
`viewDefinition` the view catalog is in scope, so the model repairs it with the component
documentation rather than blind.

### 4. Evolution

Triggered by `SpecGenerator.generateEvolution(currentSpec, evolutionRequest[, includeView])`.
The prompt also lists the paths already `DERIVED` in the current spec (`derivedPathsBlock()`)
so the model upserts the same path instead of duplicating or redeclaring it as writable.

> The evolution loop is view-aware: pass `includeView=true` and the retry/validation loop uses
> the view catalog throughout, so it can add or replace `newViewDefinition`/`upsertComponents`
> and the repair prompts keep the catalog in scope. The REST entry points
> (`AiEvolveController`, `GenerateStreamController`) expose a nullable `includeView` that
> defaults to auto — on when the current spec already has a `viewDefinition`, so evolving a spec
> with a UI keeps the UI in sync.

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
  newVersion, expectedVersion, removeDerivations, upsertDerivations,
  removeConstraints, upsertConstraints,
  removeEffects, upsertEffects,
  removeMetaDerivations, upsertMetaDerivations,
  removeDefaultValues, upsertDefaultValues,
  upsertConstants, removeConstants, newConstants,
  upsertSchemaDefs, removeSchemaDefs, upsertSchemaNodes, removeSchemaNodes, newSchema
  [+ newDefaultView, upsertViews, removeViews,
     upsertComponents, removeComponents, newViewDefinition when includeView]

<targeted-diff guidance — see below>

Output only the JSON SpecEvolution, nothing else.
```

The field list is followed by **targeted-diff guidance** (`evolutionGuidance()`) that steers
the model away from wholesale section replacement:

- To change one part of the schema, use `upsertSchemaNodes` (by canonical data path) or
  `upsertSchemaDefs` (by `$defs` name) — not `newSchema`, which is reserved for restructuring
  and is mutually exclusive with the schema diff fields in one evolution.
- `upsertSchemaNodes[].schema` replaces that node wholesale; `"required": true/false`
  adds/removes the field in its parent's required list; a path may not traverse a `$ref`
  (edit the shared definition via `upsertSchemaDefs` instead, which fans out to every usage).
- `upsertConstants`/`removeConstants` change named **values**; do not confuse them with
  `upsertConstraints` (boolean **invariants**). `removeConstants` is rejected while the
  constant is still referenced.
- `expectedVersion` (optional) applies the evolution only if the model is still at that
  version (optimistic concurrency).
- With `includeView`: change one screen/widget via `upsertViews` (whole view, by id) or
  `upsertComponents`/`removeComponents` (one component, by id, with optional
  `parentId`/`beforeId` placement) — not `newViewDefinition` unless redesigning the UI.

> **Historical note:** the prompt used to advertise `removeActions`/`upsertActions` while
> `SpecEvolution`'s real JSON property names are `removeEffects`/`upsertEffects`. That
> mismatch is fixed — `evolutionFields()` now emits the correct names, matching the
> structured-output schema (`SpecGenerationSchema.java`).

### 5. Evolution repair

Triggered when a generated `SpecEvolution` fails validation or its embedded tests, mirroring
prompt type 2/3 for the diff path: it echoes the rejected `SpecEvolution`, a rule-named
`FIX:`-hinted feedback block, the unchanged current spec, and the derived-paths block, then
asks the model to re-apply the same change request fixing the specific problem.

---

## Tool use

When a `WebTool` bean is configured (it exists whenever `valem.llm.web-fetch.enabled` **or**
`valem.llm.eval-tool.enabled` is true — both default on), every call in a
`generate()`/`generateEvolution()` session is made via `completeWithTools`, offering:

- **`web_fetch`** — fetch a URL for authoritative domain info (SSRF-guarded; see
  [security-model.md](../deployment/security-model.md)); budget `valem.llm.web-fetch.max-calls`
  (default 5), `max-chars` (default 8000) per fetch.
- **`web_search`** — web search so the model finds URLs instead of guessing them; pluggable
  backend via `valem.llm.web-search.provider`: `duckduckgo` (default, keyless), `brave`, or
  `tavily` (the latter two need `valem.llm.web-search.api-key`); budget
  `valem.llm.web-search.max-calls` (default 3), `max-results` (default 5).
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
pipeline: `extractJson` → `collapseStringNewlines` → `repairJson` (JSON-structural fixes) →
`fixExpressions` → `repairConstraintPolicy` (fills a missing constraint `"policy"` with
`"rollback"`). `fixExpressions` is a **tree-walk**, not a whole-document regex: it parses the
JSON and applies the JSONata-syntax passes (`ExpressionRepairer` — lambda-body braces,
function-body/binding commas, `mod`/`between`/`in`/`!==`/`==`/`$power` rewrites, paren-balancing,
`$currentYear()`/`$toInteger` normalization, …) **only at real expression locations**
(`derivations[*].expr`, `constraints[*].expr`, effect/view expressions, …). A constraint
`message`, view `helperText`, or any other user-visible string is therefore left byte-for-byte
intact instead of being silently corrupted. Only when the response won't parse as JSON does the
old whole-document raw-string pass run, as a last-ditch rescue. Every successful spec/evolution
also passes through `markDerivedFieldsReadOnly()` so the schema stays consistent with the
derivations.

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
    if VERIFIABLE tests fail  → remember best-effort spec; prompt = testRepairPrompt(...) → next
    return GenerationResult.Success(markDerivedFieldsReadOnly(spec), attemptsUsed)

    [evolution path]
    evolution = JSON.parse(repairedJson)
    if parse fails                     → next attempt (same evolutionRepairPrompt shape)
    merged = evolution.applyTo(currentSpec)     // validates + runs embedded tests
    if invalid                          → prompt = evolutionRepairPrompt(feedback) → next
    if VERIFIABLE tests fail            → remember best-effort evolution;
                                          prompt = evolutionRepairPrompt(feedback) → next
    return evolution

attempt i+1 < maxRetries          → always continue on failure
attempt i+1 >= maxRetries         → continue only while the error count is still converging,
                                     up to maxRetriesHard
exhaust retries → best-effort result if one exists, else GenerationResult.Failure / LlmException
```

**Verifiable failures only** (`retainVerifiableFailures`): a failing embedded-test assertion
is dropped — it never consumes retry budget or blocks generation — when its expected value is
a whole array/object (a computed collection can't be hand-computed exactly) or the derivation
at its path depends on `$now()`/`$millis()` (a fixed expectation is wrong by runtime).

**Best-effort fallback:** embedded tests are a self-verification aid, not a hard gate. If the
budget runs out and a structurally-valid spec (or evolution) was produced whose only remaining
problem is failing verifiable self-tests, the one with the fewest failures is returned instead
of failing the generation outright.

**Temperature:** the first attempt uses `valem.llm.generation-temperature` (default
`0.0`, deterministic); the first repair uses `valem.llm.repair-temperature` (default `0.2`),
and each **subsequent** repair rises by `valem.llm.repair-temperature-step` (default `0.15`),
clamped to `valem.llm.repair-temperature-max` (default `0.8`). Repair starts slightly *above*
generation and escalates on purpose: after a deterministic attempt failed, a little — then
progressively more — randomness helps a stuck model escape the rut rather than re-emit the same
output.

**Structured output:** when `valem.llm.structured-output.enabled=true` (the default),
the `ModelSpec`/`SpecEvolution` JSON Schema constrains the output shape. OpenAI-compatible
providers receive it as `response_format` (non-strict mode). Anthropic receives it as a forced
`submit_spec` tool whose `input_schema` is the schema — forced via `tool_choice` when no
grounding tools are configured (the model can only answer by calling it), or offered
un-forced alongside `web_search`/`eval_jsonata` (a `submit_spec` tool call is then the terminal
answer; plain text still falls back to text handling).

**Prompt caching:** on Anthropic, the stable `tools`+`system` prefix carries an `ephemeral`
`cache_control` breakpoint (`valem.llm.prompt-cache.enabled`, default on), so retries and
tool-loop turns re-read it at ~10% of input price.

**Tool-loop ceiling:** tool round-trips are capped at `valem.llm.tool-loop.max-iterations`
(default `40`); on the cap, one final tools-withheld request forces the answer so a model stuck
calling exhausted tools cannot loop unbounded.

---

## Summary table

| Prompt type | Caller supplies | System adds |
|---|---|---|
| Initial generation | `domainDescription`, `includeView` | System context (+ view catalog), model ID, shape exemplars, output instruction |
| Repair (validation) | nothing (auto retry) | System context, FIX-hinted error list, previous spec JSON (or the truncated-response variant) |
| Repair (test failures) | nothing (auto retry) | System context, FIX-hinted failure list (with failing `expr`), previous spec JSON |
| Evolution | `evolutionRequest`, `includeView` (view-aware through the whole loop) | System context (+ view catalog), current spec JSON, derived-paths block, shape exemplars, SpecEvolution field docs + targeted-diff guidance |
| Evolution repair | nothing (auto retry) | System context, rejected evolution, FIX-hinted feedback, current spec, derived-paths block |

---

## See also

See [configuration.md](../deployment/configuration.md) for the full `valem.llm.*` property list
(8 providers, per-provider model/base-URL defaults, tool budgets, retry/temperature knobs).
