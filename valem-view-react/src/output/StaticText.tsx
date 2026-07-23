import { useJSONataText } from '../hooks/useJSONata';
import { useViewContext } from '../ViewContext';
import { escapeHtml, markdownToHtml } from '../markdown';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { StaticTextSpec } from '../types';

/**
 * `staticText` — a block of text, rendered according to `format`.
 *
 * The default is `markdown`, which **escapes** its content before applying light formatting.
 * That matters because a `staticText` is often bound (via a JSONata `text` expression) to model
 * state, and Valem has no per-field access control — so a low-trust mutator could otherwise inject
 * markup that runs in another user's browser. `html` still exists for authored, trusted content,
 * but it injects its content unescaped and must be opted into explicitly. `text` shows the content
 * verbatim with no formatting.
 */
export function StaticText({ component: c }: BaseComponentProps<StaticTextSpec>) {
  const { state } = useViewContext();
  const rawText = typeof c.text === 'string' ? c.text : undefined;
  const resolved = useJSONataText(rawText, state) ?? rawText ?? '';

  const format = c.format ?? 'markdown';
  const html = format === 'html' ? resolved
             : format === 'text' ? escapeHtml(resolved)
             : markdownToHtml(resolved);

  return (
    <div
      data-testid={c.id}
      style={{ fontSize: 14, color: '#374151', lineHeight: 1.6 }}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
