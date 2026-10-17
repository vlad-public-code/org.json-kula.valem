import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { fieldColors } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { BasicInputSpec } from '../types';
import { controlBox, helperText, labelText, requiredMark } from '../themeColors';

export function TimeField({ component: c, enabled, readOnly, required }: BaseComponentProps<BasicInputSpec>) {
  const { state, onMutate } = useViewContext();
  const bindKey = c.bind?.replace(/^\$\./, '');
  const value = bindKey ? String(getByPath(state, bindKey) ?? '') : '';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}
      <input
        type="time"
        value={value}
        disabled={!enabled}
        readOnly={readOnly}
        style={{
          ...controlBox,
          background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg,
          color: fieldColors.text,
        }}
        onChange={e => {
          if (c.bind && !readOnly) {
            onMutate({ [c.bind]: e.target.value });
          }
        }}
      />
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
