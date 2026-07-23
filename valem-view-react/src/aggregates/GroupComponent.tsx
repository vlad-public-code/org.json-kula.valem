import { LayoutContainer } from './LayoutContainer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';

/** `group` — the plain container. Its `layout` now reaches tabs and wizard too. */
export function GroupComponent({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  return (
    <LayoutContainer
      components={c.components ?? []}
      layout={c.layout}
      columns={c.columns}
      state={state}
    />
  );
}
