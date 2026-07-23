import { useState, useEffect, useRef } from 'react';
import { buildSubscribeUrl } from '../wsAuth';
import type { ChangeEvent } from '../types';

interface TimestampedEvent extends ChangeEvent {
  ts: number;
}

interface Props { modelId: string }

type Status = 'connecting' | 'connected' | 'disconnected' | 'error';

export default function LivePanel({ modelId }: Props) {
  const [status, setStatus] = useState<Status>('disconnected');
  const [events, setEvents] = useState<TimestampedEvent[]>([]);
  const [paused, setPaused] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  useEffect(() => {
    setStatus('connecting');
    setEvents([]);

    const ws = new WebSocket(buildSubscribeUrl(modelId));

    ws.onopen  = () => setStatus('connected');
    ws.onclose = () => setStatus('disconnected');
    ws.onerror = () => setStatus('error');
    ws.onmessage = (e: MessageEvent) => {
      if (pausedRef.current) return;
      try {
        const evt = JSON.parse(e.data as string) as ChangeEvent;
        setEvents(prev => [...prev.slice(-199), { ...evt, ts: Date.now() }]);
      } catch { /* ignore malformed frame */ }
    };

    return () => ws.close();
  }, [modelId]);

  useEffect(() => {
    if (!paused) bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [events, paused]);

  const dotClass =
    status === 'connected'    ? 'dot-green' :
    status === 'error'        ? 'dot-red'   : 'dot-gray';

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <div className="live-status">
          <span className={`dot ${dotClass}`} />
          <span style={{ textTransform: 'capitalize' }}>{status}</span>
          {status === 'connected' && <span style={{ color: 'var(--text-muted)', fontSize: 12 }}>— {events.length} event{events.length !== 1 ? 's' : ''}</span>}
        </div>
        <div className="btn-row">
          <button className="btn btn-sm" onClick={() => setPaused(p => !p)}>
            {paused ? '▶ Resume' : '⏸ Pause'}
          </button>
          <button className="btn btn-sm" onClick={() => setEvents([])}>Clear</button>
        </div>
      </div>

      <div style={{ maxHeight: 'calc(100vh - 180px)', overflowY: 'auto' }}>
        {events.length === 0 ? (
          <div className="card" style={{ color: 'var(--text-muted)', textAlign: 'center', padding: '32px 16px' }}>
            Waiting for mutations… Apply a mutation in the Mutate tab to see events here.
          </div>
        ) : (
          events.map((evt, i) => (
            <div className="live-event" key={i}>
              <span className="live-event-time">{new Date(evt.ts).toLocaleTimeString()}</span>
              {evt.kind === 'spec-evolved' ? (
                <div>
                  <strong>spec evolved:</strong>{' '}
                  <span className="badge badge-purple">v{evt.version}</span>
                </div>
              ) : (
              <>
              <div>
                <strong>mutated:</strong>{' '}
                {evt.mutatedPaths.map(p => <span key={p} className="badge badge-blue" style={{ marginRight: 3 }}>{p}</span>)}
              </div>
              {evt.derivedUpdated.length > 0 && (
                <div>
                  <strong>derived:</strong>{' '}
                  {evt.derivedUpdated.map(p => <span key={p} className="badge badge-purple" style={{ marginRight: 3 }}>{p}</span>)}
                </div>
              )}
              {evt.flaggedConstraints.length > 0 && (
                <div>
                  <strong>flagged:</strong>{' '}
                  {evt.flaggedConstraints.map(c => (
                    <span key={c.constraintId} className="badge badge-orange" style={{ marginRight: 3 }} title={c.message}>
                      {c.constraintId}
                    </span>
                  ))}
                </div>
              )}
              {evt.dispatchedEffects.length > 0 && (
                <div>
                  <strong>effects:</strong>{' '}
                  {evt.dispatchedEffects.map(e => <span key={e.effectId} className="badge badge-green" style={{ marginRight: 3 }}>{e.effectId}</span>)}
                </div>
              )}
              </>
              )}
            </div>
          ))
        )}
        <div ref={bottomRef} />
      </div>
    </>
  );
}
