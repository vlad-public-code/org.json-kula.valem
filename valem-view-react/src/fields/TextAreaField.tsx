import { useViewContext } from '../ViewContext';
import { useDeferredMutate } from '../hooks/useDeferredMutate';
import { fieldColors } from './fieldColors';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { TextAreaSpec } from '../types';
import { controlBox, helperText, labelText, requiredMark } from '../themeColors';

export function TextAreaField({ component: c, enabled, readOnly, required }: BaseComponentProps<TextAreaSpec>) {
  const { state } = useViewContext();
  const { draft, schedule, handleBlur } = useDeferredMutate(c.bind, state);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label htmlFor={c.id} style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
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
          ...controlBox,
          resize: 'vertical',
          background: readOnly ? fieldColors.bgReadOnly : fieldColors.bg,
          color: fieldColors.text,
        }}
        onChange={e => { if (!readOnly) schedule(e.target.value, e.target.value); }}
        onBlur={handleBlur}
      />
      {c.helperText && <span style={helperText}>{c.helperText}</span>}
    </div>
  );
}
