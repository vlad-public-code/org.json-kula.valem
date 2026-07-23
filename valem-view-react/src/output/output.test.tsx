import { describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderComponent } from '../test/renderComponent';
import type { KeyValueListSpec, StaticTextSpec, ValidationSummarySpec } from '../types';

describe('KeyValueList', () => {
  const spec = (over: Partial<KeyValueListSpec> = {}): KeyValueListSpec => ({
    id: 'kv', type: 'summaryList', label: 'Summary', items: [], ...over,
  });

  it('formats a money row in the currency the row names', () => {
    renderComponent(
      spec({ items: [{ label: 'Total', bind: '$.total', format: 'currency', currency: 'EUR' }] }),
      { state: { total: 1440 } },
    );
    expect(screen.getByTestId('kv-row-0')).toHaveTextContent('€');
    expect(screen.getByTestId('kv-row-0')).not.toHaveTextContent('$');
  });

  it('lets rows carry different currencies', () => {
    renderComponent(
      spec({ items: [
        { label: 'Quoted', bind: '$.a', format: 'currency', currency: 'EUR' },
        { label: 'Converted', bind: '$.b', format: 'currency', currency: 'GBP' },
      ] }),
      { state: { a: 10, b: 9 } },
    );
    expect(screen.getByTestId('kv-row-0')).toHaveTextContent('€');
    expect(screen.getByTestId('kv-row-1')).toHaveTextContent('£');
  });

  it('shows a dash for a missing path rather than "undefined"', () => {
    renderComponent(spec({ items: [{ label: 'Nope', bind: '$.absent' }] }), { state: {} });
    expect(screen.getByTestId('kv-row-0')).toHaveTextContent('—');
  });

  it('prefers bind over text so a row never shows two things', async () => {
    renderComponent(
      spec({ items: [{ label: 'Name', bind: '$.name', text: '$uppercase(name)' }] }),
      { state: { name: 'Alice' } },
    );
    await waitFor(() => expect(screen.getByTestId('kv-row-0')).toHaveTextContent('Alice'));
    expect(screen.getByTestId('kv-row-0')).not.toHaveTextContent('ALICE');
  });

  it('falls back to the evaluated text when a row has no bind', async () => {
    renderComponent(spec({ items: [{ label: 'Shout', text: '$uppercase(name)' }] }), { state: { name: 'Alice' } });
    await waitFor(() => expect(screen.getByTestId('kv-row-0')).toHaveTextContent('ALICE'));
  });
});

describe('StaticText format modes', () => {
  const spec = (over: Partial<StaticTextSpec>): StaticTextSpec => ({
    id: 'st', type: 'staticText', ...over,
  });

  it('defaults to markdown, so a stored tag is escaped rather than injected', () => {
    // The default must be safe: staticText is often bound to model state, and rendering that as
    // raw HTML is stored XSS. An unset format escapes.
    renderComponent(spec({ text: '<b>hi</b>' }));
    expect(screen.getByTestId('st').querySelector('b')).toBeNull();
    expect(screen.getByTestId('st')).toHaveTextContent('<b>hi</b>');
  });

  it('renders html only when explicitly opted in', () => {
    renderComponent(spec({ text: '<b>hi</b>', format: 'html' }));
    expect(screen.getByTestId('st').querySelector('b')).not.toBeNull();
  });

  it('escapes markup in text mode', () => {
    renderComponent(spec({ text: '<b>hi</b>', format: 'text' }));
    expect(screen.getByTestId('st').querySelector('b')).toBeNull();
    expect(screen.getByTestId('st')).toHaveTextContent('<b>hi</b>');
  });

  it('renders markdown without letting injected HTML through', () => {
    // The read half of richTextField: it must render **bold** while neutralising a stored tag.
    renderComponent(spec({ text: '**bold** <script>x</script>', format: 'markdown' }));
    const el = screen.getByTestId('st');
    expect(el.querySelector('strong')).not.toBeNull();
    expect(el.querySelector('script')).toBeNull();
  });
});

describe('ValidationSummary', () => {
  const spec = (over: Partial<ValidationSummarySpec> = {}): ValidationSummarySpec => ({
    id: 'vs', type: 'validationSummary', label: 'Problems', ...over,
  });

  it('shows constraint violations that map to no path at all', () => {
    // These are the ones the component exists for: a flag constraint spanning three fields sits
    // beside none of them, and before this they reached the renderer nowhere.
    renderComponent(spec(), { formErrors: ['Estimate exceeds the approved budget'] });
    expect(screen.getByTestId('vs')).toHaveTextContent('Estimate exceeds the approved budget');
  });

  it('shows path-scoped violations too', () => {
    renderComponent(spec(), { fieldErrors: { '$.a': 'A is wrong' } });
    expect(screen.getByTestId('vs')).toHaveTextContent('A is wrong');
  });

  it('narrows to a section with pathPrefix', () => {
    renderComponent(spec({ pathPrefix: '$.order' }), {
      fieldErrors: { '$.order.total': 'in scope', '$.other': 'out of scope' },
    });
    const el = screen.getByTestId('vs');
    expect(el).toHaveTextContent('in scope');
    expect(el).not.toHaveTextContent('out of scope');
  });

  it('does not treat $.orderTotal as inside the $.order section', () => {
    renderComponent(spec({ pathPrefix: '$.order' }), { fieldErrors: { '$.orderTotal': 'sibling' } });
    expect(screen.queryByTestId('vs')).not.toBeInTheDocument();
  });

  it('excludes path-less violations when a pathPrefix asks for one section', () => {
    renderComponent(spec({ pathPrefix: '$.order' }), { formErrors: ['global problem'] });
    expect(screen.queryByTestId('vs')).not.toBeInTheDocument();
  });

  it('renders nothing when clean and no emptyText is given', () => {
    renderComponent(spec());
    expect(screen.queryByTestId('vs')).not.toBeInTheDocument();
  });

  it('renders emptyText when clean and one is given', () => {
    renderComponent(spec({ emptyText: 'All good' }));
    expect(screen.getByTestId('vs')).toHaveTextContent('All good');
  });

  it('caps the list at maxItems', () => {
    renderComponent(spec({ maxItems: 1 }), { fieldErrors: { '$.a': 'one', '$.b': 'two' } });
    expect(screen.getAllByTestId('vs-item')).toHaveLength(1);
  });
});

describe('JsonViewer', () => {
  it('shows the whole state when bound to $', () => {
    renderComponent({ id: 'jv', type: 'jsonViewer', bind: '$' }, { state: { a: 1, b: { c: 2 } } });
    expect(screen.getByTestId('jv').querySelector('pre')).toHaveTextContent('"a": 1');
  });

  it('truncates below maxDepth instead of dropping the key', () => {
    // A dropped key reads as "this field is empty"; an ellipsis says "there is more here".
    renderComponent({ id: 'jv', type: 'jsonViewer', bind: '$', maxDepth: 1 }, { state: { a: { b: { c: 1 } } } });
    const pre = screen.getByTestId('jv').querySelector('pre')!;
    expect(pre.textContent).toContain('…');
    expect(pre.textContent).not.toContain('"c"');
  });

  it('starts folded when collapsed is set, and opens on click', () => {
    renderComponent({ id: 'jv', type: 'jsonViewer', bind: '$', collapsed: true }, { state: { a: 1 } });
    expect(screen.getByTestId('jv').querySelector('pre')).toBeNull();
    fireEvent.click(screen.getByTestId('jv-toggle'));
    expect(screen.getByTestId('jv').querySelector('pre')).not.toBeNull();
  });
});

describe('SeparatorLine and spacer', () => {
  it('renders a rule for separatorLine and a bare gap for spacer', () => {
    renderComponent({ id: 'sep', type: 'separatorLine' });
    expect(screen.getByTestId('sep').tagName).toBe('HR');

    renderComponent({ id: 'sp', type: 'spacer', size: 32 });
    expect(screen.getByTestId('sp').tagName).toBe('DIV');
    expect(screen.getByTestId('sp')).toHaveStyle({ height: '32px' });
  });
});

describe('LinkComponent', () => {
  it('adds noopener noreferrer to an external link', () => {
    renderComponent({ id: 'l', type: 'link', href: 'https://example.test', target: '_blank', label: 'Docs' });
    const a = screen.getByTestId('l');
    expect(a).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('renders nothing when it has no destination', () => {
    renderComponent({ id: 'l', type: 'link', label: 'Nowhere' });
    expect(screen.queryByTestId('l')).not.toBeInTheDocument();
  });
});

describe('ImageComponent', () => {
  it('resolves a bound BlobRef to its /blobs URL', () => {
    // This is what makes an upload and its preview two components over one path.
    renderComponent({ id: 'img', type: 'image', bind: '$.photo', alt: 'Venue' },
      { state: { photo: { $blobId: 'abc123', $mediaType: 'image/png' } } });
    expect(screen.getByAltText('Venue')).toHaveAttribute('src', '/blobs/abc123');
  });

  it('renders nothing when there is no source', () => {
    renderComponent({ id: 'img', type: 'image', alt: 'none' });
    expect(screen.queryByTestId('img')).not.toBeInTheDocument();
  });
});
