import { useState, useCallback } from 'react';
import type { ViewDefinition, ModelState, MetaCache, MutationMap } from './types';
import { ViewContext } from './ViewContext';
import { LayoutContainer } from './aggregates/LayoutContainer';
import { useJSONataBoolean, useJSONataText } from './hooks/useJSONata';

export interface ViewRendererProps {
  modelId: string;
  viewDef: ViewDefinition;
  state: ModelState;
  meta: MetaCache;
  onMutate: (mutations: MutationMap) => Promise<void>;
  onNavigate?: (viewId: string) => void;
  activeViewId?: string;
  /** Constraint violations keyed by the bound path they resolved to. */
  violations?: Record<string, string>;
  /**
   * Violations that resolved to no path. Passing these lets a `validationSummary` show the
   * constraints that have no field to sit beside — the ones that motivate the component.
   */
  formErrors?: string[];
}

/**
 * Root renderer component. Evaluates all dynamic expressions from the ViewDefinition
 * client-side and renders the component tree for the active view.
 */
export function ViewRenderer({
  modelId,
  viewDef,
  state,
  meta,
  onMutate,
  onNavigate,
  activeViewId: externalViewId,
  violations = {},
  formErrors = [],
}: ViewRendererProps) {
  const [internalViewId, setInternalViewId] = useState<string>(
    externalViewId ?? viewDef.defaultView ?? viewDef.views[0]?.id ?? '',
  );
  const activeViewId = externalViewId ?? internalViewId;

  const handleNavigate = useCallback(
    (viewId: string) => {
      setInternalViewId(viewId);
      onNavigate?.(viewId);
    },
    [onNavigate],
  );

  const view = viewDef.views.find(v => v.id === activeViewId) ?? viewDef.views[0];
  if (!view) return null;

  return (
    <ViewContext.Provider
      value={{
        modelId, state, meta, onMutate,
        onNavigate: handleNavigate, activeViewId,
        fieldErrors: violations, formErrors,
      }}
    >
      {/*
        `tabs` and `wizard` have always been legal values of `ViewSpec.layout`, but nothing
        rendered them — a view asking for either silently got a vertical stack. LayoutContainer
        implements all five, and is shared with the container components so a view-level
        `layout: "wizard"` and a `group` with the same layout behave identically.
      */}
      <LayoutContainer
        components={view.components}
        layout={view.layout}
        columns={view.columns}
        state={state}
      />
    </ViewContext.Provider>
  );
}

// ── Helpers exported for use in field components ──────────────────────────────

export { useJSONataBoolean, useJSONataText };
