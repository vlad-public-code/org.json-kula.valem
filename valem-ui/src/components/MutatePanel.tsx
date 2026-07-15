import { useState } from 'react';
import { api, ApiError } from '../api';
import type { MutationResponse } from '../types';

type Mode = 'path' | 'patch';

const TEMPLATE_PATH = `{
  "$.field.path": 42
}`;

const TEMPLATE_PATCH = `[
  { "op": "add", "path": "/items/-", "value": { "name": "New item", "price": 1.0, "qty": 1 } }
]`;

interface Props { modelId: string }

export default function MutatePanel({ modelId }: Props) {
  const [mode, setMode]     = useState<Mode>('path');
  const [input, setInput]   = useState(TEMPLATE_PATH);
  const [result, setResult] = useState<MutationResponse | null>(null);
  const [error, setError]   = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const switchMode = (next: Mode) => {
    setMode(next);
    setInput(next === 'path' ? TEMPLATE_PATH : TEMPLATE_PATCH);
    setResult(null);
    setError(null);
  };

  const handleApply = async () => {
    setError(null);
    setResult(null);
    setLoading(true);
    try {
      const parsed = JSON.parse(input);
      const res = mode === 'path'
        ? await api.mutate(modelId, parsed as Record<string, unknown>)
        : await api.patchMutate(modelId, parsed as unknown[]);
      setResult(res);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setResult({ success: false, mutatedPaths: [], derivedUpdated: [], flaggedConstraints: [], dispatchedEffects: [], traces: [] });
      } else {
        setError(String(e));
      }
    } finally {
      setLoading(false);
    }
  };

  const template = mode === 'path' ? TEMPLATE_PATH : TEMPLATE_PATCH;

  return (
    <>
      <div className="panel-row">
        <div className="panel-col">
          <div className="card">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
              <div className="card-title" style={{ marginBottom: 0 }}>Mutation Input</div>
              <div style={{ marginLeft: 'auto', display: 'flex', border: '1px solid var(--border)', borderRadius: 6, overflow: 'hidden' }}>
                <ModeBtn label="Path"       active={mode === 'path'}  onClick={() => switchMode('path')} />
                <ModeBtn label="JSON Patch" active={mode === 'patch'} onClick={() => switchMode('patch')} />
              </div>
            </div>

            {mode === 'path' ? (
              <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 8 }}>
                JSON object mapping JsonPath keys to new values.
                Keys must use <code>$.</code> prefix (e.g. <code>$.items[0].price</code>).
                Derived fields are read-only and will be rejected.
              </p>
            ) : (
              <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 8 }}>
                RFC 6902 JSON Patch array. Paths use JSON Pointer (<code>/</code>-separated).
                Use <code>/items/-</code> to <em>append</em> to an array (<code>-</code> = after last element);
                use <code>/items/0</code> to target a specific index.
                Other ops: <code>remove</code>, <code>replace</code>, <code>move</code>, <code>copy</code>, <code>test</code>.
              </p>
            )}

            <textarea
              value={input}
              onChange={e => setInput(e.target.value)}
              rows={14}
              spellCheck={false}
            />
            <div className="btn-row mt-8">
              <button className="btn btn-primary" onClick={handleApply} disabled={loading}>
                {loading ? 'Applying…' : '▶ Apply'}
              </button>
              <button className="btn btn-sm" onClick={() => { setInput(template); setResult(null); setError(null); }}>
                Reset
              </button>
            </div>
          </div>
        </div>

        <div className="panel-col">
          {error && <div className="banner banner-error">{error}</div>}
          {result && (
            <>
              <div className={`banner ${result.success ? 'banner-success' : 'banner-error'}`}>
                {result.success ? '✓ Committed' : '✗ Rolled back'}
              </div>
              <div className="card">
                <div className="card-title">Result</div>
                <PathList label="Mutated"        paths={result.mutatedPaths}       color="blue" />
                <PathList label="Derived updated" paths={result.derivedUpdated}    color="purple" />
                <PathList label="Flagged"         paths={result.flaggedConstraints.map(c => c.constraintId)} color="orange" />
                {result.dispatchedEffects.length > 0 && (
                  <div style={{ marginTop: 8 }}>
                    <span className="field-label">Dispatched effects</span>
                    {result.dispatchedEffects.map((e, i) => (
                      <div key={i} style={{ marginTop: 4 }}>
                        <span className="badge badge-green">{e.effectId}</span>
                        <span style={{ marginLeft: 6, fontSize: 12, color: 'var(--text-muted)' }}>{e.emit}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
              {result.traces.length > 0 && (
                <div className="card">
                  <div className="card-title">Traces</div>
                  {result.traces.map((t, i) => (
                    <div className="trace-card" key={i}>
                      <div className="trace-path">{t.targetPath}</div>
                      <div className="trace-expr">{t.expression}</div>
                      <div className="trace-meta">
                        result: <strong>{JSON.stringify(t.result)}</strong>
                        {t.error && <span style={{ color: 'var(--red)', marginLeft: 8 }}>{t.error}</span>}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </>
  );
}

function ModeBtn({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      style={{
        padding: '4px 10px',
        fontSize: 12,
        border: 'none',
        cursor: 'pointer',
        background: active ? 'var(--accent)' : 'transparent',
        color: active ? '#fff' : 'var(--text-muted)',
        fontWeight: active ? 600 : 400,
        transition: 'background 0.15s',
      }}
    >
      {label}
    </button>
  );
}

function PathList({ label, paths, color }: { label: string; paths: string[]; color: string }) {
  if (paths.length === 0) return null;
  return (
    <div style={{ marginBottom: 8 }}>
      <span className="field-label">{label}</span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 4 }}>
        {paths.map(p => (
          <span key={p} className={`badge badge-${color}`}>{p}</span>
        ))}
      </div>
    </div>
  );
}
