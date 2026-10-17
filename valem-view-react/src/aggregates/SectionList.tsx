import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { useJSONataBoolean } from '../hooks/useJSONata';
import { getByPath } from '../hooks/useDeferredMutate';
import { ComponentRenderer } from '../ComponentRenderer';
import type { SectionListSpec } from '../types';
import { scopeToIndex } from '../navigation';
import type { BaseComponentProps } from '../ComponentRenderer';
import { accentRing, accentSoft, buttonStyle, controlEdge, ink, labelText, muted, panel, rule, tone } from '../themeColors';

/** Row-action buttons: the shared shape at 32px, so a list of rows stays compact. */
const compact = { minHeight: 32, padding: '0 12px', fontSize: 13 } as const;

function itemLabel(item: unknown, idx: number): string {
  if (typeof item !== 'object' || item === null) return String(item);
  const obj = item as Record<string, unknown>;
  if (obj.name != null) return String(obj.name);
  if (obj.title != null) return String(obj.title);
  if (obj.label != null) return String(obj.label);
  const pairs = Object.entries(obj)
    .filter(([, v]) => v != null && typeof v !== 'object')
    .slice(0, 3)
    // The values alone ("Ada Lovelace · Host"): field names are the model's vocabulary, not the
    // reader's, and "person: Ada Lovelace, role: Host" read as debug output.
    .map(([, v]) => String(v))
    .join(' · ');
  return pairs || `Item ${idx + 1}`;
}

export function SectionList({ component: c, state }: BaseComponentProps<SectionListSpec>) {
  const { onMutate, onNavigate } = useViewContext();
  const [editingIndex, setEditingIndex] = useState<number | null>(null);

  const bindKey = c.bind?.replace(/^\$\./, '');
  const raw = bindKey ? getByPath(state, bindKey) : undefined;
  const items: unknown[] = Array.isArray(raw) ? raw : [];

  const canAdd    = useJSONataBoolean(c.canAdd,    state, true);
  const canRemove = useJSONataBoolean(c.canRemove, state, true);

  const hasInlineEditor = (c.components?.length ?? 0) > 0;

  function handleAdd() {
    if (!c.bind) return;
    const newIdx = items.length;
    /*
     * The new element goes in EMPTY — no keys.
     *
     * It used to be seeded with one null per editor field, which looks harmless and is not: in
     * JSONata a null propagates through arithmetic and out of an aggregate, so a single freshly
     * added row turned every total over the array null ($sum(items.qty) → null) until the user had
     * filled in every field. An ABSENT key is skipped instead, so the running total stays right
     * while the row is being typed. Nothing needs the keys to exist — a mutation to
     * `$.items[2].qty` creates the path it writes — and an absent field is also what lets a
     * `defaultValues` rule for the element fill it, since those fill only caller-absent fields.
     */
    onMutate({ [c.bind]: [...items, {}] });
    if (hasInlineEditor) setEditingIndex(newIdx);
    else if (c.itemView) onNavigate(c.itemView, newIdx);
  }

  function handleRemove(idx: number) {
    if (!c.bind) return;
    onMutate({ [c.bind]: items.filter((_, i) => i !== idx) });
    setEditingIndex(prev => {
      if (prev === idx) return null;
      if (prev !== null && prev > idx) return prev - 1;
      return prev;
    });
  }

  function handleEdit(idx: number) {
    if (hasInlineEditor) {
      setEditingIndex(editingIndex === idx ? null : idx);
    } else if (c.itemView) {
      onNavigate(c.itemView, idx);
    }
  }

  return (
    <div data-testid={c.id}>
      {c.label && <div style={{ ...labelText, marginBottom: 8 }}>{c.label}</div>}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {items.map((item, idx) => (
          <div key={idx}>
            {/* Row */}
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '6px 8px 6px 12px',
                border: `1px solid ${editingIndex === idx ? accentRing() : rule()}`,
                borderRadius: editingIndex === idx ? '8px 8px 0 0' : 8,
                background: editingIndex === idx ? accentSoft() : panel(),
              }}
            >
              <span style={{ flex: 1, minWidth: 0, fontSize: 14, color: ink() }}>
                {itemLabel(item, idx)}
              </span>
              {(hasInlineEditor || c.itemView) && (
                <button
                  type="button"
                  aria-expanded={hasInlineEditor ? editingIndex === idx : undefined}
                  onClick={() => handleEdit(idx)}
                  style={{ ...buttonStyle('secondary'), ...compact }}
                >
                  {editingIndex === idx ? 'Collapse' : 'Edit'}
                </button>
              )}
              {canRemove && (
                <button
                  type="button"
                  aria-label={`${c.removeLabel ?? 'Remove'} ${itemLabel(item, idx)}`}
                  onClick={() => handleRemove(idx)}
                  style={{ ...buttonStyle('secondary'), ...compact, color: tone.dangerText() }}
                >
                  {c.removeLabel ?? 'Remove'}
                </button>
              )}
            </div>

            {/* Inline editor */}
            {editingIndex === idx && hasInlineEditor && (
              <div
                style={{
                  border: `1px solid ${accentRing()}`,
                  borderTop: 'none',
                  borderRadius: '0 0 8px 8px',
                  padding: '12px 14px',
                  background: panel(),
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 10,
                }}
              >
                {scopeToIndex(c.components!, idx).map(child => (
                  <ComponentRenderer key={child.id} component={child} state={state} />
                ))}
              </div>
            )}
          </div>
        ))}

        {items.length === 0 && (
          <div style={{ fontSize: 13, color: muted(), padding: '12px 0' }}>No items yet.</div>
        )}
      </div>

      {canAdd && (
        <button
          type="button"
          onClick={handleAdd}
          style={{
            ...buttonStyle('ghost'),
            marginTop: 10,
            border: `1px dashed ${controlEdge()}`,
          }}
        >
          + {c.addLabel ?? 'Add item'}
        </button>
      )}
    </div>
  );
}
