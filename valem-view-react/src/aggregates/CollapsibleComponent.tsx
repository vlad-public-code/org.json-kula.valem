import { useState } from 'react';
import { LayoutContainer } from './LayoutContainer';
import { useJSONataBoolean } from '../hooks/useJSONata';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';
import { fontSans, ink, muted, panel, radius, rule, surface } from '../themeColors';

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
        style={{ border: `1px solid ${rule()}`, borderRadius: radius.md, overflow: 'hidden', background: panel() }}
      >
        {c.label && (
          <div style={{
            padding: '10px 14px',
            background: surface(),
            borderBottom: `1px solid ${rule()}`,
            fontSize: 14,
            fontWeight: 650,
            color: ink(),
          }}>
            {c.label}
          </div>
        )}
        <div style={{ padding: '12px 14px' }}>
          <LayoutContainer components={c.components ?? []} layout="vertical" state={state} />
        </div>
      </div>
    );
  }

  return (
    <section data-testid={c.id} style={{ border: `1px solid ${rule()}`, borderRadius: radius.md, background: panel() }}>
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
          minHeight: 44,
          padding: '10px 14px',
          border: 'none',
          background: 'transparent',
          fontFamily: fontSans,
          fontSize: 14,
          fontWeight: 650,
          color: ink(),
          cursor: 'pointer',
          textAlign: 'left',
        }}
      >
        <span aria-hidden="true" style={{
          display: 'inline-block',
          fontSize: 11,
          transform: isOpen ? 'rotate(90deg)' : 'none',
          transition: 'transform 0.15s',
          color: muted(),
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
