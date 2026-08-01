import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { buildEmbedSrc, encodeSpec, type EmbedMode, type EmbedTheme } from './embedUrl';

const DEFAULT_ENDPOINT = 'https://valem.onrender.com';

export interface ConstraintViolation {
  constraintId: string;
  message: string;
  [k: string]: unknown;
}

export interface ValemModelProps {
  /** Inline model spec (encoded to a fragment internally). Provide this OR `embedRef`. */
  spec?: Record<string, unknown>;
  /** A pre-encoded share fragment ("<fmt>.<blob>") from the sandbox Embed dialog. Provide this OR `spec`. */
  embedRef?: string;
  /** 'interactive' (default) lets the viewer edit; 'readonly' renders a computed snapshot. */
  mode?: EmbedMode;
  /** 'auto' (default) follows the viewer's OS preference. */
  theme?: EmbedTheme;
  /** The Valem sandbox origin to compute against. Defaults to the public sandbox. */
  endpoint?: string;
  /** A fixed pixel height, or 'auto' to size the frame from its content via postMessage. */
  height?: number | 'auto';
  /** Show the "Powered by Valem" footer inside the frame (default true). */
  attribution?: boolean;
  /** Initial viewId to open. */
  view?: string;
  /**
   * Defer creating the model until the component scrolls into view (default true). Keeps an article
   * with many embeds from firing every model create on load.
   */
  lazy?: boolean;
  className?: string;
  style?: CSSProperties;
  onReady?: () => void;
  onChange?: (state: Record<string, unknown>) => void;
  onConstraintFlag?: (violations: ConstraintViolation[]) => void;
  onError?: (message: string) => void;
}

interface FrameMessage {
  type?: string;
  px?: number;
  state?: Record<string, unknown>;
  violations?: ConstraintViolation[];
  message?: string;
}

/**
 * Embeds a single live, interactive Valem model into a React tree. Today this mounts the sandbox's
 * `/embed.html` route in an <iframe> (Transport A) — the spec is loaded and computed on the Valem
 * server, and the viewer's edits are private to their session. The prop surface is transport-agnostic
 * so a future native mount is not a breaking change.
 */
export function ValemModel({
  spec,
  embedRef,
  mode = 'interactive',
  theme = 'auto',
  endpoint = DEFAULT_ENDPOINT,
  height = 480,
  attribution = true,
  view,
  lazy = true,
  className,
  style,
  onReady,
  onChange,
  onConstraintFlag,
  onError,
}: ValemModelProps) {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [autoHeight, setAutoHeight] = useState<number | null>(null);
  const [visible, setVisible] = useState(!lazy);

  // Keep callbacks in a ref so the message listener isn't re-installed on every parent render.
  const cbRef = useRef({ onReady, onChange, onConstraintFlag, onError });
  cbRef.current = { onReady, onChange, onConstraintFlag, onError };

  const fragment = embedRef ?? (spec ? encodeSpec(spec) : null);
  const src = fragment
    ? buildEmbedSrc(fragment, { endpoint, mode, theme, attribution, view })
    : null;

  useEffect(() => {
    const expectedOrigin = new URL(endpoint, typeof location !== 'undefined' ? location.href : undefined).origin;
    function onMessage(e: MessageEvent) {
      // Only trust messages from the embed's own origin and from THIS iframe's window.
      if (e.origin !== expectedOrigin) return;
      if (iframeRef.current && e.source !== iframeRef.current.contentWindow) return;
      const d = (e.data ?? {}) as FrameMessage;
      switch (d.type) {
        case 'valem:ready': cbRef.current.onReady?.(); break;
        case 'valem:change': cbRef.current.onChange?.(d.state ?? {}); break;
        case 'valem:constraint': cbRef.current.onConstraintFlag?.(d.violations ?? []); break;
        case 'valem:error': cbRef.current.onError?.(d.message ?? 'embed error'); break;
        case 'valem:height':
          if (height === 'auto' && typeof d.px === 'number') setAutoHeight(d.px);
          break;
      }
    }
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [endpoint, height]);

  // Lazy mount: reveal the iframe (and thus create the model) once it scrolls near the viewport.
  useEffect(() => {
    if (visible || !containerRef.current) return;
    if (typeof IntersectionObserver === 'undefined') { setVisible(true); return; } // SSR/old engines
    const io = new IntersectionObserver((entries) => {
      if (entries.some(e => e.isIntersecting)) { setVisible(true); io.disconnect(); }
    }, { rootMargin: '200px' });
    io.observe(containerRef.current);
    return () => io.disconnect();
  }, [visible]);

  // On unmount, ask the frame to dispose its ephemeral server model before the browser detaches it
  // (a detach doesn't reliably fire pagehide inside the frame). Best-effort; the model also TTLs out.
  useEffect(() => {
    const iframe = iframeRef.current;
    const expectedOrigin = new URL(endpoint, typeof location !== 'undefined' ? location.href : undefined).origin;
    return () => {
      try { iframe?.contentWindow?.postMessage({ type: 'valem:dispose' }, expectedOrigin); } catch { /* frame gone */ }
    };
  }, [endpoint, visible]);

  if (!src) {
    return <div className={className} style={style}>Valem embed: provide a `spec` or `embedRef` prop.</div>;
  }

  const resolvedHeight = height === 'auto' ? (autoHeight ?? 480) : height;

  return (
    <div ref={containerRef} className={className} style={{ width: '100%', minHeight: resolvedHeight, ...style }}>
      {visible && (
        <iframe
          ref={iframeRef}
          src={src}
          title="Valem model"
          loading="lazy"
          sandbox="allow-scripts allow-forms allow-same-origin"
          style={{ width: '100%', border: 0, height: resolvedHeight, display: 'block' }}
        />
      )}
    </div>
  );
}
