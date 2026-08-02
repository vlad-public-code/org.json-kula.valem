import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderComponent } from './test/renderComponent';
import type { ProvenanceRuntime } from './ViewContext';
import type { BasicInputSpec, ProvenanceInfo, StaticTextSpec } from './types';

/*
 * In-view "Why is this number?" overlay. Covers F1/F2 (popover + in-place input highlight), F3
 * (transitive base inputs), F7 (inline-expression leaves), F11 (cross-highlight selection), and F12
 * (live pulse). Driven entirely by the ProvenanceRuntime supplied through ViewContext.
 */

const numeric = (over: Partial<BasicInputSpec> = {}): BasicInputSpec => ({
  id: 'f', type: 'numericField', bind: '$.total', label: 'Total', ...over,
});

const totalInfo: ProvenanceInfo = {
  path: '$.total', kind: 'DERIVED', label: 'total', expression: 'subtotal + tax', value: 120,
  inputs: [
    { path: '$.subtotal', label: 'subtotal', value: 100 },
    { path: '$.tax', label: 'tax', value: 20 },
  ],
  baseInputs: [
    { path: '$.price', label: 'price', value: 130 },
    { path: '$.discount', label: 'discount', value: 10 },
  ],
};

// An inline staticText whose text is a JSONata expression reading a field (F7). Must contain `$`
// for the renderer's $-gate to treat it as JSONata rather than literal text.
const inlineText = (): StaticTextSpec => ({
  id: 'lbl', type: 'staticText', text: '"$" & $string(total)',
});

const inlineInfo: ProvenanceInfo = {
  path: '', kind: 'DERIVED', label: 'this value', expression: '"$" & $string(total)', value: '$120',
  inputs: [{ path: '$.total', label: 'total', value: 120 }],
};

function runtime(over: Partial<ProvenanceRuntime> = {}): ProvenanceRuntime {
  return {
    source: {
      explain: (p) => (p === '$.total' ? totalInfo : null),
      explainExpression: (expr) => (expr.includes('total') ? inlineInfo : null),
    },
    hoveredLeafId: null,
    onHover: () => {},
    highlightedPaths: new Set<string>(),
    selectedPath: null,
    onSelect: () => {},
    pulsingPaths: new Set<string>(),
    ...over,
  };
}

describe('in-view provenance overlay', () => {
  it('does not wrap or explain when no provenance source is supplied', () => {
    renderComponent(numeric(), { state: { total: 120 } });
    expect(screen.queryByTestId('provenance-target')).toBeNull();
    expect(screen.queryByTestId('provenance-popover')).toBeNull();
  });

  it('marks a derived leaf as an explainable target; a base input is not', () => {
    renderComponent(numeric(), { state: { total: 120 }, provenance: runtime() });
    expect(screen.getByTestId('provenance-target')).toBeInTheDocument();

    renderComponent(numeric({ bind: '$.subtotal', label: 'Subtotal' }),
      { state: { subtotal: 100 }, provenance: runtime() });
    expect(screen.queryByTestId('provenance-target')).toBeInTheDocument(); // still only the first one
  });

  it('reports the hovered leaf id and its input paths on enter/leave', () => {
    const calls: [string | null, string[]][] = [];
    renderComponent(numeric(), {
      state: { total: 120 },
      provenance: runtime({ onHover: (id, paths) => calls.push([id, paths]) }),
    });
    const target = screen.getByTestId('provenance-target');
    fireEvent.mouseEnter(target);
    fireEvent.mouseLeave(target);
    expect(calls).toEqual([['$.total', ['$.subtotal', '$.tax']], [null, []]]);
  });

  it('shows the popover with expression, direct inputs, and transitive base inputs (F1/F3)', () => {
    renderComponent(numeric(), { state: { total: 120 }, provenance: runtime({ hoveredLeafId: '$.total' }) });
    const pop = screen.getByTestId('provenance-popover');
    expect(pop).toHaveTextContent('subtotal + tax');
    expect(pop).toHaveTextContent('subtotal');
    expect(pop).toHaveTextContent('100');
    expect(pop).toHaveTextContent('ultimately from'); // F3 base-inputs section
    expect(pop).toHaveTextContent('price');
    expect(pop).toHaveTextContent('discount');
    expect(pop.getAttribute('data-placement')).toBe('above'); // default: fits above the leaf
    expect(pop.style.bottom).toBe('calc(100% + 6px)');
  });

  it('flips the popover below the leaf when the above placement would be clipped by the header', () => {
    // Simulate a leaf near the top of a scroll panel: the above-placed box lands above the clip edge
    // (viewport top = 0 here), so it must flip below rather than hide behind the header.
    const orig = Element.prototype.getBoundingClientRect;
    Element.prototype.getBoundingClientRect = function () {
      return { top: -12, bottom: 0, left: 0, right: 0, width: 0, height: 0, x: 0, y: 0, toJSON: () => ({}) } as DOMRect;
    };
    try {
      renderComponent(numeric(), { state: { total: 120 }, provenance: runtime({ hoveredLeafId: '$.total' }) });
      const pop = screen.getByTestId('provenance-popover');
      expect(pop.getAttribute('data-placement')).toBe('below');
      expect(pop.style.top).toBe('calc(100% + 6px)');
      expect(pop.style.bottom).toBe('');
    } finally {
      Element.prototype.getBoundingClientRect = orig;
    }
  });

  it('outlines an input leaf that is in the hovered node’s highlight set (F2)', () => {
    renderComponent(numeric({ bind: '$.subtotal', label: 'Subtotal' }), {
      state: { subtotal: 100 },
      provenance: runtime({ highlightedPaths: new Set(['$.subtotal']) }),
    });
    const wrapper = document.querySelector('[data-bind="$.subtotal"]') as HTMLElement;
    expect(wrapper.style.outline).toContain('solid');
  });

  it('explains an inline-expression leaf that has no single bind (F7)', () => {
    renderComponent(inlineText(), { state: { total: 120 }, provenance: runtime({ hoveredLeafId: '#lbl' }) });
    expect(screen.getByTestId('provenance-target')).toBeInTheDocument();
    const pop = screen.getByTestId('provenance-popover');
    expect(pop).toHaveTextContent('$string(total)');
    expect(pop).toHaveTextContent('total');
  });

  it('reports a selection on click, and marks the selected leaf (F11)', () => {
    const selected: (string | null)[] = [];
    renderComponent(numeric(), {
      state: { total: 120 },
      provenance: runtime({ selectedPath: '$.total', onSelect: (p) => selected.push(p) }),
    });
    const wrapper = document.querySelector('[data-bind="$.total"]') as HTMLElement;
    expect(wrapper.getAttribute('data-selected')).toBe('1');
    fireEvent.click(screen.getByTestId('provenance-target'));
    expect(selected).toEqual(['$.total']);
  });

  it('flashes a leaf whose path is pulsing from a live update (F12)', () => {
    renderComponent(numeric(), {
      state: { total: 120 },
      provenance: runtime({ pulsingPaths: new Set(['$.total']) }),
    });
    const wrapper = document.querySelector('[data-bind="$.total"]') as HTMLElement;
    expect(wrapper.getAttribute('data-pulsing')).toBe('1');
  });
});
