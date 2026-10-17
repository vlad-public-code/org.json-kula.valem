import { useEffect, useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { fieldColors } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ChoiceInputSpec, OptionSpec } from '../types';
import { controlBox, helperText, labelText, requiredMark } from '../themeColors';

export function SelectField({ component: c, enabled, readOnly, required }: BaseComponentProps<ChoiceInputSpec>) {
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
        <label htmlFor={c.id} style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
      <select
        id={c.id}
        value={value}
        disabled={!enabled || readOnly}
        style={{
          ...controlBox,
          background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg,
          color: fieldColors.text,
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
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}

function getPath(obj: unknown, path: string): unknown {
  return path.split('.').reduce((cur, k) => (cur as Record<string, unknown>)?.[k], obj);
}
