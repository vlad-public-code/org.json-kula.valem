import { ComponentRenderer } from '../ComponentRenderer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';

export function FieldSetComponent({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  const children = c.components ?? [];

  return (
    <fieldset
      style={{
        border: '1px solid #d1d5db',
        borderRadius: 6,
        padding: '12px 16px',
        margin: 0,
      }}
    >
      {c.legend && (
        <legend style={{ fontSize: 13, fontWeight: 600, padding: '0 6px', color: '#374151' }}>
          {c.legend}
        </legend>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {children.map(child => (
          <ComponentRenderer key={child.id} component={child} state={state} />
        ))}
      </div>
    </fieldset>
  );
}
