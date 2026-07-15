import { useState, useEffect, useCallback } from 'react';
import { api } from '../api';
import type { ModelInfo, Snapshot } from '../types';
import JsonViewer from './JsonViewer';

interface Props {
  modelId: string;
  onDeleted: () => void;
}

function extractPaths(obj: Record<string, unknown>, prefix = '$'): string[] {
  const paths: string[] = [];
  for (const [k, v] of Object.entries(obj)) {
    const p = `${prefix}.${k}`;
    paths.push(p);
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
      paths.push(...extractPaths(v as Record<string, unknown>, p));
    }
  }
  return paths;
}

export default function StatePanel({ modelId, onDeleted }: Props) {
  const [info, setInfo] = useState<ModelInfo | null>(null);
  const [state, setState] = useState<Record<string, unknown> | null>(null);
  const [schemas, setSchemas] = useState<Record<string, Record<string, unknown>>>({});
  const [error, setError] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [restoreJson, setRestoreJson] = useState('');
  const [msg, setMsg] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [i, s] = await Promise.all([
        api.getModel(modelId),
        api.getState(modelId),
      ]);
      setInfo(i);
      setState(s);
      setError(null);
    } catch (e) {
      setError(String(e));
    }
  }, [modelId]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!state || !info || info.metaDerivationCount === 0) {
      setSchemas({});
      return;
    }
    const paths = extractPaths(state);
    Promise.allSettled(
      paths.map(p => api.effectiveSchema(modelId, p).then(s => [p, s] as const))
    ).then(results => {
      const map: Record<string, Record<string, unknown>> = {};
      for (const r of results) {
        if (r.status === 'fulfilled') {
          const [p, s] = r.value;
          map[p] = s;
        }
      }
      setSchemas(map);
    });
  }, [state, info, modelId]);

  const handleSnapshot = async () => {
    try {
      const snap = await api.snapshot(modelId);
      setSnapshot(snap);
      setRestoreJson(JSON.stringify(snap, null, 2));
      setMsg('Snapshot taken.');
    } catch (e) { setMsg(String(e)); }
  };

  const handleRestore = async () => {
    try {
      const snap = JSON.parse(restoreJson) as Snapshot;
      await api.restore(modelId, snap);
      setMsg('Restored.');
      load();
    } catch (e) { setMsg(String(e)); }
  };

  const handleDownloadSpec = async () => {
    try {
      const spec = await api.getSpec(modelId);
      const blob = new Blob([JSON.stringify(spec, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${modelId}-spec.json`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) { setMsg(String(e)); }
  };

  const handleDelete = async () => {
    if (!confirm(`Delete model "${modelId}"?`)) return;
    try {
      await api.deleteModel(modelId);
      onDeleted();
    } catch (e) { setMsg(String(e)); }
  };

  return (
    <>
      {info && (
        <div className="info-grid">
          <div className="info-cell">
            <div className="info-cell-label">Version</div>
            <div className="info-cell-value" style={{ fontSize: 15, marginTop: 4, fontFamily: 'var(--font-mono)' }}>
              {info.version || '—'}
            </div>
          </div>
          <div className="info-cell">
            <div className="info-cell-label">Derivations</div>
            <div className="info-cell-value">{info.derivationCount}</div>
          </div>
          <div className="info-cell">
            <div className="info-cell-label">Constraints</div>
            <div className="info-cell-value">{info.constraintCount}</div>
          </div>
          <div className="info-cell">
            <div className="info-cell-label">Effects</div>
            <div className="info-cell-value">{info.effectCount}</div>
          </div>
          <div className="info-cell">
            <div className="info-cell-label">Meta derivations</div>
            <div className="info-cell-value">{info.metaDerivationCount}</div>
          </div>
        </div>
      )}

      {msg && <div className={`banner ${msg.startsWith('HTTP') ? 'banner-error' : 'banner-success'}`}>{msg}</div>}
      {error && <div className="banner banner-error">{error}</div>}

      <div className="btn-row mt-8" style={{ marginBottom: 16 }}>
        <button className="btn btn-sm" onClick={load}>↻ Refresh</button>
        <button className="btn btn-sm" onClick={handleSnapshot}>Take Snapshot</button>
        <button className="btn btn-sm" onClick={handleDownloadSpec}>↓ Download Spec</button>
        <button className="btn btn-danger btn-sm" style={{ marginLeft: 'auto' }} onClick={handleDelete}>
          Delete Model
        </button>
      </div>

      <div className="card">
        <div className="card-title">Merged State</div>
        <div className="json-viewer">
          {state !== null ? <JsonViewer value={state} initialDepth={3} schemas={schemas} /> : <span style={{ color: 'var(--text-muted)' }}>Loading…</span>}
        </div>
      </div>

      {snapshot !== null && (
        <div className="card">
          <div className="card-title">Snapshot / Restore</div>
          <label className="field-label">Snapshot JSON (edit to restore from a previous one)</label>
          <textarea
            value={restoreJson}
            onChange={e => setRestoreJson(e.target.value)}
            rows={10}
          />
          <div className="btn-row mt-8">
            <button className="btn btn-primary btn-sm" onClick={handleRestore}>Restore</button>
          </div>
        </div>
      )}
    </>
  );
}
