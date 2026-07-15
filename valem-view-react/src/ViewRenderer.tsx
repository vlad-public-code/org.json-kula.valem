import { useState, useCallback } from 'react';
import type { ViewDefinition, ModelState, MetaCache, MutationMap } from './types';
import { ViewContext } from './ViewContext';
import { ComponentRenderer } from './ComponentRenderer';
import { useJSONataBoolean, useJSONataText } from './hooks/useJSONata';

export interface ViewRendererProps {
  modelId: string;
  viewDef: ViewDefinition;
  state: ModelState;
  meta: MetaCache;
  onMutate: (mutations: MutationMap) => Promise<void>;
  onNavigate?: (viewId: string) => void;
  activeViewId?: string;
  violations?: Record<string, string>;
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
      value={{ modelId, state, meta, onMutate, onNavigate: handleNavigate, activeViewId, fieldErrors: violations }}
    >
      <ViewContainer layout={view.layout} columns={view.columns}>
        {view.components.map(c => (
          <ComponentRenderer key={c.id} component={c} state={state} />
        ))}
      </ViewContainer>
    </ViewContext.Provider>
  );
}

interface ViewContainerProps {
  layout?: string;
  columns?: number;
  children: React.ReactNode;
}

function ViewContainer({ layout = 'vertical', columns = 2, children }: ViewContainerProps) {
  const style: React.CSSProperties =
    layout === 'grid'
      ? { display: 'grid', gridTemplateColumns: `repeat(${columns}, 1fr)`, gap: 16 }
      : layout === 'horizontal'
        ? { display: 'flex', flexDirection: 'row', flexWrap: 'wrap', gap: 16 }
        : { display: 'flex', flexDirection: 'column', gap: 12 };

  return <div style={style}>{children}</div>;
}

// ── Helpers exported for use in field components ──────────────────────────────

export { useJSONataBoolean, useJSONataText };
