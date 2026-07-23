/**
 * The read half of `richTextField`.
 *
 * `richTextField` deliberately stores **markdown**, not HTML, so the value stays something a
 * derivation can read and a diff can show. That left it with no way to be displayed: `staticText`
 * renders its content as HTML, so markdown bound to it came out with the asterisks showing.
 *
 * This converts the subset the toolbar can produce — bold, italic, inline code, headings, lists,
 * links, paragraphs — and nothing else. It is not a general markdown implementation and does not
 * try to be: a spec author who needs more can still use `format: "html"`.
 *
 * **HTML is escaped first.** Every character of the source is neutralised before any markup is
 * introduced, so a stored `<script>` renders as visible text rather than executing. That makes
 * `format: "markdown"` safe for model-authored content in a way `format: "html"` is not.
 */
export function markdownToHtml(source: string): string {
  const escaped = escapeHtml(source);
  const lines = escaped.split(/\r?\n/);
  const out: string[] = [];
  let listType: 'ul' | 'ol' | null = null;

  const closeList = () => {
    if (listType) { out.push(`</${listType}>`); listType = null; }
  };

  for (const line of lines) {
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    const bullet = /^\s*[-*]\s+(.*)$/.exec(line);
    const ordered = /^\s*\d+\.\s+(.*)$/.exec(line);

    if (heading) {
      closeList();
      const level = Math.min(heading[1].length + 2, 6); // a doc's h1 is the page, not the field
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`);
    } else if (bullet || ordered) {
      const wanted = bullet ? 'ul' : 'ol';
      if (listType !== wanted) { closeList(); out.push(`<${wanted}>`); listType = wanted; }
      out.push(`<li>${inline((bullet ?? ordered)![1])}</li>`);
    } else if (line.trim() === '') {
      closeList();
    } else {
      closeList();
      out.push(`<p>${inline(line)}</p>`);
    }
  }
  closeList();
  return out.join('');
}

/** A private-use sentinel; escapeHtml never emits one, so it cannot collide with real content. */
const CODE_TOKEN = '';

/**
 * Inline marks, applied to already-escaped text.
 *
 * Code spans are lifted out before the other marks run and put back afterwards. Simply ordering
 * the replacements does not work: turning `` `**x**` `` into `<code>**x**</code>` first still
 * leaves the `**` sitting in the string for the bold rule to find, so the literal a user wrapped
 * in backticks to *show* markup would get rendered as markup instead.
 */
function inline(text: string): string {
  const codes: string[] = [];
  let s = text.replace(/`([^`]+)`/g, (_, body: string) => {
    codes.push(body);
    return `${CODE_TOKEN}${codes.length - 1}${CODE_TOKEN}`;
  });

  s = s
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>')
    .replace(/_([^_]+)_/g, '<em>$1</em>')
    // Only http(s) links become anchors. `javascript:` and `data:` hrefs match nothing here and
    // are left as visible text, so a stored one is inert rather than clickable.
    .replace(/\[([^\]]+)]\((https?:&#x2F;&#x2F;[^)\s]+)\)/g,
             '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');

  return s.replace(
    new RegExp(`${CODE_TOKEN}(\\d+)${CODE_TOKEN}`, 'g'),
    (_, i: string) => `<code>${codes[Number(i)]}</code>`,
  );
}

export function escapeHtml(s: string): string {
  return s
    // Strip the sentinel `inline` uses, so content can never forge a code-span placeholder.
    .replace(new RegExp(CODE_TOKEN, 'g'), '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
    .replace(/\//g, '&#x2F;');
}
