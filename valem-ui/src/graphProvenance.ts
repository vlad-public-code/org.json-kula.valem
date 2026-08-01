// Pure provenance helpers for the "Why is this number?" surface (docs/sandbox/why-this-number.md).
// Kept DOM-free so the tracing logic — value resolution and the dependency/dependent indexes — can
// be unit-tested independently of the React GraphPanel that renders it.

import { canonicalPath } from 'valem-view-react';
import type { GraphNode, ModelGraph } from './types';

/** Resolves a node key (`$.order.total`, `$.tax#minimum`) to its current value in the state doc. */
export function valueAtKey(state: Record<string, unknown> | null | undefined, key: string): unknown {
  if (!state) return undefined;
  if (key.startsWith('$constraint:') || key.startsWith('$effect:')) return undefined;
  let path = key.startsWith('$.') ? key.slice(2) : key;
  const hash = path.indexOf('#');
  if (hash >= 0) path = path.slice(0, hash);      // drop meta #property suffix
  if (path.includes('[*]')) return undefined;     // wildcard pattern — not a single cell
  if (path === '') return undefined;
  let cur: unknown = state;
  for (const seg of path.split('.')) {
    if (cur == null || typeof cur !== 'object') return undefined;
    cur = (cur as Record<string, unknown>)[seg];
  }
  return cur;
}

export interface GraphIndexes {
  nodesByKey: Map<string, GraphNode>;
  /** key → its upstream inputs (the nodes it reads from). */
  deps: Map<string, string[]>;
  /** key → its downstream dependents (the nodes that read it). */
  dependents: Map<string, string[]>;
}

/** Builds the lookup + reverse-edge indexes the panel walks for provenance (direct inputs/dependents). */
export function buildGraphIndexes(graph: ModelGraph): GraphIndexes {
  const nodesByKey = new Map<string, GraphNode>();
  // Tolerate a malformed/empty response (missing nodes/edges) — never throw during render.
  (graph?.nodes ?? []).forEach(n => nodesByKey.set(n.key, n));

  const deps = new Map<string, string[]>();
  const dependents = new Map<string, string[]>();
  const push = (m: Map<string, string[]>, k: string, v: string) => {
    const arr = m.get(k);
    if (arr) arr.push(v); else m.set(k, [v]);
  };
  (graph?.edges ?? []).forEach(({ from, to }) => {
    push(deps, to, from);          // `to` depends on `from`
    push(dependents, from, to);    // `from` feeds `to`
  });
  return { nodesByKey, deps, dependents };
}

/**
 * Best-effort input extraction for an inline `text`/`value` JSONata expression (F7). Tokenises the
 * expression for path-like identifiers, canonicalises each, and keeps only those that are actual
 * graph nodes — so `isNode` (the graph) is the source of truth and there are no false positives
 * (JSONata functions like `$sum` canonicalise to non-nodes and are dropped).
 */
export function inlineInputPaths(expression: string, isNode: (key: string) => boolean): string[] {
  const found = new Set<string>();
  const re = /\$?\.?[A-Za-z_][A-Za-z0-9_.]*/g;
  for (const m of expression.match(re) ?? []) {
    const key = canonicalPath(m);
    if (isNode(key)) found.add(key);
  }
  return [...found];
}

/** All transitive upstream inputs of `key` (BFS over dependency edges) — the full causal chain. */
export function transitiveInputs(deps: Map<string, string[]>, key: string): string[] {
  const seen = new Set<string>();
  const queue = [...(deps.get(key) ?? [])];
  while (queue.length) {
    const cur = queue.shift()!;
    if (seen.has(cur)) continue;
    seen.add(cur);
    for (const up of deps.get(cur) ?? []) if (!seen.has(up)) queue.push(up);
  }
  return [...seen];
}
