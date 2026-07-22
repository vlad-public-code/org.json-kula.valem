import { ComponentRenderer } from '../ComponentRenderer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';

export function GroupComponent({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  const children = c.components ?? [];
  const layout = c.layout ?? 'vertical';
  const columns = c.columns ?? 2;

  const style: React.CSSProperties =
    layout === 'grid'
      ? { display: 'grid', gridTemplateColumns: `repeat(${columns}, 1fr)`, gap: 16 }
      : layout === 'horizontal'
        ? { display: 'flex', flexDirection: 'row', flexWrap: 'wrap', gap: 16 }
        : { display: 'flex', flexDirection: 'column', gap: 12 };

  return (
    <div style={style}>
      {children.map(child => (
        <ComponentRenderer key={child.id} component={child} state={state} />
      ))}
    </div>
  );
}
