import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';

export function TimeField({ component: c, enabled, readOnly, required }: BaseComponentProps) {
  const { state, onMutate } = useViewContext();
  const bindKey = c.bind?.replace(/^\$\./, '');
  const value = bindKey ? String(getByPath(state, bindKey) ?? '') : '';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      <input
        type="time"
        value={value}
        disabled={!enabled}
        readOnly={readOnly}
        style={{
          padding: '6px 10px',
          border: '1px solid #ccc',
          borderRadius: 4,
          fontSize: 14,
          background: readOnly ? '#f5f5f5' : '#fff',
        }}
        onChange={e => {
          if (c.bind && !readOnly) {
            onMutate({ [c.bind]: e.target.value });
          }
        }}
      />
      {c.helperText && <span style={{ fontSize: 11, color: '#666' }}>{c.helperText}</span>}
    </div>
  );
}
