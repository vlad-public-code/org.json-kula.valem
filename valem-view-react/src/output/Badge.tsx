import type { CSSProperties } from 'react';
import { useViewContext } from '../ViewContext';
import { useJSONataText } from '../hooks/useJSONata';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';

const VARIANT_STYLES: Record<string, CSSProperties> = {
  primary:   { background: '#dbeafe', color: '#1d4ed8' },
  secondary: { background: '#f3f4f6', color: '#374151' },
  success:   { background: '#dcfce7', color: '#15803d' },
  warning:   { background: '#fef9c3', color: '#854d0e' },
  danger:    { background: '#fee2e2', color: '#b91c1c' },
};

export function Badge({ component: c, text }: BaseComponentProps) {
  const { state } = useViewContext();
  const rawText = typeof c.text === 'string' ? c.text : undefined;
  const resolved = useJSONataText(rawText, state) ?? text;

  const bindKey = c.bind?.replace(/^\$\./, '');
  const boundValue = bindKey ? getByPath(state, bindKey) : undefined;
  const display = resolved ?? (boundValue != null ? String(boundValue) : c.label ?? '');

  const rawVariant = typeof c.variant === 'string' ? c.variant : undefined;
  const resolvedVariant = useJSONataText(rawVariant, state) ?? 'secondary';
  const variantStyle = VARIANT_STYLES[resolvedVariant] ?? VARIANT_STYLES.secondary;

  return (
    <span
      data-testid={c.id}
      style={{
        display: 'inline-block',
        padding: '2px 10px',
        borderRadius: 12,
        fontSize: 12,
        fontWeight: 600,
        ...variantStyle,
      }}
    >
      {display}
    </span>
  );
}
