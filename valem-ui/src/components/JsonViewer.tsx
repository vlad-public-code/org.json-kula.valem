import { useState } from 'react';

interface Props {
  value: unknown;
  depth?: number;
  initialDepth?: number;
  schemas?: Record<string, Record<string, unknown>>;
}

export default function JsonViewer({ value, depth = 0, initialDepth = 2, schemas = {} }: Props) {
  return <JsonValue value={value} depth={depth} initialDepth={initialDepth} schemas={schemas} path="$" />;
}

interface InternalProps {
  value: unknown;
  depth: number;
  initialDepth: number;
  schemas: Record<string, Record<string, unknown>>;
  path?: string;
}

function JsonValue({ value, depth, initialDepth, schemas, path }: InternalProps) {
  if (value === null) return <span className="json-null">null</span>;
  if (value === undefined) return <span className="json-null">undefined</span>;
  if (typeof value === 'boolean') return <span className="json-bool">{String(value)}</span>;
  if (typeof value === 'number') return <span className="json-num">{value}</span>;
  if (typeof value === 'string') return <span className="json-str">"{value}"</span>;
  if (Array.isArray(value)) return <JsonArray value={value} depth={depth} initialDepth={initialDepth} schemas={schemas} />;
  if (typeof value === 'object') return <JsonObject value={value as Record<string, unknown>} depth={depth} initialDepth={initialDepth} schemas={schemas} path={path} />;
  return <span>{String(value)}</span>;
}

function MetaBadges({ schema }: { schema: Record<string, unknown> }) {
  const items: string[] = [];
  if ('x-valem-relevant' in schema) {
    items.push(schema['x-valem-relevant'] ? 'relevant' : 'not relevant');
  }
  if (schema.readOnly === true) {
    items.push('read-only');
  }
  if (items.length === 0) return null;
  return (
    <>
      {items.map(item => (
        <span key={item} style={{ color: 'var(--text-muted)', fontSize: '0.8em', marginLeft: 6 }}>
          [{item}]
        </span>
      ))}
    </>
  );
}

function JsonObject({ value, depth, initialDepth, schemas, path }: {
  value: Record<string, unknown>; depth: number; initialDepth: number;
  schemas: Record<string, Record<string, unknown>>; path?: string;
}) {
  const [open, setOpen] = useState(depth < initialDepth);
  const keys = Object.keys(value);

  if (keys.length === 0) return <span style={{ color: 'var(--text-muted)' }}>{'{}'}</span>;

  return (
    <>
      <button className="json-toggle" onClick={() => setOpen(o => !o)}>{open ? '▾' : '▸'}</button>
      {!open ? (
        <span className="json-collapsed"> {'{'}{keys.length} key{keys.length !== 1 ? 's' : ''}{'}'}</span>
      ) : (
        <div className="json-indent">
          {keys.map(k => {
            const childPath = path ? `${path}.${k}` : undefined;
            const schema = childPath ? schemas[childPath] : undefined;
            return (
              <div key={k}>
                <span className="json-key">"{k}"</span>
                <span className="json-colon">: </span>
                <JsonValue value={value[k]} depth={depth + 1} initialDepth={initialDepth} schemas={schemas} path={childPath} />
                {schema && <MetaBadges schema={schema} />}
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}

function JsonArray({ value, depth, initialDepth, schemas }: {
  value: unknown[]; depth: number; initialDepth: number;
  schemas: Record<string, Record<string, unknown>>;
}) {
  const [open, setOpen] = useState(depth < initialDepth);

  if (value.length === 0) return <span style={{ color: 'var(--text-muted)' }}>[]</span>;

  return (
    <>
      <button className="json-toggle" onClick={() => setOpen(o => !o)}>{open ? '▾' : '▸'}</button>
      {!open ? (
        <span className="json-collapsed"> [{value.length} item{value.length !== 1 ? 's' : ''}]</span>
      ) : (
        <div className="json-indent">
          {value.map((v, i) => (
            <div key={i}>
              <span className="json-colon">{i}: </span>
              <JsonValue value={v} depth={depth + 1} initialDepth={initialDepth} schemas={schemas} />
            </div>
          ))}
        </div>
      )}
    </>
  );
}
