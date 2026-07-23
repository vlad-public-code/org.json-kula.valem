import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { vi } from 'vitest';
import { ViewContext } from '../ViewContext';
import type { ComponentSpec, ModelState, MutationMap } from '../types';
import { ComponentRenderer } from '../ComponentRenderer';

export interface Harness {
  state?: ModelState;
  meta?: Record<string, unknown>;
  fieldErrors?: Record<string, string>;
  formErrors?: string[];
  activeViewId?: string;
}

/**
 * Renders one component inside a real `ViewContext`, returning the mutation spy alongside the
 * usual render result.
 *
 * Going through `ComponentRenderer` rather than the concrete component is deliberate: the
 * visible/enabled/readOnly resolution and the `isKnownComponent` narrowing are part of what these
 * tests are checking, and a test that instantiated `TagsField` directly would skip both.
 */
export function renderComponent(component: ComponentSpec, harness: Harness = {}) {
  const onMutate = vi.fn<(m: MutationMap) => Promise<void>>().mockResolvedValue(undefined);
  const onNavigate = vi.fn<(id: string) => void>();
  const state = harness.state ?? {};

  const ui: ReactElement = (
    <ViewContext.Provider
      value={{
        modelId: 'test-model',
        state,
        meta: harness.meta ?? {},
        onMutate,
        onNavigate,
        activeViewId: harness.activeViewId ?? 'main',
        fieldErrors: harness.fieldErrors ?? {},
        formErrors: harness.formErrors ?? [],
      }}
    >
      <ComponentRenderer component={component} state={state} />
    </ViewContext.Provider>
  );

  return { ...render(ui), onMutate, onNavigate };
}
