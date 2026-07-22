import { useEffect, useState } from 'react';
import { useViewContext } from '../ViewContext';
import { useJSONataBoolean } from '../hooks/useJSONata';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { TracePanelSpec } from '../types';

/**
 * The union of what the two endpoints return: `DerivationTrace` from `/explain` and
 * `AuditRecord` from `/audit`. Field names mirror the Java records exactly — a trace's path is
 * `targetPath`, not `path`, and a constraint trace carries `constraintPassed` instead of a result.
 */
interface TraceRow {
  // DerivationTrace
  targetPath?: string;
  expression?: string;
  inputPaths?: string[];
  result?: unknown;
  constraintPassed?: boolean | null;
  errorMessage?: string | null;
  // AuditRecord
  timestamp?: string;
  sequence?: number;
  source?: string;
  mutations?: Record<string, unknown>;
  derivedUpdated?: string[];
  flaggedConstraints?: string[];
}

/**
 * `explainPanel` and `auditTimeline` — Valem's two path-scoped record trails.
 *
 * The component fetches its own rows. The server sends only the declaration (which path, how
 * many) because `ViewEvaluator` has no access to the trace ring buffer or the audit store, and
 * giving it one would put an unbounded read inside every view evaluation and every `viewDelta`.
 *
 * It refetches when the model state changes, which is the cheap way to stay current: any mutation
 * that could add a trace row also changes state, and the endpoints are both bounded reads.
 */
export function TracePanel({ component: c, state }: BaseComponentProps<TracePanelSpec>) {
  const { modelId } = useViewContext();
  const initiallyCollapsed = useJSONataBoolean(c.collapsed, state, false);
  const [open, setOpen] = useState<boolean | null>(null);
  const isOpen = open ?? !initiallyCollapsed;

  const [rows, setRows] = useState<TraceRow[]>([]);
  const [error, setError] = useState<string | null>(null);

  const isAudit = c.type === 'auditTimeline';
  const url = buildUrl(modelId, c, isAudit);

  useEffect(() => {
    if (!isOpen || !url) return;
    let cancelled = false;
    fetch(url)
      .then(r => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((data: unknown) => {
        if (cancelled) return;
        setError(null);
        setRows(normalise(data, isAudit));
      })
      .catch((e: Error) => { if (!cancelled) { setError(e.message); setRows([]); } });
    return () => { cancelled = true; };
  }, [url, isOpen, isAudit, state]);

  return (
    <section data-testid={c.id} title={c.tooltip} style={{ border: '1px solid #e5e7eb', borderRadius: 8 }}>
      <button
        type="button"
        aria-expanded={isOpen}
        data-testid={`${c.id}-toggle`}
        onClick={() => setOpen(!isOpen)}
        style={{
          display: 'flex', alignItems: 'center', gap: 6, width: '100%',
          padding: '8px 14px', border: 'none', background: 'transparent',
          fontSize: 13, fontWeight: 600, color: '#111827', cursor: 'pointer', textAlign: 'left',
        }}
      >
        <span style={{ color: '#6b7280' }}>{isOpen ? '▾' : '▸'}</span>
        {c.label ?? (isAudit ? 'History' : 'Why this value?')}
        {c.bind && <code style={{ fontSize: 11, color: '#6b7280', fontWeight: 400 }}>{c.bind}</code>}
      </button>

      {isOpen && (
        <div style={{ padding: '0 14px 12px' }}>
          {error && <span style={{ fontSize: 12, color: '#b91c1c' }}>Could not load: {error}</span>}
          {!error && rows.length === 0 && (
            <span style={{ fontSize: 12, color: '#6b7280' }}>
              {isAudit ? 'No recorded changes yet.' : 'No derivation recorded for this path yet.'}
            </span>
          )}
          {rows.length > 0 && (
            <ol style={{ margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 6 }}>
              {rows.map((row, i) => (
                <li key={i} data-testid={`${c.id}-row`} style={rowStyle}>
                  {isAudit ? <AuditRow row={row} /> : <ExplainRow row={row} />}
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </section>
  );
}

function ExplainRow({ row }: { row: TraceRow }) {
  // A constraint trace carries `constraintPassed` and no result; `$constraint:<id>` is the
  // synthetic target path the runtime records them under.
  const isConstraint = row.constraintPassed != null;
  return (
    <>
      <code style={{ fontSize: 11, color: '#2563eb' }}>{row.targetPath}</code>
      {row.expression && (
        <code style={{ fontSize: 11, color: '#374151', display: 'block', marginTop: 2 }}>
          {row.expression}
        </code>
      )}
      {row.errorMessage ? (
        <span style={{ fontSize: 11, color: '#b91c1c' }}>{row.errorMessage}</span>
      ) : (
        <span style={{ fontSize: 11, color: '#6b7280' }}>
          {isConstraint
            ? (row.constraintPassed ? '✓ held' : '✗ violated')
            : `→ ${row.result != null ? JSON.stringify(row.result) : '—'}`}
        </span>
      )}
      {(row.inputPaths?.length ?? 0) > 0 && (
        <span style={{ fontSize: 10, color: '#9ca3af' }}>from {row.inputPaths!.join(', ')}</span>
      )}
    </>
  );
}

function AuditRow({ row }: { row: TraceRow }) {
  const changed = Object.keys(row.mutations ?? {});
  return (
    <>
      <div style={{ display: 'flex', gap: 8, alignItems: 'baseline' }}>
        <span style={{ fontSize: 11, fontWeight: 600, color: '#111827' }}>#{row.sequence}</span>
        {row.source && <span style={{ fontSize: 10, color: '#6b7280' }}>{row.source}</span>}
        {row.timestamp && <span style={{ fontSize: 10, color: '#9ca3af' }}>{row.timestamp}</span>}
      </div>
      {changed.length > 0 && (
        <code style={{ fontSize: 11, color: '#2563eb' }}>{changed.join(', ')}</code>
      )}
      {(row.derivedUpdated?.length ?? 0) > 0 && (
        <span style={{ fontSize: 10, color: '#6b7280' }}>
          recomputed {row.derivedUpdated!.length} derived field{row.derivedUpdated!.length === 1 ? '' : 's'}
        </span>
      )}
    </>
  );
}

function buildUrl(modelId: string, c: TracePanelSpec, isAudit: boolean): string | null {
  if (!modelId) return null;
  if (isAudit) {
    const params = new URLSearchParams();
    if (c.bind) params.set('path', c.bind);
    params.set('limit', String(c.limit ?? 20));
    return `/models/${encodeURIComponent(modelId)}/audit?${params}`;
  }
  if (!c.bind) return null;
  return `/models/${encodeURIComponent(modelId)}/explain/${encodeURIComponent(c.bind)}`;
}

/** Both endpoints return either a bare array or an object wrapping one; accept both. */
function normalise(data: unknown, isAudit: boolean): TraceRow[] {
  const arr = Array.isArray(data)
    ? data
    : (data as Record<string, unknown>)?.[isAudit ? 'records' : 'traces'] ?? [];
  return Array.isArray(arr) ? (arr as TraceRow[]) : [];
}

const rowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
  padding: '6px 8px',
  background: '#f9fafb',
  borderRadius: 4,
};
