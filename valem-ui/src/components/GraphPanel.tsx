import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { canonicalPath } from 'valem-view-react';
import { api } from '../api';
import { buildSubscribeUrl } from '../wsAuth';
import { buildGraphIndexes, valueAtKey } from '../graphProvenance';
import type { GraphNodeKind, ModelGraph } from '../types';

interface Props {
  modelId: string;
  /** Cross-highlight (F11): the selected node path, shared with the in-view overlay. */
  selectedPath?: string | null;
  /** Report a node click so the in-view overlay can sync (F11). */
  onSelectPath?: (path: string | null) => void;
}

// "Why is this number?" — a read-only lens over the model's dependency graph
// (docs/sandbox/why-this-number.md). Nodes are laid out by the engine's topological levels; select
// one to see the expression that computes it, the inputs that feed it (with their current values),
// and everything downstream that it in turn feeds.

const KIND_LABEL: Record<GraphNodeKind, string> = {
  BASE: 'Input', DERIVED: 'Derived', META: 'Meta', CONSTRAINT: 'Constraint', EFFECT: 'Effect',
};

// Distinct, theme-token-based colours per kind (no hard-coded light values — works in dark mode).
const KIND_COLOR: Record<GraphNodeKind, string> = {
  BASE:       'var(--text-muted)',
  DERIVED:    'var(--accent)',
  META:       'var(--text-light)',
  CONSTRAINT: 'var(--danger, #d9534f)',
  EFFECT:     'var(--signal, #10b981)',
};

function fmtValue(v: unknown): string {
  if (v === undefined) return '—';
  if (v === null) return 'null';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

export default function GraphPanel({ modelId, selectedPath = null, onSelectPath }: Props) {
  const [graph, setGraph] = useState<ModelGraph | null>(null);
  const [state, setState] = useState<Record<string, unknown> | null>(null);
  const [localSelected, setLocalSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [pulsing, setPulsing] = useState<Set<string>>(() => new Set());
  const pulseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // The shared selection (F11) wins when supplied; otherwise the panel's own click state drives it.
  const selected = selectedPath ?? localSelected;
  const select = useCallback((key: string | null) => {
    setLocalSelected(key);
    onSelectPath?.(key ? canonicalPath(key) : null);
  }, [onSelectPath]);

  useEffect(() => {
    let live = true;
    setLoading(true);
    setError(null);
    Promise.all([api.graph(modelId), api.getState(modelId).catch(() => null)])
      .then(([g, s]) => { if (live) { setGraph(g); setState(s); } })
      .catch(e => { if (live) setError(String(e)); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [modelId]);

  // F12 — live pulse: subscribe to the model's change stream and flash nodes that recompute, so an
  // agent (or another tab) driving the model is visible as a ripple through the graph.
  useEffect(() => {
    let cancelled = false;
    let ws: WebSocket | null = null;
    let reconnect: ReturnType<typeof setTimeout> | null = null;
    const connect = () => {
      ws = new WebSocket(buildSubscribeUrl(modelId));
      ws.onmessage = (e: MessageEvent) => {
        try {
          const f = JSON.parse(e.data as string) as { mutatedPaths?: string[]; derivedUpdated?: string[] };
          const paths = [...(f.mutatedPaths ?? []), ...(f.derivedUpdated ?? [])].map(canonicalPath);
          if (!paths.length) return;
          setPulsing(new Set(paths));
          if (pulseTimerRef.current) clearTimeout(pulseTimerRef.current);
          pulseTimerRef.current = setTimeout(() => setPulsing(new Set()), 650);
          // Values changed — refresh so the detail panel shows current numbers.
          api.getState(modelId).then(s => { if (!cancelled) setState(s); }).catch(() => {});
        } catch { /* ignore malformed frame */ }
      };
      ws.onclose = () => { if (!cancelled) reconnect = setTimeout(connect, 3000); };
    };
    connect();
    return () => { cancelled = true; if (reconnect) clearTimeout(reconnect); ws?.close(); };
  }, [modelId]);

  // Lookup + reverse-edge indexes (dependencies = upstream inputs, dependents = downstream).
  const { nodesByKey, deps, dependents } = useMemo(
    () => buildGraphIndexes(graph ?? { modelId: '', nodes: [], edges: [], levels: [] }),
    [graph],
  );

  const selectedDeps = selected ? deps.get(selected) ?? [] : [];
  const selectedDependents = selected ? dependents.get(selected) ?? [] : [];
  const highlighted = useMemo(() => new Set([...selectedDeps, ...selectedDependents]), [selectedDeps, selectedDependents]);

  if (loading) return <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>Loading dependency graph…</div>;
  if (error) return <div className="banner banner-error">{error}</div>;
  if (!graph || graph.nodes.length === 0) {
    return <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>This model has no derivations, constraints, or effects to graph yet.</div>;
  }

  const selectedNode = selected ? nodesByKey.get(selected) ?? null : null;

  return (
    <>
      <div className="banner banner-info" style={{ fontSize: 12, fontFamily: 'inherit' }}>
        <strong>Why is this number?</strong> Every derived value is a function of its inputs. Select a
        node to trace the expression and the cells that feed it.
      </div>

      {/* Legend */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, margin: '8px 0 12px' }} data-testid="graph-legend">
        {(Object.keys(KIND_LABEL) as GraphNodeKind[]).map(k => (
          <span key={k} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11.5, color: 'var(--text-muted)' }}>
            <span style={{ width: 9, height: 9, borderRadius: 2, background: KIND_COLOR[k] }} />
            {KIND_LABEL[k]}
          </span>
        ))}
      </div>

      {/* Levels laid out shallowest → deepest */}
      <div style={{ display: 'flex', gap: 14, overflowX: 'auto', paddingBottom: 8 }} data-testid="graph-levels">
        {graph.levels.map((level, i) => (
          <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 6, minWidth: 150 }}>
            <div style={{ fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-light)' }}>
              Level {i}
            </div>
            {level.map(key => {
              const node = nodesByKey.get(key);
              if (!node) return null;
              const isSel = key === selected;
              const isHi = highlighted.has(key);
              const isPulse = pulsing.has(key);
              return (
                <button
                  key={key}
                  data-testid="graph-node"
                  data-node-key={key}
                  data-kind={node.kind}
                  data-pulsing={isPulse ? '1' : undefined}
                  onClick={() => select(isSel ? null : key)}
                  title={node.key}
                  style={{
                    textAlign: 'left', cursor: 'pointer', borderRadius: 6, padding: '5px 8px',
                    fontSize: 12, color: 'var(--text)',
                    background: isPulse ? 'var(--signal-soft, #d6f5e6)' : 'var(--panel-bg)',
                    transition: 'background-color 0.6s ease-out',
                    borderLeft: `3px solid ${KIND_COLOR[node.kind]}`,
                    border: `1px solid ${isSel ? 'var(--accent)' : isHi ? 'var(--accent-ring, var(--accent))' : 'var(--border)'}`,
                    outline: isSel ? '2px solid var(--accent-soft, var(--accent))' : 'none',
                    fontWeight: isSel ? 650 : 400, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                  }}
                >
                  {node.label}
                </button>
              );
            })}
          </div>
        ))}
      </div>

      {/* Selected-node provenance */}
      {selectedNode && (
        <div className="card" data-testid="graph-detail" style={{ marginTop: 12 }}>
          <div className="card-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ width: 9, height: 9, borderRadius: 2, background: KIND_COLOR[selectedNode.kind] }} />
            {selectedNode.label}
            <span style={{ fontSize: 11, color: 'var(--text-muted)', fontWeight: 400 }}>{KIND_LABEL[selectedNode.kind]}</span>
          </div>

          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 6 }}>
            Current value: <code style={{ fontFamily: 'var(--font-mono)' }}>{fmtValue(valueAtKey(state, selectedNode.key))}</code>
          </div>

          {selectedNode.expression != null ? (
            <pre data-testid="graph-expr" style={{
              fontFamily: 'var(--font-mono)', fontSize: 12, background: 'var(--surface-inset, var(--panel-bg))',
              border: '1px solid var(--border)', borderRadius: 6, padding: '8px 10px', overflowX: 'auto', margin: '0 0 10px',
            }}>{selectedNode.expression}</pre>
          ) : (
            <p style={{ fontSize: 12, color: 'var(--text-light)', fontStyle: 'italic', margin: '0 0 10px' }}>
              A base input — you set this directly; nothing computes it.
            </p>
          )}

          <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
            <div style={{ minWidth: 200 }}>
              <div style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--text-muted)', marginBottom: 4 }}>
                Inputs ({selectedDeps.length})
              </div>
              {selectedDeps.length === 0 ? (
                <div style={{ fontSize: 12, color: 'var(--text-light)' }}>none</div>
              ) : selectedDeps.map(dep => (
                <button
                  key={dep}
                  data-testid="graph-input"
                  onClick={() => select(dep)}
                  style={{ display: 'block', textAlign: 'left', background: 'none', border: 'none', cursor: 'pointer',
                    fontSize: 12, color: 'var(--text)', padding: '2px 0', fontFamily: 'var(--font-mono)' }}
                >
                  {nodesByKey.get(dep)?.label ?? dep}
                  <span style={{ color: 'var(--text-muted)' }}> = {fmtValue(valueAtKey(state, dep))}</span>
                </button>
              ))}
            </div>

            <div style={{ minWidth: 200 }}>
              <div style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--text-muted)', marginBottom: 4 }}>
                Feeds into ({selectedDependents.length})
              </div>
              {selectedDependents.length === 0 ? (
                <div style={{ fontSize: 12, color: 'var(--text-light)' }}>nothing</div>
              ) : selectedDependents.map(d => (
                <button
                  key={d}
                  onClick={() => select(d)}
                  style={{ display: 'block', textAlign: 'left', background: 'none', border: 'none', cursor: 'pointer',
                    fontSize: 12, color: 'var(--text)', padding: '2px 0', fontFamily: 'var(--font-mono)' }}
                >
                  {nodesByKey.get(d)?.label ?? d}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
