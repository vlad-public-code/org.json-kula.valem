import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { formatValue } from '../format';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ProgressBarSpec } from '../types';
import { accentFill, helperText, ink, labelText, ruleStrong } from '../themeColors';

const RADIUS = 46;
const CIRCUMFERENCE = Math.PI * RADIUS; // half circle

/** `gauge` — the same bound number as a `progressBar`, drawn as a 180° arc. */
export function Gauge({ component: c }: BaseComponentProps<ProgressBarSpec>) {
  const { state } = useViewContext();
  const bindPath = c.bind?.replace(/^\$\./, '');
  const raw = bindPath ? getByPath(state, bindPath) : 0;
  const value = typeof raw === 'number' ? raw : parseFloat(String(raw)) || 0;

  const min = c.min ?? 0;
  const max = c.max ?? 100;
  const clamped = Math.min(Math.max(value, min), max);
  const ratio = max > min ? (clamped - min) / (max - min) : 0;
  const showValue = c.showValue !== false;

  const display = c.format === 'value'
    ? `${formatValue(clamped, 'number')} / ${formatValue(max, 'number')}`
    : `${Math.round(ratio * 100)}%`;

  return (
    <div
      data-testid={c.id}
      role="meter"
      aria-valuenow={clamped}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-label={c.label ?? c.id}
      style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 }}
    >
      <svg viewBox="0 0 120 66" style={{ width: 140, maxWidth: '100%' }}>
        <path
          d="M 14 60 A 46 46 0 0 1 106 60"
          fill="none"
          style={{ stroke: ruleStrong() }}
          strokeWidth={10}
          strokeLinecap="round"
        />
        <path
          d="M 14 60 A 46 46 0 0 1 106 60"
          fill="none"
          strokeWidth={10}
          strokeLinecap="round"
          strokeDasharray={CIRCUMFERENCE}
          strokeDashoffset={CIRCUMFERENCE * (1 - ratio)}
          style={{ stroke: accentFill(), transition: 'stroke-dashoffset 0.3s ease' }}
        />
        {showValue && (
          <text x="60" y="56" textAnchor="middle" fontSize="16" fontWeight="700" style={{ fill: ink(), fontVariantNumeric: 'tabular-nums' }}>
            {display}
          </text>
        )}
      </svg>
      {c.label && <span style={labelText}>{c.label}</span>}
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
