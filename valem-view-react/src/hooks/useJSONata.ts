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
