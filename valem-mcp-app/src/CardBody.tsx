import { useMemo } from 'react';
import type { ReactNode } from 'react';
import { ViewRenderer, buildGraphIndexes, createGraphProvenance } from 'valem-view-react';
import type { ViewDefinition } from 'valem-view-react';
import { splitIssues, viewTabs } from './cardModel';
import type { CardPayload } from './cardModel';
import type { Rejection } from './useCard';

interface Props {
  payload: CardPayload;
  shown: Record<string, unknown>;
  rejection: Rejection | null;
  busy: boolean;
  onMutate: (mutations: Record<string, unknown>) => Promise<void>;
  /** Buttons for the header, beside the status. */
  actions?: ReactNode;
  /** The view on screen; with several views the card shows tabs and keeps this in step. */
  viewId?: string;
  onViewChange?: (viewId: string) => void;
}

/** A computed model: its title, rule messages and the view, as the chat card and the share page show it. */
export function CardBody({ payload, shown, rejection, busy, onMutate, actions, viewId, onViewChange }: Props) {
  const flags = splitIssues(payload.flags);
  const fieldErrors = { ...flags.fieldErrors, ...rejection?.fieldErrors };
  const rejected = rejection?.formErrors ?? [];
  const formErrors = [...rejected, ...flags.formErrors];
  const title = payload.spec.id + (payload.spec.version ? ` · ${payload.spec.version}` : '');
  const tabs = useMemo(() => viewTabs(payload.spec.viewDefinition), [payload.spec.viewDefinition]);
  // "Why is this number?": the graph says what each computed field reads; the values are the ones on screen.
  const indexes = useMemo(() => (payload.graph ? buildGraphIndexes(payload.graph) : null), [payload.graph]);
  const provenance = useMemo(() => (indexes ? createGraphProvenance(indexes, shown) : undefined), [indexes, shown]);

  return (
    <div className="card">
      <div className="card-head">
        <span className="card-title">{title}</span>
        <span className="card-side">
          <span className="card-status" aria-live="polite">{busy ? 'Recomputing…' : 'Computed by Valem'}</span>
          {actions}
        </span>
      </div>
      {tabs.length > 0 && onViewChange && (
        <div className="card-tabs" role="tablist">
          {tabs.map(t => (
            <button key={t.id} type="button" role="tab" aria-selected={t.id === viewId}
                    className={t.id === viewId ? 'card-tab card-tab-active' : 'card-tab'}
                    onClick={() => onViewChange(t.id)}>
              {t.label}
            </button>
          ))}
        </div>
      )}
      {formErrors.map((m, i) => (
        <div key={i} className={i < rejected.length ? 'banner banner-error' : 'banner banner-warn'}>{m}</div>
      ))}
      <ViewRenderer
        modelId={payload.spec.id}
        viewDef={payload.spec.viewDefinition as ViewDefinition}
        state={shown}
        meta={payload.meta}
        onMutate={onMutate}
        activeViewId={viewId}
        onNavigate={onViewChange}
        provenance={provenance}
        violations={fieldErrors}
        formErrors={formErrors}
      />
    </div>
  );
}
