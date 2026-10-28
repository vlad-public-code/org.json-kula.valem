import { useCallback, useEffect, useState } from 'react';
import { payloadOf, readShare, shareUrl } from './cardModel';
import type { CardPayload, ToolResult } from './cardModel';
import { CardBody } from './CardBody';
import { textOf, useCard } from './useCard';

/** The model-card MCP endpoint on the server that serves this page. */
const ENDPOINT = '/mcp/app';

let rpcId = 0;

/** One `tools/call` to the endpoint, as plain JSON-RPC over HTTP (it is stateless: no session). */
async function callTool(name: string, args: Record<string, unknown>): Promise<ToolResult> {
  const res = await fetch(ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: ++rpcId, method: 'tools/call', params: { name, arguments: args } }),
  });
  if (res.status === 429) throw new Error('Too many requests from this address. Wait a minute and try again.');
  const body = await res.json() as { result?: ToolResult; error?: { message?: string } };
  if (body.error) throw new Error(body.error.message ?? 'The server refused the request.');
  return body.result ?? {};
}

type Status =
  | { kind: 'loading' }
  | { kind: 'failed'; message: string }
  | { kind: 'ready' };

/**
 * A model opened from a share link. The link's fragment carries the spec and the inputs; the page
 * sends them to `render_model` on this server, and after that it behaves like the chat card. The
 * address bar follows every accepted edit, so copying it shares what is on screen.
 */
export function SharePage() {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  const [copied, setCopied] = useState(false);

  const onCommit = useCallback((p: CardPayload) => {
    document.title = `${p.spec.id} · Valem`;
    void shareUrl(location.origin + location.pathname, { spec: p.spec, inputs: p.inputs })
      .then(url => history.replaceState(null, '', url));
  }, []);
  const card = useCard(callTool, onCommit);
  const { load } = card;

  const open = useCallback(async () => {
    const shared = await readShare(location.hash);
    if (!shared) {
      setStatus({ kind: 'failed', message: 'This link does not contain a Valem model. Check that it was copied whole.' });
      return;
    }
    try {
      const result = await callTool('render_model', { spec: shared.spec, inputs: shared.inputs });
      const p = payloadOf(result);
      if (!p) {
        setStatus({ kind: 'failed', message: textOf(result) || 'The model could not be computed.' });
        return;
      }
      load(p);
      setStatus({ kind: 'ready' });
    } catch (e) {
      setStatus({ kind: 'failed', message: e instanceof Error ? e.message : String(e) });
    }
  }, [load]);

  useEffect(() => {
    const dark = window.matchMedia?.('(prefers-color-scheme: dark)');
    const theme = () => document.documentElement.setAttribute('data-theme', dark?.matches ? 'dark' : 'light');
    theme();
    dark?.addEventListener?.('change', theme);
    void open();
    // A link pasted into this tab changes only the fragment. The page's own replaceState fires no
    // hashchange, so every one of these is a different model to open.
    const onHash = () => void open();
    window.addEventListener('hashchange', onHash);
    return () => {
      dark?.removeEventListener?.('change', theme);
      window.removeEventListener('hashchange', onHash);
    };
  }, [open]);

  const copy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(location.href);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* no clipboard access: the address bar holds the same link */
    }
  }, []);

  return (
    <main className="share">
      {status.kind === 'loading' && <div className="card card-note">Opening the model…</div>}
      {status.kind === 'failed' && (
        <div className="card">
          <div className="banner banner-warn">This model could not be opened.</div>
          <pre className="card-note" style={{ whiteSpace: 'pre-wrap' }}>{status.message}</pre>
        </div>
      )}
      {status.kind === 'ready' && card.committed && (
        <CardBody
          payload={card.committed}
          shown={card.shown}
          rejection={card.rejection}
          busy={card.busy}
          onMutate={card.onMutate}
          actions={
            <button type="button" className="card-action" onClick={() => void copy()}>
              {copied ? 'Copied' : 'Copy link'}
            </button>
          }
        />
      )}
      <footer className="share-foot">
        Every number here is computed by the <a href="/" target="_blank" rel="noopener">Valem</a> engine.
        The link holds the whole model and your values, so anyone you send it to sees the same thing.
      </footer>
    </main>
  );
}
