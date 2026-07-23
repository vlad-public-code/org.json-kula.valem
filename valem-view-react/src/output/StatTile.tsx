import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { useJSONata, useJSONataLiteral, useJSONataText } from '../hooks/useJSONata';
import { formatValue } from '../format';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { StatTileSpec } from '../types';

const TRENDS: Record<string, { glyph: string; color: string }> = {
  up:   { glyph: '▲', color: '#15803d' },
  down: { glyph: '▼', color: '#b91c1c' },
  flat: { glyph: '▬', color: '#6b7280' },
};

/**
 * `statTile` / `metric` — one headline number with its supporting text.
 *
 * `trend` colours the delta and is authored separately from the delta's sign on purpose: rising
 * spend is bad news and rising savings is good, and only the spec knows which this is. With no
 * `trend` the delta is shown in neutral grey rather than guessed at.
 */
export function StatTile({ component: c }: BaseComponentProps<StatTileSpec>) {
  const { state } = useViewContext();

  const bindPath = c.bind?.replace(/^\$\./, '');
  const boundValue = bindPath ? getByPath(state, bindPath) : undefined;
  const exprValue = useJSONata(c.bind ? undefined : c.value, state);
  const value = c.bind ? boundValue : exprValue;

  const delta = useJSONataText(c.delta, state);
  const caption = useJSONataText(c.caption, state);
  const trendName = useJSONataLiteral(c.trend, state);
  const trend = trendName ? TRENDS[trendName] : undefined;

  return (
    <div
      data-testid={c.id}
      title={c.tooltip}
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 4,
        padding: '12px 16px',
        border: '1px solid #e5e7eb',
        borderRadius: 8,
        background: '#fff',
        minWidth: 140,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {c.icon && <span aria-hidden style={{ fontSize: 14 }}>{c.icon}</span>}
        {c.label && (
          <span style={{ fontSize: 12, color: '#6b7280', textTransform: 'uppercase', letterSpacing: 0.4 }}>
            {c.label}
          </span>
        )}
      </div>

      <span data-testid={`${c.id}-value`} style={{ fontSize: 24, fontWeight: 700, color: '#111827', lineHeight: 1.15 }}>
        {formatValue(value, c.format, c.currency) || '—'}
      </span>

      {delta && (
        <span
          data-testid={`${c.id}-delta`}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, color: trend?.color ?? '#6b7280' }}
        >
          {trend && <span aria-hidden>{trend.glyph}</span>}
          {delta}
        </span>
      )}

      {caption && <span style={{ fontSize: 11, color: '#9ca3af' }}>{caption}</span>}
    </div>
  );
}
