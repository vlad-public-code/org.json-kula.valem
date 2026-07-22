import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { useJSONataLiteral, useJSONataText } from '../hooks/useJSONata';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { LinkSpec } from '../types';

/**
 * `link` — an anchor out of the model.
 *
 * `rel="noopener noreferrer"` is attached to every `target="_blank"` link rather than left to the
 * spec: a generated ViewDefinition will not remember it, and without `noopener` the opened page
 * can reach back through `window.opener`.
 */
export function LinkComponent({ component: c }: BaseComponentProps<LinkSpec>) {
  const { state } = useViewContext();

  const fromSpec = useJSONataLiteral(typeof c.href === 'string' ? c.href : undefined, state);
  const bindPath = c.bind?.replace(/^\$\./, '');
  const bound = bindPath ? getByPath(state, bindPath) : undefined;
  const href = fromSpec ?? (bound != null ? String(bound) : undefined);

  const body = useJSONataText(typeof c.text === 'string' ? c.text : undefined, state);
  const caption = body ?? c.label ?? href;

  if (!href) return null;
  const external = c.target === '_blank';

  return (
    <a
      data-testid={c.id}
      href={href}
      target={c.target}
      rel={external ? 'noopener noreferrer' : undefined}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        color: '#2563eb',
        fontSize: 14,
        textDecoration: 'underline',
      }}
    >
      {c.icon && <span aria-hidden>{c.icon}</span>}
      {caption}
      {external && <span aria-hidden style={{ fontSize: 11 }}>↗</span>}
    </a>
  );
}
