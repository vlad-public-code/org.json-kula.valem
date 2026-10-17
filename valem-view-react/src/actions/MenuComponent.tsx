import { useViewContext } from '../ViewContext';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { MenuSpec } from '../types';
import { accentSoft, fontSans, ink, muted, radius } from '../themeColors';

export function MenuComponent({ component: c }: BaseComponentProps<MenuSpec>) {
  const { onNavigate, activeViewId } = useViewContext();
  const items = c.menuItems ?? [];
  const orientation = (c.orientation as string) ?? 'horizontal';

  return (
    <nav
      data-testid={c.id}
      aria-label="Views"
      style={{
        display: 'flex',
        flexDirection: orientation === 'vertical' ? 'column' : 'row',
        gap: 4,
      }}
    >
      {items.map((item, i) => {
        const isActive = item.targetView === activeViewId;
        return (
          <button
            key={i}
            type="button"
            aria-current={isActive ? 'page' : undefined}
            onClick={() => item.targetView && onNavigate(item.targetView)}
            style={{
              minHeight: 36,
              padding: orientation === 'vertical' ? '8px 14px' : '6px 14px',
              borderRadius: radius.sm,
              border: 'none',
              background: isActive ? accentSoft() : 'transparent',
              color: isActive ? ink() : muted(),
              fontFamily: fontSans,
              fontWeight: isActive ? 650 : 500,
              fontSize: 14,
              cursor: 'pointer',
              textAlign: orientation === 'vertical' ? 'left' : 'center',
              transition: 'background 0.1s, color 0.1s',
            }}
          >
            {item.icon && <span aria-hidden="true" style={{ marginRight: 6 }}>{item.icon}</span>}
            {item.label}
          </button>
        );
      })}
    </nav>
  );
}
