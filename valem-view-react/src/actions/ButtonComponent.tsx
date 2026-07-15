import type { CSSProperties } from 'react';
import { useViewContext } from '../ViewContext';
import type { BaseComponentProps } from '../ComponentRenderer';
import jsonata from 'jsonata';

const VARIANT_STYLES: Record<string, CSSProperties> = {
  primary:   { background: '#2563eb', color: '#fff', border: 'none' },
  secondary: { background: '#f3f4f6', color: '#374151', border: '1px solid #d1d5db' },
  danger:    { background: '#dc2626', color: '#fff', border: 'none' },
  ghost:     { background: 'transparent', color: '#2563eb', border: '1px solid #93c5fd' },
};

export function ButtonComponent({ component: c, enabled, text }: BaseComponentProps) {
  const { state, onMutate, onNavigate } = useViewContext();

  const variant = (c.variant as string) ?? 'primary';
  const variantStyle = VARIANT_STYLES[variant] ?? VARIANT_STYLES.primary;

  async function handleClick() {
    if (!enabled) return;
    const handler = c.onClick;
    if (!handler) return;

    if (handler.mutations) {
      try {
        const result = await jsonata(handler.mutations).evaluate(state as Record<string, unknown>);
        if (result && typeof result === 'object') {
          await onMutate(result as Record<string, unknown>);
        }
      } catch {
        // expression evaluation failure is non-fatal
      }
    }
    if (handler.navigate) {
      onNavigate(handler.navigate);
    }
  }

  return (
    <button
      type="button"
      disabled={!enabled}
      onClick={handleClick}
      style={{
        padding: '8px 18px',
        borderRadius: 6,
        fontSize: 14,
        fontWeight: 500,
        cursor: enabled ? 'pointer' : 'not-allowed',
        opacity: enabled ? 1 : 0.5,
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        transition: 'opacity 0.15s',
        ...variantStyle,
      }}
    >
      {c.icon && <span>{c.icon}</span>}
      {c.label ?? text ?? 'Submit'}
    </button>
  );
}
