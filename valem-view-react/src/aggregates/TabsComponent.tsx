import { LayoutContainer } from './LayoutContainer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';

/**
 * `tabs` — a container whose children each get a tab, captioned by their own `label`.
 *
 * The children are ordinary containers (`tabItem` reads best, but any container works), so a tab
 * body is authored exactly like any other group. The active tab is local UI state and is
 * deliberately not in the model: it is not something a derivation, a constraint or an audit
 * record should ever have an opinion about. When a step *should* be model state — a wizard whose
 * position must survive a reload — use separate views and navigate between them instead.
 */
export function TabsComponent({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  return (
    <div data-testid={c.id}>
      {c.label && (
        <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{c.label}</div>
      )}
      <LayoutContainer components={c.components ?? []} layout="tabs" state={state} />
    </div>
  );
}
