import { useState } from 'react';
import { api } from '../api';
import type { DerivationTrace } from '../types';

interface Props { modelId: string }

export default function ExplainPanel({ modelId }: Props) {
  const [path, setPath] = useState('');
  const [traces, setTraces] = useState<DerivationTrace[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleExplain = async () => {
    if (!path.trim()) return;
    setError(null);
    setTraces(null);
    setLoading(true);
    try {
      const result = await api.explain(modelId, path.trim());
      setTraces(result);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <div className="banner banner-info" style={{ fontSize: 12, fontFamily: 'inherit' }}>
        Enter a target path to fetch evaluation traces. Constraint traces use synthetic keys like{' '}
        <code>$constraint:my-constraint-id</code>. Derivation traces are not yet recorded by the runtime.
      </div>

      <div className="card">
        <div className="card-title">Target Path</div>
        <input
          type="text"
          value={path}
          onChange={e => setPath(e.target.value)}
          placeholder="e.g. $constraint:credit-check"
          onKeyDown={e => e.key === 'Enter' && handleExplain()}
          style={{ fontFamily: 'var(--font-mono)', fontSize: 13 }}
        />
        <div className="btn-row mt-8">
          <button className="btn btn-primary" onClick={handleExplain} disabled={loading || !path.trim()}>
            {loading ? 'Fetching…' : 'Explain'}
          </button>
        </div>
      </div>

      {error && <div className="banner banner-error">{error}</div>}

      {traces !== null && (
        <div className="card">
          <div className="card-title">Traces ({traces.length})</div>
          {traces.length === 0 ? (
            <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>
              No traces found for this path. Traces are recorded for constraint evaluations only.
            </p>
          ) : (
            traces.map((t, i) => (
              <div className="trace-card" key={i}>
                <div className="trace-path">{t.targetPath}</div>
                <div className="trace-expr">{t.expression}</div>
                <div style={{ display: 'flex', gap: 6, marginBottom: 4, flexWrap: 'wrap' }}>
                  {t.inputPaths.map(p => (
                    <span key={p} className="badge badge-blue">{p}</span>
                  ))}
                </div>
                <div className="trace-meta">
                  result:{' '}
                  <strong style={{ color: t.error ? 'var(--red)' : 'var(--green)' }}>
                    {JSON.stringify(t.result)}
                  </strong>
                  {t.error && <span style={{ color: 'var(--red)', marginLeft: 8 }}>error: {t.error}</span>}
                  <span style={{ marginLeft: 12 }}>{t.timestamp}</span>
                </div>
              </div>
            ))
          )}
        </div>
      )}
    </>
  );
}
