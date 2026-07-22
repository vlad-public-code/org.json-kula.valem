import { LayoutContainer } from './LayoutContainer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';

/** `card` — a `group` on a titled surface. `label` is the heading. */
export function CardComponent({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  return (
    <section
      data-testid={c.id}
      style={{
        border: '1px solid #e5e7eb',
        borderRadius: 8,
        background: '#fff',
        boxShadow: '0 1px 2px rgba(0,0,0,0.04)',
        overflow: 'hidden',
      }}
    >
      {c.label && (
        <header style={{
          padding: '10px 16px',
          borderBottom: '1px solid #f3f4f6',
          fontSize: 14,
          fontWeight: 600,
          color: '#111827',
        }}>
          {c.label}
        </header>
      )}
      <div style={{ padding: 16 }}>
        <LayoutContainer
          components={c.components ?? []}
          layout={c.layout}
          columns={c.columns}
          state={state}
        />
      </div>
    </section>
  );
}
