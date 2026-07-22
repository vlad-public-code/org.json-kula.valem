import { useState } from 'react';
import { LayoutContainer } from './LayoutContainer';
import { useJSONataBoolean } from '../hooks/useJSONata';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';

/**
 * `collapsible` — one foldable panel — and `accordion`, a stack of them.
 *
 * The accordion does not coordinate its children: each `collapsible` inside it owns its own open
 * state, so several can be open at once. Single-open behaviour would mean the accordion closing a
 * panel the user opened, which reads as the form fighting back, and it would need the parent to
 * reach into children it renders only generically.
 *
 * `collapsed` seeds the initial state and is not a live binding — see `ContainerSpec` in
 * valem-view. Driving it from state would make a section slam shut mid-edit whenever an unrelated
 * field changed; `visible` is the field for "this section should not be here".
 */
export function CollapsibleComponent({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  const initiallyCollapsed = useJSONataBoolean(c.collapsed, state, false);
  const [open, setOpen] = useState<boolean | null>(null);
  const isOpen = open ?? !initiallyCollapsed;

  if (c.type === 'accordion') {
    return (
      <div
        data-testid={c.id}
        style={{ border: '1px solid #e5e7eb', borderRadius: 8, overflow: 'hidden' }}
      >
        {c.label && (
          <div style={{
            padding: '8px 14px',
            background: '#f9fafb',
            borderBottom: '1px solid #e5e7eb',
            fontSize: 13,
            fontWeight: 600,
          }}>
            {c.label}
          </div>
        )}
        <LayoutContainer components={c.components ?? []} layout="vertical" state={state} />
      </div>
    );
  }

  return (
    <section data-testid={c.id} style={{ border: '1px solid #e5e7eb', borderRadius: 8 }}>
      <button
        type="button"
        aria-expanded={isOpen}
        data-testid={`${c.id}-toggle`}
        onClick={() => setOpen(!isOpen)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          width: '100%',
          padding: '10px 14px',
          border: 'none',
          background: 'transparent',
          fontSize: 14,
          fontWeight: 600,
          color: '#111827',
          cursor: 'pointer',
          textAlign: 'left',
        }}
      >
        <span style={{
          display: 'inline-block',
          transform: isOpen ? 'rotate(90deg)' : 'none',
          transition: 'transform 0.15s',
          color: '#6b7280',
        }}>
          ▶
        </span>
        {c.label ?? c.id}
      </button>
      {isOpen && (
        <div style={{ padding: '0 14px 14px' }}>
          <LayoutContainer
            components={c.components ?? []}
            layout={c.layout}
            columns={c.columns}
            state={state}
          />
        </div>
      )}
    </section>
  );
}
