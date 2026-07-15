import { useState, useEffect } from 'react';

export interface Country {
  cca2: string;
  name: { common: string };
  idd?: { root?: string; suffixes?: string[] };
}

const cache: { data: Country[] | null } = { data: null };

export function useCountries(): Country[] {
  const [countries, setCountries] = useState<Country[]>(cache.data ?? []);

  useEffect(() => {
    if (cache.data) return;
    fetch('https://restcountries.com/v3.1/all?fields=name,cca2,idd')
      .then(r => r.json())
      .then((data: Country[]) => {
        const sorted = [...data].sort((a, b) =>
          a.name.common.localeCompare(b.name.common),
        );
        cache.data = sorted;
        setCountries(sorted);
      })
      .catch(() => {});
  }, []);

  return countries;
}

export function dialCode(country: Country | undefined): string {
  if (!country) return '';

  const { root = '', suffixes = [] } = country.idd ?? {};
  if (!suffixes.length) return root;
  if (suffixes.length === 1) return root + suffixes[0];
  return root;
}
