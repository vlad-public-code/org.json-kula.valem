/**
 * Display formatting for the components that carry a `format` — currencyField, percentField,
 * statTile, keyValueList rows, progressBar.
 *
 * Formatting is display-only and never round-trips: the value written back to the model is always
 * the plain number the user typed. That is why `percent` appends a sign rather than multiplying
 * by 100 — a model whose derivation already computes `rate * 100` would otherwise show 750% for
 * 7.5, and a renderer silently rescaling a stored number is a bug no amount of spec-reading finds.
 */
export function formatValue(value: unknown, format?: string, currency?: string): string {
  if (value == null || value === '') return '';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (typeof value === 'object') return JSON.stringify(value);

  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) return String(value);

  switch (format) {
    case 'currency':
      try {
        return new Intl.NumberFormat(undefined, {
          style: 'currency',
          currency: currency ?? 'USD',
        }).format(n);
      } catch {
        // An invalid ISO-4217 code throws rather than degrading; show the number and the code.
        return `${formatNumber(n)} ${currency ?? ''}`.trim();
      }
    case 'percent':
      return `${formatNumber(n)}%`;
    case 'integer':
      return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(n);
    case 'number':
      return formatNumber(n);
    default:
      return String(value);
  }
}

function formatNumber(n: number): string {
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(n);
}

/** The symbol a currency input shows as its prefix, or the code when the locale has no symbol. */
export function currencySymbol(currency?: string): string {
  const code = currency ?? 'USD';
  try {
    const parts = new Intl.NumberFormat(undefined, { style: 'currency', currency: code })
      .formatToParts(0);
    return parts.find(p => p.type === 'currency')?.value ?? code;
  } catch {
    return code;
  }
}
