import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { useJSONata, useJSONataLiteral, useJSONataText } from '../hooks/useJSONata';
import { useFlashOnChange } from '../hooks/useFlashOnChange';
import { formatValue } from '../format';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { StatTileSpec } from '../types';
import { fontSans, ink, muted, panel, radius, rule, signal, tone } from '../themeColors';

// Trend colours read from the design tokens, with literal fallbacks for standalone use.
const TRENDS: Record<string, { glyph: string; color: string }> = {
  up:   { glyph: '▲', color: tone.successText() },
  down: { glyph: '▼', color: tone.dangerText() },
  flat: { glyph: '▬', color: muted() },
};

/**
 * `statTile` / `metric` — one headline number with its supporting text.
 *
 * Styled as the reactive instrument's readout: an emerald "computed" edge, monospace tabular
 * numerals, and the emerald flash (via {@link useFlashOnChange}) when the number recomputes — so a
 * derived total reads as the model's live answer, not body copy. Consumes the shared design tokens
 * (`--signal`, `--panel-bg`, `--text`, …) and falls back to literals when they're absent.
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

  // The flash fades back to the tile's own panel colour, whichever theme painted it.
  const flashRef = useFlashOnChange<HTMLDivElement>(value, panel());

  return (
    <div
      ref={flashRef}
      data-testid={c.id}
      title={c.tooltip}
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 4,
        padding: '11px 14px',
        border: `1px solid ${rule()}`,
        borderLeft: `3px solid ${signal()}`,
        borderRadius: radius.md,
        background: panel(),
        minWidth: 140,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {c.icon && <span aria-hidden style={{ fontSize: 14 }}>{c.icon}</span>}
        {c.label && (
          <span style={{ fontSize: 12, fontWeight: 600, color: muted() }}>
            {c.label}
          </span>
        )}
      </div>

      <span
        data-testid={`${c.id}-value`}
        style={{
          // The body face with tabular digits. --font-numeric lets a host choose another readout face
          // (a monospace one, say) without touching code blocks, which keep --font-mono.
          fontFamily: `var(--font-numeric, ${fontSans})`,
          fontVariantNumeric: 'tabular-nums',
          fontSize: 26,
          fontWeight: 650,
          letterSpacing: '-0.01em',
          color: ink(),
          lineHeight: 1.1,
          marginTop: 2,
        }}
      >
        {formatValue(value, c.format, c.currency) || '—'}
      </span>

      {delta && (
        <span
          data-testid={`${c.id}-delta`}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12,
            fontVariantNumeric: 'tabular-nums', color: trend?.color ?? muted(),
          }}
        >
          {trend && <span aria-hidden>{trend.glyph}</span>}
          {delta}
        </span>
      )}

      {caption && <span style={{ fontSize: 12, color: muted() }}>{caption}</span>}
    </div>
  );
}
