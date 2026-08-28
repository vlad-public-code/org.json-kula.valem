import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { render } from '@testing-library/react';
import { ViewRenderer } from './ViewRenderer';
import { renderComponent } from './test/renderComponent';
import { hasNavigationAway, scopeToIndex } from './navigation';
import type { ComponentSpec, SectionListSpec, ViewDefinition } from './types';

/*
 * List-of-items navigation.
 *
 * A `sectionList` can edit its elements in a separate view (`itemView`). That mode used to strand
 * the user: the navigation carried no index, so the item view's `[*]` binds resolved to nothing and
 * its edits went to a literal `$.debts[*].name`; and nothing led back to the list. These cover both
 * halves — the scope that arrives with the navigation, and the Back that appears when the author
 * has provided no way out.
 */

const listView = (over: Partial<SectionListSpec> = {}): SectionListSpec => ({
  id: 'debtsList',
  type: 'sectionList',
  label: 'Your debts',
  bind: '$.debts',
  itemView: 'debtItem',
  ...over,
});

const itemFields: ComponentSpec[] = [
  { id: 'debtName', type: 'textField', label: 'Name', bind: '$.debts[*].name' } as ComponentSpec,
  { id: 'debtBalance', type: 'numericField', label: 'Balance', bind: '$.debts[*].balance' } as ComponentSpec,
];

const twoViews = (itemComponents: ComponentSpec[] = itemFields): ViewDefinition => ({
  defaultView: 'main',
  views: [
    { id: 'main', label: 'Debt payoff', components: [listView()] },
    { id: 'debtItem', label: 'Debt', components: itemComponents },
  ],
});

function renderViews(viewDef: ViewDefinition, state: Record<string, unknown> = {}) {
  const onMutate = vi.fn<(m: Record<string, unknown>) => Promise<void>>().mockResolvedValue(undefined);
  const result = render(
    <ViewRenderer modelId="m" viewDef={viewDef} state={state} meta={{}} onMutate={onMutate} />,
  );
  return { ...result, onMutate };
}

describe('scopeToIndex', () => {
  it('binds a wildcard path to one concrete element, in bracket notation', () => {
    const [name] = scopeToIndex(itemFields, 2);
    // Bracket, not `debts.2.name`: canonical JsonPath is what ModelSpecValidator accepts and what
    // CompiledModel's schema cache normalises back to [*].
    expect(name.bind).toBe('$.debts[2].name');
  });

  it('scopes a nested container, a dateRange and a summary row', () => {
    const components: ComponentSpec[] = [
      {
        id: 'g', type: 'group', components: [
          { id: 'period', type: 'dateRangeField', bindFrom: '$.debts[*].from', bindTo: '$.debts[*].to' },
          { id: 'sum', type: 'summaryList', items: [{ label: 'Owed', bind: '$.debts[*].balance' }] },
        ],
      } as ComponentSpec,
    ];
    const group = scopeToIndex(components, 1)[0] as unknown as { components: Array<Record<string, unknown>> };
    expect(group.components[0].bindFrom).toBe('$.debts[1].from');
    expect(group.components[0].bindTo).toBe('$.debts[1].to');
    expect((group.components[1].items as Array<{ bind: string }>)[0].bind).toBe('$.debts[1].balance');
  });

  it('leaves an inner wildcard for a nested list to scope in turn', () => {
    const nested: ComponentSpec[] = [
      { id: 'p', type: 'numericField', bind: '$.debts[*].payments[*].amount' } as ComponentSpec,
    ];
    expect(scopeToIndex(nested, 3)[0].bind).toBe('$.debts[3].payments[*].amount');
  });

  it('leaves a component with no path untouched', () => {
    const plain: ComponentSpec[] = [{ id: 't', type: 'staticText', text: 'hello' } as ComponentSpec];
    expect(scopeToIndex(plain, 0)[0]).toMatchObject({ id: 't', text: 'hello' });
  });
});

describe('hasNavigationAway', () => {
  it('sees a button that navigates elsewhere', () => {
    const components = [{ id: 'b', type: 'button', onClick: { navigate: 'main' } }] as ComponentSpec[];
    expect(hasNavigationAway(components, 'debtItem')).toBe(true);
  });

  it('sees a stepper nested inside a container', () => {
    const components = [{
      id: 'card', type: 'card',
      components: [{ id: 's', type: 'stepper', menuItems: [{ label: 'Plan', targetView: 'main' }] }],
    }] as ComponentSpec[];
    expect(hasNavigationAway(components, 'debtItem')).toBe(true);
  });

  it('does not count a control that only re-targets the current view', () => {
    const components = [{ id: 'b', type: 'button', onClick: { navigate: 'debtItem' } }] as ComponentSpec[];
    expect(hasNavigationAway(components, 'debtItem')).toBe(false);
  });

  it('does not count a sectionList itemView — that leads further in, not out', () => {
    expect(hasNavigationAway([listView()], 'main')).toBe(false);
  });
});

describe('sectionList → itemView navigation', () => {
  it('carries the row index when editing an element', () => {
    const { onNavigate } = renderComponent(listView(), {
      state: { debts: [{ name: 'Card' }, { name: 'Loan' }] },
    });
    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]);
    expect(onNavigate).toHaveBeenCalledWith('debtItem', 1);
  });

  it('adds an EMPTY element and opens the item view at its index', () => {
    // No null-valued keys: a null propagates out of every JSONata aggregate over the array, so a
    // seeded row used to turn $sum(debts.balance) null until every field had been filled in.
    const { onMutate, onNavigate } = renderComponent(listView(), {
      state: { debts: [{ name: 'Card' }] },
    });
    fireEvent.click(screen.getByRole('button', { name: '+ Add item' }));
    expect(onMutate).toHaveBeenCalledWith({ '$.debts': [{ name: 'Card' }, {}] });
    expect(onNavigate).toHaveBeenCalledWith('debtItem', 1);
  });

  it('adds an EMPTY element inline too, and expands its editor in place', () => {
    const inline = listView({ itemView: undefined, components: itemFields });
    const { onMutate, onNavigate } = renderComponent(inline, { state: { debts: [{ name: 'Card' }] } });
    fireEvent.click(screen.getByRole('button', { name: '+ Add item' }));
    expect(onMutate).toHaveBeenCalledWith({ '$.debts': [{ name: 'Card' }, {}] });
    expect(onNavigate).not.toHaveBeenCalled();
  });
});

describe('item view rendering', () => {
  it('edits the element the user opened, not the first one', () => {
    const state = { debts: [{ name: 'Card', balance: 1 }, { name: 'Loan', balance: 2 }] };
    const { onMutate } = renderViews(twoViews(), state);

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]);

    const name = screen.getByLabelText('Name');
    expect(name).toHaveValue('Loan');       // the second element, because the index travelled
    fireEvent.change(name, { target: { value: 'Car loan' } });
    fireEvent.blur(name);
    expect(onMutate).toHaveBeenCalledWith({ '$.debts[1].name': 'Car loan' });
  });
});

describe('automatic Back', () => {
  it('appears on a navigated-to view that authors no way out, and returns to the list', () => {
    const state = { debts: [{ name: 'Card' }] };
    renderViews(twoViews(), state);

    expect(screen.queryByTestId('view-back')).toBeNull();   // the list itself is home

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    const back = screen.getByTestId('view-back');
    expect(back).toHaveTextContent('Back to Debt payoff');

    fireEvent.click(back);
    expect(screen.queryByTestId('view-back')).toBeNull();
    expect(screen.getByText('Your debts')).toBeInTheDocument();
  });

  it('stands aside when the author already supplied navigation', () => {
    const authored = twoViews([
      ...itemFields,
      { id: 'done', type: 'button', label: 'Done', onClick: { navigate: 'main' } } as ComponentSpec,
    ]);
    renderViews(authored, { debts: [{ name: 'Card' }] });

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    expect(screen.queryByTestId('view-back')).toBeNull();
    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument();
  });

  it('never shows on the default view, however the user arrived at it', () => {
    // main → debtItem → main (via the item view's own button): main is home, so no Back on it.
    const authored = twoViews([
      ...itemFields,
      { id: 'done', type: 'button', label: 'Done', onClick: { navigate: 'main' } } as ComponentSpec,
    ]);
    renderViews(authored, { debts: [{ name: 'Card' }] });

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByTestId('view-back')).toBeNull();
  });

  it('restores the item scope of the view it returns to', () => {
    // A list nested one level deeper: main → debtItem[1] → note, then back to debtItem STILL on [1].
    const viewDef: ViewDefinition = {
      defaultView: 'main',
      views: [
        { id: 'main', label: 'Debts', components: [listView()] },
        {
          id: 'debtItem', label: 'Debt',
          components: [
            ...itemFields,
            { id: 'toNote', type: 'button', label: 'Note', onClick: { navigate: 'note' } } as ComponentSpec,
          ],
        },
        { id: 'note', label: 'Note', components: [
          { id: 'n', type: 'textField', label: 'Note', bind: '$.note' } as ComponentSpec,
        ] },
      ],
    };
    const state = { debts: [{ name: 'Card' }, { name: 'Loan' }], note: '' };
    renderViews(viewDef, state);

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]);
    expect(screen.getByLabelText('Name')).toHaveValue('Loan');

    fireEvent.click(screen.getByRole('button', { name: 'Note' }));
    fireEvent.click(screen.getByTestId('view-back'));

    expect(screen.getByLabelText('Name')).toHaveValue('Loan');   // scope survived the round trip
  });

  it('treats the first view as home when no defaultView is declared', () => {
    // ViewRenderer opens on views[0] in that case, so views[0] is home and a Back on it would lead
    // somewhere less expected than staying. A sectionList's itemView is the way in, and does not
    // count as the list view's own way out — without this the list would grow a Back to the element.
    const undeclared: ViewDefinition = { views: twoViews().views };
    renderViews(undeclared, { debts: [{ name: 'Card' }] });

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    fireEvent.click(screen.getByTestId('view-back'));

    expect(screen.getByText('Your debts')).toBeInTheDocument();
    expect(screen.queryByTestId('view-back')).toBeNull();
  });

  it('does not appear on a view opened directly — an embed deep-linked into it has no trail', () => {
    render(
      <ViewRenderer
        modelId="m" viewDef={twoViews()} state={{ debts: [{ name: 'Card' }] }} meta={{}}
        onMutate={vi.fn().mockResolvedValue(undefined)} activeViewId="debtItem"
      />,
    );
    expect(screen.queryByTestId('view-back')).toBeNull();
  });

  it('drives a host that owns activeViewId, in both directions', () => {
    // ViewPanel is a controlled host: it holds the active view and updates it from onNavigate.
    // Back has to travel the same route, or it moves the renderer and leaves the host behind.
    function Host() {
      const [active, setActive] = useState('main');
      return (
        <ViewRenderer
          modelId="m" viewDef={twoViews()} state={{ debts: [{ name: 'Card' }] }} meta={{}}
          onMutate={vi.fn().mockResolvedValue(undefined)}
          activeViewId={active} onNavigate={setActive}
        />
      );
    }
    render(<Host />);

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    expect(screen.getByLabelText('Name')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('view-back'));
    expect(screen.getByText('Your debts')).toBeInTheDocument();
    expect(screen.queryByLabelText('Name')).toBeNull();
  });
});
