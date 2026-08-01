import { createContext, useContext } from 'react';
import type { ModelState, MutationMap, MetaCache } from './types';

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
}

export const ViewContext = createContext<ViewContextValue | null>(null);

export function useViewContext(): ViewContextValue {
  const ctx = useContext(ViewContext);
  if (!ctx) throw new Error('useViewContext must be used inside ViewRenderer');
  return ctx;
}
