// Small provenance helpers shared by ViewRenderer / ComponentRenderer / ProvenancePopover.
// Mirrors the server's GraphProjection.canonicalKey so a leaf's `bind` and a graph node key join
// regardless of whether either arrives `$.`-prefixed.

/** Normalises a field path to the canonical `$.`-prefixed form used as graph node keys. */
export function canonicalPath(path: string): string {
  if (!path) return path;
  if (path.startsWith('$constraint:') || path.startsWith('$effect:')) return path;
  if (path.startsWith('$.')) return path;
  if (path.startsWith('.')) return '$' + path;   // ".total" → "$.total"
  return '$.' + path;                             // "order.total" → "$.order.total"
}

/** Compact display of a value for the popover / input list. */
export function formatProvenanceValue(v: unknown): string {
  if (v === undefined) return '—';
  if (v === null) return 'null';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}
