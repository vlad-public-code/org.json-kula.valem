import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ProgressBarSpec } from '../types';

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
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}>
          {c.label && <span style={{ fontWeight: 500 }}>{c.label}</span>}
          {showValue && <span style={{ color: '#6b7280' }}>{displayLabel}</span>}
        </div>
      )}
      <div style={{
        position: 'relative',
        height: 8,
        background: '#e5e7eb',
        borderRadius: 4,
        overflow: 'hidden',
      }}>
        <div style={{
          position: 'absolute',
          left: 0,
          top: 0,
          bottom: 0,
          width: `${ratio * 100}%`,
          background: '#2563eb',
          borderRadius: 4,
          transition: 'width 0.2s ease',
        }} />
      </div>
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}
