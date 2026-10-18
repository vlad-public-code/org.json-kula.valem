import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ViewRenderer } from '../ViewRenderer';
import { childComponentLists, isKnownComponent } from '../types';
import { hasNavigationAway, scopeToIndex } from '../navigation';
import type { ComponentSpec, EditableTableSpec, ViewDefinition } from '../types';

/*
 * editableTable: a grid whose cells hold components. Rows come from a bound array (cell binds use
 * [*], scoped to the row) or from fixed gridRows (cells keyed by column id).
 */

const itemsTable = (over: Partial<EditableTableSpec> = {}): EditableTableSpec => ({
  id: 'itemsTable',
  type: 'editableTable',
  label: 'Order Items',
  bind: '$.items',
  canAdd: true,
  canRemove: true,
  addLabel: 'Add Item',
  rowHeader: {
    header: 'Product',
    components: [{ id: 'itemName', type: 'textField', bind: '$.items[*].name' } as ComponentSpec],
  },
  gridColumns: [
    { id: 'price', header: 'Unit Price',
      components: [{ id: 'itemPrice', type: 'numericField', bind: '$.items[*].price' } as ComponentSpec] },
    { id: 'lineTotal', header: 'Line Total', align: 'right',
      components: [{ id: 'itemLineTotal', type: 'label', bind: '$.items[*].lineTotal', format: 'currency', currency: 'USD' } as ComponentSpec] },
  ],
  ...over,
});

const quarters: EditableTableSpec = {
  id: 'quarters',
  type: 'editableTable',
  rowHeader: { header: 'Quarter' },
  gridColumns: [
    { id: 'amount', header: 'Amount',
      components: [{ id: 'fallback', type: 'staticText', text: 'n/a' } as ComponentSpec] },
  ],
  gridRows: [
    { id: 'q1', header: 'Q1', cells: { amount: [{ id: 'q1Field', type: 'numericField', bind: '$.q1' } as ComponentSpec] } },
    { id: 'q2', header: 'Q2' },
  ],
};

const view = (...components: ComponentSpec[]): ViewDefinition => ({
  defaultView: 'main',
  views: [{ id: 'main', label: 'Order', components }],
});

function renderView(viewDef: ViewDefinition, state: Record<string, unknown> = {}) {
  const onMutate = vi.fn<(m: Record<string, unknown>) => Promise<void>>().mockResolvedValue(undefined);
  const result = render(<ViewRenderer modelId="m" viewDef={viewDef} state={state} meta={{}} onMutate={onMutate} />);
  return { ...result, onMutate };
}

const twoItems = {
  items: [
    { name: 'Apple', price: 1.5, qty: 4, lineTotal: 6 },
    { name: 'Bread', price: 2.75, qty: 2, lineTotal: 5.5 },
  ],
};

describe('editableTable — array rows', () => {
  it('is a known component type, so it is not painted as an unknown box', () => {
    expect(isKnownComponent(itemsTable())).toBe(true);
  });

  it('draws column headers and a row-header column as real table headers', () => {
    renderView(view(itemsTable()), twoItems);
    const table = screen.getByRole('table', { name: 'Order Items' });
    const cols = within(table).getAllByRole('columnheader').map(h => h.textContent);
    expect(cols).toEqual(['Product', 'Unit Price', 'Line Total', 'Actions']);
    expect(within(table).getAllByRole('rowheader')).toHaveLength(2);
  });

  it('puts one editable row per element, each bound to its own element', () => {
    renderView(view(itemsTable()), twoItems);
    expect(screen.getByLabelText('Product, row 1')).toHaveValue('Apple');
    expect(screen.getByLabelText('Product, row 2')).toHaveValue('Bread');
    expect(screen.getByLabelText('Unit Price, row 2')).toHaveValue(2.75);
    expect(screen.getByTestId('itemLineTotal-r1')).toHaveTextContent(/5.50/);   // currency layout is locale-dependent
  });

  it('gives every row its own DOM ids, so labels point at the right input', () => {
    const { container } = renderView(view(itemsTable()), twoItems);
    const ids = [...container.querySelectorAll('input')].map(i => i.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toContain('itemPrice-r0');
    expect(ids).toContain('itemPrice-r1');
  });

  it('writes an edited cell to that row\'s element path', () => {
    const { onMutate } = renderView(view(itemsTable()), twoItems);
    const price = screen.getByLabelText('Unit Price, row 2');
    fireEvent.change(price, { target: { value: '3.25' } });
    fireEvent.blur(price);
    expect(onMutate).toHaveBeenCalledWith({ '$.items[1].price': 3.25 });
  });

  it('adds an EMPTY element, so a fresh row cannot null an aggregate', () => {
    const { onMutate } = renderView(view(itemsTable()), twoItems);
    fireEvent.click(screen.getByRole('button', { name: '+ Add Item' }));
    expect(onMutate).toHaveBeenCalledWith({ '$.items': [...twoItems.items, {}] });
  });

  it('removes the clicked row', () => {
    const { onMutate } = renderView(view(itemsTable()), twoItems);
    fireEvent.click(screen.getByRole('button', { name: 'Remove row 1' }));
    expect(onMutate).toHaveBeenCalledWith({ '$.items': [twoItems.items[1]] });
  });

  it('hides add and remove when the expressions say so', async () => {
    renderView(view(itemsTable({ canAdd: false, canRemove: '$count(items) > 5' })), twoItems);
    expect(screen.queryByRole('button', { name: /Add Item/ })).toBeNull();
    // canRemove is a JSONata expression, resolved asynchronously.
    await waitFor(() => expect(screen.queryByRole('button', { name: /Remove/ })).toBeNull());
    expect(screen.queryByRole('columnheader', { name: 'Actions' })).toBeNull();
  });

  it('shows the empty text when the array is empty', () => {
    renderView(view(itemsTable({ emptyText: 'Nothing ordered yet.' })), { items: [] });
    expect(screen.getByText('Nothing ordered yet.')).toBeInTheDocument();
  });
});

describe('editableTable — fixed rows', () => {
  it('draws each row header and its own cell, falling back to the column\'s components', () => {
    renderView(view(quarters), { q1: 10 });
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('rowheader').map(h => h.textContent)).toEqual(['Q1', 'Q2']);
    expect(screen.getByLabelText('Amount, Q1')).toHaveValue(10);
    expect(within(screen.getByTestId('quarters-row-q2')).getByText('n/a')).toBeInTheDocument();
  });

  it('writes a fixed cell to its absolute path and offers no add or remove', () => {
    const { onMutate } = renderView(view(quarters), { q1: 10 });
    const q1 = screen.getByLabelText('Amount, Q1');
    fireEvent.change(q1, { target: { value: '12' } });
    fireEvent.blur(q1);
    expect(onMutate).toHaveBeenCalledWith({ '$.q1': 12 });
    expect(screen.queryByRole('button')).toBeNull();
  });
});

describe('editableTable — tree walks', () => {
  it('childComponentLists reaches the row header, columns and fixed cells', () => {
    const ids = (c: ComponentSpec) => childComponentLists(c).flat().map(k => k.id);
    expect(ids(itemsTable())).toEqual(['itemName', 'itemPrice', 'itemLineTotal']);
    expect(ids(quarters)).toEqual(['fallback', 'q1Field']);
  });

  it('a table inside a list is scoped like any other subtree, keeping its own row wildcard', () => {
    const nested = itemsTable({
      bind: '$.orders[*].items',
      gridColumns: [{ id: 'p', header: 'P', components: [{ id: 'x', type: 'numericField', bind: '$.orders[*].items[*].price' } as ComponentSpec] }],
    });
    const scoped = scopeToIndex([nested], 2)[0] as EditableTableSpec;
    expect(scoped.bind).toBe('$.orders[2].items');
    expect(scoped.gridColumns![0].components![0].bind).toBe('$.orders[2].items[*].price');
  });

  it('a navigate button inside a cell counts as a way off the view', () => {
    const t = itemsTable({
      gridColumns: [{ id: 'go', header: 'Go', components: [{ id: 'b', type: 'button', onClick: { navigate: 'other' } } as ComponentSpec] }],
    });
    expect(hasNavigationAway([t], 'main')).toBe(true);
  });
});
