---
title: Effects
parent: Model guide
nav_order: 3
description: "How effects reach the outside world: the four built-in executors, the effect lifecycle, and how to add a custom effect kind as a drop-in jar."
redirect_from:
  - /guides/effects.html
---

# Effects

Effects are the **only** way a Valem model reaches outside itself. The pure core never performs
I/O — when an effect's `trigger` becomes true after a committed mutation, the core *emits a
request as data*, and a shell-side executor performs the actual I/O **asynchronously,
post-commit**, folding any result back into state as an ordinary mutation. Replay never re-runs
I/O: a folded-back response is just a logged mutation.

This guide covers the built-in executors, the runtime lifecycle, and how to add a **custom
effect kind** as a drop-in jar. The spec-side field reference (every field of every executor)
lives in [model-spec-format.md](../reference/model-spec-format.md#effects); egress/SSRF controls
are in [security-model.md](../deployment/security-model.md).

## Built-in executors

| `executor` | What it does | I/O |
|---|---|---|
| `caller` | Computes `emit` + `payload` and returns them in the mutation response (`dispatchedEffects`) and over WebSocket. | none |
| `server` | Makes a spec-defined HTTP request (single, fan-out, or a composition `target` link) behind the SSRF egress guard, then folds the response back via `response.set` (`$response` bound). | HTTP |
| `llm` | Sends a state-derived `prompt` to the configured LLM (optional `responseSchema` for structured output) and folds the parsed completion back. | LLM API |
| `timer` | Schedules the `response.set` fold-back at an absolute time (`at`) or after a delay (`afterMs`); expressions are evaluated at fire time against current state. | clock |

A worked `server` + `timer` example (live rate lookup + quote expiry) is in the
[spec format reference](../reference/model-spec-format.md#worked-server--timer-example).

## The lifecycle

Every durable effect (everything except `caller`) runs the same state machine, maintained by the
runtime at the effect's `statusPath`:

```
pending → in_flight → applied | failed | cancelled
```

- **Edge-triggered:** `dedupeKey` is the effect's edge key — it re-fires only when that value
  *transitions*, not while it merely stays truthy.
- **Keyed compare-and-swap fold-back:** if the effect's input changes while a call is in flight,
  the stale result is never applied — the effect is *superseded* (discarded and re-fired for the
  latest value) or *cancelled* (the trigger no longer holds). A stale response can't overwrite a
  newer input.
- **Deterministic replay:** fold-backs enter state through `ModelService.mutate`, so history
  replays byte-for-byte without re-contacting the outside world.

## Adding a custom effect kind

`executor` is an open string, not a closed enum. Any other value names a **pluggable kind**
supplied by a jar on the classpath — no core or api edits. A kind has two halves, mirroring
Valem's pure-core / shell split:

| Half | Interface | Discovered by | Responsibility |
|---|---|---|---|
| Pure | `org.json_kula.valem.core.engine.spi.EffectKind` | `ServiceLoader` | Validate the spec fragment; evaluate expressions; resolve to an `EffectRequest.Plugin` (**no I/O**) |
| Shell | `org.json_kula.valem.api.effects.EffectExecutor` | Spring bean (auto-configuration) | Perform the I/O; drive the status machine; fold the result back |

The repository ships a complete reference plugin, **`valem-effects-noop`** — copy it as your
template. The essentials:

### 1. The pure half: `EffectKind`

```java
public final class MyEffectKind implements EffectKind {
    @Override public String kind() { return "my-kind"; }   // the executor value specs select

    @Override
    public void validate(EffectSpec effect, String location, EffectValidationContext ctx) {
        // Check your kind-specific fields; record problems on ctx.
        // ctx.validateExpr(...) parse-checks a JSONata expression.
    }

    @Override
    public EffectRequest.Plugin resolve(EffectSpec effect, EffectEvalContext ctx, JsonNode dedupeKey) {
        // Evaluate expression-bearing fields via ctx.eval(...) into params — still no I/O.
        ObjectNode params = ...;
        return new EffectRequest.Plugin(
                kind(), effect.id(), effect.statusPath(), dedupeKey, effect.responseSet(), params);
    }
}
```

Register it for `ServiceLoader` discovery — one line in
`META-INF/services/org.json_kula.valem.core.engine.spi.EffectKind`:

```
com.example.MyEffectKind
```

If your kind is fire-and-surface with no fold-back (like `caller`), override
`durable()` to return `false`; durable kinds participate in crash-recovery reconcile and
superseded re-fire automatically.

### 2. The shell half: `EffectExecutor`

Extend `EffectShell` to inherit the status machine and the keyed compare-and-swap fold-back —
the same machinery the built-in HTTP/LLM shells use:

```java
public class MyEffectExecutor extends EffectShell implements EffectExecutor {
    public MyEffectExecutor(ModelService service, EffectMetrics metrics) { super(service, metrics); }

    @Override public String kind() { return "my-kind"; }

    @Override
    public void submit(String modelId, EffectRequest.Plugin p) {
        pool.submit(() -> {                                   // post-commit, off the mutation thread
            try {
                setPhase(modelId, p.statusPath(), p.dedupeKey(), "in_flight", null);
                JsonNode result = doTheActualIo(p.params());  // your I/O here
                var bindings = new JsonataBindings().bindValue("response", result);
                var values = evalResponseSet(p.responseSet(), mapper.nullNode(), bindings);
                applyFoldback(modelId, p.effectId(), p.statusPath(), p.dedupeKey(), values);
            } catch (Exception e) {
                setPhase(modelId, p.statusPath(), p.dedupeKey(), "failed", e.getMessage());
            }
        });
    }
}
```

Executors are Spring **beans** (not `ServiceLoader`) precisely so they can receive the managed
`ModelService` for fold-back. Ship the bean via a Spring Boot auto-configuration listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, guarded with
`@ConditionalOnBean({ModelService.class, EffectMetrics.class})` — then merely putting the jar on
the classpath registers it, and `CompositeEffectExecutor` routes your kind to it by name.

### 3. Deploy, enable, use

- **Deploy:** add your jar to the host application's classpath (e.g. a dependency of
  `valem-web`, or of your own Boot app embedding `valem-api`).
- **Enable:** by default every discovered kind is active. To restrict, set
  `valem.effects.kinds.enabled` (comma-list of kind names) — a spec selecting an unknown or
  disabled kind is **rejected at validation**, not at run time. See
  [configuration.md](../deployment/configuration.md).
- **Use from a spec:**

```json
{
  "id": "my-effect",
  "executor": "my-kind",
  "trigger": "order.state = 'placed'",
  "dedupeKey": "order.id",
  "statusPath": "$.order.ioMyKind",
  "response": { "set": { "$.order.confirmation": "$response.confirmationId" } }
}
```

### 4. Test it

`valem-effects-noop` also demonstrates the test setup: a unit test for the `EffectKind`
(validation + resolve) and a Spring Boot end-to-end test that creates a model whose spec uses the
kind, mutates it, and asserts the fold-back landed. Start from
[`NoopEffectKindTest` and `NoopEffectE2ETest`](https://github.com/vlad-public-code/org.json-kula.valem/tree/main/valem-effects-noop/src/test/java/org/json_kula/valem/effects/noop).

## Design rules for a good effect kind

- **Keep the pure half pure.** `EffectKind.resolve` evaluates expressions and packs data — if it
  performs I/O, replay determinism is broken.
- **Fold back through the machinery.** Always use `EffectShell`'s
  `setPhase`/`evalResponseSet`/`applyFoldback` rather than calling `ModelService.mutate`
  directly — that's what gives you the compare-and-swap protection against stale results.
- **Fail into the status machine.** On error, set phase `failed` with the message; the model
  stays consistent and the failure is visible at `statusPath` (and in the audit trail).
- **Respect the operator.** Document your kind's egress so deployments can decide whether to
  enable it; the built-in `server` guard rails (`EgressGuard`) do **not** apply to plugin kinds —
  enforce your own if you reach the network.
