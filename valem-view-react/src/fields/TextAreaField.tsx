import { useViewContext } from '../ViewContext';
import { useDeferredMutate } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { TextAreaSpec } from '../types';

export function TextAreaField({ component: c, enabled, readOnly, required }: BaseComponentProps<TextAreaSpec>) {
  const { state } = useViewContext();
  const { draft, schedule, handleBlur } = useDeferredMutate(c.bind, state);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      <textarea
        id={c.id}
        value={draft}
        placeholder={c.placeholder ?? ''}
        disabled={!enabled}
        readOnly={readOnly}
        rows={c.rows ?? 4}
        style={{
          padding: '6px 10px',
          border: '1px solid #ccc',
          borderRadius: 4,
          fontSize: 14,
          resize: 'vertical',
          background: readOnly ? '#f5f5f5' : '#fff',
        }}
        onChange={e => { if (!readOnly) schedule(e.target.value, e.target.value); }}
        onBlur={handleBlur}
      />
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}
