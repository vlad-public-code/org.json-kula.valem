import { useLayoutEffect, useRef, useState } from 'react';
import type { ProvenanceInfo } from './types';
import { formatProvenanceValue } from './provenance';

// The "Why is this number?" popover shown when a derived leaf is hovered/focused (F1). Inline-styled
// (this package ships no stylesheet) and theme-token-based so it reads correctly in light and dark.
// Placed above the leaf by default (parent wrapper is position:relative), but flips below when a leaf
// near the top of a scrolling panel would otherwise be clipped by the panel's top edge — which sits
// under the sandbox header, so the "above" placement read as partly hidden behind the header.

// Walk up from the popover to the nearest scroll/clip ancestor and return the y of its top edge; that
// edge (just below the header) is what clips an above-placed popover. Falls back to the viewport top.
function clipTopFor(el: HTMLElement): number {
  for (let node = el.parentElement; node; node = node.parentElement) {
    const oy = getComputedStyle(node).overflowY;
    if (oy === 'auto' || oy === 'scroll' || oy === 'hidden') {
      return node.getBoundingClientRect().top;
    }
  }
  return 0;
}

export function ProvenancePopover({ info }: { info: ProvenanceInfo }) {
  const ref = useRef<HTMLDivElement>(null);
  const [below, setBelow] = useState(false);

  // Measure once the popover is in the DOM (before paint, so there's no visible flip): if the
  // above-placed box overshoots the clipping edge, render it below the leaf instead.
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    setBelow(rect.top < clipTopFor(el));
  }, [info]);

  return (
    <div
      ref={ref}
      role="tooltip"
      data-testid="provenance-popover"
      data-placement={below ? 'below' : 'above'}
      style={{
        position: 'absolute',
        ...(below ? { top: 'calc(100% + 6px)' } : { bottom: 'calc(100% + 6px)' }),
        left: 0,
        zIndex: 60,
        minWidth: 220,
        maxWidth: 360,
        padding: '8px 10px',
        background: 'var(--panel-bg, #fff)',
        color: 'var(--text, #16161f)',
        border: '1px solid var(--border, #d7d8e0)',
        borderRadius: 8,
        boxShadow: 'var(--shadow, 0 6px 24px -8px rgba(20,22,45,0.28))',
        fontSize: 12,
        lineHeight: 1.5,
        textAlign: 'left',
        cursor: 'default',
        pointerEvents: 'none', // never steal the hover from the leaf beneath it
      }}
    >
      <div style={{ fontWeight: 650, marginBottom: 4 }}>
        Why is this {info.label}?
      </div>

      {info.expression != null && (
        <pre style={{
          fontFamily: 'var(--font-mono, ui-monospace, monospace)', fontSize: 11.5, margin: '0 0 6px',
          padding: '5px 7px', background: 'var(--surface-inset, rgba(0,0,0,0.04))',
          border: '1px solid var(--border, #d7d8e0)', borderRadius: 5, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
        }}>{info.expression}</pre>
      )}

      {info.inputs.length > 0 ? (
        <div>
          <div style={{ fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted, #6a6b7c)', marginBottom: 2 }}>
            from
          </div>
          {info.inputs.map(inp => (
            <div key={inp.path} style={{ fontFamily: 'var(--font-mono, ui-monospace, monospace)', fontSize: 11.5 }}>
              {inp.label}
              <span style={{ color: 'var(--text-muted, #6a6b7c)' }}> = {formatProvenanceValue(inp.value)}</span>
            </div>
          ))}
        </div>
      ) : (
        <div style={{ color: 'var(--text-light, #85869a)', fontStyle: 'italic' }}>
          inputs can’t be traced automatically
        </div>
      )}

      {/* F3 — the editable base fields this value ultimately traces back to (only when some input is
           itself derived, so it adds information beyond the direct `from` list above). */}
      {info.baseInputs && info.baseInputs.length > 0 && (
        <div style={{ marginTop: 6, paddingTop: 6, borderTop: '1px solid var(--border, #e5e6ee)' }}>
          <div style={{ fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted, #6a6b7c)', marginBottom: 2 }}>
            ultimately from
          </div>
          {info.baseInputs.map(inp => (
            <div key={inp.path} style={{ fontFamily: 'var(--font-mono, ui-monospace, monospace)', fontSize: 11.5 }}>
              {inp.label}
              <span style={{ color: 'var(--text-muted, #6a6b7c)' }}> = {formatProvenanceValue(inp.value)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
