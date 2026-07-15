# Reactive Engine — Internals

How the runtime turns a compiled model + a mutation into settled, derived, constraint-checked
state. This is the as-built internal reference for contributors.

Related: [overview.md](overview.md) (component map, data flow),
[../reference/model-spec-format.md](../reference/model-spec-format.md) (spec format).

---

## 1. Dependency graph

### Node kinds
- **BASE** — a path mutated directly by callers (e.g. `$.order.subtotal`). Not owned by a derivation.
- **DERIVED** — a path computed by a derivation expression (e.g. `$.order.total`). Cannot be `setValue()`d.
- **META** — a `path#property` key such as `$.loan.principal#maximum`, computed by a meta derivation.
- **Synthetic** — `$constraint:<id>` and `$effect:<id>` nodes, registered so they become dirty when their inputs change.

### Construction (`ModelSpecCompiler`)
For each derivation `{path, expr}`: `ExpressionPathExtractor.extract(expr)` walks the JSONata AST
to collect all `$.`-prefixed references `Ri`; for each, `graph.addEdge(Ri, targetPath)`
("targetPath depends on Ri"); `targetPath` is registered DERIVED. Meta derivations use
`nodeKey = path + "#" + property.toLowerCase()` (META). Constraints/effects register
`$constraint:id` / `$effect:id` with edges from every referenced path (an effect's edges come from its
`trigger` + `dedupeKey` + `prompt`/`at`/`afterMs`/`requests` expressions).

### Soundness invariant — dependencies must be statically extractable
Incremental evaluation is only correct if **every** input a derivation/constraint reads at runtime
is also an edge in the graph. `ExpressionPathExtractor` discovers edges by static analysis of the
JSONata AST, so the reactive guarantee holds **only for statically-resolvable references**
(`$.a.b`, `$.items[*].x`). Constructs whose effective path is computed at evaluation time — dynamic
key lookup (`$lookup($, $someKey)`), fully data-driven navigation, indices derived from other
fields — are **not** seen by the extractor. Such an expression can read a field with no corresponding
edge, so a change to that field will not mark the derivation dirty and its value can go **silently
stale**. This is unsupported. `ModelSpecValidator` makes a best-effort attempt to flag expressions
that reference no extractable path (or use known dynamic-lookup forms) so authors get a signal
instead of silent staleness; when in doubt, restructure to use static paths.

### Cycle detection
Kahn's topological sort: if processed nodes < total nodes, a cycle exists.
`DependencyGraph.CyclicDependencyException` reports the involved keys; `ModelSpecValidator`
surfaces it as a validation error.

### Structures (actual)
```java
class CompiledModel {
    ModelSpec spec;
    DependencyGraph graph;                              // nodes, edges, evaluationOrder, evaluationLevels
    Map<String, DerivationSpec>     derivationByPath;
    Map<String, MetaDerivationSpec> metaDerivationByKey;
    List<ConstraintSpec>            constraints;        // effects via spec.effects()
}

class DependencyGraph {
    Map<String, NodeInfo>    nodes;            // key → {key, NodeKind}
    Map<String, Set<String>> dependents;       // key → downstream
    Map<String, Set<String>> dependencies;    // key → upstream
    List<String>             evaluationOrder; // topological (sources first)
    // evaluationLevels(): List<List<String>> grouped by depth = 1 + max-predecessor-depth
}
```

---

## 2. Model state

```java
class ModelState {
    ObjectNode            baseDoc;       // mutable base state
    Map<String, JsonNode> derivedCache;  // "$.order.total" → value
    Map<String, JsonNode> metaCache;     // "$.loan.principal#maximum" → value
    Set<String>           dirtyPaths;
    Snapshot              transactionSnapshot; // non-null while a transaction is open
}
```

- `getValue(path)` checks `derivedCache` first, then `baseDoc.at(JsonPointer)`.
- `mergedDocument()` **deep-copies** `baseDoc` and splices all `derivedCache` values in — used by
  constraint and effect evaluators so they can reference derived fields. A mutation cycle now
  performs **exactly one** full materialization: `DerivationEvaluator.evaluateAndMerge`
  builds one merged document on the first level with work and carries it forward, splicing each
  level's results in via `ModelState.spliceDerived` before the next level reads it (instead of
  re-deep-copying per level). It hands that same document back to `ModelRuntime.mutate`, which
  reuses it for the meta-derivation, constraint, and effect phases (all take a merged-doc overload).
  So a cycle no longer pays one deep copy per level, one per global constraint, and one for effects —
  it pays one, total (zero if no derivations ran, in which case the phases share a single lazily
  built copy). The splice is **length-aware** (`setDerivedInDoc`): it never grows an array to fit a
  derived index, so a stale entry such as `$.items[2].lineTotal` left after the array shrank is
  skipped instead of reappearing as a phantom element — no separate stale-entry clear pass is needed.
- Transaction model: `beginTransaction()` takes a `Snapshot`; `commit()` discards it;
  `rollback()` calls `restore(snapshot)`.
- `withModel(newCompiledModel)` carries forward `baseDoc`, a filtered `derivedCache` (only
  paths still derived in the new model), and `metaCache` — used by spec evolution.

```java
record Snapshot(String modelId, String modelVersion,
                ObjectNode baseDoc,                 // deep copy
                Map<String, JsonNode> derivedCache, // unmodifiable copy
                Map<String, JsonNode> metaCache) {} // no timestamp; blobs excluded
```

---

## 3. Reactive algorithm (`ModelRuntime.mutate`)

```
mutate(model, state, mutations):  // mutations = Map<JsonPath, JsonNode>
  0. SchemaValidator pre-checks each mutation against the effective schema (422 before any tx)
  1. state.beginTransaction()                        // rollback snapshot
  2. for (path, value) in mutations:
        assert model.derivationFor(path) == null     // derived fields are read-only
        state.setValue(path, value); dirtyPaths.add(path)
  3. dirty = BFS over graph from dirtyPaths
        + wildcard pattern matching: for each "[*]" node, DirtyPropagator.matchesPattern(node, path)
  4. for nodeKey in evaluationOrder where DERIVED and dirty:   // EAGER only
        result = JSONata(expr) against the carried-forward merged document; write derivedCache
        (one merged doc per cycle; prior-level results spliced in before this level reads it)
        (LAZY derivations marked stale, evaluated on demand)
  5. for nodeKey in evaluationOrder where META and dirty:      // per-element for [*]
        write metaCache
  6. for constraint where "$constraint:id" in dirty:
        global → eval against mergedDocument(); scoped → against field value
        ROLLBACK → collect; FLAG → record
     if any ROLLBACK: state.rollback(); throw ConstraintViolationException
  7. for effect where "$effect:id" in dirty:
        if trigger(mergedDocument()) == true and not guarded: emit EffectRequest (data, no I/O)
  8. state.commit(); clearDirty(); fire effectSink post-commit (shell executes async, folds back
     as a later mutation via keyed CAS); append traces to ring buffer
```

### Evaluation-context rules
- Derivation `expr` → a **level-aware** view of the carried-forward merged document: base fields +
  all derivations from **prior** topological levels. Same-level derivations cannot see each other
  (their results are spliced in only after the whole level finishes). Wildcard expressions also
  receive `$parent` bound to the current array element.
- Meta derivation `expr` → the same per-cycle merged document for whole-document properties; the
  **element object** for per-element (`[*]`) properties.
- Constraint (global) and effect `trigger`/`dedupeKey` → the same per-cycle merged document (derived
  values visible).

### Level-ordered evaluation
`DerivationEvaluator.evaluateAndMerge` groups nodes by `evaluationLevels()`. It materializes the
merged document **once** (on the first level with dirty EAGER work) and carries it forward: every
node in a level reads the pre-level view, then the level's results are written back to `derivedCache`
**and** spliced into the shared merged document (`ModelState.spliceDerived`) so the next level sees
them. Within a level, derivations are evaluated **sequentially** (order is irrelevant — same-level
nodes are mutually independent by construction). The final merged document is returned to
`ModelRuntime.mutate` and reused by the meta/constraint/effect phases — one full deep copy per cycle.
Intra-level virtual-thread parallelism was removed: the per-task executor/future overhead dominated
the actual per-expression cost, and the real hot-path cost was the merged-document deep copy (now
amortized to one per cycle), not the lack of parallelism.

### Concurrency model and scaling ceiling
A single model serializes **all** mutations under one lock (`synchronized(runtime)` in
`ModelService`), so there is no intra-model parallelism by design — it is what makes the reactive
pipeline deterministic. Scale horizontally by running **many independent models**; a single hot or
shared model is a throughput ceiling. The mutation queue (`valem.mutation-queue-size`) bounds
in-flight mutations per model and returns 429 on overflow. See
[../guides/deployment-and-operations.md](../guides/deployment-and-operations.md) for the operational
guidance.

---

## 4. Explainability trace

```java
record DerivationTrace(
    String       targetPath,       // "$.order.total" or "$constraint:id"
    String       expression,
    List<String> inputPaths,       // paths referenced (not values)
    JsonNode     result,           // value for derivations; null for constraints
    Boolean      constraintPassed, // true/false for constraints; null for derivations
    String       errorMessage)     // set when evaluation threw
```

Both derivation and constraint evaluations write to one ring buffer (size 500) in
`ModelRuntime.traceLog`; oldest entries drop when full; cleared on explicit `restore()`.
`ModelRuntime.explain(prefix)` returns traces whose `targetPath` starts with `prefix`.
Exposed via `GET /models/{id}/explain/{path}`.

---

## 5. Technology stack

| Concern | Choice |
|---|---|
| Language | Java 21 (records, sealed interfaces, pattern matching) |
| JSON model | Jackson `JsonNode` / `ObjectNode`; `JsonPointer` navigation |
| Expression evaluator | JSONata2Java (`org.json_kula.jsonata_jvm`) — pre-compiles to bytecode; `ExpressionCache` memoises per runtime |
| AST walker | `ExpressionPathExtractor` — extracts JsonPath dependencies |
| JsonPath search / wildcards | `org.json_kula.tracked_json` (`JsonPathSearch`, `TrackedJsonNode`) |
| JSON Patch (RFC 6902) | `org.json_kula.tracked_json.json_patch.JsonPatch` (`compile().apply()`) |
| Web framework | Spring Boot 3 — REST + bare WebSocket |
| Build | Maven multi-module |

Path notation: spec/API use JsonPath (`$.order.items[*].qty`); the runtime converts to
`JsonPointer` via `PathConverter`. JSONata `expr` fields use JSONata's own dot-notation
(`order.total`) and are never rewritten.
