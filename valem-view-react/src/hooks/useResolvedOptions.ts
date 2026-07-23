import { useEffect, useState } from 'react';
import { useJSONata } from './useJSONata';
import type { ChoiceInputSpec, ModelState, OptionSpec } from '../types';

/**
 * Resolves a choice component's options from whichever of the four sources the spec used.
 *
 * All of this happens client-side: `ViewEvaluator` passes the static `options` list through and
 * never reads `optionsExpr`, `optionsUrl` or `optionsPath`. That is worth knowing when it
 * matters who computed a list — `optionsExpr` gives no more server-side auditability than
 * `optionsUrl`, and neither is subject to the server's SSRF controls. Where a list must be
 * explainable, compute it in a `derivations` field and point `optionsExpr` at that.
 *
 * Precedence is static, then expression, then URL, so a spec that sets more than one is
 * predictable rather than racing.
 */
export function useResolvedOptions(c: ChoiceInputSpec, state: ModelState): OptionSpec[] {
  const [urlOptions, setUrlOptions] = useState<OptionSpec[]>([]);
  const exprResult = useJSONata(c.optionsExpr, state);

  useEffect(() => {
    if (!c.optionsUrl) { setUrlOptions([]); return; }
    let cancelled = false;
    fetch(c.optionsUrl)
      .then(r => r.json())
      .then((data: unknown) => {
        if (cancelled) return;
        const raw = c.optionsPath ? getPath(data, c.optionsPath) : data;
        setUrlOptions(toOptions(raw));
      })
      .catch(() => { if (!cancelled) setUrlOptions([]); });
    return () => { cancelled = true; };
  }, [c.optionsUrl, c.optionsPath]);

  if (c.options?.length) return c.options;
  if (c.optionsExpr) return toOptions(exprResult);
  return urlOptions;
}

/**
 * Accepts what a JSONata expression or an API realistically returns: `{value,label}` objects,
 * or a bare array of strings/numbers, which is what a `derivations` field holding a list of ids
 * looks like.
 */
function toOptions(raw: unknown): OptionSpec[] {
  if (!Array.isArray(raw)) return [];
  return raw.map(item => {
    if (item != null && typeof item === 'object') {
      const o = item as Record<string, unknown>;
      const value = String(o.value ?? o.id ?? '');
      return { value, label: String(o.label ?? o.name ?? value) };
    }
    return { value: String(item), label: String(item) };
  });
}

function getPath(obj: unknown, path: string): unknown {
  return path.split('.').reduce((cur, k) => (cur as Record<string, unknown>)?.[k], obj);
}
