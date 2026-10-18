import type { CSSProperties } from 'react';
import { useViewContext } from '../ViewContext';
import { useJSONataBoolean } from '../hooks/useJSONata';
import { getByPath } from '../hooks/useDeferredMutate';
import { ComponentRenderer } from '../ComponentRenderer';
import { scopeToIndex } from '../navigation';
import { hasChildComponents } from '../types';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ModelState } from '../types';
import type { ComponentSpec, EditableTableSpec, GridColumnSpec } from '../types';
import { buttonStyle, controlEdge, ink, labelText, muted, radius, rule, ruleStrong, surface, tone } from '../themeColors';

/** Row-action buttons: the shared shape at 32px, so a row stays compact. */
const compact = { minHeight: 32, padding: '0 12px', fontSize: 13 } as const;

/** Present to assistive tech, absent from the page: a cell's column header is its visible label. */
const visuallyHidden: CSSProperties = {
  position: 'absolute', width: 1, height: 1, padding: 0, margin: -1,
  overflow: 'hidden', clip: 'rect(0 0 0 0)', whiteSpace: 'nowrap', border: 0,
};

const headCell: CSSProperties = {
  padding: '8px 10px',
  background: surface(),
  borderBottom: `1px solid ${ruleStrong()}`,
  fontSize: 13,
  fontWeight: 650,
  color: muted(),
  textAlign: 'left',
  whiteSpace: 'nowrap',
};

const bodyCell: CSSProperties = { padding: '6px 10px', verticalAlign: 'middle' };

/**
 * Gives every component of one row its own `id`, so the same column template drawn on ten rows
 * yields ten distinct DOM ids — fields use the id for `<input id>` and `<label htmlFor>`, and a
 * repeated id would point every label at the first row's input.
 */
function suffixIds(components: ComponentSpec[], suffix: string): ComponentSpec[] {
  return components.map(c => {
    const out: Record<string, unknown> = { ...(c as Record<string, unknown>), id: `${c.id}-${suffix}` };
    if (hasChildComponents(c) && c.components) out.components = suffixIds(c.components, suffix);
    return out as ComponentSpec;
  });
}

/**
 * The cell's contents, plus a hidden label naming column and row for a sole input that has no
 * label of its own — "Unit Price, row 2" — so a screen reader (and a test) can address the cell.
 */
function Cell({ components, name, state }: { components: ComponentSpec[]; name: string; state: ModelState }) {
  const sole = components.length === 1 && !(components[0] as { label?: string }).label ? components[0] : null;
  return (
    <>
      {sole && <label htmlFor={sole.id} style={visuallyHidden}>{name}</label>}
      {components.map(child => (
        <ComponentRenderer key={child.id} component={child} state={state} />
      ))}
    </>
  );
}

/**
 * `editableTable` — a grid whose cells hold components, so a value is edited where it is read.
 *
 * Rows come from `bind` (one per array element, cell binds scoped from `[*]` to the row's index,
 * exactly like an inline `sectionList`) or from the fixed `gridRows`, whose cells are keyed by
 * column id and fall back to the column's own components.
 */
export function EditableTable({ component: c, state }: BaseComponentProps<EditableTableSpec>) {
  const { onMutate } = useViewContext();
  const columns = c.gridColumns ?? [];
  const rowHeader = c.rowHeader;

  const bindKey = c.bind?.replace(/^\$\./, '');
  const raw = bindKey ? getByPath(state, bindKey) : undefined;
  const items: unknown[] = Array.isArray(raw) ? raw : [];
  const fromArray = c.bind != null;

  const canAddExpr    = useJSONataBoolean(c.canAdd,    state, true);
  const canRemoveExpr = useJSONataBoolean(c.canRemove, state, true);
  const canAdd = fromArray && canAddExpr;
  const canRemove = fromArray && canRemoveExpr;

  function handleAdd() {
    // Empty, not seeded with nulls: one null would turn every aggregate over the array null until
    // the row is filled in. Same reasoning as sectionList's Add.
    if (c.bind) onMutate({ [c.bind]: [...items, {}] });
  }

  function handleRemove(idx: number) {
    if (c.bind) onMutate({ [c.bind]: items.filter((_, i) => i !== idx) });
  }

  type Row = { key: string; name: string; header?: string; headerCell?: ComponentSpec[]; cells: ComponentSpec[][]; index?: number };

  const cellsFor = (col: GridColumnSpec) => col.components ?? [];

  const rows: Row[] = fromArray
    ? items.map((_, idx) => ({
        key: String(idx),
        name: `row ${idx + 1}`,
        index: idx,
        headerCell: rowHeader?.components
          ? suffixIds(scopeToIndex(rowHeader.components, idx), `r${idx}`)
          : undefined,
        cells: columns.map(col => suffixIds(scopeToIndex(cellsFor(col), idx), `r${idx}`)),
      }))
    : (c.gridRows ?? []).map((r, idx) => {
        const key = r.id ?? String(idx);
        return {
          key,
          name: r.header ?? `row ${idx + 1}`,
          header: r.header,
          cells: columns.map((col, ci) =>
            suffixIds(r.cells?.[col.id ?? ''] ?? cellsFor(col), `${key}-c${ci}`)),
        };
      });

  const showRowHeader = rowHeader != null || (!fromArray && (c.gridRows ?? []).some(r => r.header));
  const colCount = columns.length + (showRowHeader ? 1 : 0) + (canRemove ? 1 : 0);

  return (
    <div data-testid={c.id}>
      <div style={{ overflowX: 'auto', border: `1px solid ${rule()}`, borderRadius: radius.md }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14, color: ink() }}>
          {c.label && (
            <caption style={{ ...labelText, textAlign: 'left', padding: '10px 12px 6px', captionSide: 'top' }}>
              {c.label}
            </caption>
          )}
          <thead>
            <tr>
              {showRowHeader && (
                <th scope="col" style={{ ...headCell, width: rowHeader?.width }}>{rowHeader?.header ?? ''}</th>
              )}
              {columns.map((col, ci) => (
                <th key={col.id ?? ci} scope="col" style={{ ...headCell, width: col.width, textAlign: col.align ?? 'left' }}>
                  {col.header}
                </th>
              ))}
              {canRemove && <th scope="col" style={headCell}><span style={visuallyHidden}>Actions</span></th>}
            </tr>
          </thead>
          <tbody>
            {rows.map(row => (
              <tr key={row.key} data-testid={`${c.id}-row-${row.key}`} style={{ borderBottom: `1px solid ${rule()}` }}>
                {showRowHeader && (
                  <th scope="row" style={{ ...bodyCell, textAlign: 'left', fontWeight: 600, width: rowHeader?.width }}>
                    {row.headerCell
                      ? <Cell components={row.headerCell} name={`${rowHeader?.header ?? 'Row header'}, ${row.name}`} state={state} />
                      : row.header ?? ''}
                  </th>
                )}
                {columns.map((col, ci) => (
                  <td key={col.id ?? ci} style={{ ...bodyCell, textAlign: col.align ?? 'left' }}>
                    <Cell components={row.cells[ci]} name={`${col.header ?? `Column ${ci + 1}`}, ${row.name}`} state={state} />
                  </td>
                ))}
                {canRemove && (
                  <td style={{ ...bodyCell, textAlign: 'right', width: 1 }}>
                    <button
                      type="button"
                      aria-label={`${c.removeLabel ?? 'Remove'} ${row.name}`}
                      onClick={() => handleRemove(row.index!)}
                      style={{ ...buttonStyle('secondary'), ...compact, color: tone.dangerText() }}
                    >
                      {c.removeLabel ?? 'Remove'}
                    </button>
                  </td>
                )}
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={Math.max(colCount, 1)} style={{ padding: 16, textAlign: 'center', color: muted() }}>
                  {c.emptyText ?? 'No rows yet.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {canAdd && (
        <button
          type="button"
          onClick={handleAdd}
          style={{ ...buttonStyle('ghost'), marginTop: 10, border: `1px dashed ${controlEdge()}` }}
        >
          + {c.addLabel ?? 'Add row'}
        </button>
      )}
    </div>
  );
}
