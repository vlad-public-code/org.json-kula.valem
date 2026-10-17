import type { CSSProperties } from 'react';
import { useViewContext } from '../ViewContext';
import { useJSONataText, useJSONataLiteral } from '../hooks/useJSONata';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BadgeSpec } from '../types';
import { accent, ink, muted, radius, rule, surface, tone } from '../themeColors';

// Theme tokens with the previous literals as fallbacks (see themeColors.ts). Under valem-ui the text
// is the page ink rather than a dark tint, because a dark-blue or dark-red body on the dark theme's
// soft surfaces was unreadable; the severity stays in the surface, the border and the icon.
const VARIANTS: Record<string, { bg: string; fg: string; border: string; mark: string; icon: string }> = {
  info:      { bg: tone.infoBg(),    fg: ink(), border: tone.infoLine(),    mark: accent(),           icon: 'i' },
  primary:   { bg: tone.infoBg(),    fg: ink(), border: tone.infoLine(),    mark: accent(),           icon: 'i' },
  secondary: { bg: surface(),        fg: ink(), border: rule(),             mark: muted(),            icon: 'i' },
  success:   { bg: tone.successBg(), fg: ink(), border: tone.successLine(), mark: tone.successText(), icon: '✓' },
  warning:   { bg: tone.warningBg(), fg: ink(), border: tone.warningLine(), mark: tone.warningText(), icon: '!' },
  danger:    { bg: tone.dangerBg(),  fg: ink(), border: tone.dangerLine(),  mark: tone.dangerText(),  icon: '!' },
};

/**
 * `alert` and `callout` — the block-level spelling of `badge`.
 *
 * `label` is the heading and `text` the body. The severity is announced, not just coloured:
 * `danger` and `warning` render as `role="alert"` so a screen reader is told a form has a
 * problem, which colour alone never does.
 */
export function Alert({ component: c, text }: BaseComponentProps<BadgeSpec>) {
  const { state } = useViewContext();
  const rawText = typeof c.text === 'string' ? c.text : undefined;
  const body = useJSONataText(rawText, state) ?? text;

  const rawVariant = typeof c.variant === 'string' ? c.variant : undefined;
  const variantName = useJSONataLiteral(rawVariant, state) ?? 'info';
  const v = VARIANTS[variantName] ?? VARIANTS.info;
  const urgent = variantName === 'danger' || variantName === 'warning';

  return (
    <div
      data-testid={c.id}
      role={urgent ? 'alert' : 'note'}
      style={{
        display: 'flex',
        gap: 10,
        padding: '12px 14px',
        borderRadius: radius.md,
        border: `1px solid ${v.border}`,
        borderLeft: `4px solid ${v.border}`,
        background: v.bg,
        color: v.fg,
        fontSize: 14,
      }}
    >
      <span aria-hidden style={iconStyle(v.mark)}>{v.icon}</span>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
        {c.label && <strong style={{ fontSize: 14, fontWeight: 650 }}>{c.label}</strong>}
        {body && <span style={{ lineHeight: 1.45 }}>{body}</span>}
      </div>
    </div>
  );
}

function iconStyle(color: string): CSSProperties {
  return {
    flexShrink: 0,
    width: 18,
    height: 18,
    borderRadius: '50%',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 12,
    fontWeight: 700,
    border: `1px solid ${color}`,
    lineHeight: 1,
  };
}
