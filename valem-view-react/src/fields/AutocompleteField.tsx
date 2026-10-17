import { useEffect, useMemo, useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { useResolvedOptions } from '../hooks/useResolvedOptions';
import { fieldColors } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ChoiceInputSpec } from '../types';
import { accentSoft, controlBox, fontSans, helperText, labelText, radius, requiredMark, rule } from '../themeColors';

/**
 * `autocompleteField` and `comboBox` — a select that filters as you type.
 *
 * The same `options` / `optionsExpr` / `optionsUrl` contract as `selectField`; the only
 * difference is that the list is searched rather than scrolled, which is what a select degrades
 * into past a few dozen entries.
 *
 * `comboBox` additionally accepts a value that is not in the list (`allowCustom` defaults true
 * for it, and the server fills that default in). The list is still only an affordance: what the
 * model accepts is decided by its schema, so a custom value can be rejected on mutate like any
 * other, and the inline error appears the same way.
 */
export function AutocompleteField(
  { component: c, enabled, readOnly, required }: BaseComponentProps<ChoiceInputSpec>,
) {
  const { state, onMutate } = useViewContext();
  const options = useResolvedOptions(c, state);

  const bindPath = c.bind?.replace(/^\$\./, '');
  const modelValue = bindPath ? getByPath(state, bindPath) : undefined;
  const selectedLabel = useMemo(
    () => options.find(o => o.value === String(modelValue ?? ''))?.label,
    [options, modelValue],
  );

  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const allowCustom = c.allowCustom ?? c.type === 'comboBox';

  // The box shows the model's value until the user starts typing; a rejected mutation therefore
  // leaves their text in place beside the error rather than snapping back.
  useEffect(() => {
    if (!open) setQuery(selectedLabel ?? String(modelValue ?? ''));
  }, [open, selectedLabel, modelValue]);

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return options.slice(0, 50);
    return options.filter(o => o.label?.toLowerCase().includes(needle)
      || o.value?.toLowerCase().includes(needle)).slice(0, 50);
  }, [options, query]);

  const commit = (value: string) => {
    setOpen(false);
    if (c.bind && !readOnly) onMutate({ [c.bind]: value });
  };

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 4, position: 'relative' }}>
      {c.label && (
        <label htmlFor={c.id} style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
      <input
        id={c.id}
        type="text"
        role="combobox"
        aria-expanded={open}
        autoComplete="off"
        value={query}
        placeholder={c.placeholder ?? 'Type to search…'}
        disabled={!enabled}
        readOnly={readOnly}
        onChange={e => { setQuery(e.target.value); setOpen(true); }}
        onFocus={() => !readOnly && setOpen(true)}
        // Blur is deferred so a click on an option lands before the list unmounts.
        onBlur={() => setTimeout(() => {
          setOpen(false);
          if (allowCustom && query !== (selectedLabel ?? String(modelValue ?? ''))) commit(query);
        }, 150)}
        style={{
          ...controlBox,
          background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg,
          color: fieldColors.text,
        }}
      />
      {open && matches.length > 0 && (
        <ul
          role="listbox"
          style={{
            position: 'absolute',
            top: '100%',
            left: 0,
            right: 0,
            zIndex: 20,
            margin: 0,
            listStyle: 'none',
            maxHeight: 220,
            overflowY: 'auto',
            marginTop: 4,
            padding: 4,
            background: fieldColors.bg,
            border: `1px solid ${rule()}`,
            borderRadius: radius.sm,
            boxShadow: 'var(--shadow-lg, 0 12px 32px -12px rgba(20, 22, 45, 0.3))',
          }}
        >
          {matches.map(o => (
            <li key={o.value} role="option" aria-selected={o.value === String(modelValue ?? '')}>
              <button
                type="button"
                data-testid={`${c.id}-option-${o.value}`}
                onMouseDown={e => e.preventDefault()}
                onClick={() => commit(o.value)}
                style={{
                  display: 'block',
                  width: '100%',
                  minHeight: 36,
                  padding: '6px 10px',
                  border: 'none',
                  borderRadius: 6,
                  background: o.value === String(modelValue ?? '') ? accentSoft() : 'transparent',
                  fontFamily: fontSans,
                  color: fieldColors.text,
                  fontSize: 14,
                  textAlign: 'left',
                  cursor: 'pointer',
                }}
              >
                {o.label ?? o.value}
              </button>
            </li>
          ))}
        </ul>
      )}
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
