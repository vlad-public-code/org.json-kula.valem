import { useState, useEffect } from 'react';
import { api } from '../api';
import JsonViewer from './JsonViewer';

interface Props {
  modelId: string;
  specVersion?: number;
}

export default function SpecPanel({ modelId, specVersion }: Props) {
  const [spec, setSpec] = useState<unknown | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setSpec(null);
    setError(null);
    api.getSpec(modelId)
      .then(setSpec)
      .catch(e => setError(String(e)));
  }, [modelId, specVersion]);

  const handleDownload = () => {
    const blob = new Blob([JSON.stringify(spec, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${modelId}-spec.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <>
      {error && <div className="banner banner-error">{error}</div>}

      <div className="btn-row mt-8" style={{ marginBottom: 16 }}>
        <button className="btn btn-sm" disabled={spec === null} onClick={handleDownload}>
          ↓ Download
        </button>
      </div>

      <div className="card">
        <div className="card-title">Model Spec</div>
        <div className="json-viewer">
          {spec !== null
            ? <JsonViewer value={spec} initialDepth={3} />
            : <span style={{ color: 'var(--text-muted)' }}>Loading…</span>}
        </div>
      </div>
    </>
  );
}
