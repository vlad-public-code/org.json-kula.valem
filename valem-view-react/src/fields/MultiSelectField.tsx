import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ChoiceInputSpec } from '../types';

export function MultiSelectField({ component: c, enabled, readOnly, required }: BaseComponentProps<ChoiceInputSpec>) {
  const { state, onMutate } = useViewContext();
  const raw = c.bind ? getByPath(state, c.bind.replace(/^\$\./, '')) : undefined;
  const selected: string[] = Array.isArray(raw) ? (raw as string[]) : [];
  const options = c.options ?? [];

  function toggle(val: string) {
    if (!c.bind || readOnly) return;
    const next = selected.includes(val) ? selected.filter(v => v !== val) : [...selected, val];
    onMutate({ [c.bind]: next });
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {c.label && (
        <label style={{ fontSize: 13, fontWeight: 500 }}>
          {c.label}
          {required && <span style={{ color: 'red', marginLeft: 2 }}>*</span>}
        </label>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {options.map(o => (
          <label
            key={o.value}
            style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, cursor: enabled && !readOnly ? 'pointer' : 'default' }}
          >
            <input
              type="checkbox"
              checked={selected.includes(o.value)}
              disabled={!enabled || readOnly}
              onChange={() => toggle(o.value)}
            />
            {o.label}
          </label>
        ))}
      </div>
    </div>
  );
}
