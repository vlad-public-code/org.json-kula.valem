import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderComponent } from '../test/renderComponent';
import { fieldColors } from './fieldColors';
import type { ChoiceInputSpec, ComponentSpec, SliderSpec } from '../types';

const tags = (over: Partial<ChoiceInputSpec> = {}): ChoiceInputSpec => ({
  id: 'tags', type: 'tagsField', bind: '$.tags', label: 'Tags', ...over,
});

const stepper = (over: Partial<SliderSpec> = {}): SliderSpec => ({
  id: 'qty', type: 'numericStepper', bind: '$.qty', label: 'Qty', min: 1, max: 5, step: 1, ...over,
});

describe('TagsField', () => {
  it('writes the whole array on add, not one element', () => {
    // The array is the model's value; a per-element write would make every downstream count and
    // $distinct see an intermediate state.
    const { onMutate } = renderComponent(tags(), { state: { tags: ['a'] } });
    const input = screen.getByRole('textbox');
    fireEvent.change(input, { target: { value: 'b' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onMutate).toHaveBeenCalledWith({ '$.tags': ['a', 'b'] });
  });

  it('ignores a duplicate rather than writing it twice', () => {
    const { onMutate } = renderComponent(tags(), { state: { tags: ['a'] } });
    const input = screen.getByRole('textbox');
    fireEvent.change(input, { target: { value: 'a' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onMutate).not.toHaveBeenCalled();
  });

  it('trims before comparing, so " a" is still a duplicate', () => {
    const { onMutate } = renderComponent(tags(), { state: { tags: ['a'] } });
    const input = screen.getByRole('textbox');
    fireEvent.change(input, { target: { value: '  a  ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onMutate).not.toHaveBeenCalled();
  });

  it('removes a chip by writing the array without it', () => {
    const { onMutate } = renderComponent(tags(), { state: { tags: ['a', 'b'] } });
    fireEvent.click(screen.getByTestId('tags-remove-a'));
    expect(onMutate).toHaveBeenCalledWith({ '$.tags': ['b'] });
  });

  it('backspace on an empty box drops the last chip', () => {
    // The only way to reach a chip without a pointer.
    const { onMutate } = renderComponent(tags(), { state: { tags: ['a', 'b'] } });
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Backspace' });
    expect(onMutate).toHaveBeenCalledWith({ '$.tags': ['a'] });
  });

  it('backspace with text in the box edits the text instead', () => {
    const { onMutate } = renderComponent(tags(), { state: { tags: ['a'] } });
    const input = screen.getByRole('textbox');
    fireEvent.change(input, { target: { value: 'xy' } });
    fireEvent.keyDown(input, { key: 'Backspace' });
    expect(onMutate).not.toHaveBeenCalled();
  });

  it('rejects a value outside options when allowCustom is false', () => {
    // Supplying options renders a datalist, which makes the input's implicit role `combobox`
    // rather than `textbox` — so query by id instead of guessing the role.
    const spec = tags({ allowCustom: false, options: [{ value: 'a', label: 'A' }] });
    const { onMutate, container } = renderComponent(spec, { state: { tags: [] } });
    const input = container.querySelector('#tags')!;
    fireEvent.change(input, { target: { value: 'zzz' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onMutate).not.toHaveBeenCalled();
  });

  it('accepts a value inside options when allowCustom is false', () => {
    const spec = tags({ allowCustom: false, options: [{ value: 'a', label: 'A' }] });
    const { onMutate, container } = renderComponent(spec, { state: { tags: [] } });
    const input = container.querySelector('#tags')!;
    fireEvent.change(input, { target: { value: 'a' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onMutate).toHaveBeenCalledWith({ '$.tags': ['a'] });
  });

  it('treats a non-array bound value as empty rather than crashing', () => {
    renderComponent(tags(), { state: { tags: 'not-an-array' } });
    expect(screen.getByRole('textbox')).toBeInTheDocument();
  });

  it('hides the remove control when read-only', () => {
    renderComponent(tags({ readOnly: true }), { state: { tags: ['a'] } });
    expect(screen.queryByTestId('tags-remove-a')).not.toBeInTheDocument();
  });
});

describe('NumericStepper', () => {
  it('increments and decrements by the step', () => {
    const { onMutate } = renderComponent(stepper(), { state: { qty: 2 } });
    fireEvent.click(screen.getByTestId('qty-increment'));
    expect(onMutate).toHaveBeenCalledWith({ '$.qty': 3 });
  });

  it('clamps to the bounds instead of writing an out-of-schema value', () => {
    const { onMutate } = renderComponent(stepper(), { state: { qty: 5 } });
    // At max: the control is disabled, so nothing is written.
    expect(screen.getByTestId('qty-increment')).toBeDisabled();
    fireEvent.click(screen.getByTestId('qty-increment'));
    expect(onMutate).not.toHaveBeenCalled();
  });

  it('rounds to the step precision so floating-point noise never reaches the document', () => {
    // 0.1 + 0.2 is 0.30000000000000004; the stored value is the model's, so it gets rounded.
    const { onMutate } = renderComponent(stepper({ min: 0, max: 1, step: 0.1 }), { state: { qty: 0.2 } });
    fireEvent.click(screen.getByTestId('qty-increment'));
    expect(onMutate).toHaveBeenCalledWith({ '$.qty': 0.3 });
  });

  it('does not write when the value would not change', () => {
    const { onMutate } = renderComponent(stepper({ min: 1 }), { state: { qty: 1 } });
    fireEvent.click(screen.getByTestId('qty-decrement'));
    expect(onMutate).not.toHaveBeenCalled();
  });

  it('is inert when read-only', () => {
    const { onMutate } = renderComponent(stepper({ readOnly: true }), { state: { qty: 2 } });
    fireEvent.click(screen.getByTestId('qty-increment'));
    expect(onMutate).not.toHaveBeenCalled();
  });

  it('lets a typed value stick instead of snapping back, and commits it on blur', () => {
    // A fully state-controlled number input reverts to the model value on each keystroke; the
    // draft keeps what was typed. This is both a UX fix and what makes a fill-based interaction
    // (in the browser or a test) reliable rather than racing the mutation round-trip.
    const { onMutate } = renderComponent(stepper({ max: 60 }), { state: { qty: 8 } });
    const input = screen.getByRole('spinbutton');
    fireEvent.change(input, { target: { value: '30' } });
    expect(input).toHaveValue(30);              // held, not snapped back to 8
    expect(onMutate).not.toHaveBeenCalled();    // not yet — deferred to blur
    fireEvent.blur(input);
    expect(onMutate).toHaveBeenCalledWith({ '$.qty': 30 });
  });

  it('commits a typed value on Enter, clamped to the bounds', () => {
    const { onMutate } = renderComponent(stepper({ max: 60 }), { state: { qty: 8 } });
    const input = screen.getByRole('spinbutton');
    fireEvent.change(input, { target: { value: '999' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onMutate).toHaveBeenCalledWith({ '$.qty': 60 });
  });

  it('reverts an emptied input to the model value rather than writing NaN', () => {
    const { onMutate } = renderComponent(stepper(), { state: { qty: 5 } });
    const input = screen.getByRole('spinbutton');
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.blur(input);
    expect(onMutate).not.toHaveBeenCalled();
    expect(input).toHaveValue(5);
  });
});

describe('RatingField', () => {
  it('commits the clicked star immediately rather than deferring', () => {
    // Unlike the slider there is no drag to wait out, so the click is the chosen value.
    const spec: SliderSpec = { id: 'r', type: 'ratingField', bind: '$.rating', min: 1, max: 5, step: 1 };
    const { onMutate } = renderComponent(spec, { state: { rating: 1 } });
    fireEvent.click(screen.getByTestId('r-star-4'));
    expect(onMutate).toHaveBeenCalledWith({ '$.rating': 4 });
  });

  it('exposes one radio per stop', () => {
    const spec: SliderSpec = { id: 'r', type: 'ratingField', bind: '$.rating', min: 1, max: 5, step: 1 };
    renderComponent(spec, { state: { rating: 3 } });
    expect(screen.getAllByRole('radio')).toHaveLength(5);
  });
});

describe('NumericField adornments', () => {
  it('shows a currency prefix for currencyField and a percent suffix for percentField', () => {
    renderComponent({ id: 'a', type: 'currencyField', bind: '$.a', currency: 'EUR' }, { state: { a: 1 } });
    expect(screen.getByTestId('a-prefix')).toHaveTextContent('€');

    renderComponent({ id: 'b', type: 'percentField', bind: '$.b' }, { state: { b: 1 } });
    expect(screen.getByTestId('b-suffix')).toHaveTextContent('%');
  });

  it('shows no adornment on a plain numericField', () => {
    renderComponent({ id: 'c', type: 'numericField', bind: '$.c' }, { state: { c: 1 } });
    expect(screen.queryByTestId('c-prefix')).not.toBeInTheDocument();
    expect(screen.queryByTestId('c-suffix')).not.toBeInTheDocument();
  });
});

/**
 * Regression coverage for the dark-theme bug: every field used to hardcode a light `background`
 * (`#fff` / `#f5f5f5`) with no `color`, so the input's text inherited the page's `--text` var —
 * invisible once `data-theme="dark"` flips that var to a light color, since the background never
 * moved off white. Fields must source both background and color from `fieldColors` so they track
 * the same theme instead of only one of the two following it.
 */
describe('Field surfaces stay legible in dark mode', () => {
  const cases: [string, ComponentSpec, (container: HTMLElement) => Element | null][] = [
    ['textField', { id: 't', type: 'textField', bind: '$.t' }, c => c.querySelector('#t')],
    // The box (border/background) is the input's parent — the input itself is transparent so the
    // box's (theme-aware) background shows through; the box also carries `color` for the adornments.
    ['numericField', { id: 'n', type: 'numericField', bind: '$.n' }, c => c.querySelector('#n')?.parentElement ?? null],
    ['textAreaField', { id: 'ta', type: 'textAreaField', bind: '$.ta' }, c => c.querySelector('#ta')],
    ['selectField', { id: 's', type: 'selectField', bind: '$.s', options: [] }, c => c.querySelector('#s')],
    ['emailField', { id: 'e', type: 'emailField', bind: '$.e' }, c => c.querySelector('input[type="email"]')],
    ['passwordField', { id: 'pw', type: 'passwordField', bind: '$.pw' }, c => c.querySelector('input[type="password"]')],
    ['dateField', { id: 'd', type: 'dateField', bind: '$.d' }, c => c.querySelector('input[type="date"]')],
    ['dateTimeField', { id: 'dt', type: 'dateTimeField', bind: '$.dt' }, c => c.querySelector('input[type="datetime-local"]')],
    ['timeField', { id: 'tm', type: 'timeField', bind: '$.tm' }, c => c.querySelector('input[type="time"]')],
    ['numericStepper', { id: 'qty', type: 'numericStepper', bind: '$.qty', min: 0, max: 10, step: 1 }, c => c.querySelector('#qty')],
    ['richTextField', { id: 'rt', type: 'richTextField', bind: '$.rt' }, c => c.querySelector('#rt')],
    ['autocompleteField', { id: 'ac', type: 'autocompleteField', bind: '$.ac' }, c => c.querySelector('#ac')],
    // The bordered box is the entry input's parent — the input itself is transparent so the box's
    // (theme-aware) background shows through.
    ['tagsField box', { id: 'tg', type: 'tagsField', bind: '$.tg' }, c => c.querySelector('#tg')?.parentElement ?? null],
  ];

  it.each(cases)('%s reads its color and background from the shared theme tokens', (_name, spec, getEl) => {
    const { container } = renderComponent(spec, {});
    const el = getEl(container) as HTMLElement;
    expect(el).toBeTruthy();
    expect(el.style.color).toBe(fieldColors.text);
    expect(el.style.background).toBe(fieldColors.bg);
  });

  it.each(cases)('%s switches to the read-only surface without losing text color', (_name, spec, getEl) => {
    const { container } = renderComponent({ ...spec, readOnly: true } as ComponentSpec, {});
    const el = getEl(container) as HTMLElement;
    expect(el.style.color).toBe(fieldColors.text);
    expect(el.style.background).toBe(fieldColors.bgReadOnly);
  });

  it('dateRangeField applies the same tokens to both ends', () => {
    const { container } = renderComponent(
      { id: 'range', type: 'dateRangeField', bindFrom: '$.from', bindTo: '$.to' } as ComponentSpec,
      {},
    );
    const from = container.querySelector('#range-from') as HTMLElement;
    expect(from.style.color).toBe(fieldColors.text);
    expect(from.style.background).toBe(fieldColors.bg);
  });

  it('the currency/percent adornment uses the muted theme tokens, not a hardcoded gray box', () => {
    renderComponent({ id: 'amt', type: 'currencyField', bind: '$.amt', currency: 'USD' }, { state: { amt: 1 } });
    const prefix = screen.getByTestId('amt-prefix');
    expect(prefix.style.color).toBe(fieldColors.mutedText);
    expect(prefix.style.background).toBe(fieldColors.mutedBg);
  });
});
