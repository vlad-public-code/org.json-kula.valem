import { ComponentRenderer } from '../ComponentRenderer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';

export function SectionItem({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  const children = c.components ?? [];

  return (
    <div
      style={{
        border: '1px solid #e5e7eb',
        borderRadius: 8,
        padding: '14px 16px',
        background: '#fafafa',
      }}
    >
      {c.label && (
        <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 10, color: '#374151' }}>{c.label}</div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {children.map(child => (
          <ComponentRenderer key={child.id} component={child} state={state} />
        ))}
      </div>
    </div>
  );
}
