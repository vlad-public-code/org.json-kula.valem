import type { ProvenanceInfo } from './types';
import { formatProvenanceValue } from './provenance';

// The "Why is this number?" popover shown when a derived leaf is hovered/focused (F1). Inline-styled
// (this package ships no stylesheet) and theme-token-based so it reads correctly in light and dark.
// Positioned above the leaf; the parent wrapper is position:relative.

export function ProvenancePopover({ info }: { info: ProvenanceInfo }) {
  return (
    <div
      role="tooltip"
      data-testid="provenance-popover"
      style={{
        position: 'absolute',
        bottom: 'calc(100% + 6px)',
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
