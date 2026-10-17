import { useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { ComponentRenderer } from '../ComponentRenderer';
import type { ComponentSpec, ModelState } from '../types';
import { accentFill, buttonStyle, fontSans, ink, muted, onAccent, rule, ruleStrong, surface, tone } from '../themeColors';

export interface LayoutContainerProps {
  components: ComponentSpec[];
  layout?: string;
  columns?: number;
  state: ModelState;
}

/**
 * Lays out a component list, shared by `ViewRenderer` (a view's `layout`) and every container
 * component (`group`, `card`, …).
 *
 * `tabs` and `wizard` were part of the authored format from the start — `ViewSpec.layout`
 * documents both — but nothing rendered them, so a spec asking for either silently got a plain
 * vertical stack. They are implemented here rather than in a `tabs` component alone so that a
 * view-level `layout: "wizard"` and a `group` with `layout: "wizard"` behave the same way.
 *
 * Both put each direct child in its own panel, captioned by that child's `label` — which is why
 * `EvaluatedContainer` carries `label` for every container type.
 */
export function LayoutContainer({ components, layout = 'vertical', columns = 2, state }: LayoutContainerProps) {
  if (layout === 'tabs')   return <TabbedPanels components={components} state={state} />;
  if (layout === 'wizard') return <WizardPanels components={components} state={state} />;

  return (
    <div style={flowStyle(layout, columns)}>
      {components.map(c => (
        <ComponentRenderer key={c.id} component={c} state={state} />
      ))}
    </div>
  );
}

/** The three non-panelled arrangements, also used directly by card/toolbar/fieldSet. */
export function flowStyle(layout = 'vertical', columns = 2): CSSProperties {
  if (layout === 'grid') {
    // At most `columns` columns, and fewer when each would drop under 180px: a three-column fieldset
    // on a 390px phone was three 100px fields. Pure CSS, because inline styles have no media queries.
    const gap = 16;
    return {
      display: 'grid',
      gridTemplateColumns: `repeat(auto-fit, minmax(max(180px, calc((100% - ${(columns - 1) * gap}px) / ${columns})), 1fr))`,
      gap,
    };
  }
  if (layout === 'horizontal') {
    return { display: 'flex', flexDirection: 'row', flexWrap: 'wrap', gap: 16, alignItems: 'flex-end' };
  }
  return { display: 'flex', flexDirection: 'column', gap: 12 };
}

/** A component's caption, wherever it keeps one. Falls back to the id so a tab is never blank. */
export function componentLabel(c: ComponentSpec): string {
  const label = 'label' in c && typeof c.label === 'string' ? c.label : undefined;
  const legend = 'legend' in c && typeof c.legend === 'string' ? c.legend : undefined;
  return label ?? legend ?? c.id;
}

function TabbedPanels({ components, state }: { components: ComponentSpec[]; state: ModelState }) {
  const [active, setActive] = useState(0);
  // A removed or reordered child must not leave the strip pointing past the end.
  const index = Math.min(active, Math.max(components.length - 1, 0));
  const current = components[index];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div role="tablist" style={{ display: 'flex', gap: 4, borderBottom: `1px solid ${rule()}`, overflowX: 'auto' }}>
        {components.map((c, i) => (
          <button
            key={c.id}
            type="button"
            role="tab"
            aria-selected={i === index}
            data-testid={`tab-${c.id}`}
            onClick={() => setActive(i)}
            style={tabStyle(i === index)}
          >
            {componentLabel(c)}
          </button>
        ))}
      </div>
      {current && (
        <div role="tabpanel" data-testid={`tabpanel-${current.id}`}>
          <ComponentRenderer key={current.id} component={current} state={state} />
        </div>
      )}
    </div>
  );
}

function WizardPanels({ components, state }: { components: ComponentSpec[]; state: ModelState }) {
  const [step, setStep] = useState(0);
  const index = Math.min(step, Math.max(components.length - 1, 0));
  const current = components[index];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <ol style={{ display: 'flex', flexWrap: 'wrap', gap: 8, listStyle: 'none', margin: 0, padding: 0 }}>
        {components.map((c, i) => (
          <li key={c.id} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={stepBadgeStyle(i, index)}>{i + 1}</span>
            <span style={{ fontSize: 13, fontWeight: i === index ? 650 : 500, color: i === index ? ink() : muted() }}>
              {componentLabel(c)}
            </span>
            {i < components.length - 1 && <span aria-hidden="true" style={{ width: 24, height: 2, borderRadius: 1, background: i < index ? tone.successLine() : ruleStrong() }} />}
          </li>
        ))}
      </ol>

      {current && (
        <div data-testid={`step-${current.id}`}>
          <ComponentRenderer key={current.id} component={current} state={state} />
        </div>
      )}

      <div style={{ display: 'flex', gap: 8 }}>
        <WizardButton label="Back" disabled={index === 0} onClick={() => setStep(index - 1)} />
        <WizardButton
          label="Next"
          disabled={index >= components.length - 1}
          primary
          onClick={() => setStep(index + 1)}
        />
      </div>
    </div>
  );
}

function WizardButton(
  { label, disabled, primary, onClick }:
  { label: string; disabled: boolean; primary?: boolean; onClick: () => void },
): ReactNode {
  return (
    <button
      type="button"
      disabled={disabled}
      data-testid={`wizard-${label.toLowerCase()}`}
      onClick={onClick}
      style={{
        ...buttonStyle(primary ? 'primary' : 'secondary'),
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
      }}
    >
      {label}
    </button>
  );
}

function tabStyle(isActive: boolean): CSSProperties {
  return {
    minHeight: 40,
    padding: '8px 14px',
    border: 'none',
    borderBottom: isActive ? `2px solid ${accentFill()}` : '2px solid transparent',
    background: 'transparent',
    color: isActive ? ink() : muted(),
    fontFamily: fontSans,
    fontWeight: isActive ? 650 : 500,
    fontSize: 14,
    whiteSpace: 'nowrap',
    cursor: 'pointer',
    marginBottom: -1,
  };
}

function stepBadgeStyle(i: number, active: number): CSSProperties {
  const done = i < active;
  return {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: 24,
    height: 24,
    borderRadius: '50%',
    fontSize: 12,
    fontWeight: 600,
    background: i === active ? accentFill() : done ? tone.successBg() : surface(),
    color: i === active ? onAccent : done ? tone.successText() : muted(),
    border: i === active || done ? '1px solid transparent' : `1px solid ${ruleStrong()}`,
  };
}
