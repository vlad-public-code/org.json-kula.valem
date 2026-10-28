// Builds a ProvenanceSource from a model's dependency graph (GET /models/{id}/graph, or the graph a
// chat card receives with its first result). Pure and DOM-free: the indexes are a function of the graph
// alone, and only the value lookups read the live state.

import { canonicalPath } from './provenance';
import type { ProvenanceInfo, ProvenanceInput, ProvenanceSource } from './types';

/** One graph node, as the server's GraphProjection serialises it. */
export interface ProvenanceGraphNode {
  key: string;                 // canonical `$.`-prefixed key, or `$constraint:`/`$effect:` synthetic
  kind: string;                // BASE | DERIVED | META | CONSTRAINT | EFFECT
  label: string;
  expression: string | null;   // null for BASE fields
}

/** A dependency edge: `to` reads `from`. */
export interface ProvenanceGraphEdge {
  from: string;
  to: string;
}

/** The parts of a model graph provenance needs. */
export interface ProvenanceGraph<N extends ProvenanceGraphNode = ProvenanceGraphNode> {
  nodes?: N[];
  edges?: ProvenanceGraphEdge[];
}

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

export interface GraphIndexes<N extends ProvenanceGraphNode = ProvenanceGraphNode> {
  nodesByKey: Map<string, N>;
  /** key → its upstream inputs (the nodes it reads from). */
  deps: Map<string, string[]>;
  /** key → its downstream dependents (the nodes that read it). */
  dependents: Map<string, string[]>;
}

/** Builds the lookup + reverse-edge indexes provenance walks (direct inputs/dependents). */
export function buildGraphIndexes<N extends ProvenanceGraphNode>(graph: ProvenanceGraph<N> | null | undefined): GraphIndexes<N> {
  const nodesByKey = new Map<string, N>();
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
 * Best-effort input extraction for an inline `text`/`value` JSONata expression. Tokenises the
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

/**
 * The "Why is this number?" source for {@link ViewRenderer}'s `provenance` prop: a derived leaf
 * explains itself with its expression, its direct inputs and, when some of those are derived too, the
 * editable inputs it ultimately traces back to.
 */
export function createGraphProvenance(
  indexes: GraphIndexes,
  state: Record<string, unknown> | null | undefined,
): ProvenanceSource {
  const { nodesByKey, deps } = indexes;
  const kindOf = (k: string): string => nodesByKey.get(k)?.kind ?? 'BASE';
  const inputInfo = (k: string): ProvenanceInput => ({ path: k, label: nodesByKey.get(k)?.label ?? k, value: valueAtKey(state, k) });

  // Transitive base (editable) inputs, attached only when they add information (some direct input
  // is itself derived), so the popover's "ultimately from" isn't a duplicate of "from".
  const baseInputsFor = (roots: string[], directPaths: string[]): ProvenanceInput[] | undefined => {
    const hasDerivedInput = directPaths.some(p => kindOf(p) !== 'BASE');
    if (!hasDerivedInput) return undefined;
    const bases = new Set<string>();
    for (const root of roots) {
      if (kindOf(root) === 'BASE') bases.add(root);
      for (const up of transitiveInputs(deps, root)) if (kindOf(up) === 'BASE') bases.add(up);
    }
    return [...bases].map(inputInfo);
  };

  return {
    explain(bindPath: string): ProvenanceInfo | null {
      const key = canonicalPath(bindPath);
      const node = nodesByKey.get(key);
      if (!node || node.kind === 'BASE') return null;   // base inputs aren't "why is this number?"
      const directPaths = deps.get(key) ?? [];
      return {
        path: key, kind: node.kind, label: node.label, expression: node.expression,
        value: valueAtKey(state, key), inputs: directPaths.map(inputInfo),
        baseInputs: baseInputsFor([key], directPaths),
      };
    },
    // An inline text/value JSONata expression that isn't bound to a single node.
    explainExpression(expression: string, label: string, value: unknown): ProvenanceInfo | null {
      const keys = inlineInputPaths(expression, k => nodesByKey.has(k));
      if (keys.length === 0) return null;   // reads no model fields → nothing worth explaining
      return {
        path: '', kind: 'DERIVED', label, expression, value, inputs: keys.map(inputInfo),
        baseInputs: baseInputsFor(keys, keys),
      };
    },
  };
}
