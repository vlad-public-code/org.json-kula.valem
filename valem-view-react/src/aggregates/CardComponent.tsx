import { LayoutContainer } from './LayoutContainer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';
import { ink, panel, radius, rule } from '../themeColors';

/** `card` — a `group` on a titled surface. `label` is the heading. */
export function CardComponent({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  return (
    <section
      data-testid={c.id}
      style={{
        border: `1px solid ${rule()}`,
        borderRadius: radius.md,
        background: panel(),
        color: ink(),
        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(20, 22, 45, 0.06))',
        overflow: 'hidden',
      }}
    >
      {c.label && (
        <header style={{
          padding: '12px 16px',
          borderBottom: `1px solid ${rule()}`,
          fontSize: 15,
          fontWeight: 650,
          color: ink(),
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
