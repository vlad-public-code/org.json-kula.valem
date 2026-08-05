import type { BaseComponentProps } from '../ComponentRenderer';
import type { SourceListSpec } from '../types';

/**
 * `sourceList` — the citations block a calculator ends on.
 *
 * Every item is an external link, so `rel="noopener noreferrer"` is attached here rather than left
 * to the spec (a generated ViewDefinition will not remember it). The optional `date` renders as the
 * "checked" signal beside each source — the E-E-A-T freshness marker a reader and a quality rater
 * both look for.
 */
export function SourceList({ component: c }: BaseComponentProps<SourceListSpec>) {
  const items = (c.items ?? []).filter(i => i && i.url);
  if (items.length === 0) return null;

  return (
    <section data-testid={c.id} style={{ fontSize: 13, lineHeight: 1.5 }}>
      <div style={{ fontWeight: 600, color: 'var(--text-muted, #64748b)', marginBottom: 4 }}>
        {c.label ?? 'Sources'}
      </div>
      <ul style={{ margin: 0, paddingLeft: 18 }}>
        {items.map((i, idx) => (
          <li key={idx} style={{ marginBottom: 2 }}>
            <a
              href={i.url}
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: '#2563eb', textDecoration: 'underline' }}
            >
              {i.label ?? i.url}
            </a>
            {i.date && (
              <span style={{ color: 'var(--text-light, #94a3b8)', marginLeft: 6 }}>
                · checked {i.date}
              </span>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
