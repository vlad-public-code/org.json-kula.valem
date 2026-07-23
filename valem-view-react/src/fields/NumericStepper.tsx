import { useEffect, useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { SliderSpec } from '../types';

/** `numericStepper` — a bounded number with −/+ controls, for quantities. */
export function NumericStepper(
  { component: c, enabled, readOnly, required }: BaseComponentProps<SliderSpec>,
) {
  const { state, onMutate } = useViewContext();

  const bindPath = c.bind?.replace(/^\$\./, '');
  const raw = bindPath ? getByPath(state, bindPath) : undefined;
  const value = typeof raw === 'number' ? raw : parseFloat(String(raw)) || 0;

  const min = c.min ?? 0;
  const max = c.max ?? Number.MAX_SAFE_INTEGER;
  const step = c.step ?? 1;
  const interactive = enabled && !readOnly;

  // A local draft so typed digits stick. A fully state-controlled number input snaps back to the
  // committed value on every keystroke until the mutation round-trips — the field visibly fighting
  // the user mid-type — and it makes any fill-then-read interaction racy. The draft holds what is
  // being typed; it re-syncs from the model whenever the user is not editing.
  const [draft, setDraft] = useState<string>(String(value));
  const [editing, setEditing] = useState(false);
  useEffect(() => { if (!editing) setDraft(String(value)); }, [value, editing]);

  const commit = (next: number) => {
    const safe = Number.isFinite(next) ? next : value; // empty / half-typed input reverts
    const clamped = Math.min(Math.max(safe, min), max);
    // Floating-point steps accumulate error (0.1 + 0.2), and the stored value is the model's —
    // rounding to the step's precision keeps 0.30000000000000004 out of the document.
    const decimals = (String(step).split('.')[1] ?? '').length;
    const rounded = Number(clamped.toFixed(decimals));
    setEditing(false);
    setDraft(String(rounded));
    if (c.bind && interactive && rounded !== value) onMutate({ [c.bind]: rounded });
  };

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      <div style={{ display: 'inline-flex', alignItems: 'stretch', border: '1px solid #d1d5db', borderRadius: 6, overflow: 'hidden', width: 'fit-content' }}>
        <StepButton
          label="−"
          testId={`${c.id}-decrement`}
          disabled={!interactive || value <= min}
          onClick={() => commit(value - step)}
        />
        <input
          id={c.id}
          type="number"
          value={draft}
          min={min}
          max={max === Number.MAX_SAFE_INTEGER ? undefined : max}
          step={step}
          disabled={!enabled}
          readOnly={readOnly}
          onChange={e => { setEditing(true); setDraft(e.target.value); }}
          onBlur={() => commit(parseFloat(draft))}
          onKeyDown={e => { if (e.key === 'Enter') commit(parseFloat(draft)); }}
          style={{
            width: 64,
            border: 'none',
            borderLeft: '1px solid #d1d5db',
            borderRight: '1px solid #d1d5db',
            textAlign: 'center',
            fontSize: 14,
            padding: '6px 4px',
            background: readOnly ? '#f5f5f5' : '#fff',
          }}
        />
        <StepButton
          label="+"
          testId={`${c.id}-increment`}
          disabled={!interactive || value >= max}
          onClick={() => commit(value + step)}
        />
      </div>
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}

function StepButton(
  { label, testId, disabled, onClick }:
  { label: string; testId: string; disabled: boolean; onClick: () => void },
) {
  return (
    <button
      type="button"
      aria-label={label === '+' ? 'Increment' : 'Decrement'}
      data-testid={testId}
      disabled={disabled}
      onClick={onClick}
      style={{
        padding: '6px 12px',
        border: 'none',
        background: disabled ? '#f9fafb' : '#fff',
        color: disabled ? '#d1d5db' : '#374151',
        fontSize: 16,
        lineHeight: 1,
        cursor: disabled ? 'not-allowed' : 'pointer',
      }}
    >
      {label}
    </button>
  );
}
