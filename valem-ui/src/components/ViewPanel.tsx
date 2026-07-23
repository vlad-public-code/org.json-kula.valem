import { useState, useEffect, useCallback, useRef } from 'react';
import { ViewRenderer } from 'valem-view-react';
import type { ViewDefinition, EvaluatedView } from 'valem-view-react';
import { api, ApiError } from '../api';
import { buildSubscribeUrl } from '../wsAuth';
import type { ConstraintViolationItem, ConstraintViolationBody, SchemaViolationBody, ViewDeltaComponent } from '../types';

interface Props {
  modelId: string;
}

interface SpecConstraint {
  id: string;
  path?: string | string[] | null;
}

function buildConstraintPathMap(constraints: SpecConstraint[]): Record<string, string[]> {
  const map: Record<string, string[]> = {};
  for (const c of constraints) {
    if (!c.path) continue;
    const paths = Array.isArray(c.path) ? c.path : [c.path];
    // Only exact $.path constraints; skip array-scoped [*] paths
    const exact = paths.filter(p => !p.includes('[*]'));
    if (exact.length) map[c.id] = exact;
  }
  return map;
}

function mapViolations(
  violations: ConstraintViolationItem[],
  constraintPathMap: Record<string, string[]>,
): { fieldErrors: Record<string, string>; formErrors: string[] } {
  const fieldErrors: Record<string, string> = {};
  const formErrors: string[] = [];

  for (const v of violations) {
    const paths = constraintPathMap[v.constraintId];
    if (paths?.length) {
      for (const p of paths) fieldErrors[p] = v.message;
    } else {
      formErrors.push(v.message);
    }
  }
  return { fieldErrors, formErrors };
}

export default function ViewPanel({ modelId }: Props) {
  const [viewDef, setViewDef] = useState<ViewDefinition | null>(null);
  const [state, setState] = useState<Record<string, unknown>>({});
  const [meta, setMeta] = useState<Record<string, unknown>>({});
  const [loadError, setLoadError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formErrors, setFormErrors] = useState<string[]>([]);
  const [activeViewId, setActiveViewId] = useState<string | undefined>(undefined);
  const constraintPathMapRef = useRef<Record<string, string[]>>({});
  const wsRef = useRef<WebSocket | null>(null);
  // Counts mutations whose WS broadcast we should ignore (we already applied viewDelta locally)
  const pendingOwnMutationRef = useRef(0);

  const loadSpec = useCallback(async () => {
    try {
      const spec = await api.getSpec(modelId) as {
        viewDefinition?: ViewDefinition;
        constraints?: SpecConstraint[];
      };
      if (!spec.viewDefinition) {
        setLoadError('This model has no viewDefinition. Add one to the spec to use this tab.');
        return;
      }
      constraintPathMapRef.current = buildConstraintPathMap(spec.constraints ?? []);
      setViewDef(spec.viewDefinition);
      setLoadError(null);
    } catch (e) {
      setLoadError(String(e));
    }
  }, [modelId]);

  const loadState = useCallback(async () => {
    try {
      const s = await api.getState(modelId);
      setState(s);
    } catch {
      // state load failure is non-critical after spec load
    }
  }, [modelId]);

  const loadMeta = useCallback(async () => {
    try {
      const evaluated: EvaluatedView = await api.getView(modelId);
      const metaMap: Record<string, unknown> = {};

      function collectMeta(comps: EvaluatedView['components']) {
        for (const comp of comps ?? []) {
          if (comp.bind) {
            metaMap[`${comp.bind}#readOnly`] = comp.readOnly;
            metaMap[`${comp.bind}#visible`]  = comp.visible;
            metaMap[`${comp.bind}#required`] = comp.required;
          }
          if (comp.components?.length) collectMeta(comp.components);
        }
      }
      collectMeta(evaluated.components);
      setMeta(metaMap);
    } catch {
      // meta is best-effort
    }
  }, [modelId]);

  // Switching views must refresh state, not just swap which components render. A mutation's
  // viewDelta only keeps the fields bound in the *active* view current, and the per-mutation
  // background loadState can land while a different view is showing — so a view that binds fields
  // the previous one didn't (a summary of derived totals, say) would otherwise show values stale
  // from before the user's edits elsewhere. Refetching on navigate keeps every view's derived
  // figures honest.
  const handleNavigate = useCallback((viewId: string) => {
    setActiveViewId(viewId);
    void loadState();
    void loadMeta();
  }, [loadState, loadMeta]);

  useEffect(() => {
    loadSpec();
    loadState();
    loadMeta();
  }, [loadSpec, loadState, loadMeta]);

  // WebSocket live updates. Reconnects on close (e.g. a dropped connection or a deployment that
  // rejected the initial, token-less handshake before buildSubscribeUrl had one to send) so the
  // view doesn't go silently stale for the rest of the session.
  useEffect(() => {
    let cancelled = false;
    let reconnectTimer: ReturnType<typeof setTimeout>;
    let ws: WebSocket;

    function connect() {
      ws = new WebSocket(buildSubscribeUrl(modelId));
      wsRef.current = ws;

      // Resync on every (re)connect: a broadcast that lands while the socket is down (dropped
      // connection, proxy hiccup) would otherwise go unnoticed forever, since onmessage is the
      // only other trigger for a refetch and there's nothing to replay a missed one.
      ws.onopen = () => {
        loadState();
        loadMeta();
      };

      ws.onmessage = (e: MessageEvent) => {
        let kind = 'mutation';
        try {
          kind = (JSON.parse(e.data as string) as { kind?: string }).kind ?? 'mutation';
        } catch { /* malformed frame — treat as a mutation-shaped nudge */ }
        if (kind === 'spec-evolved') {
          // Another client evolved the spec (e.g. a paired MCP's evolve_spec): the viewDefinition
          // and constraints may have changed, so re-fetch the spec too. Doesn't touch the
          // own-mutation slot below — that accounting is for mutation broadcasts only.
          loadSpec();
          loadState();
          loadMeta();
          return;
        }
        // Skip re-fetch for mutations we just made ourselves — we already applied viewDelta locally
        if (pendingOwnMutationRef.current > 0) {
          pendingOwnMutationRef.current--;
          return;
        }
        loadState();
        loadMeta();
      };
      ws.onclose = () => {
        if (!cancelled) reconnectTimer = setTimeout(connect, 3000);
      };
    }

    connect();
    return () => {
      cancelled = true;
      clearTimeout(reconnectTimer);
      ws.close();
    };
  }, [modelId, loadSpec, loadState, loadMeta]);

  function applyViewDelta(delta: Record<string, ViewDeltaComponent> | undefined, derivedPaths: string[]) {
    if (!delta) return;
    const derivedSet = new Set(derivedPaths);
    const stateUpdates: Record<string, unknown> = {};
    const metaUpdates: Record<string, unknown> = {};
    for (const comp of Object.values(delta)) {
      if (comp.bind) {
        // Only update state for derived fields that carry an explicit value in the JSON.
        // Skips types like sectionList (no value field) and null values (omitted by NON_NULL).
        // Directly-mutated fields are applied separately via applyMutations().
        if (derivedSet.has(comp.bind) && 'value' in comp) {
          stateUpdates[comp.bind.replace(/^\$\./, '')] = comp.value ?? null;
        }
        metaUpdates[`${comp.bind}#readOnly`] = comp.readOnly;
        metaUpdates[`${comp.bind}#visible`]  = comp.visible;
        metaUpdates[`${comp.bind}#required`] = comp.required;
      }
    }
    if (Object.keys(stateUpdates).length > 0) setState(prev => ({ ...prev, ...stateUpdates }));
    if (Object.keys(metaUpdates).length > 0)  setMeta(prev => ({ ...prev, ...metaUpdates }));
  }

  function applyMutations(mutations: Record<string, unknown>) {
    // Optimistically patch top-level paths ($.field) into local state immediately.
    // Nested paths ($.items.0.name) are left to the background loadState() call.
    const stateUpdates: Record<string, unknown> = {};
    for (const [dotPath, value] of Object.entries(mutations)) {
      if (/^\$\.\w+$/.test(dotPath)) {
        stateUpdates[dotPath.replace(/^\$\./, '')] = value;
      }
    }
    if (Object.keys(stateUpdates).length > 0) setState(prev => ({ ...prev, ...stateUpdates }));
  }

  async function handleMutate(mutations: Record<string, unknown>) {
    setFieldErrors({});
    setFormErrors([]);
    // Claim one WS broadcast slot — server broadcasts once per successful mutation
    pendingOwnMutationRef.current++;
    // Resolve the active view even when user hasn't navigated (activeViewId stays undefined)
    const effectiveViewId = activeViewId ?? viewDef?.defaultView ?? viewDef?.views[0]?.id;
    try {
      const result = await api.mutate(modelId, mutations, effectiveViewId);
      if (result.flaggedConstraints?.length) {
        const { fieldErrors: fe, formErrors: fme } = mapViolations(
          result.flaggedConstraints,
          constraintPathMapRef.current,
        );
        setFieldErrors(fe);
        setFormErrors(fme);
      }
      // Optimistic state update: mutations applied directly, derived values from delta
      applyMutations(mutations);
      applyViewDelta(result.viewDelta, result.derivedUpdated ?? []);
      // Background sync — corrects nested-path state, null values, and visibility changes
      // that viewDelta doesn't cover (meta-derivations affecting other components).
      void loadState();
      void loadMeta();
    } catch (e) {
      // On error the server doesn't broadcast, so release the slot we claimed
      pendingOwnMutationRef.current--;
      if (e instanceof ApiError && e.status === 409) {
        try {
          const body = JSON.parse(e.body) as ConstraintViolationBody;
          if (body.violations?.length) {
            const { fieldErrors: fe, formErrors: fme } = mapViolations(
              body.violations,
              constraintPathMapRef.current,
            );
            setFieldErrors(fe);
            setFormErrors(fme);
            return;
          }
        } catch {
          // fall through to generic error
        }
      }
      if (e instanceof ApiError && e.status === 422) {
        try {
          const body = JSON.parse(e.body) as SchemaViolationBody;
          if (body.violations?.length) {
            const fe: Record<string, string> = {};
            const fme: string[] = [];
            for (const v of body.violations) {
              if (v.path) fe[v.path] = v.message;
              else fme.push(v.message);
            }
            setFieldErrors(fe);
            setFormErrors(fme);
            return;
          }
        } catch {
          // fall through to generic error
        }
      }
      setLoadError(String(e));
    }
  }

  if (loadError) {
    return (
      <div className="banner banner-error" style={{ marginTop: 16 }}>
        {loadError}
      </div>
    );
  }

  if (!viewDef) {
    return <div style={{ padding: 24, color: 'var(--text-muted)' }}>Loading view…</div>;
  }

  return (
    <div style={{ padding: 20 }}>
      {formErrors.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          {formErrors.map((msg, i) => (
            <div key={i} className="banner banner-error" style={{ marginBottom: 4 }}>
              {msg}
            </div>
          ))}
        </div>
      )}
      <ViewRenderer
        modelId={modelId}
        viewDef={viewDef}
        state={state}
        meta={meta}
        onMutate={handleMutate}
        onNavigate={handleNavigate}
        activeViewId={activeViewId}
        violations={fieldErrors}
        formErrors={formErrors}
      />
    </div>
  );
}
