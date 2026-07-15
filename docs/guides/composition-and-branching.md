# Composition & Branching

How models reference each other, how a model is branched from a template, how effects inherited
across an ownership boundary are approved, and how a model is promoted between repositories. This
guide ties together pieces that each have a canonical home:
[model-spec-format.md](../reference/model-spec-format.md) (the `template`/`lineage`/`target`
fields), [api-reference.md](../reference/api-reference.md) (the endpoints), and
[configuration.md](../reference/configuration.md#model-composition--references) (the
`valem.composition.*` / `valem.authz.*` properties).

---

## Coordinates

Models are addressed by a **coordinate**: `[namespace/]name`, each segment starting with a letter
(letters/digits/`-`/`_`, no `@`). A plain `id` is a coordinate in the default namespace. Coordinates
are what `template.ref` and a `server` effect's `target.ref` name; the shell resolves them through
the repository chain (the pure core never resolves a coordinate).

## Branching from a template

Creating a model whose spec carries `template` makes it a **branch**:

```json
{ "id": "acme/quote-eu", "template": { "ref": "acme/quote-base" }, "schema": { } }
```

`POST /models` **materializes** (flattens) the referenced template into the new model's own
self-contained inlined spec before validation — the branch does not stay live-coupled to its
template. The materializer pins the ancestor chain into the read-only `lineage` field (validated
acyclic) and stamps every inherited effect with a read-only `origin` (`{fromRef, fromOwner}`)
recording which ancestor contributed it. Inspect the chain at `GET /models/{id}/lineage`
(`[]` for a non-branch model).

## Inherited-effect approval (cross-owner branching)

Branching across an **ownership boundary** (the new model's owner differs from an ancestor's
`fromOwner`) quarantines every inherited effect that performs I/O (any executor other than the pure
`caller`): the effect is inert, its `statusPath` shows phase `blocked` /
`effect_approval_required`, until the new owner explicitly approves it.

- `GET /models/{id}/effects/pending` — list quarantined inherited effects.
- `POST /models/{id}/effects/{effectId}/approve` — approve one, keyed to the effect's current
  `definitionHash`; **any edit to the effect's executable bytes re-quarantines it**.
- Policy: `valem.authz.inherited-effects` = `approve` (default) | `allow` (trust inherited
  effects) | `deny` (never run cross-owner inherited effects).

Same-owner and branch-authored effects always run — only cross-owner inheritance is gated. See
[security-model.md](../reference/security-model.md#inherited-effect-approval-multi-tenant-branching).

## Links between models

A `server` effect can name another **model** as its destination instead of a URL, via the `target`
block (see the [effect field reference](../reference/model-spec-format.md#effect-field-reference)):

- **Write-link** — `"target": { "ref": "<coordinate>", "path": "$.some.field" }` plus a sibling
  `body` (JSONata): mutate the target model at `path` and fold its reply back.
- **Read-link** — `"target": { "ref": "<coordinate>", "read": "$.some.field" }`: read the value at
  `read` without mutating the target.

By default a link to a model that is not yet registered is rejected at create/evolve with 422
(`UnresolvedLinkTarget`). Set `valem.composition.lazy-binding=true` to allow out-of-order or peer
(A⇄B) creation — the link then binds and validates at first fire.

The cross-model link/lineage topology is queryable at `GET /composition/graph` (computed on
demand — never authoritative state).

## Repositories and promotion

Coordinates resolve through a priority-ordered **repository chain**: the implicit in-process
`local` repo first, then each entry of `valem.composition.repositories` (per entry: `id`,
`transport` — `http` or `mcp`; `repo-class` — `local` or `web`, inferred from the transport when
unset; `locator`; optional `credential`, `trusted`). A repository's **class**, not its transport,
drives the rules: only a **web-class** repo is a valid promote target and satisfies the
reference-locality closure.

`POST /models/{id}/promote` with body `{ "toRepo": "<repo id>" }` moves a local model into a web
repository — **one-way** (demotion is unsupported, HTTP 405) and **closure-checked**: a promotion
whose references would break the closure or locality rules is rejected with 409
(`PromotionClosureFailure` / `ReferenceLocalityViolation`).

## Endpoint summary

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/models` (spec with `template`) | Create a branch (materialize + pin lineage) |
| `GET` | `/models/{id}/lineage` | The pinned ancestor chain |
| `GET` | `/models/{id}/effects/pending` | Quarantined inherited effects |
| `POST` | `/models/{id}/effects/{effectId}/approve` | Approve a quarantined effect |
| `POST` | `/models/{id}/promote` | Promote into a web-class repository |
| `GET` | `/composition/graph` | Cross-model link/lineage topology |

Full request/response detail: [api-reference.md](../reference/api-reference.md).
