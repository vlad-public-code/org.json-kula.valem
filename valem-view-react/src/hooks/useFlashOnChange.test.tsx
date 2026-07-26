import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { ViewContext } from '../ViewContext';
import { ComponentRenderer } from '../ComponentRenderer';
import type { ModelState, StatTileSpec } from '../types';

// StatTile is a pure derived-value display, so it's the natural probe: its headline number changes
// only when the model recomputes, which is exactly when the flash should fire.
const tile: StatTileSpec = {
  id: 'total', type: 'statTile', label: 'Total', bind: '$.total', format: 'currency', currency: 'USD',
};

function tree(state: ModelState) {
  return (
    <ViewContext.Provider
      value={{
        modelId: 'm', state, meta: {},
        onMutate: vi.fn().mockResolvedValue(undefined), onNavigate: vi.fn(),
        activeViewId: 'main', fieldErrors: {}, formErrors: [],
      }}
    >
      <ComponentRenderer component={tile} state={state} />
    </ViewContext.Provider>
  );
}

// jsdom implements neither element.animate nor matchMedia; stand both up so the hook's real path runs.
const animate = vi.fn();
const hadAnimate = 'animate' in Element.prototype;

function setReducedMotion(reduce: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: reduce, media: query, onchange: null,
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

beforeEach(() => {
  animate.mockClear();
  (Element.prototype as unknown as { animate: unknown }).animate = animate;
  setReducedMotion(false);
});

afterEach(() => {
  if (!hadAnimate) delete (Element.prototype as unknown as { animate?: unknown }).animate;
});

describe('useFlashOnChange (wired through StatTile)', () => {
  it('does not flash on first render', () => {
    render(tree({ total: 100 }));
    expect(animate).not.toHaveBeenCalled();
  });

  it('flashes emerald when the value recomputes', () => {
    const { rerender } = render(tree({ total: 100 }));
    rerender(tree({ total: 250 }));

    expect(animate).toHaveBeenCalledTimes(1);
    const [keyframes, options] = animate.mock.calls[0];
    expect(keyframes[0]).toMatchObject({ backgroundColor: '#d6f5e6' });
    expect(options).toMatchObject({ duration: 650 });
  });

  it('does not flash when the value is unchanged across a re-render', () => {
    const { rerender } = render(tree({ total: 100 }));
    rerender(tree({ total: 100 }));
    expect(animate).not.toHaveBeenCalled();
  });

  it('respects prefers-reduced-motion', () => {
    setReducedMotion(true);
    const { rerender } = render(tree({ total: 100 }));
    rerender(tree({ total: 250 }));
    expect(animate).not.toHaveBeenCalled();
  });

  it('is a no-op where element.animate is unavailable', () => {
    delete (Element.prototype as unknown as { animate?: unknown }).animate;
    const { rerender } = render(tree({ total: 100 }));
    expect(() => rerender(tree({ total: 250 }))).not.toThrow();
  });
});
