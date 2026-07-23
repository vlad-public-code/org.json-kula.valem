import jsonata from 'jsonata';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { EffectStatusSpec, MutationMap } from '../types';

const STATUSES: Record<string, { label: string; bg: string; fg: string; spin: boolean }> = {
  pending:   { label: 'Queued',    bg: '#f3f4f6', fg: '#374151', spin: false },
  in_flight: { label: 'Running',   bg: '#eff6ff', fg: '#1d4ed8', spin: true },
  applied:   { label: 'Done',      bg: '#f0fdf4', fg: '#15803d', spin: false },
  failed:    { label: 'Failed',    bg: '#fef2f2', fg: '#b91c1c', spin: false },
  cancelled: { label: 'Cancelled', bg: '#f9fafb', fg: '#6b7280', spin: false },
  superseded:{ label: 'Superseded',bg: '#f9fafb', fg: '#6b7280', spin: false },
};

/**
 * `effectStatus` — an effect's `statusPath` machine, drawn.
 *
 * The status is an ordinary model field, so it arrives through the same `viewDelta` as everything
 * else and needs no polling: when the fold-back mutation commits, the bound path changes and this
 * re-renders.
 *
 * The retry button runs `onRetry`'s mutations like any other handler — typically resetting the
 * status to `pending`, which re-arms the dedupe guard so the engine re-fires the effect. Nothing
 * here dispatches an effect directly; a view can only ask the model to.
 */
export function EffectStatus({ component: c }: BaseComponentProps<EffectStatusSpec>) {
  const { state, onMutate } = useViewContext();

  const status = readPath(state, c.bind);
  const error = readPath(state, c.errorPath);
  const key = String(status ?? '').toLowerCase();
  const s = STATUSES[key];

  const failed = key === 'failed';
  const showRetry = (c.showRetry ?? failed) && !!c.onRetry?.mutations;

  const retry = async () => {
    if (!c.onRetry?.mutations) return;
    try {
      const result = await jsonata(c.onRetry.mutations).evaluate(state as Record<string, unknown>);
      if (result && typeof result === 'object') await onMutate(result as MutationMap);
    } catch {
      // A malformed handler must not take the panel down with it; the status stays as it was.
    }
  };

  return (
    <div data-testid={c.id} title={c.tooltip} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        {c.label && <span style={{ fontSize: 13, fontWeight: 500 }}>{c.label}</span>}
        <span
          data-testid={`${c.id}-state`}
          role="status"
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            padding: '2px 10px',
            borderRadius: 12,
            fontSize: 12,
            fontWeight: 600,
            background: s?.bg ?? '#f3f4f6',
            color: s?.fg ?? '#6b7280',
          }}
        >
          {s?.spin && <Spinner color={s.fg} />}
          {s?.label ?? (status != null ? String(status) : 'Not started')}
        </span>
        {showRetry && (
          <button
            type="button"
            data-testid={`${c.id}-retry`}
            onClick={retry}
            style={{
              padding: '2px 10px',
              borderRadius: 6,
              border: '1px solid #d1d5db',
              background: '#fff',
              fontSize: 12,
              cursor: 'pointer',
            }}
          >
            {c.retryLabel ?? 'Retry'}
          </button>
        )}
      </div>
      {failed && error != null && (
        <span data-testid={`${c.id}-error`} style={{ fontSize: 11, color: '#b91c1c' }}>
          {String(error)}
        </span>
      )}
    </div>
  );
}

function Spinner({ color }: { color: string }) {
  return (
    <svg width="10" height="10" viewBox="0 0 20 20" aria-hidden>
      <circle cx="10" cy="10" r="8" fill="none" stroke={color} strokeWidth="3" strokeOpacity="0.25" />
      <path d="M10 2 a8 8 0 0 1 8 8" fill="none" stroke={color} strokeWidth="3" strokeLinecap="round">
        <animateTransform
          attributeName="transform"
          type="rotate"
          from="0 10 10"
          to="360 10 10"
          dur="0.8s"
          repeatCount="indefinite"
        />
      </path>
    </svg>
  );
}

function readPath(state: unknown, bind?: string): unknown {
  if (!bind) return undefined;
  return getByPath(state, bind.replace(/^\$\./, ''));
}
