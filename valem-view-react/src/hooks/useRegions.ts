import { useState, useEffect } from 'react';

export interface Region {
  id: number;
  name: string;
  state_code: string;
}

interface StateEntry {
  iso2: string;
  states: Region[];
}

const cache = new Map<string, Region[]>();
let allStates: StateEntry[] | null = null;
let pendingFetch: Promise<StateEntry[]> | null = null;

async function fetchAll(): Promise<StateEntry[]> {
  if (allStates) return allStates;
  if (!pendingFetch) {
    pendingFetch = fetch(
      'https://raw.githubusercontent.com/dr5hn/countries-states-cities-database/master/states.json',
    )
      .then(r => r.json())
      .then((data: StateEntry[]) => {
        allStates = data;
        return data;
      });
  }
  return pendingFetch;
}

export function useRegions(countryCode: string | undefined): Region[] {
  const [regions, setRegions] = useState<Region[]>(
    countryCode ? (cache.get(countryCode) ?? []) : [],
  );

  useEffect(() => {
    if (!countryCode) {
      setRegions([]);
      return;
    }
    const cached = cache.get(countryCode);
    if (cached) {
      setRegions(cached);
      return;
    }
    fetchAll()
      .then(data => {
        const entry = data.find(e => e.iso2 === countryCode);
        const result = entry?.states ?? [];
        cache.set(countryCode, result);
        setRegions(result);
      })
      .catch(() => {});
  }, [countryCode]);

  return regions;
}
