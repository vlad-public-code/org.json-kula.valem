import type { CSSProperties } from 'react';
import { controlBox, controlEdge, ink, inset, muted, panel, surface } from '../themeColors';

/**
 * Shared colour tokens for editable field surfaces (native `<input>`/`<select>`/`<textarea>`).
 *
 * These read the same CSS variables valem-ui defines for `data-theme="dark"` (see
 * `valem-ui/src/index.css`, via `themeColors.ts`), so fields repaint correctly under the dark theme
 * instead of a hardcoded light background colliding with the page's (theme-following) inherited text
 * color. The literal fallback inside each `var(...)` keeps a complete light look for any host that
 * renders this library without valem-ui's stylesheet (e.g. a bare embed).
 *
 * `border` is the control EDGE token (3:1 or better against the page, WCAG 1.4.11), not the
 * decorative hairline: an input whose outline cannot be seen does not look like an input.
 */
export const fieldColors = {
  bg: panel(),
  bgReadOnly: inset(),
  text: ink(),
  border: controlEdge(),
  mutedBg: surface(),
  mutedText: muted(),
} as const;

/** The box of a text-like input, read-only aware. Every field uses this, so they match. */
export function fieldInput(readOnly?: boolean): CSSProperties {
  return {
    ...controlBox,
    background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg,
    color: fieldColors.text,
    cursor: readOnly ? 'default' : undefined,
  };
}
