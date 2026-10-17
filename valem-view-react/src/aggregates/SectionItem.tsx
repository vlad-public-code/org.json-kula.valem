import { ComponentRenderer } from '../ComponentRenderer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';
import { ink, inset, labelText, radius, rule } from '../themeColors';

export function SectionItem({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  const children = c.components ?? [];

  return (
    <div
      style={{
        border: `1px solid ${rule()}`,
        borderRadius: radius.md,
        padding: '14px 16px',
        background: inset(),
        color: ink(),
      }}
    >
      {c.label && (
        <div style={{ ...labelText, marginBottom: 10 }}>{c.label}</div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {children.map(child => (
          <ComponentRenderer key={child.id} component={child} state={state} />
        ))}
      </div>
    </div>
  );
}
