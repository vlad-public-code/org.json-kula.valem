import { useState, useEffect, useCallback } from 'react';
import { api } from '../api';
import type { LlmInteraction, WebFetchCall } from '../types';

function formatTs(ts: string) {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function WebFetchTable({ calls }: { calls: WebFetchCall[] }) {
  if (calls.length === 0) return null;
  return (
    <div>
      <div className="field-label" style={{ marginBottom: 4 }}>Web Fetches ({calls.length})</div>
      <table style={{ width: '100%', fontSize: 11, borderCollapse: 'collapse', background: 'var(--code-bg, #1a1a1a)', borderRadius: 4 }}>
        <thead>
          <tr style={{ color: 'var(--muted, #888)' }}>
            <th style={{ textAlign: 'left', padding: '4px 8px', fontWeight: 500 }}>URL</th>
            <th style={{ textAlign: 'right', padding: '4px 8px', fontWeight: 500 }}>Code</th>
            <th style={{ textAlign: 'left', padding: '4px 8px', fontWeight: 500 }}>Media Type</th>
            <th style={{ textAlign: 'right', padding: '4px 8px', fontWeight: 500 }}>Raw</th>
            <th style={{ textAlign: 'right', padding: '4px 8px', fontWeight: 500 }}>Extracted</th>
          </tr>
        </thead>
        <tbody>
          {calls.map((wf, i) => {
            const ok = wf.responseCode >= 200 && wf.responseCode < 300;
            const codeColor = wf.responseCode === 0
              ? 'var(--muted, #888)'
              : ok ? 'var(--success-fg, #4caf50)' : 'var(--error-fg, #f44336)';
            return (
              <tr key={i} style={{ borderTop: '1px solid var(--border)' }}>
                <td style={{ padding: '4px 8px', wordBreak: 'break-all', color: 'var(--text, #ccc)' }}>{wf.url}</td>
                <td style={{ padding: '4px 8px', textAlign: 'right', color: codeColor, fontWeight: 600 }}>
                  {wf.responseCode === 0 ? '—' : wf.responseCode}
                </td>
                <td style={{ padding: '4px 8px', color: 'var(--muted, #888)' }}>{wf.mediaType || '—'}</td>
                <td style={{ padding: '4px 8px', textAlign: 'right', color: 'var(--muted, #888)' }}>
                  {wf.rawLength > 0 ? wf.rawLength.toLocaleString() : '—'}
                </td>
                <td style={{ padding: '4px 8px', textAlign: 'right', color: 'var(--muted, #888)' }}>
                  {wf.extractedLength > 0 ? wf.extractedLength.toLocaleString() : '—'}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function InteractionRow({ item }: { item: LlmInteraction }) {
  const [open, setOpen] = useState(false);
  const ok = item.errorMessage == null;
  const fetchCount = item.webFetchCalls?.length ?? 0;

  return (
    <div className="card" style={{ marginBottom: 8, padding: 0, overflow: 'hidden' }}>
      <div
        style={{
          display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px',
          cursor: 'pointer', userSelect: 'none',
        }}
        onClick={() => setOpen(o => !o)}
      >
        <span style={{
          fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 4,
          background: ok ? 'var(--success-bg, #1a3a2a)' : 'var(--error-bg, #3a1a1a)',
          color: ok ? 'var(--success-fg, #4caf50)' : 'var(--error-fg, #f44336)',
        }}>
          {ok ? 'OK' : 'ERR'}
        </span>
        <span style={{ fontSize: 12, color: 'var(--muted, #888)' }}>{formatTs(item.timestamp)}</span>
        <span style={{ fontSize: 12, color: 'var(--muted, #888)' }}>{item.durationMs} ms</span>
        {fetchCount > 0 && (
          <span style={{ fontSize: 11, color: 'var(--muted, #888)', background: 'var(--code-bg, #1a1a1a)', padding: '1px 6px', borderRadius: 4 }}>
            {fetchCount} fetch{fetchCount !== 1 ? 'es' : ''}
          </span>
        )}
        <span style={{ flex: 1, fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--text, #ccc)' }}>
          {item.prompt.slice(0, 120).replace(/\n/g, ' ')}
        </span>
        <span style={{ fontSize: 12, color: 'var(--muted, #888)' }}>{open ? '▲' : '▼'}</span>
      </div>

      {open && (
        <div style={{ borderTop: '1px solid var(--border)', padding: 14, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div className="field-label" style={{ marginBottom: 4 }}>Prompt</div>
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12, color: 'var(--text, #ccc)', background: 'var(--code-bg, #1a1a1a)', padding: 10, borderRadius: 4, maxHeight: 400, overflow: 'auto' }}>
              {item.prompt}
            </pre>
          </div>
          <WebFetchTable calls={item.webFetchCalls ?? []} />
          {ok ? (
            <div>
              <div className="field-label" style={{ marginBottom: 4 }}>Response</div>
              <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12, color: 'var(--text, #ccc)', background: 'var(--code-bg, #1a1a1a)', padding: 10, borderRadius: 4, maxHeight: 400, overflow: 'auto' }}>
                {item.response}
              </pre>
            </div>
          ) : (
            <div>
              <div className="field-label" style={{ marginBottom: 4 }}>Error</div>
              <div className="banner banner-error">{item.errorMessage}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

interface Props {
  onClose: () => void;
}

export default function AiLogPanel({ onClose }: Props) {
  const [interactions, setInteractions] = useState<LlmInteraction[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setInteractions(await api.getLlmInteractions());
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ padding: '12px 20px', background: 'var(--panel-bg)', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>AI Interaction Log</span>
        <span style={{ fontSize: 12, color: 'var(--muted, #888)' }}>{interactions.length} entries (newest first, last 50)</span>
        <button className="btn btn-sm" onClick={load} disabled={loading} style={{ marginLeft: 'auto' }}>
          {loading ? 'Loading…' : '↻ Refresh'}
        </button>
        <button className="btn btn-sm" onClick={onClose}>Close</button>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: 16 }}>
        {error && <div className="banner banner-error" style={{ marginBottom: 12 }}>{error}</div>}
        {!loading && interactions.length === 0 && !error && (
          <div className="empty-state" style={{ paddingTop: 60 }}>
            <div style={{ fontSize: 13, color: 'var(--muted, #888)' }}>No LLM interactions recorded yet.</div>
          </div>
        )}
        {interactions.map((item, i) => (
          <InteractionRow key={i} item={item} />
        ))}
      </div>
    </div>
  );
}
