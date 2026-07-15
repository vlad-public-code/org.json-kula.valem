import { useViewContext } from '../ViewContext';
import { useJSONataText } from '../hooks/useJSONata';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';

export function LabelComponent({ component: c, text }: BaseComponentProps) {
  const { state } = useViewContext();
  const rawText = typeof c.text === 'string' ? c.text : (typeof c.text === 'object' && c.text ? String(c.text) : undefined);
  const resolved = useJSONataText(rawText, state) ?? text;

  const bindKey = c.bind?.replace(/^\$\./, '');
  const boundValue = bindKey ? getByPath(state, bindKey) : undefined;
  const display = resolved ?? (boundValue != null ? String(boundValue) : bindKey ? '' : c.label ?? '');

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {c.label && (resolved != null || bindKey) && (
        <span style={{ fontSize: 12, color: '#666', fontWeight: 500 }}>{c.label}</span>
      )}
      <span style={{ fontSize: 14 }}>{display}</span>
    </div>
  );
}
