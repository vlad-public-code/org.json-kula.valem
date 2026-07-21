import { useViewContext } from '../ViewContext';
import { useDeferredMutate } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';

export function SliderField({ component: c, enabled, readOnly, required }: BaseComponentProps) {
  const { state } = useViewContext();
  const { draft, schedule, handleBlur, commit } = useDeferredMutate(c.bind, state);

  const min = c.min ?? 0;
  const max = c.max ?? 100;
  const step = c.step ?? 1;
  const numVal = parseFloat(draft) || min;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
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
          style={{ flex: 1, cursor: readOnly ? 'not-allowed' : 'pointer' }}
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
        <span style={{ fontSize: 13, fontWeight: 500, minWidth: 36, textAlign: 'right' }}>
          {numVal}
        </span>
      </div>
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}
