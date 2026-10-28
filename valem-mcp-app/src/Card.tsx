import { useCallback, useEffect, useState } from 'react';
import {
  App,
  applyDocumentTheme,
  applyHostFonts,
  applyHostStyleVariables,
} from '@modelcontextprotocol/ext-apps/app-with-deps';
import type { McpUiHostContext } from '@modelcontextprotocol/ext-apps/app-with-deps';
import { payloadOf, shareUrl, summaryText } from './cardModel';
import type { CardPayload, Inputs, ToolResult } from './cardModel';
import { CardBody } from './CardBody';
import { textOf, useCard } from './useCard';

type Status =
  | { kind: 'waiting' }
  | { kind: 'failed'; message: string }
  | { kind: 'ready' };

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

/**
 * The Valem model card inside a chat. `render_model` hands it a spec and the state the server
 * computed; every edit goes back through the host as `valem_recompute`, and the assistant is told the
 * new values. Nothing is computed here.
 */
export function Card({ app }: { app: App }) {
  const [status, setStatus] = useState<Status>({ kind: 'waiting' });

  const callTool = useCallback(
    async (name: string, args: Record<string, unknown>) =>
      (await app.callServerTool({ name, arguments: args })) as ToolResult,
    [app],
  );
  const onCommit = useCallback((p: CardPayload, byEdit: boolean) => {
    openai()?.setWidgetState?.({ specHash: p.specHash, inputs: p.inputs });
    if (!byEdit) return;
    void app.updateModelContext({
      content: [{ type: 'text', text: summaryText(p.spec, p.inputs, p.state, p.flags) }],
    }).catch(() => { /* a host without model context still shows the card */ });
  }, [app]);
  const card = useCard(callTool, onCommit);
  const { load, recompute } = card;

  useEffect(() => {
    app.ontoolinput = () => setStatus(s => (s.kind === 'ready' ? s : { kind: 'waiting' }));
    app.ontoolresult = result => {
      const p = payloadOf(result as ToolResult);
      if (!p) {
        // An invalid spec: the assistant sees the errors and is expected to try again.
        setStatus({ kind: 'failed', message: textOf(result as ToolResult) });
        return;
      }
      load(p);
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
  }, [app, load, recompute]);

  const committed = card.committed;
  const openInBrowser = useCallback(async () => {
    if (!committed?.shareBase) return;
    const url = await shareUrl(committed.shareBase, { spec: committed.spec, inputs: committed.inputs });
    await app.openLink({ url }).catch(() => { /* the host declined; nothing to undo */ });
  }, [app, committed]);

  if (status.kind === 'failed' && !committed) {
    return (
      <div className="card">
        <div className="banner banner-warn">The model spec has problems, so there is nothing to show yet.</div>
        {status.message && <pre className="card-note" style={{ whiteSpace: 'pre-wrap' }}>{status.message}</pre>}
      </div>
    );
  }
  if (!committed) return <div className="card card-note">Building the model…</div>;

  return (
    <CardBody
      payload={committed}
      shown={card.shown}
      rejection={card.rejection}
      busy={card.busy}
      onMutate={card.onMutate}
      actions={committed.shareBase && (
        <button type="button" className="card-action" onClick={() => void openInBrowser()}
                title="Open a live copy you can share, with the values you have now">
          Open in browser
        </button>
      )}
    />
  );
}
