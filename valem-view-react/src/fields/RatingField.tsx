import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { SliderSpec } from '../types';
import { faint, helperText, labelText, requiredMark, tone } from '../themeColors';

/**
 * `ratingField` — a slider drawn as stars.
 *
 * Unlike the slider it commits on click rather than deferring: there is no drag to wait out, so
 * the click is unambiguously the chosen value and dependent derivations should recompute at once.
 */
export function RatingField(
  { component: c, enabled, readOnly, required }: BaseComponentProps<SliderSpec>,
) {
  const { state, onMutate } = useViewContext();
  const [hover, setHover] = useState<number | null>(null);

  const bindPath = c.bind?.replace(/^\$\./, '');
  const raw = bindPath ? getByPath(state, bindPath) : undefined;
  const value = typeof raw === 'number' ? raw : parseFloat(String(raw)) || 0;

  const min = c.min ?? 1;
  const max = c.max ?? 5;
  const step = c.step ?? 1;
  const stops: number[] = [];
  for (let v = min; v <= max; v += step) stops.push(v);

  const shown = hover ?? value;
  const interactive = enabled && !readOnly;

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <span style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </span>
      )}
      <div
        role="radiogroup"
        aria-label={c.label ?? c.id}
        onMouseLeave={() => setHover(null)}
        style={{ display: 'flex', alignItems: 'center', gap: 2 }}
      >
        {stops.map(v => (
          <button
            key={v}
            type="button"
            role="radio"
            aria-checked={value === v}
            aria-label={String(v)}
            data-testid={`${c.id}-star-${v}`}
            disabled={!interactive}
            onMouseEnter={() => interactive && setHover(v)}
            onClick={() => { if (interactive && c.bind) onMutate({ [c.bind]: v }); }}
            style={{
              border: 'none',
              background: 'transparent',
              padding: 0,
              fontSize: 22,
              lineHeight: 1,
              cursor: interactive ? 'pointer' : 'default',
              // Graphics, so 3:1 (WCAG 1.4.11) rather than text contrast: the status amber for a
              // chosen star, the decorative grey for an empty one. The button's aria-label names it.
              color: v <= shown ? tone.warningLine() : faint(),
              transition: 'color 0.1s',
            }}
          >
            <span aria-hidden="true">★</span>
          </button>
        ))}
        <span style={{ ...helperText, marginLeft: 6, fontVariantNumeric: 'tabular-nums' }}>
          {value ? `${value} / ${max}` : ''}
        </span>
      </div>
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
