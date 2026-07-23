import { describe, expect, it } from 'vitest';
import { currencySymbol, formatValue } from './format';

describe('formatValue', () => {
  it('renders a currency in the named code, not a default one', () => {
    // The bug this pins: a hardcoded USD meant a euro total displayed as dollars, silently and
    // only in the browser.
    expect(formatValue(1250.5, 'currency', 'EUR')).toMatch(/1[,.\s]250[.,]50/);
    expect(formatValue(1250.5, 'currency', 'EUR')).toContain('€');
    expect(formatValue(1250.5, 'currency', 'JPY')).not.toContain('€');
  });

  it('does not rescale a percent', () => {
    // Intl's `style: 'percent'` multiplies by 100. A model whose derivation already computes
    // `rate * 100` would then show 750% for 7.5 — so percent appends a sign instead.
    expect(formatValue(7.5, 'percent')).toBe('7.5%');
    expect(formatValue(100, 'percent')).toBe('100%');
  });

  it('falls back to the renderer default when a currency format names no code', () => {
    expect(formatValue(10, 'currency')).toMatch(/10/);
  });

  it('survives an invalid ISO code instead of throwing', () => {
    // Intl throws on a bad code; a spec typo must not take the whole view down.
    const out = formatValue(42, 'currency', 'NOTACODE');
    expect(out).toContain('42');
  });

  it('leaves a non-numeric value alone', () => {
    expect(formatValue('n/a', 'currency', 'EUR')).toBe('n/a');
    expect(formatValue('hello', 'number')).toBe('hello');
  });

  it('renders empty for null and undefined rather than "null"', () => {
    expect(formatValue(null, 'currency')).toBe('');
    expect(formatValue(undefined, 'number')).toBe('');
    expect(formatValue('', 'number')).toBe('');
  });

  it('renders booleans and objects readably', () => {
    expect(formatValue(true)).toBe('Yes');
    expect(formatValue(false)).toBe('No');
    expect(formatValue({ a: 1 })).toBe('{"a":1}');
  });

  it('rounds integers and keeps at most two decimals otherwise', () => {
    expect(formatValue(1234.567, 'integer')).toBe('1,235');
    expect(formatValue(1234.567, 'number')).toBe('1,234.57');
  });

  it('passes an unformatted value straight through', () => {
    expect(formatValue(42)).toBe('42');
    expect(formatValue(42, 'unrecognised')).toBe('42');
  });
});

describe('currencySymbol', () => {
  it('resolves a symbol for a known code and degrades to the code otherwise', () => {
    expect(currencySymbol('EUR')).toBe('€');
    expect(currencySymbol('NOTACODE')).toBe('NOTACODE');
    expect(currencySymbol()).toBeTruthy();
  });
});
