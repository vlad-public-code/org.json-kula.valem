import { useJSONataText } from '../hooks/useJSONata';
import { useViewContext } from '../ViewContext';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { StaticTextSpec } from '../types';

export function StaticText({ component: c }: BaseComponentProps<StaticTextSpec>) {
  const { state } = useViewContext();
  const rawText = typeof c.text === 'string' ? c.text : undefined;
  const resolved = useJSONataText(rawText, state) ?? rawText ?? '';

  return (
    <div
      style={{ fontSize: 14, color: '#374151', lineHeight: 1.6 }}
      dangerouslySetInnerHTML={{ __html: resolved }}
    />
  );
}
