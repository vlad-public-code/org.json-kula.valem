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
  /**
   * Render every input disabled and swallow mutations — the view becomes a read-only computed
   * snapshot. Used by read-only embeds; defaults to false so the sandbox app is unaffected.
   */
  readOnly?: boolean;
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
  readOnly = false,
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

  // A read-only view still lets its inputs render (disabled — see ComponentRenderer), but no edit
  // must ever reach the server. Guard onMutate here as well as at each field so an action component
  // that calls onMutate directly (a button, a stepper) can't slip a mutation through either.
  const effectiveOnMutate = readOnly ? async () => {} : onMutate;

  return (
    <ViewContext.Provider
      value={{
        modelId, state, meta, onMutate: effectiveOnMutate,
        onNavigate: handleNavigate, activeViewId,
        fieldErrors: violations, formErrors, readOnly,
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
