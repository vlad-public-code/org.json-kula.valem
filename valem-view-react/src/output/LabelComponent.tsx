import { useViewContext } from '../ViewContext';
import { useJSONataText } from '../hooks/useJSONata';
import { getByPath } from '../hooks/useDeferredMutate';
import { useFlashOnChange } from '../hooks/useFlashOnChange';
import { formatValue } from '../format';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { LabelSpec } from '../types';

export function LabelComponent({ component: c, text }: BaseComponentProps<LabelSpec>) {
  const { state } = useViewContext();
  const rawText = typeof c.text === 'string' ? c.text : (typeof c.text === 'object' && c.text ? String(c.text) : undefined);
  const resolved = useJSONataText(rawText, state) ?? text;

  const bindKey = c.bind?.replace(/^\$\./, '');
  const boundValue = bindKey ? getByPath(state, bindKey) : undefined;
  // A bound value formats through the same path as statTile/keyValueList when the label asks for it;
  // an unformatted label keeps its verbatim String() rendering, so ids/years/text are untouched.
  const boundText = boundValue != null
    ? (c.format ? formatValue(boundValue, c.format, c.currency) : String(boundValue))
    : bindKey ? '' : c.label ?? '';
  const display = resolved ?? boundText;

  // Flash the value when it recomputes. A label showing a fixed string never changes, so it never
  // flashes; only a bound/derived value moving does.
  const flashRef = useFlashOnChange<HTMLSpanElement>(display);

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {c.label && (resolved != null || bindKey) && (
        <span style={{ fontSize: 12, color: '#666', fontWeight: 500 }}>{c.label}</span>
      )}
      <span ref={flashRef} style={{ fontSize: 14, borderRadius: 4 }}>{display}</span>
    </div>
  );
}
