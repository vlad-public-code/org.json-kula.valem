import { useViewContext } from '../ViewContext';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { MenuSpec } from '../types';

export function MenuComponent({ component: c }: BaseComponentProps<MenuSpec>) {
  const { onNavigate, activeViewId } = useViewContext();
  const items = c.menuItems ?? [];
  const orientation = (c.orientation as string) ?? 'horizontal';

  return (
    <nav
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
            onClick={() => item.targetView && onNavigate(item.targetView)}
            style={{
              padding: orientation === 'vertical' ? '8px 16px' : '6px 14px',
              borderRadius: 6,
              border: 'none',
              background: isActive ? '#2563eb' : 'transparent',
              color: isActive ? '#fff' : '#374151',
              fontWeight: isActive ? 600 : 400,
              fontSize: 14,
              cursor: 'pointer',
              textAlign: orientation === 'vertical' ? 'left' : 'center',
              transition: 'background 0.1s, color 0.1s',
            }}
          >
            {item.icon && <span style={{ marginRight: 6 }}>{item.icon}</span>}
            {item.label}
          </button>
        );
      })}
    </nav>
  );
}
