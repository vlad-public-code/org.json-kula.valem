---
title: Effect-driven workflows
parent: Usage scenarios
nav_order: 3
description: "Reach the outside world — HTTP, LLM, timers, human approval — without giving up determinism or replay."
---

# Effect-driven workflows
{: .no_toc }

State changes that must *do something*: call an API, ask a model, wait an hour, hand a decision to a
human. Declared in the spec, executed post-commit, folded back as ordinary mutations.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## The problem

The moment a stateful system talks to the outside world, two properties usually die together:
determinism (replaying history re-fires the emails) and clarity (the side effects are buried in
imperative code far from the rule that triggered them).

## The shape of the fix

Valem splits it at the **functional-core / imperative-shell** seam:

1. The pure core never performs I/O. When an effect's `trigger` becomes true after a committed
   mutation, it *emits a request as data* (`EffectRequest`).
2. A shell-side executor performs the actual I/O, asynchronously, **post-commit**.
3. The result **folds back into state as an ordinary mutation** — logged like any other.

Because the fold-back is just a mutation, replaying history reproduces the state without
re-contacting anything. Effects are declared next to the rule that triggers them, and their progress
is visible in state via a `statusPath` machine: `pending → in_flight → applied | failed`.

## What you can build with it

| Pattern | How |
|---|---|
| Enrich state from an API | A `server` effect: spec-provided URL, retries, SSRF egress guard, response folded back |
| Ask an LLM as part of the model | An `llm` effect: state-derived prompt, JSON completion folded back into a field |
| Schedule or poll | A `timer` effect: fold back at `at`/`afterMs`; re-arm it from a derived tick for a self-refreshing loop |
| Hand something to the caller | A `caller` effect: surfaced in the mutation response — the seam for human-in-the-loop steps |
| Your own kind | A `ServiceLoader` `EffectKind` + an `EffectExecutor` bean — a drop-in jar, no core or API edits |

The world-clock example in the [gallery](examples-gallery.md) composes two of these into a model that
refreshes itself: a `server` (HTTP) effect fetches the time, a `timer` re-arms it every ten seconds.

## What keeps it honest

- **Edge-triggered, not level-triggered.** A `dedupeKey` plus the `statusPath` guard means a
  still-true trigger doesn't re-fire the effect on every unrelated mutation.
- **Stale results can't win.** Fold-backs use a keyed compare-and-swap under the model lock: an
  in-flight effect whose input has since changed is `SUPERSEDED` (discarded and re-fired for the
  latest value) or `CANCELLED` (the trigger no longer holds — also how a timer cancels).
- **Egress is guarded.** The built-in `server` executor goes through an SSRF guard (loopback,
  private ranges, redirects), and effect kinds are gated by an enable-list. See
  [security model]({% link deployment/security-model.md %}).
- **Replay never re-runs I/O.** A folded-back response is a logged mutation, nothing more.

## Worth knowing before you build on it

- Effects are **asynchronous and post-commit**: the mutation that triggered one returns before the
  I/O finishes. Model the waiting state explicitly (that's what `statusPath` is for).
- Executors live in the shell (`valem-api`), so a bare `valem-core` embedding gets `caller` effects
  and emits the rest as data for you to execute.
- An effect URL or prompt comes from the **spec**. Treat spec authorship as a privileged operation,
  particularly when specs are LLM- or agent-generated.

## Next

- [Effects guide]({% link model-guide/effects.md %}) — the four built-in executors, the lifecycle, and
  how to add a custom kind.
- [Model spec format]({% link reference/model-spec-format.md %}#effects) — every effect field.
- [Configuration]({% link deployment/configuration.md %}) — egress, retries, timeouts, enable-lists.
