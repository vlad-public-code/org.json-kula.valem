import { useViewContext } from '../ViewContext';
import { useDeferredMutate } from '../hooks/useDeferredMutate';
import { fieldInput } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';
import { helperText, labelText, requiredMark } from '../themeColors';

export function TextField({ component: c, enabled, readOnly, required, text }: BaseComponentProps<BasicInputSpec>) {
  const { state } = useViewContext();
  const { draft, schedule, handleBlur } = useDeferredMutate(c.bind, state);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {(c.label || text) && (
        <label htmlFor={c.id} style={labelText}>
          {c.label ?? text}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
      <input
        id={c.id}
        type="text"
        value={draft}
        placeholder={c.placeholder ?? ''}
        disabled={!enabled}
        readOnly={readOnly}
        style={fieldInput(readOnly)}
        onChange={e => { if (!readOnly) schedule(e.target.value, e.target.value); }}
        onBlur={handleBlur}
      />
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
