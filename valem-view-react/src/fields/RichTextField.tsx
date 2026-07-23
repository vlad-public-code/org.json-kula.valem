import { useRef } from 'react';
import { useViewContext } from '../ViewContext';
import { useDeferredMutate } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { TextAreaSpec } from '../types';

const MARKS: { label: string; title: string; wrap: [string, string] }[] = [
  { label: 'B',  title: 'Bold',    wrap: ['**', '**'] },
  { label: 'I',  title: 'Italic',  wrap: ['_', '_'] },
  { label: '<>', title: 'Code',    wrap: ['`', '`'] },
  { label: '•',  title: 'List',    wrap: ['\n- ', ''] },
  { label: 'H',  title: 'Heading', wrap: ['\n## ', ''] },
];

/**
 * `richTextField` — a markdown editor over an ordinary string field.
 *
 * It stores markdown rather than HTML deliberately: the value stays something a derivation can
 * read, a constraint can measure and a diff can show, and there is no sanitising step between
 * the model and what gets stored. The toolbar only wraps the selection in markup — the source is
 * always editable directly, so nothing the toolbar cannot express is out of reach.
 */
export function RichTextField(
  { component: c, enabled, readOnly, required }: BaseComponentProps<TextAreaSpec>,
) {
  const { state } = useViewContext();
  const { draft, schedule, handleBlur } = useDeferredMutate(c.bind, state);
  const ref = useRef<HTMLTextAreaElement>(null);
  const toolbar = c.toolbar ?? 'basic';
  const marks = toolbar === 'full' ? MARKS : MARKS.slice(0, 3);

  const applyMark = (open: string, close: string) => {
    const el = ref.current;
    if (!el || readOnly) return;
    const { selectionStart: s, selectionEnd: e } = el;
    const next = `${draft.slice(0, s)}${open}${draft.slice(s, e)}${close}${draft.slice(e)}`;
    schedule(next, next);
    // Restore the caret inside the marks; without this it jumps to the end after every click.
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(s + open.length, e + open.length);
    });
  };

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      {toolbar !== 'none' && (
        <div style={{ display: 'flex', gap: 2 }}>
          {marks.map(m => (
            <button
              key={m.title}
              type="button"
              title={m.title}
              aria-label={m.title}
              data-testid={`${c.id}-mark-${m.title.toLowerCase()}`}
              disabled={!enabled || readOnly}
              onClick={() => applyMark(m.wrap[0], m.wrap[1])}
              style={{
                minWidth: 28,
                padding: '2px 6px',
                border: '1px solid #d1d5db',
                borderRadius: 4,
                background: '#fff',
                fontSize: 12,
                fontWeight: m.label === 'B' ? 700 : 400,
                fontStyle: m.label === 'I' ? 'italic' : 'normal',
                cursor: enabled && !readOnly ? 'pointer' : 'not-allowed',
              }}
            >
              {m.label}
            </button>
          ))}
        </div>
      )}
      <textarea
        id={c.id}
        ref={ref}
        rows={c.rows ?? 6}
        value={draft}
        placeholder={c.placeholder ?? ''}
        disabled={!enabled}
        readOnly={readOnly}
        onChange={e => { if (!readOnly) schedule(e.target.value, e.target.value); }}
        onBlur={handleBlur}
        style={{
          padding: '6px 10px',
          border: '1px solid #ccc',
          borderRadius: 4,
          fontSize: 14,
          fontFamily: 'inherit',
          resize: 'vertical',
          background: readOnly ? '#f5f5f5' : '#fff',
        }}
      />
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}
