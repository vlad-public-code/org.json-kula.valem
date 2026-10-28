import { useCallback, useEffect, useState } from 'react';
import {
  App,
  applyDocumentTheme,
  applyHostFonts,
  applyHostStyleVariables,
} from '@modelcontextprotocol/ext-apps/app-with-deps';
import type { McpUiDisplayMode, McpUiHostContext } from '@modelcontextprotocol/ext-apps/app-with-deps';
import { payloadOf, summaryText } from './cardModel';
import type { CardPayload, Inputs, RecomputeResult, ToolResult } from './cardModel';
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
 * new values. `show_model` hands it a model the server keeps, and edits are saved to it instead.
 * Nothing is computed here.
 */
export function Card({ app }: { app: App }) {
  const [status, setStatus] = useState<Status>({ kind: 'waiting' });
  const [viewId, setViewId] = useState<string | undefined>(undefined);
  // Fullscreen is offered only where the host says it can do it.
  const [displayModes, setDisplayModes] = useState<McpUiDisplayMode[]>([]);
  const [displayMode, setDisplayMode] = useState<McpUiDisplayMode>('inline');
  const trackDisplay = useCallback((ctx: Partial<McpUiHostContext> | undefined) => {
    if (ctx?.availableDisplayModes) setDisplayModes(ctx.availableDisplayModes);
    if (ctx?.displayMode) setDisplayMode(ctx.displayMode);
  }, []);

  const callTool = useCallback(
    async (name: string, args: Record<string, unknown>) =>
      (await app.callServerTool({ name, arguments: args })) as ToolResult,
    [app],
  );
  const onCommit = useCallback((p: CardPayload, byEdit: boolean) => {
    if (!p.modelId) openai()?.setWidgetState?.({ specHash: p.specHash, inputs: p.inputs });
    if (!byEdit) return;
    void app.updateModelContext({
      content: [{ type: 'text', text: summaryText(p.spec, p.inputs, p.state, p.flags, p.modelId) }],
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
      const vd = p.spec.viewDefinition as { defaultView?: string; views?: { id: string }[] } | undefined;
      setViewId(vd?.defaultView ?? vd?.views?.[0]?.id);
      // A stored model may have changed since this result was made (a reload from history, the
      // assistant's own mutate): show it as it is now.
      if (p.modelId) { void recompute({}); return; }
      // A ChatGPT reload of a card the user had edited: bring their edits back.
      const saved = openai()?.widgetState;
      if (saved?.specHash === p.specHash && saved.inputs && JSON.stringify(saved.inputs) !== JSON.stringify(p.inputs)) {
        void recompute(saved.inputs);
      }
    };
    app.onhostcontextchanged = ctx => { applyHostContext(ctx); trackDisplay(ctx); };
    app.connect()
      .then(() => { const ctx = app.getHostContext(); applyHostContext(ctx); trackDisplay(ctx); })
      .catch(e => setStatus({ kind: 'failed', message: `Could not connect to the chat: ${e instanceof Error ? e.message : String(e)}` }));
  }, [app, load, recompute, trackDisplay]);

  const committed = card.committed;
  // An evolved stored model may have dropped the view on screen.
  useEffect(() => {
    const vd = committed?.spec.viewDefinition as { defaultView?: string; views?: { id: string }[] } | undefined;
    if (!vd?.views || !viewId || vd.views.some(v => v.id === viewId)) return;
    setViewId(vd.defaultView ?? vd.views[0]?.id);
  }, [committed?.spec, viewId]);
  // The link format is the server's: ask it for one carrying the values on screen now.
  const openInBrowser = useCallback(async () => {
    if (!committed?.shareUrl) return;
    let url = committed.shareUrl;
    try {
      const res = await callTool('valem_recompute',
        { specHash: committed.specHash, spec: committed.spec, inputs: committed.inputs });
      const out = res.structuredContent as RecomputeResult | undefined;
      if (out && 'shareUrl' in out && out.shareUrl) url = out.shareUrl;
    } catch {
      /* fall back to the link for the starting values */
    }
    await app.openLink({ url }).catch(() => { /* the host declined; nothing to undo */ });
  }, [app, callTool, committed]);

  const fullscreen = displayMode === 'fullscreen';
  const toggleFullscreen = useCallback(async () => {
    const mode: McpUiDisplayMode = fullscreen ? 'inline' : 'fullscreen';
    try {
      setDisplayMode((await app.requestDisplayMode({ mode })).mode);
    } catch { /* the host declined; the card stays as it is */ }
  }, [app, fullscreen]);

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
      viewId={viewId}
      onViewChange={setViewId}
      actions={<>
        {displayModes.includes('fullscreen') && (
          <button type="button" className="card-action" onClick={() => void toggleFullscreen()}
                  aria-pressed={fullscreen}>
            {fullscreen ? 'Exit full screen' : 'Full screen'}
          </button>
        )}
        {committed.shareUrl && (
          <button type="button" className="card-action" onClick={() => void openInBrowser()}
                  title="Open a live copy you can share, with the values you have now">
            Open in browser
          </button>
        )}
      </>}
    />
  );
}
