import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { useJSONataText } from '../hooks/useJSONata';
import { formatValue } from '../format';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { KeyValueItemSpec, KeyValueListSpec, ModelState } from '../types';

/**
 * `keyValueList` / `summaryList` — the read-only summary a derivation-heavy model ends on.
 *
 * A `<dl>` rather than a table: these are captioned values, not rows of a dataset, and the
 * caption/value pairing is what a screen reader should be told. `dataTable` remains the component
 * for an array.
 */
export function KeyValueList({ component: c }: BaseComponentProps<KeyValueListSpec>) {
  const { state } = useViewContext();
  const items = c.items ?? [];
  const columns = c.columns ?? 1;

  return (
    <div data-testid={c.id} title={c.tooltip} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {c.label && <span style={{ fontSize: 13, fontWeight: 600 }}>{c.label}</span>}
      <dl style={{
        display: 'grid',
        gridTemplateColumns: `repeat(${columns}, minmax(0, auto) minmax(0, 1fr))`,
        columnGap: 16,
        rowGap: 6,
        margin: 0,
        fontSize: 13,
      }}>
        {items.map((item, i) => (
          <Row key={`${item.label ?? item.bind ?? i}-${i}`} item={item} state={state} testId={`${c.id}-row-${i}`} />
        ))}
      </dl>
    </div>
  );
}

function Row({ item, state, testId }: { item: KeyValueItemSpec; state: ModelState; testId: string }) {
  const bindPath = item.bind?.replace(/^\$\./, '');
  const bound = bindPath ? getByPath(state, bindPath) : undefined;
  // Mirrors the server: bind wins, so a row never shows two different things depending on which
  // field the reader happened to look at.
  const text = useJSONataText(item.bind ? undefined : item.text, state);
  const display = item.bind ? formatValue(bound, item.format, item.currency) : text ?? '';

  return (
    <>
      <dt style={{ color: '#6b7280' }}>{item.label}</dt>
      <dd data-testid={testId} style={{ margin: 0, fontWeight: 500, color: '#111827' }}>
        {display || '—'}
      </dd>
    </>
  );
}
