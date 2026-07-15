import type { LlmProgressEventData } from '../api';

const TOOL_LABELS: Record<string, string> = {
  web_search: 'Search',
  web_fetch: 'Fetch',
  eval_jsonata: 'Test expr',
};

function toolLabel(tool: string) {
  return TOOL_LABELS[tool] ?? tool;
}

function eventRow(e: LlmProgressEventData): { icon: string; text: string; muted: boolean } {
  switch (e.type) {
    case 'llm_requesting':
      return { icon: '⟳', text: e.attempt === 1 ? 'Sending to LLM…' : `Attempt ${e.attempt} — sending to LLM…`, muted: false };
    case 'tool_calling':
      return { icon: '→', text: `${toolLabel(e.tool)}: ${e.detail}`, muted: false };
    case 'tool_completed':
      return { icon: '←', text: `${toolLabel(e.tool)} result: ${e.resultSummary}`, muted: true };
    case 'validating':
      return { icon: '✓', text: 'Validating spec…', muted: false };
    case 'validation_failed':
      return {
        icon: '✗',
        text: `Validation failed (${e.errors.length} error${e.errors.length !== 1 ? 's' : ''}): ${e.errors[0] ?? ''}${e.errors.length > 1 ? ' …' : ''}`,
        muted: false,
      };
    case 'test_running':
      return { icon: '▶', text: 'Running embedded tests…', muted: false };
    case 'test_failed':
      return { icon: '✗', text: `${e.failCount} test${e.failCount !== 1 ? 's' : ''} failed`, muted: false };
    case 'retrying':
      return { icon: '↺', text: `Repair attempt ${e.attempt} of ${e.maxAttempts}…`, muted: false };
    default:
      return { icon: '·', text: String((e as { type: string }).type), muted: true };
  }
}

interface Props {
  events: LlmProgressEventData[];
  /** Extra line shown after the last event while still loading, e.g. "Waiting…" */
  pendingLabel?: string;
}

export default function LlmProgressLog({ events, pendingLabel }: Props) {
  return (
    <div style={{
      fontFamily: 'var(--font-mono, monospace)',
      fontSize: 12,
      background: 'var(--code-bg, #111)',
      border: '1px solid var(--border)',
      borderRadius: 6,
      padding: '10px 14px',
      display: 'flex',
      flexDirection: 'column',
      gap: 4,
      maxHeight: 320,
      overflowY: 'auto',
    }}>
      {events.map((e, i) => {
        const { icon, text, muted } = eventRow(e);
        const isError = e.type === 'validation_failed' || e.type === 'test_failed';
        return (
          <div key={i} style={{
            display: 'flex',
            gap: 8,
            color: isError
              ? 'var(--error-fg, #f88)'
              : muted
                ? 'var(--text-muted, #666)'
                : 'var(--text, #ccc)',
          }}>
            <span style={{ width: 14, textAlign: 'center', flexShrink: 0 }}>{icon}</span>
            <span>{text}</span>
          </div>
        );
      })}
      {pendingLabel && (
        <div style={{ display: 'flex', gap: 8, color: 'var(--text-muted, #888)' }}>
          <span style={{ width: 14, textAlign: 'center', flexShrink: 0 }}>…</span>
          <span>{pendingLabel}</span>
        </div>
      )}
      {events.length === 0 && !pendingLabel && (
        <div style={{ color: 'var(--text-muted, #888)' }}>Waiting for LLM…</div>
      )}
    </div>
  );
}
