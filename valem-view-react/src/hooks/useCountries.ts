import { COUNTRY_DIAL_CODES } from '../data/countryDialCodes';

export interface Country {
  cca2: string;
  name: { common: string };
  idd?: { root?: string; suffixes?: string[] };
}

/**
 * Every ISO 3166-1 country, named in English and sorted by name, with its calling code.
 *
 * Built from bundled data (data/countryDialCodes.ts) and the browser's own `Intl.DisplayNames`:
 * no network. It used to fetch restcountries.com, which the sandbox and embed CSP block
 * (connect-src 'self') and which has since retired that API, so every countrySelector and
 * phoneNumberField rendered empty. The shape is unchanged, so callers did not change.
 */
let cached: Country[] | null = null;

function build(): Country[] {
  let names: Intl.DisplayNames | null = null;
  try { names = new Intl.DisplayNames(['en'], { type: 'region' }); } catch { /* old runtime: show codes */ }
  return COUNTRY_DIAL_CODES
    .map(([code, dial]) => ({ cca2: code, name: { common: names?.of(code) ?? code }, idd: { root: dial, suffixes: [] } }))
    .sort((a, b) => a.name.common.localeCompare(b.name.common));
}

export function useCountries(): Country[] {
  return (cached ??= build());
}

export function dialCode(country: Country | undefined): string {
  if (!country) return '';

  const { root = '', suffixes = [] } = country.idd ?? {};
  if (!suffixes.length) return root;
  if (suffixes.length === 1) return root + suffixes[0];
  return root;
}
