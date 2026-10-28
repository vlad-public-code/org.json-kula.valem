---
title: Build models in Claude or ChatGPT
parent: Getting started
nav_order: 4
description: "Add one connector URL and your chat assistant turns a calculation, quote or budget into a live, editable Valem card in the conversation."
---

# Build models in Claude or ChatGPT
{: .no_toc }

Describe a calculation, quote, budget or rule set in a chat. The assistant writes a Valem model and the
chat shows it as a **live card**: change an input and every value recomputes, with the numbers coming
from Valem's engine rather than from the assistant.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## Add the connector

The sandbox hosts the chat card server at **`{{ site.sandbox_url }}/mcp/app`**. There is nothing to
install and no account to create.

- **Claude** (web or desktop app): Settings → Connectors → *Add custom connector*, with the URL
  `{{ site.sandbox_url }}/mcp/app`.
- **ChatGPT**: turn on developer mode (Settings → Apps & Connectors → Advanced settings), then create a
  connector with the same URL.
- **Any other MCP client** connects to the same URL over Streamable HTTP. Clients that support
  [MCP Apps](https://modelcontextprotocol.io/extensions/apps) show the card; the rest get the computed
  values and a link as text.

## Ask for a model

Ask in plain words, for example *"Make me a quote calculator for a cleaning business: price per room,
a weekend surcharge and a minimum charge."* The assistant:

1. reads `valem_spec_guide` once, to learn the spec format;
2. writes a ModelSpec with a view and a few embedded tests, and calls `render_model`;
3. fixes the spec and tries again if Valem reports errors or a failing test. A spec whose own tests fail
   is never shown.

The card appears in the conversation. Edit any input and it recomputes in place; a value that breaks
a hard rule is put back, with the reason shown. The assistant is told the new values after each edit,
so you can keep asking questions about them.

## Share or embed it

Each card comes with two links:

- a **share link** (`{{ site.sandbox_url }}/#share=1.…`) that opens the model in the
  [sandbox]({% link getting-started/sandbox.md %}), with the values you entered;
- an **embed URL** (`{{ site.sandbox_url }}/embed.html#e=1.…`) to put in an `<iframe>` on any web
  page.

The card's **Open in browser** button opens the share link with the values on screen right now. The
whole model rides in the link, so nothing has to be saved anywhere for it to work. A very large model
can be too long for a link; then there is none.

## `/mcp/app` or `/mcp`?

The sandbox runs two MCP servers for two different jobs.

| | `/mcp/app` (this page) | `/mcp` ([Connect your agent]({% link getting-started/connect-your-agent.md %})) |
|---|---|---|
| Where the model lives | In the chat, as a card | In a session on valem.run, shown in a browser tab |
| Setup | Add the URL | Add the URL, then pair the agent with a browser tab |
| State | None kept: each edit is recomputed from the spec the card holds | Kept in the paired session; you and the agent edit the same model |
| Tools | `render_model`, `valem_spec_guide` | The full model tool set: create, mutate, evolve, explain, … |
| Best for | Chat users who want a working calculator in the conversation | Developers iterating on a model with an agent |

Both are free to try on the public sandbox and need no Java or download.

## Limits on the public sandbox

The hosted server allows 60 requests a minute per IP, and 20 new or changed models an hour. A model
the server hasn't seen for an hour is forgotten; the card sends its spec again on the next edit, so
you won't notice. Treat anything you put in a public sandbox as public.

Full tool detail, including the card's result shape: [MCP tools reference]({% link reference/mcp-tools.md %}).
