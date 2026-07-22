import { describe, expect, it } from 'vitest';
import { escapeHtml, markdownToHtml } from './markdown';

describe('markdownToHtml', () => {
  it('renders the marks the richTextField toolbar produces', () => {
    expect(markdownToHtml('**bold**')).toBe('<p><strong>bold</strong></p>');
    expect(markdownToHtml('_italic_')).toBe('<p><em>italic</em></p>');
    expect(markdownToHtml('`code`')).toBe('<p><code>code</code></p>');
    expect(markdownToHtml('## Heading')).toContain('Heading');
  });

  it('groups consecutive bullets into one list and closes it', () => {
    expect(markdownToHtml('- one\n- two')).toBe('<ul><li>one</li><li>two</li></ul>');
    expect(markdownToHtml('1. one\n2. two')).toBe('<ol><li>one</li><li>two</li></ol>');
  });

  it('closes a list when the text goes back to prose', () => {
    expect(markdownToHtml('- one\n\nafter')).toBe('<ul><li>one</li></ul><p>after</p>');
  });

  it('escapes HTML in the source before adding any markup', () => {
    // This is the whole reason markdown is safe for user-typed content where `html` is not.
    const out = markdownToHtml('<script>alert(1)</script>');
    expect(out).not.toContain('<script>');
    expect(out).toContain('&lt;script&gt;');
  });

  it('does not let an injected tag survive inside a mark', () => {
    const out = markdownToHtml('**<img src=x onerror=alert(1)>**');
    expect(out).toContain('<strong>');
    expect(out).not.toContain('<img');
  });

  it('leaves a javascript: link as inert text rather than making it an anchor', () => {
    // Showing the raw source is the correct outcome — what must not happen is it becoming
    // clickable, so the assertion is about the anchor, not about the substring appearing at all.
    const out = markdownToHtml('[click](javascript:alert(1))');
    expect(out).not.toContain('<a ');
    expect(out).not.toContain('href');
    expect(out).toContain('[click]');
  });

  it('leaves a data: link inert too', () => {
    const out = markdownToHtml('[x](data:text/html,<script>alert(1)</script>)');
    expect(out).not.toContain('<a ');
    expect(out).not.toContain('<script>');
  });

  it('makes an http link an anchor that cannot reach back through window.opener', () => {
    const out = markdownToHtml('[docs](https://example.test/x)');
    expect(out).toContain('<a href="https:&#x2F;&#x2F;example.test&#x2F;x"');
    expect(out).toContain('rel="noopener noreferrer"');
  });

  it('keeps markup inside inline code literal', () => {
    expect(markdownToHtml('`**not bold**`')).toBe('<p><code>**not bold**</code></p>');
  });

  it('renders an empty source as nothing', () => {
    expect(markdownToHtml('')).toBe('');
  });
});

describe('escapeHtml', () => {
  it('neutralises every character that can start markup', () => {
    expect(escapeHtml(`<>&"'/`)).toBe('&lt;&gt;&amp;&quot;&#39;&#x2F;');
  });

  it('escapes the ampersand first so entities are not double-decoded', () => {
    expect(escapeHtml('&lt;')).toBe('&amp;lt;');
  });
});
