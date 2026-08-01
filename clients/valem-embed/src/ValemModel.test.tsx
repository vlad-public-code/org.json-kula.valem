import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, cleanup, act } from '@testing-library/react';
import { ValemModel } from './ValemModel';
import { encodeSpec, buildEmbedSrc } from './embedUrl';

afterEach(cleanup);

const SPEC = { id: 'demo', schema: { type: 'object' } };

describe('encodeSpec / buildEmbedSrc', () => {
  it('round-trips a spec into a fmt=0 fragment carrying the spec', () => {
    const frag = encodeSpec(SPEC);
    expect(frag.startsWith('0.')).toBe(true);
    const json = JSON.parse(Buffer.from(frag.slice(2), 'base64url').toString('utf8'));
    expect(json).toMatchObject({ v: 1, mode: 'spec', name: 'demo', spec: SPEC });
  });

  it('omits default options and always appends #e=', () => {
    const src = buildEmbedSrc('1.abc', { endpoint: 'https://x.test', mode: 'interactive', theme: 'auto', attribution: true });
    expect(src).toBe('https://x.test/embed.html#e=1.abc');
  });

  it('serializes non-defaults and strips a trailing slash from the endpoint', () => {
    const src = buildEmbedSrc('1.abc', { endpoint: 'https://x.test/', mode: 'readonly', theme: 'dark', attribution: false, view: 'summary' });
    expect(src).toBe('https://x.test/embed.html?mode=readonly&theme=dark&attribution=0&view=summary#e=1.abc');
  });
});

describe('<ValemModel>', () => {
  it('renders an iframe whose src points at the endpoint /embed.html with the encoded spec', () => {
    const { container } = render(<ValemModel spec={SPEC} endpoint="https://x.test" />);
    const iframe = container.querySelector('iframe')!;
    expect(iframe).toBeTruthy();
    expect(iframe.getAttribute('src')).toContain('https://x.test/embed.html#e=0.');
    expect(iframe.getAttribute('loading')).toBe('lazy');
    expect(iframe.getAttribute('sandbox')).toBe('allow-scripts allow-forms allow-same-origin');
  });

  it('uses a pre-encoded embedRef verbatim and reflects mode/theme', () => {
    const { container } = render(<ValemModel embedRef="1.xyz" endpoint="https://x.test" mode="readonly" theme="dark" />);
    const src = container.querySelector('iframe')!.getAttribute('src')!;
    expect(src).toBe('https://x.test/embed.html?mode=readonly&theme=dark#e=1.xyz');
  });

  it('translates frame messages from the embed origin into callbacks', () => {
    const onReady = vi.fn();
    const onChange = vi.fn();
    const onConstraintFlag = vi.fn();
    const { container } = render(
      <ValemModel embedRef="1.xyz" endpoint="https://x.test" onReady={onReady} onChange={onChange} onConstraintFlag={onConstraintFlag} />,
    );
    const source = container.querySelector('iframe')!.contentWindow;

    const fire = (data: unknown) => act(() => {
      window.dispatchEvent(new MessageEvent('message', { data, origin: 'https://x.test', source: source as Window }));
    });

    fire({ type: 'valem:ready' });
    fire({ type: 'valem:change', state: { amount: 3 } });
    fire({ type: 'valem:constraint', violations: [{ constraintId: 'c1', message: 'too big' }] });

    expect(onReady).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith({ amount: 3 });
    expect(onConstraintFlag).toHaveBeenCalledWith([{ constraintId: 'c1', message: 'too big' }]);
  });

  it('ignores messages from a foreign origin', () => {
    const onChange = vi.fn();
    render(<ValemModel embedRef="1.xyz" endpoint="https://x.test" onChange={onChange} />);
    act(() => {
      window.dispatchEvent(new MessageEvent('message', { data: { type: 'valem:change', state: { a: 1 } }, origin: 'https://evil.test' }));
    });
    expect(onChange).not.toHaveBeenCalled();
  });

  it('prompts for a payload when neither spec nor embedRef is given', () => {
    const { container } = render(<ValemModel />);
    expect(container.querySelector('iframe')).toBeNull();
    expect(container.textContent).toContain('provide a');
  });

  it('respects an explicit height and hides attribution', () => {
    const { container } = render(<ValemModel embedRef="1.xyz" endpoint="https://x.test" height={720} attribution={false} />);
    const iframe = container.querySelector('iframe')!;
    expect(iframe.style.height).toBe('720px');
    expect(iframe.getAttribute('src')).toContain('attribution=0');
  });
});

describe('<ValemModel> lazy mount', () => {
  const realIO = globalThis.IntersectionObserver;
  let trigger: ((entries: Partial<IntersectionObserverEntry>[]) => void) | null = null;

  beforeEach(() => {
    trigger = null;
    // A controllable IntersectionObserver stub — the component only renders the iframe once we fire it.
    globalThis.IntersectionObserver = class {
      constructor(cb: IntersectionObserverCallback) {
        trigger = (entries) => cb(entries as IntersectionObserverEntry[], this as unknown as IntersectionObserver);
      }
      observe() {}
      disconnect() {}
      unobserve() {}
      takeRecords() { return []; }
      root = null; rootMargin = ''; thresholds = [];
    } as unknown as typeof IntersectionObserver;
  });
  afterEach(() => { globalThis.IntersectionObserver = realIO; });

  it('defers the iframe until it scrolls into view', () => {
    const { container } = render(<ValemModel embedRef="1.xyz" endpoint="https://x.test" />);
    expect(container.querySelector('iframe')).toBeNull(); // lazy by default — not yet visible
    act(() => trigger?.([{ isIntersecting: true }]));
    expect(container.querySelector('iframe')).toBeTruthy();
  });

  it('renders immediately when lazy is disabled', () => {
    const { container } = render(<ValemModel embedRef="1.xyz" endpoint="https://x.test" lazy={false} />);
    expect(container.querySelector('iframe')).toBeTruthy();
  });
});

describe('<ValemModel> dispose', () => {
  it('posts valem:dispose to the frame on unmount', () => {
    const { container, unmount } = render(<ValemModel embedRef="1.xyz" endpoint="https://x.test" lazy={false} />);
    const win = container.querySelector('iframe')!.contentWindow!;
    const spy = vi.spyOn(win, 'postMessage');
    unmount();
    expect(spy).toHaveBeenCalledWith({ type: 'valem:dispose' }, 'https://x.test');
  });
});
