import { useState, useEffect, useRef, useCallback } from 'react';
import { useViewContext } from '../ViewContext';
import type { ModelState } from '../types';

const DEBOUNCE_MS = 5000;

/** Traverses a nested state object by dot-notation path (e.g. "items.0.name"). */
export function getByPath(obj: unknown, path: string): unknown {
  if (!path) return obj;
  // Normalise bracket notation: items[0].name → items.0.name
  const segments = path.replace(/\[(\d+)\]/g, '.$1').split('.');
  let cur: unknown = obj;
  for (const seg of segments) {
    if (cur == null || typeof cur !== 'object') return undefined;
    cur = (cur as Record<string, unknown>)[seg];
  }
  return cur;
}

/**
 * Holds a local draft value for text-based inputs.
 * Mutations are sent on blur or automatically after DEBOUNCE_MS of inactivity,
 * avoiding backend round-trips on every keystroke and preventing intermediate
 * invalid states (e.g. deleting digits while typing a new number) from being sent.
 *
 * The draft stays in sync with the model value whenever there is no pending edit.
 * After a failed mutation the model value does not change, so the draft preserves
 * the user's in-progress edit alongside the inline error message.
 */
export function useDeferredMutate(bind: string | undefined, state: ModelState) {
  const { onMutate } = useViewContext();
  const bindPath = bind?.replace(/^\$\./, '') ?? '';
  const modelValue = bindPath ? getByPath(state, bindPath) : undefined;

  const [draft, setDraft] = useState<string>(String(modelValue ?? ''));
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Box the pending value so null is distinguishable from "nothing pending"
  const pendingRef = useRef<{ value: unknown } | null>(null);

  // Sync draft from model when the user is not actively editing
  useEffect(() => {
    if (!pendingRef.current) {
      setDraft(String(modelValue ?? ''));
    }
  }, [modelValue]);

  const flush = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    if (pendingRef.current !== null && bind) {
      const { value } = pendingRef.current;
      pendingRef.current = null;
      onMutate({ [bind]: value });
    }
  }, [bind, onMutate]);

  const schedule = useCallback(
    (displayValue: string, mutationValue: unknown) => {
      setDraft(displayValue);
      pendingRef.current = { value: mutationValue };
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = setTimeout(flush, DEBOUNCE_MS);
    },
    [flush],
  );

  const handleBlur = useCallback(() => flush(), [flush]);

  useEffect(
    () => () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    },
    [],
  );

  return { draft, schedule, handleBlur };
}
