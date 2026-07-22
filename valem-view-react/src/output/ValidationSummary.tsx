import { useViewContext } from '../ViewContext';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ValidationSummarySpec } from '../types';

const VARIANTS: Record<string, { bg: string; fg: string; border: string }> = {
  danger:  { bg: '#fef2f2', fg: '#b91c1c', border: '#fecaca' },
  warning: { bg: '#fffbeb', fg: '#92400e', border: '#fde68a' },
  info:    { bg: '#eff6ff', fg: '#1e40af', border: '#bfdbfe' },
};

/**
 * `validationSummary` — every flagged constraint in one block.
 *
 * The violations come from the renderer's own map (`ViewRenderer`'s `violations` prop), not from
 * the view: the evaluator has the merged document but not the runtime's flagged-constraint set.
 *
 * This exists because a `flag`-policy constraint commits. It records a violation and lets the
 * model stay editable while temporarily inconsistent — which is the point — but leaves the
 * violation with nowhere to show except beside a field bound to the same path, and a constraint
 * spanning three fields is beside none of them.
 */
export function ValidationSummary({ component: c }: BaseComponentProps<ValidationSummarySpec>) {
  const { fieldErrors, formErrors } = useViewContext();

  const prefix = c.pathPrefix;
  const scoped: [string, string][] = Object.entries(fieldErrors)
    .filter(([path]) => !prefix || path === prefix || path.startsWith(`${prefix}.`) || path.startsWith(`${prefix}[`));

  // Path-less violations are the ones with no field to sit beside — precisely what this
  // component is for — so they lead. A pathPrefix excludes them: it asks for one section, and
  // a constraint with no path belongs to no section.
  const unscoped: [string, string][] = prefix
    ? []
    : formErrors.map((message, i) => [`#${i}`, message]);

  const entries = [...unscoped, ...scoped].slice(0, c.maxItems ?? 50);

  const v = VARIANTS[c.variant ?? 'danger'] ?? VARIANTS.danger;

  if (entries.length === 0) {
    // An empty summary that keeps its box would read as a permanent warning; only an explicit
    // emptyText earns the space.
    if (!c.emptyText) return null;
    return (
      <div data-testid={c.id} style={{ fontSize: 12, color: '#15803d' }}>
        {c.emptyText}
      </div>
    );
  }

  return (
    <div
      data-testid={c.id}
      role="alert"
      style={{
        padding: '10px 14px',
        borderRadius: 6,
        border: `1px solid ${v.border}`,
        background: v.bg,
        color: v.fg,
      }}
    >
      {c.label && <strong style={{ display: 'block', fontSize: 13, marginBottom: 6 }}>{c.label}</strong>}
      <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12, lineHeight: 1.6 }}>
        {entries.map(([path, message]) => (
          <li key={path} data-testid={`${c.id}-item`}>
            {path.startsWith('#') ? (
              message
            ) : (
              <a
                href={`#${path}`}
                onClick={e => { e.preventDefault(); focusBoundField(path); }}
                style={{ color: 'inherit' }}
              >
                {message}
              </a>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Scrolls to the component bound to `path`.
 *
 * A component's `id` is not its path, so the lookup goes through the `data-bind` attribute that
 * `ComponentRenderer` puts on a field's error wrapper. That wrapper exists only while the field
 * has an error — which is exactly when this summary has a row pointing at it, so the element is
 * always there when it is needed and costs nothing when it is not.
 */
function focusBoundField(path: string) {
  // Quoted attribute value, so only backslash and the quote itself need escaping — CSS.escape is
  // for identifiers and would turn `$.total` into `\$\.total`, which matches nothing here.
  const escaped = path.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  const el = document.querySelector<HTMLElement>(`[data-bind="${escaped}"]`);
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  el?.querySelector<HTMLElement>('input, select, textarea, button')?.focus();
}
