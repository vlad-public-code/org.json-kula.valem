import { useJSONataText } from '../hooks/useJSONata';
import { useViewContext } from '../ViewContext';
import { escapeHtml, markdownToHtml } from '../markdown';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { StaticTextSpec } from '../types';

/**
 * `staticText` — a block of text, rendered according to `format`.
 *
 * `html` remains the default so existing specs render exactly as before, but it is the one mode
 * that injects its content unescaped. Prefer `markdown` for anything a user typed — it is the
 * read half of `richTextField` and escapes the source before adding markup — or `text` when the
 * content should appear verbatim.
 */
export function StaticText({ component: c }: BaseComponentProps<StaticTextSpec>) {
  const { state } = useViewContext();
  const rawText = typeof c.text === 'string' ? c.text : undefined;
  const resolved = useJSONataText(rawText, state) ?? rawText ?? '';

  const format = c.format ?? 'html';
  const html = format === 'markdown' ? markdownToHtml(resolved)
             : format === 'text'     ? escapeHtml(resolved)
             : resolved;

  return (
    <div
      data-testid={c.id}
      style={{ fontSize: 14, color: '#374151', lineHeight: 1.6 }}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
