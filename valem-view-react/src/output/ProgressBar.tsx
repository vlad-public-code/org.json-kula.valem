import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ProgressBarSpec } from '../types';
import { accentFill, helperText, labelText, muted, ruleStrong } from '../themeColors';

export function ProgressBar({ component: c }: BaseComponentProps<ProgressBarSpec>) {
  const { state } = useViewContext();
  const bindKey = c.bind?.replace(/^\$\./, '');
  const rawValue = bindKey ? getByPath(state, bindKey) : 0;
  const value = typeof rawValue === 'number' ? rawValue : parseFloat(String(rawValue)) || 0;

  const min = c.min ?? 0;
  const max = c.max ?? 100;
  const clamped = Math.min(Math.max(value, min), max);
  const ratio = max > min ? (clamped - min) / (max - min) : 0;
  const showValue = c.showValue !== false;
  const format = c.format ?? 'percent';
  const displayLabel = format === 'value' ? `${clamped} / ${max}` : `${Math.round(ratio * 100)}%`;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {(c.label || showValue) && (
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
          {c.label && <span style={labelText}>{c.label}</span>}
          {showValue && <span style={{ fontSize: 13, color: muted(), fontVariantNumeric: 'tabular-nums' }}>{displayLabel}</span>}
        </div>
      )}
      <div
        role="progressbar"
        aria-valuenow={clamped}
        aria-valuemin={min}
        aria-valuemax={max}
        aria-label={c.label ?? c.id}
        style={{
          position: 'relative',
          height: 8,
          background: ruleStrong(),
          borderRadius: 999,
          overflow: 'hidden',
        }}
      >
        <div style={{
          position: 'absolute',
          left: 0,
          top: 0,
          bottom: 0,
          width: `${ratio * 100}%`,
          background: accentFill(),
          borderRadius: 999,
          transition: 'width 0.2s ease',
        }} />
      </div>
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
