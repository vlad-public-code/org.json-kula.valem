import { useViewContext } from '../ViewContext';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ButtonSpec } from '../types';
import jsonata from 'jsonata';
import { buttonStyle } from '../themeColors';

export function ButtonComponent({ component: c, enabled, text }: BaseComponentProps<ButtonSpec>) {
  const { state, onMutate, onNavigate } = useViewContext();

  const variant = (c.variant as string) ?? 'primary';

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
        ...buttonStyle(variant),
        cursor: enabled ? 'pointer' : 'not-allowed',
        opacity: enabled ? 1 : 0.5,
        transition: 'opacity 0.15s, background 0.15s',
      }}
    >
      {c.icon && <span aria-hidden="true">{c.icon}</span>}
      {c.label ?? text ?? 'Submit'}
    </button>
  );
}
