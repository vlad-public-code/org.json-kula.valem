import { createContext, useContext } from 'react';
import type { ModelState, MutationMap, MetaCache, ProvenanceSource } from './types';

/**
 * Interactive provenance state, present only when a {@link ProvenanceSource} was passed to
 * {@link ViewRenderer}. {@code ComponentRenderer} reads it to attach hover/focus affordances to
 * bound (and inline-expression) leaves, show the popover on the hovered leaf, highlight the hovered
 * node's inputs in place, keep a cross-highlighted selection in sync with the graph panel (F11), and
 * pulse leaves on a live update (F12).
 */
export interface ProvenanceRuntime {
  source: ProvenanceSource;
  /** Identity of the hovered leaf: its bound path, or `#<componentId>` for an inline-expression leaf. */
  hoveredLeafId: string | null;
  /** A leaf reports hover: its id plus the input paths it wants highlighted (null id clears). */
  onHover: (leafId: string | null, inputPaths: string[]) => void;
  /** Canonical paths of the hovered leaf's inputs — those leaves highlight in place (F2). */
  highlightedPaths: Set<string>;
  /** Canonical path selected elsewhere (the graph panel, or a click) — highlighted persistently (F11). */
  selectedPath: string | null;
  /** A leaf reports a click/selection so other surfaces can sync (F11). */
  onSelect: (path: string | null) => void;
  /** Canonical paths pulsing from a live ChangeEvent (mutated + derived-updated) — brief flash (F12). */
  pulsingPaths: Set<string>;
}

export interface ViewContextValue {
  modelId: string;
  state: ModelState;
  meta: MetaCache;
  onMutate: (mutations: MutationMap) => Promise<void>;
  onNavigate: (viewId: string) => void;
  activeViewId: string;
  /**
   * When true, every input renders disabled and `onMutate` is a no-op — the whole view is a
   * computed, non-editable snapshot. Used by read-only embeds; defaults to false everywhere else.
   */
  readOnly: boolean;
  /** Constraint violations that resolved to a bound path, keyed by that path. */
  fieldErrors: Record<string, string>;
  /**
   * Violations that resolved to no single path — a constraint spanning three fields, or one
   * whose paths are all array-scoped. These have nowhere to appear beside a field, which is
   * what `validationSummary` is for.
   */
  formErrors: string[];
  /** Provenance interaction state, or null when no ProvenanceSource was supplied (the default). */
  provenance: ProvenanceRuntime | null;
}

export const ViewContext = createContext<ViewContextValue | null>(null);

export function useViewContext(): ViewContextValue {
  const ctx = useContext(ViewContext);
  if (!ctx) throw new Error('useViewContext must be used inside ViewRenderer');
  return ctx;
}
