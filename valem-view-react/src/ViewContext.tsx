import { createContext, useContext } from 'react';
import type { ModelState, MutationMap, MetaCache } from './types';

export interface ViewContextValue {
  modelId: string;
  state: ModelState;
  meta: MetaCache;
  onMutate: (mutations: MutationMap) => Promise<void>;
  onNavigate: (viewId: string) => void;
  activeViewId: string;
  fieldErrors: Record<string, string>;
}

export const ViewContext = createContext<ViewContextValue | null>(null);

export function useViewContext(): ViewContextValue {
  const ctx = useContext(ViewContext);
  if (!ctx) throw new Error('useViewContext must be used inside ViewRenderer');
  return ctx;
}
