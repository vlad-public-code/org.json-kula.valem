import { useState } from 'react';
import { api, ApiError } from '../api';

interface Example {
  id: string;
  name: string;
  description: string;
  spec: Record<string, unknown>;
}

type RawExample = Record<string, unknown> & { _name?: string; _description?: string; id: string };

const rawModules = import.meta.glob<RawExample>('../examples/*.json', { eager: true, import: 'default' });

function kebabToTitle(s: string) {
  return s.split('-').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

const EXAMPLES: Example[] = Object.values(rawModules)
  .map(raw => ({
    id: raw.id,
    name: raw._name ?? kebabToTitle(raw.id),
    description: raw._description ?? '',
    spec: Object.fromEntries(Object.entries(raw).filter(([k]) => !k.startsWith('_')))
  }))
  .sort((a, b) => a.id.localeCompare(b.id));

interface Props {
  onCreated: (id: string) => void;
  onCancel: () => void;
}

export default function CreatePanel({ onCreated, onCancel }: Props) {
  const [selectedExample, setSelectedExample] = useState<string>(EXAMPLES[0]?.id ?? '');
  const [input, setInput] = useState(JSON.stringify(EXAMPLES[0]?.spec ?? {}, null, 2));
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSelectExample = (ex: Example) => {
    setSelectedExample(ex.id);
    setInput(JSON.stringify(ex.spec, null, 2));
    setError(null);
  };

  const handleCreate = async () => {
    setError(null);
    setLoading(true);
    try {
      const spec = JSON.parse(input) as unknown;
      const res = await api.createModel(spec);
      onCreated(res.id);
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.body);
      } else {
        setError(String(e));
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ padding: '12px 20px', background: 'var(--panel-bg)', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>New Model</span>
        <button className="btn btn-sm" onClick={onCancel} style={{ marginLeft: 'auto' }}>Cancel</button>
        <button className="btn btn-primary btn-sm" onClick={handleCreate} disabled={loading}>
          {loading ? 'Creating…' : 'Create Model'}
        </button>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 16 }}>
          Choose a starter example or write your own spec JSON below. The <code>id</code> field determines the model's URL path.
        </p>

        {EXAMPLES.length > 0 && (
          <div className="card" style={{ marginBottom: 16 }}>
            <div className="card-title">Examples</div>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              {EXAMPLES.map(ex => (
                <button
                  key={ex.id}
                  onClick={() => handleSelectExample(ex)}
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'flex-start',
                    gap: 3,
                    padding: '8px 12px',
                    border: `1px solid ${selectedExample === ex.id ? 'var(--blue)' : 'var(--border)'}`,
                    borderRadius: 6,
                    background: selectedExample === ex.id ? 'var(--blue-light)' : 'var(--panel-bg)',
                    cursor: 'pointer',
                    textAlign: 'left',
                    minWidth: 180,
                    maxWidth: 260,
                    transition: 'border-color 0.15s'
                  }}
                >
                  <span style={{ fontWeight: 600, fontSize: 13, color: selectedExample === ex.id ? 'var(--blue)' : 'var(--text)' }}>
                    {ex.name}
                  </span>
                  {ex.description && (
                    <span style={{ fontSize: 11, color: 'var(--text-muted)', lineHeight: 1.4 }}>
                      {ex.description}
                    </span>
                  )}
                </button>
              ))}
            </div>
          </div>
        )}

        {error && <div className="banner banner-error" style={{ marginBottom: 12 }}>{error}</div>}

        <div className="card">
          <div className="card-title">Model Spec JSON</div>
          <textarea
            value={input}
            onChange={e => { setInput(e.target.value); setSelectedExample(''); }}
            rows={30}
            spellCheck={false}
            style={{ minHeight: 500 }}
          />
        </div>

        <div className="card">
          <div className="card-title">Spec Structure</div>
          <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                <th style={{ textAlign: 'left', padding: '4px 8px', color: 'var(--text-muted)' }}>Field</th>
                <th style={{ textAlign: 'left', padding: '4px 8px', color: 'var(--text-muted)' }}>Type</th>
                <th style={{ textAlign: 'left', padding: '4px 8px', color: 'var(--text-muted)' }}>Description</th>
              </tr>
            </thead>
            <tbody>
              {SPEC_FIELDS.map(f => (
                <tr key={f.field} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '5px 8px', fontFamily: 'var(--font-mono)' }}>{f.field}</td>
                  <td style={{ padding: '5px 8px', color: 'var(--text-muted)' }}>{f.type}</td>
                  <td style={{ padding: '5px 8px', color: 'var(--text-light)' }}>{f.desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

const SPEC_FIELDS = [
  { field: 'id',                type: 'string (required)', desc: 'Unique model identifier. Used in all API paths.' },
  { field: 'version',           type: 'string',            desc: 'Semantic version string (e.g. 1.0.0).' },
  { field: 'schema',            type: 'object',            desc: 'JSON Schema for the base document. Decorative at runtime.' },
  { field: 'derivations[]',     type: 'array',             desc: 'path ($.x.y) + expr (JSONata). Computed from base fields only.' },
  { field: 'constraints[]',     type: 'array',             desc: 'id + expr + policy (ROLLBACK | FLAG). May include path for scoped checks.' },
  { field: 'effects[]',         type: 'array',             desc: 'id + executor (caller/server) + trigger + emit/payload (caller) or request/response (server).' },
  { field: 'metaDerivations[]', type: 'array',             desc: 'path + property (MINIMUM, MAXIMUM, READ_ONLY, …) + expr.' },
  { field: 'tests[]',           type: 'array',             desc: 'Declarative test cases (parsed but not yet executed).' },
];
