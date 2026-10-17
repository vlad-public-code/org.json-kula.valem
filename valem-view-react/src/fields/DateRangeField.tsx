import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { fieldColors } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { DateRangeSpec } from '../types';
import { controlBox, helperText, labelText, muted, requiredMark } from '../themeColors';

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
        <span style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </span>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
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
        <span aria-hidden="true" style={{ color: muted(), alignSelf: 'flex-end', paddingBottom: 9 }}>→</span>
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
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
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
      <label htmlFor={id} style={{ ...helperText, fontWeight: 500 }}>{label}</label>
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
          ...controlBox,
          background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg,
          color: fieldColors.text,
        }}
      />
    </div>
  );
}
