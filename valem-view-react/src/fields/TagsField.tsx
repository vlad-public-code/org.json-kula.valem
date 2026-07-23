import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { useResolvedOptions } from '../hooks/useResolvedOptions';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ChoiceInputSpec } from '../types';

/**
 * `tagsField` — an array of scalars, edited as chips.
 *
 * Before this, a `string[]` needed a whole `sectionList` with add/remove controls and an item
 * view, which is a lot of spec for a list of words. The mutation writes the whole array at the
 * bound path, so the field's derivations and constraints see one change per edit rather than one
 * per element.
 */
export function TagsField(
  { component: c, enabled, readOnly, required }: BaseComponentProps<ChoiceInputSpec>,
) {
  const { state, onMutate } = useViewContext();
  const options = useResolvedOptions(c, state);
  const [entry, setEntry] = useState('');

  const bindPath = c.bind?.replace(/^\$\./, '');
  const raw = bindPath ? getByPath(state, bindPath) : undefined;
  const tags: string[] = Array.isArray(raw) ? raw.map(String) : [];
  const allowCustom = c.allowCustom ?? true;

  const write = (next: string[]) => {
    if (c.bind && !readOnly) onMutate({ [c.bind]: next });
  };

  const add = (value: string) => {
    const trimmed = value.trim();
    // Silently ignoring a duplicate beats writing one: the array is the model's value, and a
    // repeated tag would change what every downstream count and $distinct sees.
    if (!trimmed || tags.includes(trimmed)) { setEntry(''); return; }
    if (!allowCustom && !options.some(o => o.value === trimmed)) { setEntry(''); return; }
    write([...tags, trimmed]);
    setEntry('');
  };

  const suggestions = options.filter(o => !tags.includes(o.value));

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      <div style={{
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 6,
        padding: '4px 6px',
        border: '1px solid #ccc',
        borderRadius: 4,
        background: readOnly ? '#f5f5f5' : '#fff',
        minHeight: 32,
      }}>
        {tags.map(tag => (
          <span
            key={tag}
            data-testid={`${c.id}-tag-${tag}`}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              padding: '2px 8px',
              borderRadius: 12,
              background: '#e0e7ff',
              color: '#3730a3',
              fontSize: 12,
            }}
          >
            {tag}
            {!readOnly && enabled && (
              <button
                type="button"
                aria-label={`Remove ${tag}`}
                data-testid={`${c.id}-remove-${tag}`}
                onClick={() => write(tags.filter(t => t !== tag))}
                style={{
                  border: 'none', background: 'transparent', cursor: 'pointer',
                  fontSize: 13, lineHeight: 1, color: '#4338ca', padding: 0,
                }}
              >
                ×
              </button>
            )}
          </span>
        ))}
        <input
          id={c.id}
          type="text"
          list={suggestions.length ? `${c.id}-suggestions` : undefined}
          value={entry}
          placeholder={tags.length ? '' : c.placeholder ?? 'Add…'}
          disabled={!enabled}
          readOnly={readOnly}
          onChange={e => setEntry(e.target.value)}
          onKeyDown={e => {
            if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add(entry); }
            // Backspace on an empty box removes the last chip — the expected shortcut, and the
            // only way to reach a chip without a pointer.
            else if (e.key === 'Backspace' && !entry && tags.length) write(tags.slice(0, -1));
          }}
          onBlur={() => add(entry)}
          style={{ flex: 1, minWidth: 80, border: 'none', outline: 'none', fontSize: 14, background: 'transparent' }}
        />
      </div>
      {suggestions.length > 0 && (
        <datalist id={`${c.id}-suggestions`}>
          {suggestions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </datalist>
      )}
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}
