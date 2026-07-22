import type { CSSProperties } from 'react';
import { useViewContext } from '../ViewContext';
import { useDeferredMutate } from '../hooks/useDeferredMutate';
import { currencySymbol } from '../format';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';

/**
 * `numericField`, `currencyField` and `percentField`.
 *
 * The three differ only in the adornment beside the box: the input stays `type="number"` and the
 * value written back is always the plain number the user typed. Formatting the box contents while
 * editing would mean parsing a formatted string back out on every keystroke, which is where
 * currency inputs usually start eating decimal separators.
 */
export function NumericField({ component: c, enabled, readOnly, required }: BaseComponentProps<BasicInputSpec>) {
  const { state } = useViewContext();
  const { draft, schedule, handleBlur } = useDeferredMutate(c.bind, state);

  const format = c.format ?? defaultFormat(c.type);
  const prefix = format === 'currency' ? currencySymbol(c.currency) : undefined;
  const suffix = format === 'percent' ? '%' : undefined;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      <div style={{
        display: 'flex',
        alignItems: 'stretch',
        border: '1px solid #ccc',
        borderRadius: 4,
        background: readOnly ? '#f5f5f5' : '#fff',
        overflow: 'hidden',
      }}>
        {prefix && <span data-testid={`${c.id}-prefix`} style={adornment}>{prefix}</span>}
        <input
          id={c.id}
          type="number"
          value={draft}
          placeholder={c.placeholder ?? ''}
          disabled={!enabled}
          readOnly={readOnly}
          style={{
            flex: 1,
            minWidth: 0,
            padding: '6px 10px',
            border: 'none',
            outline: 'none',
            fontSize: 14,
            background: 'transparent',
          }}
          onChange={e => {
            if (!readOnly) {
              const n = parseFloat(e.target.value);
              schedule(e.target.value, isNaN(n) ? null : n);
            }
          }}
          onBlur={handleBlur}
        />
        {suffix && <span data-testid={`${c.id}-suffix`} style={adornment}>{suffix}</span>}
      </div>
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}

/**
 * Mirrors `ViewEvaluator.defaultFormat`. Needed because this renderer's primary path takes the
 * *raw* ViewDefinition, not the server-evaluated view — so the type-driven default has to exist
 * on both sides or a `currencyField` would show a symbol through one path and not the other.
 */
function defaultFormat(type: string): string | undefined {
  if (type === 'currencyField') return 'currency';
  if (type === 'percentField') return 'percent';
  return undefined;
}

const adornment: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  padding: '0 8px',
  fontSize: 13,
  color: '#6b7280',
  background: '#f9fafb',
};
