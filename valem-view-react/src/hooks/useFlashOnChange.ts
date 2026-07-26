import { useEffect, useRef } from 'react';
import type { RefObject } from 'react';

/**
 * The reactive runtime's whole promise is that derived values recompute as the inputs change. This
 * hook makes that visible: attach the returned ref to the element whose background should pulse, pass
 * the value that element displays, and whenever that value changes between renders — i.e. the field
 * was just recomputed — the element flashes emerald and fades back to rest.
 *
 * The change itself is the signal, so no component needs to know whether a field is "derived": a
 * static label's value never changes and so never flashes, while a derived total flashes exactly when
 * it moves. That also means it's safe to leave wired on a value the user edits directly only if that
 * value isn't echoed back into the same element mid-keystroke — so wire it on output/derived displays,
 * not on the input the user is typing into.
 *
 * Implemented with the Web Animations API rather than a CSS class on purpose: every component in this
 * package is inline-styled and the package ships no stylesheet, and `element.animate` leaves no
 * residual state — when the animation ends the node releases back to its own inline background. Honors
 * `prefers-reduced-motion`, and is a no-op wherever `element.animate` is unavailable (e.g. jsdom under
 * test), so it never affects rendered output, only motion.
 *
 * @param value         the displayed value; a change from the previous render triggers the flash.
 * @param restBackground the element's own resting background, so the fade lands on it with no snap
 *                       (default `'transparent'`; a StatTile with a white card passes `'#ffffff'`).
 */

// Valem's reactive signal — --signal-soft in valem-ui's token system. This package is light-only.
const FLASH_BACKGROUND = '#d6f5e6';
const FLASH_DURATION_MS = 650;

export function useFlashOnChange<E extends HTMLElement = HTMLElement>(
  value: unknown,
  restBackground = 'transparent',
): RefObject<E> {
  const ref = useRef<E>(null);
  const previous = useRef<unknown>(value);
  const primed = useRef(false);

  useEffect(() => {
    const changed = !Object.is(previous.current, value);
    previous.current = value;

    // Skip the first commit — mounting with an initial value isn't a recomputation.
    if (!primed.current) {
      primed.current = true;
      return;
    }
    if (!changed) return;

    const el = ref.current;
    if (!el || typeof el.animate !== 'function') return;

    const reducedMotion =
      typeof window !== 'undefined' && typeof window.matchMedia === 'function'
        ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
        : false;
    if (reducedMotion) return;

    // Instant emerald light-up, then an ease-out fade back to the element's own background.
    el.animate(
      [
        { backgroundColor: FLASH_BACKGROUND },
        { backgroundColor: FLASH_BACKGROUND, offset: 0.12 },
        { backgroundColor: restBackground },
      ],
      { duration: FLASH_DURATION_MS, easing: 'ease-out' },
    );
  }, [value, restBackground]);

  return ref;
}
