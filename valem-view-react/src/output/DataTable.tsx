import { useState } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { formatValue } from '../format';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { DataTableSpec } from '../types';
import { buttonStyle, ink, labelText, muted, radius, rule, ruleStrong, surface } from '../themeColors';

/** A column of numbers reads right-aligned, so its digits line up. */
const isNumeric = (col: { format?: string }) =>
  col.format === 'currency' || col.format === 'percent' || col.format === 'number' || col.format === 'integer';

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
      <div style={{ overflowX: 'auto', border: `1px solid ${rule()}`, borderRadius: radius.md }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14, color: ink() }}>
          {/* The label is the table's caption, so assistive tech announces it with the table. */}
          {c.label && (
            <caption style={{ ...labelText, textAlign: 'left', padding: '10px 12px 6px', captionSide: 'top' }}>
              {c.label}
            </caption>
          )}
          <thead>
            <tr>
              {columns.map(col => (
                <th
                  key={col.field}
                  style={{
                    textAlign: isNumeric(col) ? 'right' : 'left',
                    padding: '8px 12px',
                    background: surface(),
                    borderBottom: `1px solid ${ruleStrong()}`,
                    fontSize: 13,
                    fontWeight: 650,
                    color: muted(),
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
              <tr key={i} style={{ borderBottom: `1px solid ${rule()}` }}>
                {columns.map(col => {
                  const cellVal = row[col.field];
                  const display = col.format
                    ? formatValue(cellVal, col.format, col.currency)
                    : cellVal != null
                      ? String(cellVal)
                      : '';
                  return (
                    <td
                      key={col.field}
                      style={{
                        padding: '9px 12px',
                        textAlign: isNumeric(col) || typeof cellVal === 'number' ? 'right' : 'left',
                        fontVariantNumeric: 'tabular-nums',
                      }}
                    >
                      {display}
                    </td>
                  );
                })}
              </tr>
            ))}
            {visible.length === 0 && (
              <tr>
                <td colSpan={columns.length} style={{ padding: 16, textAlign: 'center', color: muted() }}>
                  No data
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {totalPages > 1 && (
        <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 8, marginTop: 8, fontSize: 13, color: muted() }}>
          <button type="button" disabled={page === 0} onClick={() => setPage(p => p - 1)} style={{ ...buttonStyle('secondary'), minHeight: 32, padding: '0 12px', fontSize: 13, opacity: page === 0 ? 0.5 : 1 }}>
            ‹ Prev
          </button>
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>
            {page + 1} / {totalPages}
          </span>
          <button type="button" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)} style={{ ...buttonStyle('secondary'), minHeight: 32, padding: '0 12px', fontSize: 13, opacity: page >= totalPages - 1 ? 0.5 : 1 }}>
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
