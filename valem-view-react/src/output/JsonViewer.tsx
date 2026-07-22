import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { useJSONataBoolean } from '../hooks/useJSONata';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { JsonViewerSpec } from '../types';

/**
 * `jsonViewer` — the bound subtree as formatted JSON.
 *
 * Bind it to `$` for the whole document. Note that this renders the *client's* state map, which
 * is the merged document the server sent — base fields with derived values already spliced in —
 * so what you see is what expressions evaluate against, not the base document underneath.
 */
export function JsonViewer({ component: c }: BaseComponentProps<JsonViewerSpec>) {
  const { state } = useViewContext();
  const initiallyCollapsed = useJSONataBoolean(c.collapsed, state, false);
  const [open, setOpen] = useState<boolean | null>(null);
  const isOpen = open ?? !initiallyCollapsed;

  const bindPath = c.bind?.replace(/^\$\./, '');
  const value = !bindPath || c.bind === '$' ? state : getByPath(state, bindPath);
  const text = stringify(value, c.maxDepth);

  return (
    <div data-testid={c.id} title={c.tooltip} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <button
        type="button"
        aria-expanded={isOpen}
        data-testid={`${c.id}-toggle`}
        onClick={() => setOpen(!isOpen)}
        style={{
          alignSelf: 'flex-start',
          border: 'none',
          background: 'transparent',
          padding: 0,
          fontSize: 13,
          fontWeight: 600,
          color: '#374151',
          cursor: 'pointer',
        }}
      >
        {isOpen ? '▾' : '▸'} {c.label ?? c.bind ?? 'JSON'}
      </button>
      {isOpen && (
        <pre style={{
          margin: 0,
          padding: 12,
          background: '#0f172a',
          color: '#e2e8f0',
          borderRadius: 6,
          fontSize: 12,
          lineHeight: 1.5,
          overflowX: 'auto',
          maxHeight: 360,
        }}>
          {text}
        </pre>
      )}
    </div>
  );
}

/**
 * Truncates below `maxDepth` rather than printing a whole model. A deep node becomes `…`, which
 * says "there is more here" — dropping the key entirely would read as "this field is empty".
 */
function stringify(value: unknown, maxDepth?: number): string {
  const limit = maxDepth ?? Infinity;
  const prune = (node: unknown, depth: number): unknown => {
    if (node == null || typeof node !== 'object') return node;
    if (depth >= limit) return Array.isArray(node) ? ['…'] : '…';
    if (Array.isArray(node)) return node.map(v => prune(v, depth + 1));
    return Object.fromEntries(
      Object.entries(node as Record<string, unknown>).map(([k, v]) => [k, prune(v, depth + 1)]),
    );
  };
  try {
    return JSON.stringify(prune(value, 0), null, 2) ?? 'undefined';
  } catch {
    return String(value);
  }
}
