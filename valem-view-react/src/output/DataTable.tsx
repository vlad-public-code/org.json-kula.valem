import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { formatValue } from '../format';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { DataTableSpec } from '../types';

export function DataTable({ component: c }: BaseComponentProps<DataTableSpec>) {
  const { state } = useViewContext();
  const [page, setPage] = useState(0);

  const bindKey = c.bind?.replace(/^\$\./, '');
  const raw = bindKey ? getByPath(state, bindKey) : undefined;
  const rows: Record<string, unknown>[] = Array.isArray(raw) ? (raw as Record<string, unknown>[]) : [];
  const columns = c.tableColumns ?? [];
  const pageSize = c.pageSize ?? 10;
  const totalPages = Math.ceil(rows.length / pageSize);
  const visible = rows.slice(page * pageSize, (page + 1) * pageSize);

  return (
    <div>
      {c.label && <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 6 }}>{c.label}</div>}
      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr>
              {columns.map(col => (
                <th
                  key={col.field}
                  style={{
                    textAlign: 'left',
                    padding: '6px 10px',
                    background: '#f9fafb',
                    borderBottom: '2px solid #e5e7eb',
                    fontWeight: 600,
                    whiteSpace: 'nowrap',
                    width: col.width ?? undefined,
                  }}
                >
                  {col.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {visible.map((row, i) => (
              <tr key={i} style={{ borderBottom: '1px solid #f3f4f6' }}>
                {columns.map(col => {
                  const cellVal = row[col.field];
                  const display = col.format
                    ? formatValue(cellVal, col.format, col.currency)
                    : cellVal != null
                      ? String(cellVal)
                      : '';
                  return (
                    <td key={col.field} style={{ padding: '6px 10px' }}>
                      {display}
                    </td>
                  );
                })}
              </tr>
            ))}
            {visible.length === 0 && (
              <tr>
                <td colSpan={columns.length} style={{ padding: 16, textAlign: 'center', color: '#9ca3af' }}>
                  No data
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {totalPages > 1 && (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8, fontSize: 12 }}>
          <button disabled={page === 0} onClick={() => setPage(p => p - 1)} style={{ padding: '2px 8px' }}>
            ‹ Prev
          </button>
          <span style={{ lineHeight: '24px' }}>
            {page + 1} / {totalPages}
          </span>
          <button disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)} style={{ padding: '2px 8px' }}>
            Next ›
          </button>
        </div>
      )}
    </div>
  );
}

// Formatting lives in ../format so a column, a summaryList row and a statTile over the same
// field always read the same. The local helper this replaced hardcoded USD and used
// Intl's `style: 'percent'`, which multiplies by 100 — so a percent column and a percent
// summary row disagreed about what the same stored number meant.
