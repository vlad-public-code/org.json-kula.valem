import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';

export function ToggleField({ component: c, enabled, readOnly, required }: BaseComponentProps<BasicInputSpec>) {
  const { state, onMutate } = useViewContext();
  const checked = c.bind ? Boolean(getByPath(state, c.bind.replace(/^\$\./, ''))) : false;
  const interactive = enabled && !readOnly;

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        disabled={!interactive}
        onClick={() => {
          if (c.bind && interactive) {
            onMutate({ [c.bind]: !checked });
          }
        }}
        style={{
          width: 44,
          height: 24,
          borderRadius: 12,
          border: 'none',
          background: checked ? '#2563eb' : '#d1d5db',
          position: 'relative',
          cursor: interactive ? 'pointer' : 'default',
          opacity: !enabled ? 0.5 : 1,
          transition: 'background 0.15s',
          flexShrink: 0,
        }}
      >
        <span
          style={{
            position: 'absolute',
            top: 2,
            left: checked ? 22 : 2,
            width: 20,
            height: 20,
            borderRadius: '50%',
            background: '#fff',
            transition: 'left 0.15s',
            boxShadow: '0 1px 3px rgba(0,0,0,.3)',
          }}
        />
      </button>
      {c.label && (
        <span style={{ fontSize: 14 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </span>
      )}
    </div>
  );
}
