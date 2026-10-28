// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readShare, shareUrl } from './cardModel';

describe('share links', () => {
  const model = { spec: { id: 'quote', version: '1.0.0', note: 'Größe ✓' }, inputs: { '$.rooms': 4 } };

  it('round-trip the spec and inputs through the fragment', async () => {
    const url = await shareUrl('https://valem.run/m', model);
    expect(url).toMatch(/^https:\/\/valem\.run\/m#v1\.[A-Za-z0-9_-]+$/);
    expect(await readShare(new URL(url).hash)).toEqual(model);
  });

  it('reject a fragment that is not a share link', async () => {
    expect(await readShare('#section-2')).toBeNull();
    expect(await readShare('#v1.not-deflate')).toBeNull();
  });
});
