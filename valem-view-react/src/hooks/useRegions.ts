import { useState, useEffect } from 'react';

export interface Region {
  id: number;
  name: string;
  state_code: string;
}

/**
 * The top-level subdivisions of a country, from bundled Unicode CLDR data (data/regions.ts).
 *
 * The table is imported lazily, so it is a separate same-origin chunk fetched only by views that
 * render a countryRegionSelector. It used to come from a GitHub-hosted JSON file, which the CSP
 * blocked and which has since moved (404), leaving every region selector empty.
 *
 * `loaded` distinguishes "still loading" from "this country has no subdivisions", so the field can
 * offer free text in the second case instead of an empty dropdown.
 */
let table: Promise<Record<string, ReadonlyArray<readonly [string, string]>>> | null = null;
const loadTable = () => (table ??= import('../data/regions').then(m => m.REGIONS));

export function useRegions(countryCode: string | undefined): Region[] & { loaded?: boolean } {
  const [state, setState] = useState<{ code?: string; regions: Region[]; loaded: boolean }>(
    { regions: [], loaded: false },
  );

  useEffect(() => {
    if (!countryCode) { setState({ regions: [], loaded: true }); return; }
    let cancelled = false;
    loadTable()
      .then(all => {
        if (cancelled) return;
        const rows = all[countryCode.toUpperCase()] ?? [];
        setState({
          code: countryCode,
          regions: rows.map(([code, name], i) => ({ id: i, name, state_code: code })),
          loaded: true,
        });
      })
      .catch(() => { if (!cancelled) setState({ regions: [], loaded: true }); });
    return () => { cancelled = true; };
  }, [countryCode]);

  const out = state.regions as Region[] & { loaded?: boolean };
  out.loaded = state.loaded && state.code === countryCode;
  return out;
}
