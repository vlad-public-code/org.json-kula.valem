import type { CSSProperties } from 'react';
import { useViewContext } from '../ViewContext';
import { useJSONataText, useJSONataLiteral } from '../hooks/useJSONata';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BadgeSpec } from '../types';

const VARIANTS: Record<string, { bg: string; fg: string; border: string; icon: string }> = {
  info:      { bg: '#eff6ff', fg: '#1e40af', border: '#bfdbfe', icon: 'ℹ' },
  primary:   { bg: '#eff6ff', fg: '#1e40af', border: '#bfdbfe', icon: 'ℹ' },
  secondary: { bg: '#f9fafb', fg: '#374151', border: '#e5e7eb', icon: 'ℹ' },
  success:   { bg: '#f0fdf4', fg: '#15803d', border: '#bbf7d0', icon: '✓' },
  warning:   { bg: '#fffbeb', fg: '#92400e', border: '#fde68a', icon: '!' },
  danger:    { bg: '#fef2f2', fg: '#b91c1c', border: '#fecaca', icon: '!' },
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
        padding: '10px 14px',
        borderRadius: 6,
        border: `1px solid ${v.border}`,
        background: v.bg,
        color: v.fg,
        fontSize: 13,
      }}
    >
      <span aria-hidden style={iconStyle(v.fg)}>{v.icon}</span>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
        {c.label && <strong style={{ fontSize: 13, fontWeight: 600 }}>{c.label}</strong>}
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
