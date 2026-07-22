import { useState, useEffect } from 'react';
import jsonata from 'jsonata';
import type { ModelState } from '../types';

export function useJSONata(
  expression: string | undefined | null,
  state: ModelState,
): unknown {
  const [result, setResult] = useState<unknown>(undefined);

  useEffect(() => {
    if (!expression) { setResult(undefined); return; }
    let cancelled = false;
    (async () => {
      try {
        const compiled = jsonata(expression);
        const res = await compiled.evaluate(state as Record<string, unknown>);
        if (!cancelled) setResult(res);
      } catch {
        if (!cancelled) setResult(undefined);
      }
    })();
    return () => { cancelled = true; };
  }, [expression, state]);

  return result;
}

export function useJSONataBoolean(
  expression: string | boolean | undefined | null,
  state: ModelState,
  fallback: boolean,
): boolean {
  const [result, setResult] = useState<boolean>(() => {
    if (expression === undefined || expression === null) return fallback;
    if (typeof expression === 'boolean') return expression;
    return fallback;
  });

  useEffect(() => {
    if (expression === undefined || expression === null) { setResult(fallback); return; }
    if (typeof expression === 'boolean') { setResult(expression); return; }
    let cancelled = false;
    (async () => {
      try {
        const compiled = jsonata(expression as string);
        const res = await compiled.evaluate(state as Record<string, unknown>);
        if (!cancelled) setResult(Boolean(res));
      } catch {
        if (!cancelled) setResult(fallback);
      }
    })();
    return () => { cancelled = true; };
  }, [expression, state, fallback]);

  return result;
}

/**
 * Evaluates `expression`, falling back to the literal string when it resolves to nothing.
 *
 * `useJSONataText` returns undefined for an expression that parses but matches no path — which is
 * exactly what a plain word does, since JSONata reads `up` or `success` as a path lookup. The
 * server keeps such values literal (`ViewEvaluator` only evaluates text containing `$`), so
 * without this fallback the same spec renders one way in the browser and another through
 * `GET /models/{id}/view`. Use this for fields that are usually a literal and occasionally an
 * expression: `variant`, `trend`, `icon`, `target`.
 */
export function useJSONataLiteral(
  expression: string | undefined | null,
  state: ModelState,
): string | undefined {
  const evaluated = useJSONataText(expression, state);
  return evaluated ?? expression ?? undefined;
}

export function useJSONataText(
  expression: string | undefined | null,
  state: ModelState,
): string | undefined {
  const [result, setResult] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (!expression) { setResult(undefined); return; }
    let cancelled = false;
    (async () => {
      try {
        const compiled = jsonata(expression);
        const res = await compiled.evaluate(state as Record<string, unknown>);
        if (!cancelled) setResult(res != null ? String(res) : undefined);
      } catch {
        // Not a valid JSONata expression — treat as literal string
        if (!cancelled) setResult(expression);
      }
    })();
    return () => { cancelled = true; };
  }, [expression, state]);

  return result;
}
