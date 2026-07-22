import { createContext, useContext } from 'react';
import type { ModelState, MutationMap, MetaCache } from './types';

export interface ViewContextValue {
  modelId: string;
  state: ModelState;
  meta: MetaCache;
  onMutate: (mutations: MutationMap) => Promise<void>;
  onNavigate: (viewId: string) => void;
  activeViewId: string;
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
