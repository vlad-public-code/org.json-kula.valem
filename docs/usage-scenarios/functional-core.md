---
title: A functional core without code review
parent: Usage scenarios
nav_order: 5
description: "Functional core, imperative shell — with the core as a spec, safe by construction, gated by tests instead of reviewers."
---

# A functional core without code review
{: .no_toc }

In *functional core, imperative shell*, most of the logic lives in the core. Make that core a
`ModelSpec` and it stops being code anyone has to read — it becomes data whose safety is structural
and whose correctness is testable.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## The problem

AI made writing code cheap. Reviewing it didn't get cheaper. An agent can produce more diff than a
team can conscientiously read, so the review queue — not the writing — becomes the bottleneck of an
AI-assisted SDLC. And you can't simply skip the reading, because review does a job tests can't:
imperative code can hide I/O in a helper, mutate shared state, leak a connection, open a security
hole — none of which shows up in a green test run. Behavior alone doesn't prove the *absence* of
side effects, so a human reads every line.

That reasoning has a flip side. If a piece of logic **provably has no side effects**, the reviewer
has nothing left to look for that a test wouldn't catch.

## The shape of the fix

Split the system along the classic **functional-core / imperative-shell** seam — and make the
functional core a spec, not code:

- **The core is a `ModelSpec`.** Schema, constants, derivations, constraints, embedded tests —
  declarative data, interpreted by a runtime that was reviewed once and is shared by every model.
- **The shell stays imperative and small.** Effect executors, integration code, deployment
  configuration — written by hand, changed rarely, reviewed as usual.

The core is safe to accept without line-by-line review because everything review exists to catch is
absent **by construction**:

- Expressions are [JSONata](https://jsonata.org) — no I/O, no filesystem, no network, no classpath,
  no shared mutable state. A derivation *cannot* hide a side effect; the language has nowhere to put one.
- The pure core never performs I/O. Even declared side effects leave it only as data
  (`EffectRequest`), executed post-commit by the shell.
- The blast radius of a wrong spec is a wrong value or a refused write — **observable behaviors**,
  which is precisely what tests check.

So the gate moves from reading to testing. A spec is admissible when it passes its suites:
**functionality** (embedded tests, golden suites, dry-runs), **performance** (expression compile
budget at load, evaluation cost under representative mutations), **memory** (soak the runtime at
expected model size). All of it is mechanical, parallelizable, and doesn't consume a senior
engineer's afternoon.

## Who writes the core

Once review is out of the loop, spec authorship opens up:

- **An AI dev agent, inside the SDLC.** Over [MCP]({% link deployment/mcp-server.md %}) the loop is
  generate → `validate_spec` → `eval_expression` → `test_spec` → `dry_run` — the agent generates,
  Valem verifies, and nothing touches a live model until the checks pass. See
  [structured state for AI agents](agent-state.md).
- **A domain expert, outside the SDLC entirely.** The low-code path: a rate change or a new
  eligibility rule is a [spec evolution]({% link reference/model-spec/tests-and-evolution.md %})
  with `expectedVersion` and tests — not a ticket, a sprint, and a release train.
- **A conventional developer** — who now writes the interesting 10% (the shell) instead of the
  voluminous 90% (the arithmetic).

## What Valem provides

| Need | Mechanism |
|---|---|
| Prove behavior without reading code | Embedded `tests` run by `TestCaseRunner`; `test_spec` over MCP |
| Try a change with zero blast radius | `dry_run` — the full reactive cascade in a throwaway runtime |
| Gate a merge mechanically | `validate_spec` + `GET /models/{id}/verification` — a green/amber consistency report, cached per spec version |
| Ship a rule change without a release | `evolve_spec` with `expectedVersion` and a schema check that refuses to strand existing data |
| Keep invariants out of reach of the author | `constraints` with `rollback`/`flag` — enforced by the runtime, not promised by the spec |
| See what the core did in production | `explain` traces + the durable, tamper-evident [audit trail]({% link deployment/operations.md %}) |
| Keep the shell thin and pluggable | Effects declared as data; executors live in the shell; custom kinds are a drop-in jar |

## What review is still for

Skipping review of the pure core is not skipping engineering judgment — it's concentrating it where
purity doesn't hold:

- **Effect declarations.** An effect's URL, prompt, and executor come from the spec and *do* reach
  the outside world. Review them the way you review configuration — small, scoped, and backed by the
  SSRF egress guard and the `valem.effects.kinds.enabled` list. See
  [effect-driven workflows](workflows-and-effects.md).
- **The shell itself.** Executors, custom `EffectKind` jars, integration and deployment code —
  ordinary imperative software, reviewed the ordinary way.
- **The tests.** When tests replace the reviewer as the gate, the tests inherit the reviewer's
  authority — so give *them* the scrutiny. The one circularity to avoid is an agent grading its own
  homework: keep test authorship independent of spec authorship, with a human owning the statements
  of what must be true and the agent free to satisfy them.

The division of labor becomes: **humans state what must be true, agents make it true, the runtime
keeps it true.**

## Worth knowing before you build on it

- "Safe" means *pure and bounded*, not *correct*. A wrong derivation still computes a wrong number;
  the test suite is the gate, so its coverage is the ceiling of your confidence.
- Spec authorship is still a privileged operation — because of effects, not because of the pure
  sections. An author who can declare effects can reach the network.
- Expressions are javac-compiled once per runtime and cached; budget compile time at model load, and
  remember wildcard derivations evaluate once per array element.

## Next

- [Rules & calculations as data](rules-and-calculations.md) — the same core, seen from the business side.
- [Tests & evolution reference]({% link reference/model-spec/tests-and-evolution.md %}) — the test and
  spec-diff formats that make the gate mechanical.
- [Generating specs with an LLM]({% link model-guide/generating-specs-with-llm.md %}) — the
  validate-and-retry loop for machine-authored cores.
