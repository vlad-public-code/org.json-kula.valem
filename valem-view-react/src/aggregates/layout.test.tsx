import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import { ViewContext } from '../ViewContext';
import { LayoutContainer, componentLabel } from './LayoutContainer';
import { renderComponent } from '../test/renderComponent';
import type { ComponentSpec } from '../types';

const panels: ComponentSpec[] = [
  { id: 'one', type: 'tabItem', label: 'First', components: [{ id: 'f1', type: 'staticText', text: 'body one' }] },
  { id: 'two', type: 'tabItem', label: 'Second', components: [{ id: 'f2', type: 'staticText', text: 'body two' }] },
];

function renderLayout(layout: string, components = panels) {
  const onMutate = vi.fn().mockResolvedValue(undefined);
  return render(
    <ViewContext.Provider
      value={{
        modelId: 'm', state: {}, meta: {}, onMutate, onNavigate: vi.fn(),
        activeViewId: 'main', fieldErrors: {}, formErrors: [],
      }}
    >
      <LayoutContainer components={components} layout={layout} state={{}} />
    </ViewContext.Provider>,
  );
}

describe('LayoutContainer — tabs', () => {
  it('mounts only the active panel', () => {
    // Both were rendered as a plain stack before tabs existed; only one should be present now.
    renderLayout('tabs');
    expect(screen.getByTestId('tabpanel-one')).toBeInTheDocument();
    expect(screen.queryByTestId('tabpanel-two')).not.toBeInTheDocument();
  });

  it('switches panels on click and tracks aria-selected', () => {
    renderLayout('tabs');
    fireEvent.click(screen.getByTestId('tab-two'));
    expect(screen.getByTestId('tabpanel-two')).toBeInTheDocument();
    expect(screen.getByTestId('tab-two')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('tab-one')).toHaveAttribute('aria-selected', 'false');
  });

  it('captions each tab from the child label', () => {
    renderLayout('tabs');
    expect(screen.getByTestId('tab-one')).toHaveTextContent('First');
  });

  it('survives an empty component list', () => {
    renderLayout('tabs', []);
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
  });
});

describe('LayoutContainer — wizard', () => {
  it('shows one step with Back disabled at the start', () => {
    renderLayout('wizard');
    expect(screen.getByTestId('step-one')).toBeInTheDocument();
    expect(screen.getByTestId('wizard-back')).toBeDisabled();
    expect(screen.getByTestId('wizard-next')).toBeEnabled();
  });

  it('advances and disables Next on the last step', () => {
    renderLayout('wizard');
    fireEvent.click(screen.getByTestId('wizard-next'));
    expect(screen.getByTestId('step-two')).toBeInTheDocument();
    expect(screen.getByTestId('wizard-next')).toBeDisabled();
    expect(screen.getByTestId('wizard-back')).toBeEnabled();
  });

  it('goes back again', () => {
    renderLayout('wizard');
    fireEvent.click(screen.getByTestId('wizard-next'));
    fireEvent.click(screen.getByTestId('wizard-back'));
    expect(screen.getByTestId('step-one')).toBeInTheDocument();
  });
});

describe('LayoutContainer — flow layouts', () => {
  it('renders every child for vertical, horizontal and grid', () => {
    for (const layout of ['vertical', 'horizontal', 'grid']) {
      const { unmount } = renderLayout(layout);
      expect(screen.getByTestId('f1')).toBeInTheDocument();
      expect(screen.getByTestId('f2')).toBeInTheDocument();
      unmount();
    }
  });
});

describe('componentLabel', () => {
  it('prefers label, then legend, then falls back to the id so a tab is never blank', () => {
    expect(componentLabel({ id: 'x', type: 'group', label: 'L' })).toBe('L');
    expect(componentLabel({ id: 'x', type: 'fieldSet', legend: 'G' })).toBe('G');
    expect(componentLabel({ id: 'x', type: 'group' })).toBe('x');
  });
});

describe('container chrome', () => {
  it('renders a card heading from its label', () => {
    renderComponent({ id: 'c', type: 'card', label: 'Costs', components: [] });
    expect(screen.getByTestId('c')).toHaveTextContent('Costs');
  });

  it('collapsible starts open, closes on toggle, and respects an initial collapsed', () => {
    const spec = { id: 'p', type: 'collapsible' as const, label: 'More',
      components: [{ id: 'inner', type: 'staticText' as const, text: 'hidden?' }] };

    const { unmount } = renderComponent(spec);
    expect(screen.getByTestId('inner')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('p-toggle'));
    expect(screen.queryByTestId('inner')).not.toBeInTheDocument();
    unmount();

    renderComponent({ ...spec, collapsed: true });
    expect(screen.queryByTestId('inner')).not.toBeInTheDocument();
  });

  it('buttonGroup and toolbar both render their children in a row', () => {
    renderComponent({ id: 'tb', type: 'toolbar', components: [{ id: 'b1', type: 'button', label: 'Go' }] });
    expect(screen.getByTestId('tb')).toHaveAttribute('role', 'toolbar');

    renderComponent({ id: 'bg', type: 'buttonGroup', components: [{ id: 'b2', type: 'button', label: 'Go' }] });
    expect(screen.getByTestId('bg')).toHaveAttribute('role', 'group');
  });
});

describe('stepper and breadcrumb', () => {
  const items = [
    { label: 'Plan', targetView: 'plan' },
    { label: 'Summary', targetView: 'summary' },
  ];

  it('marks the active step from the active view id, not its own state', () => {
    // Position is the active view id, so it survives a reload and cannot disagree with the screen.
    renderComponent({ id: 's', type: 'stepper', menuItems: items }, { activeViewId: 'summary' });
    expect(screen.getByTestId('s-step-summary')).toHaveAttribute('aria-current', 'step');
    expect(screen.getByTestId('s-step-plan')).not.toHaveAttribute('aria-current');
  });

  it('navigates on click', () => {
    const { onNavigate } = renderComponent({ id: 's', type: 'stepper', menuItems: items }, { activeViewId: 'plan' });
    fireEvent.click(screen.getByTestId('s-step-summary'));
    expect(onNavigate).toHaveBeenCalledWith('summary');
  });

  it('breadcrumb marks the current crumb as the page', () => {
    renderComponent({ id: 'b', type: 'breadcrumb', menuItems: items }, { activeViewId: 'summary' });
    expect(screen.getByTestId('b-crumb-summary')).toHaveAttribute('aria-current', 'page');
  });
});
