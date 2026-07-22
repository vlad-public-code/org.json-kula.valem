import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { DateRangeSpec } from '../types';

/**
 * `dateRangeField` — two coupled dates written to `bindFrom` and `bindTo`.
 *
 * Each end mutates independently rather than the pair being sent together: they are separate
 * model fields with their own derivations, and batching them would hide which one the user
 * actually changed from the audit record.
 *
 * The picker's own `min`/`max` follow the other end, so the obvious invalid range is awkward to
 * enter — but the model's constraint is what actually rejects it. This is an affordance, not a
 * rule; a range typed in, pasted, or arriving through the API is still checked server-side.
 */
export function DateRangeField(
  { component: c, enabled, readOnly, required }: BaseComponentProps<DateRangeSpec>,
) {
  const { state, onMutate } = useViewContext();

  const readEnd = (bind?: string) => {
    const path = bind?.replace(/^\$\./, '');
    const raw = path ? getByPath(state, path) : undefined;
    return raw != null ? String(raw) : '';
  };

  const from = readEnd(c.bindFrom);
  const to = readEnd(c.bindTo);
  const interactive = enabled && !readOnly;

  const write = (bind: string | undefined, value: string) => {
    if (bind && interactive) onMutate({ [bind]: value || null });
  };

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <span style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </span>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <End
          id={`${c.id}-from`}
          label={c.fromLabel ?? 'From'}
          value={from}
          min={c.minDate}
          max={to || c.maxDate}
          enabled={enabled}
          readOnly={readOnly}
          onChange={v => write(c.bindFrom, v)}
        />
        <span style={{ color: '#9ca3af' }}>→</span>
        <End
          id={`${c.id}-to`}
          label={c.toLabel ?? 'To'}
          value={to}
          min={from || c.minDate}
          max={c.maxDate}
          enabled={enabled}
          readOnly={readOnly}
          onChange={v => write(c.bindTo, v)}
        />
      </div>
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}

function End(
  { id, label, value, min, max, enabled, readOnly, onChange }: {
    id: string; label: string; value: string;
    min?: string; max?: string;
    enabled: boolean; readOnly: boolean;
    onChange: (v: string) => void;
  },
) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <label htmlFor={id} style={{ fontSize: 11, color: '#6b7280' }}>{label}</label>
      <input
        id={id}
        data-testid={id}
        type="date"
        value={value}
        min={min}
        max={max}
        disabled={!enabled}
        readOnly={readOnly}
        onChange={e => onChange(e.target.value)}
        style={{
          padding: '6px 10px',
          border: '1px solid #ccc',
          borderRadius: 4,
          fontSize: 14,
          background: readOnly ? '#f5f5f5' : '#fff',
        }}
      />
    </div>
  );
}
