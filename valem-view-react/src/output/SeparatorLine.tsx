import type { BaseComponentProps } from '../ComponentRenderer';
import type { SeparatorLineSpec } from '../types';

/** `separatorLine` — a rule — and `spacer`, the same vertical gap without one. */
export function SeparatorLine({ component: c }: BaseComponentProps<SeparatorLineSpec>) {
  if (c.type === 'spacer') {
    return <div data-testid={c.id} aria-hidden style={{ height: c.size ?? 16 }} />;
  }
  return (
    <hr
      data-testid={c.id}
      style={{ border: 'none', borderTop: '1px solid #e5e7eb', margin: '4px 0' }}
    />
  );
}
