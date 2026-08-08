/**
 * Shared color tokens for editable field surfaces (native `<input>`/`<select>`/`<textarea>`).
 *
 * These read the same CSS variables valem-ui defines for `data-theme="dark"` (see
 * `valem-ui/src/index.css`), so fields repaint correctly under the dark theme instead of a
 * hardcoded light background colliding with the page's (theme-following) inherited text color.
 * The literal fallback after each `var(...)` keeps today's light look for any host that renders
 * this library without valem-ui's stylesheet (e.g. a bare embed).
 */
export const fieldColors = {
  bg: 'var(--panel-bg, #fff)',
  bgReadOnly: 'var(--surface-inset, #f5f5f5)',
  text: 'var(--text, #16161f)',
  border: 'var(--border-strong, #ccc)',
  mutedBg: 'var(--surface-2, #f9fafb)',
  mutedText: 'var(--text-muted, #6b7280)',
} as const;
