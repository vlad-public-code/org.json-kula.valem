import { useState, useCallback, useMemo } from 'react';
import type { ViewDefinition, ModelState, MetaCache, MutationMap, ProvenanceSource } from './types';
import { ViewContext } from './ViewContext';
import { canonicalPath } from './provenance';
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
  /**
   * Optional "Why is this number?" lens. When supplied, hovering/focusing a bound leaf that
   * resolves to a derived node shows its expression + inputs and highlights those inputs in place.
   * Omitted everywhere except the sandbox interact view, so embeds and the default path are
   * unchanged (and incur no extra DOM).
   */
  provenance?: ProvenanceSource;
  /** Cross-highlight (F11): the currently-selected path — its leaf is highlighted persistently. */
  provenanceSelectedPath?: string | null;
  /** Cross-highlight (F11): called when a leaf is clicked/focused, so the graph panel can sync. */
  onProvenanceSelect?: (path: string | null) => void;
  /** Live pulse (F12): paths from the latest ChangeEvent (mutated + derived-updated) to flash briefly. */
  provenancePulsingPaths?: Set<string>;
}

const EMPTY_PATHS: Set<string> = new Set();

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
  provenance,
  provenanceSelectedPath = null,
  onProvenanceSelect,
  provenancePulsingPaths,
}: ViewRendererProps) {
  // Hover is ephemeral and owned here; a leaf reports its id + the input paths to highlight.
  const [hover, setHover] = useState<{ id: string; paths: Set<string> } | null>(null);
  const onHover = useCallback((leafId: string | null, inputPaths: string[]) => {
    setHover(leafId ? { id: leafId, paths: new Set(inputPaths.map(canonicalPath)) } : null);
  }, []);
  const noop = useCallback(() => {}, []);

  const provenanceRuntime = useMemo(() => {
    if (!provenance) return null;
    return {
      source: provenance,
      hoveredLeafId: hover?.id ?? null,
      onHover,
      highlightedPaths: hover?.paths ?? EMPTY_PATHS,
      selectedPath: provenanceSelectedPath ? canonicalPath(provenanceSelectedPath) : null,
      onSelect: onProvenanceSelect ?? noop,
      pulsingPaths: provenancePulsingPaths ?? EMPTY_PATHS,
    };
  }, [provenance, hover, onHover, provenanceSelectedPath, onProvenanceSelect, provenancePulsingPaths, noop]);
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
        provenance: provenanceRuntime,
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
