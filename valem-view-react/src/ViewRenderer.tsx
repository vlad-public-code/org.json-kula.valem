import { useState, useCallback, useMemo } from 'react';
import type { CSSProperties } from 'react';
import type { ViewDefinition, ModelState, MetaCache, MutationMap, ProvenanceSource } from './types';
import { ViewContext } from './ViewContext';
import { canonicalPath } from './provenance';
import { hasNavigationAway, scopeToIndex } from './navigation';
import { fieldColors } from './fields/fieldColors';
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

/** One view the user has been on, with the item scope it was showing. */
interface NavEntry {
  viewId: string;
  scope: ItemScope | null;
}

/** Binds a view to one element of an array — see `onNavigate`'s `itemIndex`. */
interface ItemScope {
  viewId: string;
  index: number;
}

/**
 * How far back the trail is kept. Deep enough for any real navigation, bounded so a session
 * spent clicking between two views cannot grow it without limit.
 */
const MAX_HISTORY = 20;

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

  // Where the user came from, innermost last. Each entry remembers the item scope that view was
  // showing, so returning to a list from an element editor restores the list exactly as it was.
  const [history, setHistory] = useState<NavEntry[]>([]);
  const [itemScope, setItemScope] = useState<ItemScope | null>(null);

  const handleNavigate = useCallback(
    (viewId: string, itemIndex?: number) => {
      if (viewId !== activeViewId) {
        setHistory(prev => [...prev, { viewId: activeViewId, scope: itemScope }].slice(-MAX_HISTORY));
      }
      setItemScope(itemIndex == null ? null : { viewId, index: itemIndex });
      setInternalViewId(viewId);
      onNavigate?.(viewId);
    },
    [onNavigate, activeViewId, itemScope],
  );

  const goBack = useCallback(() => {
    const target = history[history.length - 1];
    if (!target) return;
    setHistory(prev => prev.slice(0, -1));
    setItemScope(target.scope);
    setInternalViewId(target.viewId);
    onNavigate?.(target.viewId);
  }, [history, onNavigate]);

  const view = viewDef.views.find(v => v.id === activeViewId) ?? viewDef.views[0];

  // An item view is authored against the array pattern (`$.debts[*].name`); the scope binds it to
  // the element the user actually opened. Unscoped views are passed through untouched.
  const components = useMemo(() => {
    const authored = view?.components ?? [];
    return view && itemScope?.viewId === view.id ? scopeToIndex(authored, itemScope.index) : authored;
  }, [view, itemScope]);

  /*
   * The automatic way out.
   *
   * A view reached by navigation that authors no navigation control of its own is a dead end — the
   * shape an LLM produces most often for a `sectionList`'s `itemView`, where the user enters an
   * element's data and is then stuck on it. Rather than require every generated spec to remember a
   * back button, the renderer supplies one, and stands aside the moment the author has provided any
   * navigation of their own (a button that navigates, a menu, a stepper, a breadcrumb).
   *
   * Home never shows it: home is where Back would lead, so a Back on it can only lead somewhere
   * less expected than staying. Home is the declared `defaultView`, or the first view when none is
   * declared — the same fallback the initial active view uses, so the two cannot disagree.
   */
  const home = viewDef.defaultView ?? viewDef.views[0]?.id;
  const backTarget = history[history.length - 1];
  const showBack =
    !!backTarget &&
    !!view &&
    view.id !== home &&
    !hasNavigationAway(view.components, view.id);
  const backLabel = viewDef.views.find(v => v.id === backTarget?.viewId)?.label;

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
      {showBack && (
        <button type="button" data-testid="view-back" onClick={goBack} style={BACK_STYLE}>
          <span aria-hidden>←</span>
          {backLabel ? `Back to ${backLabel}` : 'Back'}
        </button>
      )}
      {/*
        `tabs` and `wizard` have always been legal values of `ViewSpec.layout`, but nothing
        rendered them — a view asking for either silently got a vertical stack. LayoutContainer
        implements all five, and is shared with the container components so a view-level
        `layout: "wizard"` and a `group` with the same layout behave identically.
      */}
      <LayoutContainer
        components={components}
        layout={view.layout}
        columns={view.columns}
        state={state}
      />
    </ViewContext.Provider>
  );
}

// Theme tokens rather than literals: a hardcoded white background under the dark theme puts the
// label's (inherited, theme-following) color on top of it and the control disappears.
const BACK_STYLE: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  marginBottom: 12,
  padding: '4px 10px',
  border: `1px solid ${fieldColors.border}`,
  borderRadius: 6,
  background: fieldColors.mutedBg,
  color: fieldColors.text,
  fontSize: 13,
  cursor: 'pointer',
};

// ── Helpers exported for use in field components ──────────────────────────────

export { useJSONataBoolean, useJSONataText };
