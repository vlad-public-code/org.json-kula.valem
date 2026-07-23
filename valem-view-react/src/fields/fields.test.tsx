import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderComponent } from '../test/renderComponent';
import type { ChoiceInputSpec, SliderSpec } from '../types';

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
