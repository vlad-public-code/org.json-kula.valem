import { ComponentRenderer } from '../ComponentRenderer';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ContainerSpec } from '../types';
import { ink, panel, radius, rule } from '../themeColors';
import { flowStyle } from './LayoutContainer';

export function FieldSetComponent({ component: c, state }: BaseComponentProps<ContainerSpec>) {
  const children = c.components ?? [];

  return (
    <fieldset
      data-testid={c.id}
      style={{
        border: `1px solid ${rule()}`,
        borderRadius: radius.md,
        padding: '16px',
        margin: 0,
        // A fieldset's default min-width is min-content, which lets a wide child push the whole
        // page sideways on a phone.
        minWidth: 0,
        background: panel(),
      }}
    >
      {c.legend && (
        // Floated, the legend leaves the border and sits inside the box as its heading, which reads
        // as a card section rather than a 1990s form group straddling a rounded corner.
        <legend style={{ float: 'left', width: '100%', padding: 0, margin: '0 0 12px', fontSize: 15, fontWeight: 650, color: ink() }}>
          {c.legend}
        </legend>
      )}
      {/* The spec's layout and columns apply here as on any container (they were ignored). */}
      <div style={{ ...flowStyle(c.layout, c.columns), clear: 'both' }}>
        {children.map(child => (
          <ComponentRenderer key={child.id} component={child} state={state} />
        ))}
      </div>
    </fieldset>
  );
}
