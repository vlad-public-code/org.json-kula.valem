import { useCallback, useEffect, useRef, useState } from 'react';
import {
  App,
  applyDocumentTheme,
  applyHostFonts,
  applyHostStyleVariables,
} from '@modelcontextprotocol/ext-apps/app-with-deps';
import type { McpUiHostContext } from '@modelcontextprotocol/ext-apps/app-with-deps';
import { ViewRenderer } from 'valem-view-react';
import type { ViewDefinition } from 'valem-view-react';
import {
  mergeInputs,
  payloadOf,
  setAt,
  splitIssues,
  summaryText,
  toCanonical,
} from './cardModel';
import type { CardPayload, Inputs, RecomputeResult } from './cardModel';

/** How long the card waits after the last keystroke before asking the server to recompute. */
const RECOMPUTE_DEBOUNCE_MS = 250;

const RECOMPUTE_TOOL = 'valem_recompute';

type Status =
  | { kind: 'waiting' }
  | { kind: 'failed'; message: string }
  | { kind: 'ready' };

interface Rejection {
  fieldErrors: Record<string, string>;
  formErrors: string[];
}

/** ChatGPT keeps a little per-card state across reloads; other hosts have no such store. */
interface OpenAiGlobals {
  widgetState?: { specHash?: string; inputs?: Inputs } | null;
  setWidgetState?: (state: unknown) => void;
}
const openai = (): OpenAiGlobals | undefined => (window as unknown as { openai?: OpenAiGlobals }).openai;

function applyHostContext(ctx: Partial<McpUiHostContext> | undefined) {
  if (!ctx) return;
  if (ctx.theme) applyDocumentTheme(ctx.theme);
  if (ctx.styles?.variables) applyHostStyleVariables(ctx.styles.variables);
  if (ctx.styles?.css?.fonts) applyHostFonts(ctx.styles.css.fonts);
}

function textOf(result: { content?: { type: string; text?: string }[] }): string {
  return (result.content ?? []).filter(c => c.type === 'text' && c.text).map(c => c.text).join('\n');
}

/**
 * The Valem model card. `render_model` hands it a spec and the state the server computed; every
 * edit after that goes back to the server as `valem_recompute` with the accumulated inputs, and the
 * answer replaces what is on screen. Nothing is computed here.
 */
export function Card({ app }: { app: App }) {
  const [status, setStatus] = useState<Status>({ kind: 'waiting' });
  // `committed` is the last state the server agreed to; `shown` adds the edits still in flight.
  const [committed, setCommitted] = useState<CardPayload | null>(null);
  const [shown, setShown] = useState<Record<string, unknown>>({});
  const [rejection, setRejection] = useState<Rejection | null>(null);
  const [busy, setBusy] = useState(false);

  const committedRef = useRef<CardPayload | null>(null);
  const pendingInputs = useRef<Inputs | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const sequence = useRef(0);

  const commit = useCallback((p: CardPayload) => {
    committedRef.current = p;
    setCommitted(p);
    setShown(p.state);
    openai()?.setWidgetState?.({ specHash: p.specHash, inputs: p.inputs });
  }, []);

  const recompute = useCallback(async (inputs: Inputs) => {
    const base = committedRef.current;
    if (!base) return;
    const mine = ++sequence.current;
    setBusy(true);
    const call = async (withSpec: boolean) => {
      const res = await app.callServerTool({
        name: RECOMPUTE_TOOL,
        arguments: withSpec ? { specHash: base.specHash, spec: base.spec, inputs } : { specHash: base.specHash, inputs },
      });
      return { res, out: res.structuredContent as RecomputeResult | undefined };
    };
    try {
      let { res, out } = await call(false);
      // The server forgot the spec (restart, eviction, another replica): send it once more.
      if (out && 'error' in out && out.error === 'spec_unknown') ({ res, out } = await call(true));
      if (mine !== sequence.current) return;                     // a newer edit has been sent since
      if (!out || 'error' in out) {
        setRejection({ fieldErrors: {}, formErrors: [textOf(res) || 'The model could not be recomputed.'] });
        setShown(base.state);
        return;
      }
      if ('rejected' in out) {
        // The edit broke a hard rule or the schema: put the last accepted values back, say why.
        setRejection(splitIssues(out.issues));
        setShown(base.state);
        return;
      }
      const next: CardPayload = { ...base, specHash: out.specHash, inputs, state: out.state, meta: out.meta, flags: out.flags };
      commit(next);
      setRejection(null);
      void app.updateModelContext({
        content: [{ type: 'text', text: summaryText(next.spec, inputs, next.state, next.flags) }],
      }).catch(() => { /* a host without model context still shows the card */ });
    } catch (e) {
      if (mine !== sequence.current) return;
      setRejection({ fieldErrors: {}, formErrors: [`Recompute failed: ${e instanceof Error ? e.message : String(e)}`] });
      setShown(base.state);
    } finally {
      if (mine === sequence.current) setBusy(false);
    }
  }, [app, commit]);

  useEffect(() => {
    app.ontoolinput = () => setStatus(s => (s.kind === 'ready' ? s : { kind: 'waiting' }));
    app.ontoolresult = result => {
      const p = payloadOf(result as { _meta?: Record<string, unknown> });
      if (!p) {
        // An invalid spec: the assistant sees the errors and is expected to try again.
        setStatus({ kind: 'failed', message: textOf(result as { content?: { type: string; text?: string }[] }) });
        return;
      }
      commit(p);
      setRejection(null);
      setStatus({ kind: 'ready' });
      // A ChatGPT reload of a card the user had edited: bring their edits back.
      const saved = openai()?.widgetState;
      if (saved?.specHash === p.specHash && saved.inputs && JSON.stringify(saved.inputs) !== JSON.stringify(p.inputs)) {
        void recompute(saved.inputs);
      }
    };
    app.onhostcontextchanged = ctx => applyHostContext(ctx);
    app.connect()
      .then(() => applyHostContext(app.getHostContext()))
      .catch(e => setStatus({ kind: 'failed', message: `Could not connect to the chat: ${e instanceof Error ? e.message : String(e)}` }));
    return () => window.clearTimeout(timer.current);
  }, [app, commit, recompute]);

  const onMutate = useCallback(async (mutations: Record<string, unknown>) => {
    const base = committedRef.current;
    if (!base) return;
    const inputs = mergeInputs(pendingInputs.current ?? base.inputs, mutations);
    pendingInputs.current = inputs;
    setShown(prev => Object.entries(mutations).reduce((s, [k, v]) => setAt(s, toCanonical(k), v), prev));
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      const send = pendingInputs.current;
      pendingInputs.current = null;
      if (send) void recompute(send);
    }, RECOMPUTE_DEBOUNCE_MS);
  }, [recompute]);

  if (status.kind === 'failed' && !committed) {
    return (
      <div className="card">
        <div className="banner banner-warn">The model spec has problems, so there is nothing to show yet.</div>
        {status.message && <pre className="card-note" style={{ whiteSpace: 'pre-wrap' }}>{status.message}</pre>}
      </div>
    );
  }
  if (!committed) return <div className="card card-note">Building the model…</div>;

  const flags = splitIssues(committed.flags);
  const fieldErrors = { ...flags.fieldErrors, ...rejection?.fieldErrors };
  const formErrors = [...(rejection?.formErrors ?? []), ...flags.formErrors];
  const title = committed.spec.id + (committed.spec.version ? ` · ${committed.spec.version}` : '');

  return (
    <div className="card">
      <div className="card-head">
        <span className="card-title">{title}</span>
        <span className="card-status" aria-live="polite">{busy ? 'Recomputing…' : 'Computed by Valem'}</span>
      </div>
      {formErrors.map((m, i) => (
        <div key={i} className={i < (rejection?.formErrors.length ?? 0) ? 'banner banner-error' : 'banner banner-warn'}>{m}</div>
      ))}
      <ViewRenderer
        modelId={committed.spec.id}
        viewDef={committed.spec.viewDefinition as ViewDefinition}
        state={shown}
        meta={committed.meta}
        onMutate={onMutate}
        violations={fieldErrors}
        formErrors={formErrors}
      />
    </div>
  );
}
