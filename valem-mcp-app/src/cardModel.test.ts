import { describe, expect, it } from 'vitest';
import { mergeInputs, payloadOf, setAt, splitIssues, summaryText, toCanonical, valueAt } from './cardModel';

describe('cardModel', () => {
  it('reads the payload only from a result that carries one', () => {
    const card = { spec: { id: 'q' }, specHash: 'sha256:1', inputs: {}, state: {}, meta: {}, flags: [] };
    expect(payloadOf({ _meta: { 'valem/card': card } })).toBe(card);
    expect(payloadOf({ _meta: {} })).toBeNull();
    expect(payloadOf(undefined)).toBeNull();
  });

  it('turns renderer paths into canonical addresses', () => {
    expect(toCanonical('$.items.0.qty')).toBe('$.items[0].qty');
    expect(toCanonical('rooms')).toBe('$.rooms');
    expect(toCanonical('$.a[2].b')).toBe('$.a[2].b');
  });

  it('lets a later write replace earlier writes at or under its path, and puts it last', () => {
    const prev = { '$.items[0].qty': 3, '$.rooms': 2, '$.itemsCount': 1 };
    const next = mergeInputs(prev, { '$.items': [] });
    expect(next).toEqual({ '$.rooms': 2, '$.itemsCount': 1, '$.items': [] });
    expect(Object.keys(mergeInputs({ '$.a': 1, '$.b': 2 }, { '$.a': 5 }))).toEqual(['$.b', '$.a']);
  });

  it('splits issues into field errors and form errors', () => {
    const r = splitIssues([
      { id: 'min', message: 'Too low', paths: ['$.price'] },
      { id: 'cap', message: '', paths: [] },
    ]);
    expect(r).toEqual({ fieldErrors: { '$.price': 'Too low' }, formErrors: ['cap'] });
  });

  it('reads and writes nested paths without touching the original', () => {
    const s = { order: { items: [{ qty: 1 }] } };
    const t = setAt(s, '$.order.items[0].qty', 4);
    expect(valueAt(t, '$.order.items[0].qty')).toBe(4);
    expect(valueAt(s, '$.order.items[0].qty')).toBe(1);
    expect(setAt({}, '$.list[1]', 'x')).toEqual({ list: [undefined, 'x'] });
  });

  it('summarises inputs, results and flags for the assistant', () => {
    const text = summaryText(
      { id: 'quote', version: '1.0.0', derivations: [{ path: '$.price' }, { path: '$.items[*].total' }] },
      { '$.rooms': 4 },
      { rooms: 4, price: 120 },
      [{ id: 'min', message: 'Below minimum', paths: [] }],
    );
    expect(text).toContain('quote 1.0.0');
    expect(text).toContain('$.rooms = 4');
    expect(text).toContain('- $.price = 120');
    expect(text).not.toContain('[*]');
    expect(text).toContain('Flagged rules: Below minimum.');
  });
});
