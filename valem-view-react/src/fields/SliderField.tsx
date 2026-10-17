import { useViewContext } from '../ViewContext';
import { useDeferredMutate } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { SliderSpec } from '../types';
import { accentFill, helperText, ink, labelText, requiredMark } from '../themeColors';

export function SliderField({ component: c, enabled, readOnly, required }: BaseComponentProps<SliderSpec>) {
  const { state } = useViewContext();
  const { draft, schedule, handleBlur, commit } = useDeferredMutate(c.bind, state);

  const min = c.min ?? 0;
  const max = c.max ?? 100;
  const step = c.step ?? 1;
  const numVal = parseFloat(draft) || min;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <input
          id={c.id}
          type="range"
          min={min}
          max={max}
          step={step}
          value={numVal}
          disabled={!enabled || readOnly}
          style={{ flex: 1, accentColor: accentFill(), cursor: readOnly ? 'not-allowed' : 'pointer' }}
          onChange={e => {
            if (!readOnly) {
              const n = parseFloat(e.target.value);
              schedule(e.target.value, n);
            }
          }}
          // Dragging fires onChange per pixel, so the drag only schedules; releasing the thumb is the
          // real "I meant this value" signal and commits immediately, so dependent derivations
          // recompute without the user having to move focus away. Pointer events cover mouse, touch
          // and pen; onKeyUp covers arrow-key nudges; onBlur remains the backstop.
          onPointerUp={commit}
          onKeyUp={commit}
          onBlur={handleBlur}
        />
        <span style={{ fontSize: 14, fontWeight: 600, color: ink(), minWidth: 36, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
          {numVal}
        </span>
      </div>
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
