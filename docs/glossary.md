# Glossary

Canonical definitions of the core Valem terms. Other docs link here instead of redefining
them inline.

| Term | Definition |
|---|---|
| **ModelSpec** | The declarative JSON document describing a model: schema, constants, defaultValues, derivations, meta-derivations, constraints, effects, tests, and an optional view definition. Usually LLM-generated. See [reference/model-spec-format.md](reference/model-spec-format.md). |
| **Base document** (`baseDoc`) | The writable portion of model state — a Jackson `ObjectNode`. Only base fields can be mutated directly. |
| **Derivation** | A computed, read-only field defined by a JSONata expression, re-evaluated when its dependencies change. `eager` (computed during the mutation) or `lazy` (computed on demand). |
| **Meta-derivation** | A computed piece of per-field *metadata* (min, max, required, `relevant`, …) stored in the meta cache, not the base document. Drives effective schema and view behavior. |
| **Constraint** | A boolean invariant evaluated after each mutation. Policy `rollback` (revert + 409) or `flag` (commit + report). |
| **Effect** | An effect request the pure core emits as *data* when a JSONata `trigger` fires on state change; the imperative shell then executes it, selected by `executor`: `caller` (pure — surfaced as `dispatchedEffects` in the mutation result, no egress), `server` (a guarded HTTP request), `llm` (an `LlmClient` call), or `timer` (a scheduled fold-back), plus operator-installed plugin kinds. The result **folds back as an ordinary mutation**, so state stays deterministic and replay never re-runs the I/O. Replaces the removed `actions` section (a spec still carrying `actions` is rejected). See [reference/model-spec-format.md](reference/model-spec-format.md) and [reference/security-model.md](reference/security-model.md). |
| **Merged document** (`mergedDocument()`) | A deep copy of the base document with all derived-cache values spliced in. The evaluation context for global constraints and effect triggers, and the shape returned by `GET /state`. |
| **Derived cache** | In-memory map of `$.path → value` holding computed derivation results. |
| **Meta cache** | In-memory map of `$.path#property → value` holding meta-derivation results. |
| **Dependency graph** | Precompiled DAG of node dependencies that makes incremental evaluation possible — only nodes reachable from a mutation are recomputed. Built by `ModelSpecCompiler`. |
| **Node kinds** | `BASE` (writable path), `DERIVED` (computed path), `META` (`path#property`), plus synthetic `$constraint:<id>` / `$effect:<id>` nodes. |
| **Dirty propagation** | BFS over the dependency graph from mutated paths (plus wildcard `[*]` pattern matching) to compute the full set of nodes needing re-evaluation. |
| **Evaluation level** | Topological depth group (`depth = 1 + max-predecessor-depth`). Nodes in one level are mutually independent; a level-k+1 derivation can see level-k results but not its own-level siblings. |
| **Snapshot** | An immutable deep copy of state (`baseDoc` + derived/meta caches). Used for rollback, point-in-time history, and restore. No timestamp; blobs excluded (content-addressed). |
| **SpecEvolution** | An incremental diff applied to a spec (upsert/remove per section + optional new schema/version/view). Validated and recompiled; live state carried forward via `withModel()`. |
| **Derivation trace** | A record (expression, input paths, result or pass/fail, error) written to a 500-entry ring buffer for both derivation and constraint evaluations. Powers `GET /explain`. |
| **BlobRef** | A lightweight pointer `{$blobId, $mediaType, $bytes}` stored in state in place of binary data; bytes live in a `BlobStore` (content-addressed by SHA-256). |
| **Effective schema** | A field's static JSON Schema fragment overlaid with live meta-cache values. Returned by `GET /schema/{path}`. |
| **View definition / EvaluatedView** | A renderer-agnostic UI component tree embedded in the spec; `ViewEvaluator` resolves it against merged state + meta into an `EvaluatedView`. See [reference/view-system.md](reference/view-system.md). |
| **Address vs expression path** (DEC-6) | An **address** (a path used as data: spec `path` fields, `defaultValues` paths, mutation/patch keys, view `bind`) must be canonical JSON Path — `$.`-rooted with bracket indices (`$.order.items[0].qty`). The validator **rejects** legacy dot-index (`$.items.0.x`) and unrooted addresses as errors (`PathConverter.toCanonicalAddress` gives the canonical rewrite). An **expression** body (`expr`/`trigger`/`payload` and JSONata view fields) uses JSONata navigation (`order.total`) and is never rewritten or constrained. `PathConverter` also bridges JSON Path → `JsonPointer`. |
