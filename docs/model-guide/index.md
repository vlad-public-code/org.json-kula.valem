---
title: Model guide
nav_order: 4
has_children: true
description: "The principles behind a Valem model and what each part of a spec actually does."
redirect_from:
  - /model-spec.html
  - /guides/
---

# Model guide

How a Valem model is put together and why it behaves the way it does. This chapter is the
*explanatory* half of the docs — for the authoritative, field-by-field lists see
[Reference]({% link reference/index.md %}).

| Page | Covers |
|---|---|
| [Anatomy of a model](anatomy.md) | The sections of a `ModelSpec`, and the two path dialects that run through all of them. |
| [The reactive pipeline](reactive-pipeline.md) | What happens on every mutation, in order — and what it guarantees. |
| [Effects](effects.md) | How a model reaches the outside world without losing determinism. |
| [Views](views.md) | The `viewDefinition` UI tree and how it's evaluated. |
| [Composition & branching](composition-and-branching.md) | Links between models, branching from templates, promotion. |
| [Generating specs with an LLM](generating-specs-with-llm.md) | Compiling a plain-text domain description into a runnable spec. |

## The three principles

Everything in this chapter follows from three decisions.

**1. State is split into what you write and what is computed.**
The **base document** is the only writable part. Derived values, meta-derived field metadata, and
constraint outcomes are all functions of it. Reads see the **merged document** — base plus derived —
so nothing downstream has to know which is which.

**2. The core performs no I/O.**
Compilation and evaluation are pure. The same spec plus the same mutations always produce the same
state, which is what makes history replayable and traces trustworthy. Anything that touches the
outside world is *declared* as an effect and executed by the shell after the commit, folding back in
as an ordinary mutation.

**3. Only what changed is recomputed.**
Dependencies are extracted from the expressions at compile time into a DAG. A mutation dirties the
reachable nodes and nothing else, and those are re-evaluated in topological order — the same reason
a spreadsheet doesn't recalculate every cell when you type in one.

## Where to start

Writing your first spec: [Anatomy of a model](anatomy.md), then the
[examples gallery]({% link usage-scenarios/examples-gallery.md %}) for something running.
Debugging one: [The reactive pipeline](reactive-pipeline.md), then `explain`.
Unsure what a word means: every term used across this chapter is defined in the
[glossary]({% link glossary.md %}).
