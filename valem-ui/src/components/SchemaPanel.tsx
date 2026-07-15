import { useState } from 'react';
import { api } from '../api';
import JsonViewer from './JsonViewer';

interface Props { modelId: string }

export default function SchemaPanel({ modelId }: Props) {
  const [path, setPath] = useState('');
  const [schema, setSchema] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleFetch = async () => {
    if (!path.trim()) return;
    setError(null);
    setSchema(null);
    setLoading(true);
    try {
      const result = await api.effectiveSchema(modelId, path.trim());
      setSchema(result);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 16 }}>
        Returns the effective JSON Schema for a field — static schema from the spec merged with
        live meta-derivation values (e.g. computed minimum, maximum, readOnly).
      </p>

      <div className="card">
        <div className="card-title">Field Path</div>
        <input
          type="text"
          value={path}
          onChange={e => setPath(e.target.value)}
          placeholder="e.g. $.order.total"
          onKeyDown={e => e.key === 'Enter' && handleFetch()}
          style={{ fontFamily: 'var(--font-mono)', fontSize: 13 }}
        />
        <div className="btn-row mt-8">
          <button className="btn btn-primary" onClick={handleFetch} disabled={loading || !path.trim()}>
            {loading ? 'Fetching…' : 'Get Schema'}
          </button>
        </div>
      </div>

      {error && <div className="banner banner-error">{error}</div>}

      {schema !== null && (
        <div className="card">
          <div className="card-title">Effective Schema — {path}</div>
          <div className="json-viewer">
            <JsonViewer value={schema} initialDepth={4} />
          </div>
        </div>
      )}
    </>
  );
}
