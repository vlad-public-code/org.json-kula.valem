import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { useJSONataBoolean } from '../hooks/useJSONata';
import { getByPath } from '../hooks/useDeferredMutate';
import { ComponentRenderer } from '../ComponentRenderer';
import type { ComponentSpec } from '../types';
import type { BaseComponentProps } from '../ComponentRenderer';

/** Replaces [*] in bind paths with the concrete array index. */
function substituteIndex(components: ComponentSpec[], idx: number): ComponentSpec[] {
  return components.map(child => ({
    ...child,
    bind: child.bind?.replace('[*]', `.${idx}`),
    components: child.components ? substituteIndex(child.components, idx) : child.components,
  }));
}

function itemLabel(item: unknown, idx: number): string {
  if (typeof item !== 'object' || item === null) return String(item);
  const obj = item as Record<string, unknown>;
  if (obj.name != null) return String(obj.name);
  if (obj.title != null) return String(obj.title);
  if (obj.label != null) return String(obj.label);
  const pairs = Object.entries(obj)
    .filter(([, v]) => v != null && typeof v !== 'object')
    .slice(0, 3)
    .map(([k, v]) => `${k}: ${v}`)
    .join(', ');
  return pairs || `Item ${idx + 1}`;
}

export function SectionList({ component: c, state }: BaseComponentProps) {
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
    const defaultItem: Record<string, null> = {};
    for (const child of c.components ?? []) {
      const m = child.bind?.match(/\[\*\]\.(.+)$/);
      if (m) defaultItem[m[1]] = null;
    }
    onMutate({ [c.bind]: [...items, defaultItem] });
    if (hasInlineEditor) setEditingIndex(newIdx);
    else if (c.itemView) onNavigate(c.itemView);
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
      onNavigate(c.itemView);
    }
  }

  return (
    <div data-testid={c.id}>
      {c.label && <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>{c.label}</div>}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {items.map((item, idx) => (
          <div key={idx}>
            {/* Row */}
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '8px 12px',
                border: `1px solid ${editingIndex === idx ? '#93c5fd' : '#e5e7eb'}`,
                borderRadius: editingIndex === idx ? '6px 6px 0 0' : 6,
                background: editingIndex === idx ? '#eff6ff' : '#fff',
              }}
            >
              <span style={{ flex: 1, fontSize: 13, color: '#374151' }}>
                {itemLabel(item, idx)}
              </span>
              {(hasInlineEditor || c.itemView) && (
                <button
                  type="button"
                  onClick={() => handleEdit(idx)}
                  style={{
                    fontSize: 12,
                    padding: '2px 8px',
                    border: '1px solid #d1d5db',
                    borderRadius: 4,
                    cursor: 'pointer',
                    background: editingIndex === idx ? '#dbeafe' : '#fff',
                  }}
                >
                  {editingIndex === idx ? 'Collapse' : 'Edit'}
                </button>
              )}
              {canRemove && (
                <button
                  type="button"
                  onClick={() => handleRemove(idx)}
                  style={{
                    fontSize: 12,
                    padding: '2px 8px',
                    border: '1px solid #fca5a5',
                    borderRadius: 4,
                    cursor: 'pointer',
                    color: '#b91c1c',
                    background: '#fff',
                  }}
                >
                  {c.removeLabel ?? 'Remove'}
                </button>
              )}
            </div>

            {/* Inline editor */}
            {editingIndex === idx && hasInlineEditor && (
              <div
                style={{
                  border: '1px solid #93c5fd',
                  borderTop: 'none',
                  borderRadius: '0 0 6px 6px',
                  padding: '12px 14px',
                  background: '#f8faff',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 10,
                }}
              >
                {substituteIndex(c.components!, idx).map(child => (
                  <ComponentRenderer key={child.id} component={child} state={state} />
                ))}
              </div>
            )}
          </div>
        ))}

        {items.length === 0 && (
          <div style={{ fontSize: 13, color: '#9ca3af', padding: '12px 0' }}>No items yet.</div>
        )}
      </div>

      {canAdd && (
        <button
          type="button"
          onClick={handleAdd}
          style={{
            marginTop: 10,
            fontSize: 13,
            padding: '6px 14px',
            border: '1px dashed #93c5fd',
            borderRadius: 6,
            cursor: 'pointer',
            color: '#2563eb',
            background: '#eff6ff',
          }}
        >
          + {c.addLabel ?? 'Add item'}
        </button>
      )}
    </div>
  );
}
