import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { useResolvedOptions } from '../hooks/useResolvedOptions';
import { fieldColors } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ChoiceInputSpec } from '../types';
import { accentFill, accentRing, accentSoft, controlBox, helperText, ink, labelText, muted, requiredMark } from '../themeColors';

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
  const [focused, setFocused] = useState(false);

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
      <div style={{
        ...controlBox,
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 6,
        padding: '4px 6px',
        background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg,
        color: fieldColors.text,
        // The inner input has no outline of its own, so the box shows focus for it.
        ...(focused ? { borderColor: accentFill(), boxShadow: `0 0 0 3px ${accentRing()}` } : {}),
      }}>
        {tags.map(tag => (
          <span
            key={tag}
            data-testid={`${c.id}-tag-${tag}`}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              padding: '2px 4px 2px 10px',
              borderRadius: 999,
              background: accentSoft(),
              color: ink(),
              fontSize: 13,
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
                  fontSize: 15, lineHeight: 1, color: muted(), padding: 0,
                  width: 22, height: 22, borderRadius: 999,
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
          onFocus={() => setFocused(true)}
          onBlur={() => { setFocused(false); add(entry); }}
          style={{
            flex: 1, minWidth: 80, border: 'none', outline: 'none', fontSize: 14,
            background: 'transparent', color: fieldColors.text,
          }}
        />
      </div>
      {suggestions.length > 0 && (
        <datalist id={`${c.id}-suggestions`}>
          {suggestions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </datalist>
      )}
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
