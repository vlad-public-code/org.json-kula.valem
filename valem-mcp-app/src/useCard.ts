import { useCallback, useEffect, useRef, useState } from 'react';
import { mergeInputs, setAt, splitIssues, toCanonical } from './cardModel';
import type { CardPayload, EditResult, Inputs, RecomputeResult, ToolResult } from './cardModel';

/** How long the card waits after the last edit before asking the server to recompute. */
const RECOMPUTE_DEBOUNCE_MS = 250;

const RECOMPUTE_TOOL = 'valem_recompute';
/** Where a stored model's edits go: a mutate on the server's model. */
const EDIT_TOOL = 'valem_card_edit';

export interface Rejection {
  fieldErrors: Record<string, string>;
  formErrors: string[];
}

export function textOf(result: ToolResult): string {
  return (result.content ?? []).filter(c => c.type === 'text' && c.text).map(c => c.text).join('\n');
}

/**
 * The card's state, shared by the chat card and the share page: the last state the server agreed
 * to, the edits still in flight, and the recompute loop. Every number comes from the server:
 * `valem_recompute` for a spec the card holds, `valem_card_edit` for a model the server keeps (the
 * payload has a `modelId`). `callTool` is how this shell reaches the server.
 */
export function useCard(
  callTool: (name: string, args: Record<string, unknown>) => Promise<ToolResult>,
  onCommit?: (p: CardPayload, byEdit: boolean) => void,
) {
  const [committed, setCommitted] = useState<CardPayload | null>(null);
  const [shown, setShown] = useState<Record<string, unknown>>({});
  const [rejection, setRejection] = useState<Rejection | null>(null);
  const [busy, setBusy] = useState(false);

  const committedRef = useRef<CardPayload | null>(null);
  const pendingInputs = useRef<Inputs | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const sequence = useRef(0);
  const onCommitRef = useRef(onCommit);
  onCommitRef.current = onCommit;

  const commit = useCallback((p: CardPayload, byEdit: boolean) => {
    committedRef.current = p;
    setCommitted(p);
    setShown(p.state);
    onCommitRef.current?.(p, byEdit);
  }, []);

  /** Shows a fresh `render_model` result. */
  const load = useCallback((p: CardPayload) => {
    sequence.current++;                                          // drop answers to older edits
    window.clearTimeout(timer.current);
    pendingInputs.current = null;
    setRejection(null);
    setBusy(false);
    commit(p, false);
  }, [commit]);

  /**
   * A stored model's edits (none: read its current state back, e.g. after a reload from history or
   * a change the assistant made). The server's answer is the whole state, the spec too if it changed.
   */
  const saveEdits = useCallback(async (base: CardPayload, mutations: Inputs) => {
    const mine = ++sequence.current;
    setBusy(true);
    try {
      const res = await callTool(EDIT_TOOL, { id: base.modelId, specHash: base.specHash, mutations });
      const out = res.structuredContent as EditResult | undefined;
      if (mine !== sequence.current) return;
      if (!out || res.isError) {
        setRejection({ fieldErrors: {}, formErrors: [textOf(res) || 'The edit could not be saved.'] });
        setShown(base.state);
        return;
      }
      if ('rejected' in out) {
        setRejection(splitIssues(out.issues));
        setShown(base.state);
        return;
      }
      commit({
        ...base, specHash: out.specHash, spec: out.spec ?? base.spec, graph: out.graph ?? base.graph,
        inputs: mutations, state: out.state, meta: out.meta, flags: out.flags,
      }, Object.keys(mutations).length > 0);
      setRejection(null);
    } catch (e) {
      if (mine !== sequence.current) return;
      setRejection({ fieldErrors: {}, formErrors: [`Saving the edit failed: ${e instanceof Error ? e.message : String(e)}`] });
      setShown(base.state);
    } finally {
      if (mine === sequence.current) setBusy(false);
    }
  }, [callTool, commit]);

  const recompute = useCallback(async (inputs: Inputs) => {
    const base = committedRef.current;
    if (!base) return;
    if (base.modelId) return saveEdits(base, inputs);
    const mine = ++sequence.current;
    setBusy(true);
    const call = async (withSpec: boolean) => {
      const res = await callTool(RECOMPUTE_TOOL,
        withSpec ? { specHash: base.specHash, spec: base.spec, inputs } : { specHash: base.specHash, inputs });
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
      commit({ ...base, specHash: out.specHash, inputs, state: out.state, meta: out.meta, flags: out.flags }, true);
      setRejection(null);
    } catch (e) {
      if (mine !== sequence.current) return;
      setRejection({ fieldErrors: {}, formErrors: [`Recompute failed: ${e instanceof Error ? e.message : String(e)}`] });
      setShown(base.state);
    } finally {
      if (mine === sequence.current) setBusy(false);
    }
  }, [callTool, commit, saveEdits]);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  const onMutate = useCallback(async (mutations: Record<string, unknown>) => {
    const base = committedRef.current;
    if (!base) return;
    // A stored model already holds the earlier edits, so only the new ones are sent.
    const inputs = mergeInputs(pendingInputs.current ?? (base.modelId ? {} : base.inputs), mutations);
    pendingInputs.current = inputs;
    setShown(prev => Object.entries(mutations).reduce((s, [k, v]) => setAt(s, toCanonical(k), v), prev));
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      const send = pendingInputs.current;
      pendingInputs.current = null;
      if (send) void recompute(send);
    }, RECOMPUTE_DEBOUNCE_MS);
  }, [recompute]);

  return { committed, shown, rejection, busy, load, recompute, onMutate };
}
