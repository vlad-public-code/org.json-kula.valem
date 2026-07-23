import type { CSSProperties } from 'react';
import { useViewContext } from '../ViewContext';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { MenuSpec } from '../types';

/**
 * `stepper` and `breadcrumb` — the same `menuItems` as a `menu`, drawn as a numbered progression
 * or a trail.
 *
 * Neither holds a position of its own: "which step" is the active view id, so it survives a
 * reload, can be driven by a button's `navigate` handler, and is the same thing `onNavigate`
 * reports to the host app. A stepper that tracked its own index would silently disagree with the
 * view actually on screen the moment anything else navigated.
 *
 * Steps before the active one are marked done, which is a positional claim, not a claim about
 * validity — the model's constraints decide whether a step's data is actually acceptable.
 */
export function StepperComponent({ component: c }: BaseComponentProps<MenuSpec>) {
  const { onNavigate, activeViewId } = useViewContext();
  const items = c.menuItems ?? [];
  const activeIndex = items.findIndex(i => i.targetView === activeViewId);
  const isBreadcrumb = c.type === 'breadcrumb';

  if (isBreadcrumb) {
    return (
      <nav data-testid={c.id} aria-label="Breadcrumb">
        <ol style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, listStyle: 'none', margin: 0, padding: 0 }}>
          {items.map((item, i) => {
            const current = item.targetView === activeViewId;
            return (
              <li key={i} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <button
                  type="button"
                  aria-current={current ? 'page' : undefined}
                  data-testid={`${c.id}-crumb-${item.targetView}`}
                  onClick={() => item.targetView && onNavigate(item.targetView)}
                  style={{
                    border: 'none',
                    background: 'transparent',
                    padding: 0,
                    fontSize: 13,
                    color: current ? '#111827' : '#2563eb',
                    fontWeight: current ? 600 : 400,
                    cursor: current ? 'default' : 'pointer',
                    textDecoration: current ? 'none' : 'underline',
                  }}
                >
                  {item.icon && <span aria-hidden style={{ marginRight: 4 }}>{item.icon}</span>}
                  {item.label}
                </button>
                {i < items.length - 1 && <span aria-hidden style={{ color: '#9ca3af' }}>/</span>}
              </li>
            );
          })}
        </ol>
      </nav>
    );
  }

  const vertical = c.orientation === 'vertical';

  return (
    <nav data-testid={c.id} aria-label="Progress">
      <ol style={{
        display: 'flex',
        flexDirection: vertical ? 'column' : 'row',
        gap: vertical ? 8 : 4,
        listStyle: 'none',
        margin: 0,
        padding: 0,
      }}>
        {items.map((item, i) => {
          const current = i === activeIndex;
          const done = activeIndex >= 0 && i < activeIndex;
          return (
            <li key={i} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <button
                type="button"
                aria-current={current ? 'step' : undefined}
                data-testid={`${c.id}-step-${item.targetView}`}
                onClick={() => item.targetView && onNavigate(item.targetView)}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 8,
                  border: 'none',
                  background: 'transparent',
                  padding: 0,
                  cursor: 'pointer',
                }}
              >
                <span style={badgeStyle(current, done)}>{done ? '✓' : i + 1}</span>
                <span style={{
                  fontSize: 13,
                  fontWeight: current ? 600 : 400,
                  color: current ? '#111827' : '#6b7280',
                }}>
                  {item.label}
                </span>
              </button>
              {!vertical && i < items.length - 1 && (
                <span aria-hidden style={{ width: 24, height: 1, background: '#e5e7eb' }} />
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

function badgeStyle(current: boolean, done: boolean): CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: 24,
    height: 24,
    borderRadius: '50%',
    fontSize: 12,
    fontWeight: 600,
    background: current ? '#2563eb' : done ? '#dcfce7' : '#f3f4f6',
    color: current ? '#fff' : done ? '#15803d' : '#6b7280',
  };
}
