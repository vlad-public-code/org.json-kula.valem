import type { ReactNode } from 'react';
import { ViewRenderer } from 'valem-view-react';
import type { ViewDefinition } from 'valem-view-react';
import { splitIssues } from './cardModel';
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
}

/** A computed model: its title, rule messages and the view, as the chat card and the share page show it. */
export function CardBody({ payload, shown, rejection, busy, onMutate, actions }: Props) {
  const flags = splitIssues(payload.flags);
  const fieldErrors = { ...flags.fieldErrors, ...rejection?.fieldErrors };
  const rejected = rejection?.formErrors ?? [];
  const formErrors = [...rejected, ...flags.formErrors];
  const title = payload.spec.id + (payload.spec.version ? ` · ${payload.spec.version}` : '');

  return (
    <div className="card">
      <div className="card-head">
        <span className="card-title">{title}</span>
        <span className="card-side">
          <span className="card-status" aria-live="polite">{busy ? 'Recomputing…' : 'Computed by Valem'}</span>
          {actions}
        </span>
      </div>
      {formErrors.map((m, i) => (
        <div key={i} className={i < rejected.length ? 'banner banner-error' : 'banner banner-warn'}>{m}</div>
      ))}
      <ViewRenderer
        modelId={payload.spec.id}
        viewDef={payload.spec.viewDefinition as ViewDefinition}
        state={shown}
        meta={payload.meta}
        onMutate={onMutate}
        violations={fieldErrors}
        formErrors={formErrors}
      />
    </div>
  );
}
