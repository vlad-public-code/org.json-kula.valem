import { useViewContext } from '../ViewContext';
import { useJSONataText } from '../hooks/useJSONata';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BadgeSpec } from '../types';
import { pillStyle } from '../themeColors';

export function Badge({ component: c, text }: BaseComponentProps<BadgeSpec>) {
  const { state } = useViewContext();
  const rawText = typeof c.text === 'string' ? c.text : undefined;
  const resolved = useJSONataText(rawText, state) ?? text;

  const bindKey = c.bind?.replace(/^\$\./, '');
  const boundValue = bindKey ? getByPath(state, bindKey) : undefined;
  const display = resolved ?? (boundValue != null ? String(boundValue) : c.label ?? '');

  const rawVariant = typeof c.variant === 'string' ? c.variant : undefined;
  const resolvedVariant = useJSONataText(rawVariant, state) ?? 'secondary';

  return (
    <span
      data-testid={c.id}
      style={pillStyle(resolvedVariant)}
    >
      {display}
    </span>
  );
}
