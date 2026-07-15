import { useEffect, useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { OptionSpec } from '../types';

export function SelectField({ component: c, enabled, readOnly, required }: BaseComponentProps) {
  const { state, onMutate } = useViewContext();
  const value = c.bind ? String(getByPath(state, c.bind.replace(/^\$\./, '')) ?? '') : '';
  const [urlOptions, setUrlOptions] = useState<OptionSpec[]>([]);

  useEffect(() => {
    if (!c.optionsUrl) return;
    fetch(c.optionsUrl)
      .then(r => r.json())
      .then((data: unknown) => {
        const arr = (c.optionsPath ? getPath(data, c.optionsPath) : data) as OptionSpec[];
        setUrlOptions(Array.isArray(arr) ? arr : []);
      })
      .catch(() => {});
  }, [c.optionsUrl, c.optionsPath]);

  const options: OptionSpec[] = c.options ?? urlOptions;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      <select
        id={c.id}
        value={value}
        disabled={!enabled || readOnly}
        style={{
          padding: '6px 10px',
          border: '1px solid #ccc',
          borderRadius: 4,
          fontSize: 14,
          background: readOnly ? '#f5f5f5' : '#fff',
        }}
        onChange={e => {
          if (c.bind && !readOnly) {
            onMutate({ [c.bind]: e.target.value });
          }
        }}
      >
        <option value="">{c.placeholder ?? '— select —'}</option>
        {options.map(o => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}

function getPath(obj: unknown, path: string): unknown {
  return path.split('.').reduce((cur, k) => (cur as Record<string, unknown>)?.[k], obj);
}
