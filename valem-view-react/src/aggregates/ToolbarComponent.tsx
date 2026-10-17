import { ComponentRenderer } from '../ComponentRenderer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';
import { ink } from '../themeColors';

/**
 * `toolbar` and `buttonGroup` — a row of actions.
 *
 * They differ only in spacing: a toolbar separates its children, a buttonGroup butts them
 * together into one control. Neither honours `layout`, because a vertical toolbar is a menu.
 */
export function ToolbarComponent({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  const grouped = c.type === 'buttonGroup';

  return (
    <div
      data-testid={c.id}
      role={grouped ? 'group' : 'toolbar'}
      aria-label={c.label}
      style={{
        display: 'flex',
        flexDirection: 'row',
        flexWrap: 'wrap',
        alignItems: 'center',
        // A buttonGroup is a row of related actions; each button keeps its own edge, so the group
        // needs no frame of its own (a frame around framed buttons drew double borders).
        gap: 8,
      }}
    >
      {c.label && !grouped && (
        <span style={{ fontSize: 13, fontWeight: 600, color: ink(), marginRight: 4 }}>{c.label}</span>
      )}
      {(c.components ?? []).map(child => (
        <ComponentRenderer key={child.id} component={child} state={state} />
      ))}
    </div>
  );
}
