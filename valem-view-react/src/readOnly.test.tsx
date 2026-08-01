import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderComponent } from './test/renderComponent';
import type { BasicInputSpec, ButtonSpec } from './types';

/*
 * View-level read-only (a read-only embed): the ViewContext `readOnly` flag disables every input and
 * turns onMutate into a no-op, regardless of the component's own readOnly resolution. This is the
 * render-core half of the sandbox's read-only embed mode.
 */

const numeric = (over: Partial<BasicInputSpec> = {}): BasicInputSpec => ({
  id: 'amt', type: 'numericField', bind: '$.amount', label: 'Amount', ...over,
});

describe('view-level readOnly', () => {
  it('disables an input and swallows its edit when the view is read-only', () => {
    const { onMutate } = renderComponent(numeric(), { state: { amount: 5 }, readOnly: true });
    const input = screen.getByRole('spinbutton');
    expect(input).toBeDisabled();
    fireEvent.change(input, { target: { value: '9' } });
    expect(onMutate).not.toHaveBeenCalled();
  });

  it('leaves the input editable and mutating when the view is not read-only', () => {
    const { onMutate } = renderComponent(numeric(), { state: { amount: 5 } });
    const input = screen.getByRole('spinbutton');
    expect(input).not.toBeDisabled();
    fireEvent.change(input, { target: { value: '9' } });
    fireEvent.blur(input);   // NumericField commits on blur
    expect(onMutate).toHaveBeenCalledWith({ '$.amount': 9 });
  });

  it('overrides a component that explicitly sets readOnly:false', () => {
    // An embed author locking the view must win over a per-field enable expression.
    const { onMutate } = renderComponent(numeric({ readOnly: 'false' }), { state: { amount: 5 }, readOnly: true });
    const input = screen.getByRole('spinbutton');
    expect(input).toBeDisabled();
    fireEvent.change(input, { target: { value: '9' } });
    expect(onMutate).not.toHaveBeenCalled();
  });

  it('disables an action button and never mutates in a read-only view', () => {
    const button: ButtonSpec = {
      id: 'go', type: 'button', label: 'Apply', onClick: { mutations: '{ "$.amount": 1 }' },
    } as ButtonSpec;
    const { onMutate } = renderComponent(button, { state: { amount: 5 }, readOnly: true });
    const btn = screen.getByRole('button', { name: 'Apply' });
    expect(btn).toBeDisabled();
    fireEvent.click(btn);
    expect(onMutate).not.toHaveBeenCalled();
  });
});
