import type { CSSProperties } from 'react';

/**
 * The renderer's design tokens: every colour, radius and shared control style the components use.
 *
 * Each colour reads the CSS variable valem-ui defines for BOTH themes (`valem-ui/src/index.css`) and
 * falls back to a literal, so a host that renders this library without valem-ui's stylesheet still
 * gets a complete light theme. Components never write a hex colour of their own: before this module
 * they did, and under valem-ui's `data-theme="dark"` that left white cards, dark grey text on a
 * near-black page and invisible menu labels. `component-gallery.spec.ts` (valem-sandbox-ui) checks
 * every component type for WCAG contrast in both themes.
 *
 * Text roles meet 4.5:1 on the surfaces they are used on; `faint` is for decoration only.
 */

const v = (name: string, fallback: string) => `var(--${name}, ${fallback})`;

// ── text ──
export const ink = (fallback = '#16161f') => v('text', fallback);
export const muted = (fallback = '#5f6072') => v('text-muted', fallback);
/** Decoration only (separators, empty-star outlines): about 3.6:1, too faint for words. */
export const faint = (fallback = '#85869a') => v('text-light', fallback);
/** Accent as text: links, the active item of a menu. Lighter than the fill accent in dark mode. */
export const accent = (fallback = '#5b57eb') => `var(--accent-text, var(--accent, ${fallback}))`;

// ── fills ──
export const accentFill = (fallback = '#5b57eb') => v('accent', fallback);
export const accentFillHover = (fallback = '#4b46dd') => v('accent-hover', fallback);
export const accentSoft = (fallback = '#ecebfd') => v('accent-soft', fallback);
export const accentRing = (fallback = 'rgba(91, 87, 235, 0.30)') => v('accent-ring', fallback);
/** Text on an accent fill. */
export const onAccent = '#ffffff';

// ── surfaces and lines ──
export const panel = (fallback = '#ffffff') => v('panel-bg', fallback);
export const surface = (fallback = '#eef0f7') => v('surface-2', fallback);
export const inset = (fallback = '#f5f6fb') => v('surface-inset', fallback);
/** Decorative hairline: table rules, card edges, separators. */
export const rule = (fallback = '#e7e8f1') => v('border', fallback);
export const ruleStrong = (fallback = '#d7d9e6') => v('border-strong', fallback);
/** The edge of an interactive control: 3:1 or better against the page (WCAG 1.4.11). */
export const controlEdge = (fallback = '#85869a') => v('border-control', fallback);
export const codeBg = (fallback = '#f5f6fb') => v('code-bg', fallback);

// ── status ──
export const signal = (fallback = '#0e9f6e') => v('signal', fallback);
export const tone = {
  infoBg: (f = '#ecebfd') => v('accent-soft', f),
  infoLine: (f = 'rgba(91, 87, 235, 0.30)') => v('accent-ring', f),
  successBg: (f = '#d6f5e6') => v('green-light', f),
  successLine: (f = '#0e9f6e') => v('green', f),
  successText: (f = '#0a6e4d') => v('success-text', f),
  warningBg: (f = '#fdf1e1') => v('orange-light', f),
  warningLine: (f = '#d9770a') => v('orange', f),
  warningText: (f = '#9a5205') => v('warning-text', f),
  dangerBg: (f = '#fdecec') => v('red-light', f),
  dangerLine: (f = '#e5484d') => v('red', f),
  dangerText: (f = '#b42328') => v('danger-text', f),
  dangerFill: (f = '#c62a2f') => v('danger-fill', f),
};

// ── shape and type ──
export const radius = { sm: 'var(--radius-sm, 7px)', md: 'var(--radius, 10px)', lg: 'var(--radius-lg, 16px)' };
export const fontSans = 'var(--font-sans, inherit)';
export const fontMono = 'var(--font-mono, ui-monospace, "SF Mono", Consolas, monospace)';

/**
 * The box of every text-like input (text, email, number, select, date…), so all of them share one
 * height, radius, edge and type. `fontFamily` is stated because valem-ui sets `input[type=text]` and
 * `textarea` to its monospace face globally (for its own JSON editors), which otherwise leaks into
 * every form a view renders.
 */
export const controlBox: CSSProperties = {
  boxSizing: 'border-box',
  minHeight: 38,
  padding: '7px 11px',
  border: `1px solid ${controlEdge()}`,
  borderRadius: radius.sm,
  background: panel(),
  color: ink(),
  fontFamily: fontSans,
  fontSize: 14,
  lineHeight: 1.4,
};

export const labelText: CSSProperties = { fontSize: 13, fontWeight: 600, color: ink() };
export const helperText: CSSProperties = { fontSize: 12, color: muted(), lineHeight: 1.45 };
export const requiredMark: CSSProperties = { color: tone.dangerText(), marginLeft: 2 };

/** One option of a checkbox / radio list: a 32px row, so a thumb can hit it. */
export const choiceRow: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10, minHeight: 32, fontSize: 14, color: ink(),
};
/** The native checkbox or radio itself, in the brand accent. */
export const choiceMark: CSSProperties = {
  width: 18, height: 18, margin: 0, flexShrink: 0, accentColor: accentFill(),
};

/**
 * Every button the renderer draws (button, wizard steps, add/remove rows, retry) shares one shape:
 * 38px tall, the body face, one radius. Primary and danger are filled; secondary has a control edge;
 * ghost is text-only. White on the accent is 5.2:1 and on the danger fill 5.6:1.
 */
export function buttonStyle(variant: string = 'primary'): CSSProperties {
  const base: CSSProperties = {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6,
    minHeight: 38, padding: '0 16px', borderRadius: radius.sm,
    fontFamily: fontSans, fontSize: 14, fontWeight: 600, lineHeight: 1.2,
    border: '1px solid transparent', cursor: 'pointer',
  };
  switch (variant) {
    case 'secondary': return { ...base, background: panel(), color: ink(), borderColor: controlEdge() };
    case 'danger':    return { ...base, background: tone.dangerFill(), color: onAccent };
    case 'ghost':     return { ...base, background: 'transparent', color: accent() };
    default:          return { ...base, background: accentFill(), color: onAccent };
  }
}

/**
 * A status pill (badge, effect status). Each tone pairs its status TEXT token with its soft surface,
 * all at 4.5:1 or better; info uses the page ink because accent-on-accent-tint is 4.46:1.
 */
export function pillStyle(kind: string): CSSProperties {
  const base: CSSProperties = {
    display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 10px', borderRadius: 999,
    fontSize: 12, fontWeight: 650, lineHeight: 1.3, border: '1px solid transparent', whiteSpace: 'nowrap',
  };
  switch (kind) {
    case 'info': case 'primary': return { ...base, background: tone.infoBg(), color: ink(), borderColor: tone.infoLine() };
    case 'success': return { ...base, background: tone.successBg(), color: tone.successText() };
    case 'warning': return { ...base, background: tone.warningBg(), color: tone.warningText() };
    case 'danger':  return { ...base, background: tone.dangerBg(), color: tone.dangerText() };
    default:        return { ...base, background: surface(), color: ink(), borderColor: rule() };
  }
}
