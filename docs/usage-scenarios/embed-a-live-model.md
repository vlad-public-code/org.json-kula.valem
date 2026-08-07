---
title: Embed a live model
parent: Usage scenarios
nav_order: 7
description: "Drop a live, interactive Valem model into any page — a copy-paste iframe or a native React component."
---

# Embed a live model
{: .no_toc }

Turn a Valem model into an interactive widget on someone else's page — a blog post, a docs site, a
Notion doc, or your own React app. The reader changes an input and every derived value recomputes
live, with no spreadsheet and no backend of their own.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## Two ways to embed

Both carry the model in the link itself and compute against a Valem sandbox origin (the public
sandbox by default). Each viewer gets a **private, ephemeral copy** — their edits are never saved or
shared back to you or to other viewers.

| Surface | Use it when | Build step |
|---|---|---|
| **`<iframe>` snippet** | Any HTML host — a blog, a CMS, Notion, a plain page. | None. |
| **`@valem/embed` React component** | A React app that wants native mounting, callbacks and theming. | npm. |

## The iframe snippet

In the [sandbox]({% link getting-started/sandbox.md %}), open a model and choose **Share → Embed**.
Pick **Interactive** or **Read-only** and a theme, then copy the snippet:

```html
<iframe src="https://valem.run/embed.html#e=1.q7Fh…"
  style="width:100%;border:0" height="480" loading="lazy"
  sandbox="allow-scripts allow-forms allow-same-origin"
  title="Valem model"></iframe>
```

Paste it anywhere. The whole model travels in the `#e=…` fragment, so there is nothing to host and
nothing to keep in sync.

### Auto-resize (optional)

The frame reports its content height to the host. Paste this once per page to size the iframe to fit:

```html
<script>
  window.addEventListener('message', function (e) {
    if (e.origin !== 'https://valem.run') return;
    if ((e.data || {}).type !== 'valem:height') return;
    document.querySelectorAll('iframe').forEach(function (f) {
      if (f.contentWindow === e.source) f.style.height = e.data.px + 'px';
    });
  });
</script>
```

## The React component

```bash
npm install @valem/embed
```

```tsx
import { ValemModel } from '@valem/embed';

export function Pricing() {
  return (
    <ValemModel
      spec={pricingSpec}          // an inline ModelSpec — or pass embedRef="1.q7Fh…"
      mode="interactive"
      theme="auto"                // 'light' | 'dark' | 'auto'
      height="auto"               // or a fixed pixel number
      onChange={(state) => console.log(state)}
      onConstraintFlag={(violations) => console.warn(violations)}
    />
  );
}
```

`react` / `react-dom` (v18+) are peer dependencies. The component is **lazy by default** — it defers
creating the model until it scrolls into view, so a page with many embeds doesn't fire every model
create on load. See the
[package README]({{ site.gh_repo }}/tree/main/clients/valem-embed#readme) for the full prop list.

## Interactive vs read-only

- **Interactive** (default) — the viewer edits inputs and derivations recompute live.
- **Read-only** — the view renders with inputs disabled: a computed snapshot for showing a result
  (a filled-in pricing table, a finished calculation) without letting readers change it.

## How it works, and what it means for privacy

The spec travels in the URL fragment, which browsers never send to a server. On load, the embed
**creates a fresh throwaway model on the Valem server** from that spec and renders the live view
against it — derivations and constraints compute server-side, exactly as in the sandbox. Nothing is
persisted; the instance expires on its own.

Because the model is in the link, **anyone who can see the page can read whatever the spec (and, if
you chose *Spec + data*, the entered values) contains.** Share spec-only unless you intend the data
to be public.

## Related

- [Try the sandbox]({% link getting-started/sandbox.md %}) — where you generate the embed snippet
- [Model-driven UIs]({% link usage-scenarios/model-driven-ui.md %}) — the `viewDefinition` an embed renders
- [Embed Valem in your project]({% link extending/embedding.md %}) — embedding the **engine** (Java/TS), not a live model widget
