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

- **Why is this number?** Hover a computed value to see its formula, the values it used and the
  inputs it comes from. Ask the assistant the same question and it calls `explain_value`, so its
  answer quotes Valem's formula and values instead of a guess.
- **Several views.** A model with more than one view gets tabs, and where the chat app allows it a
  *Full screen* button for bigger models.

## Published calculators

For net salary, car tax, self-employed income and similar questions, the assistant can show one of
the [published calculators]({{ site.sandbox_url }}/calculators/) instead of writing a model:
`list_calculators` finds it and `show_calculator` puts it in the chat as the same live card, with its
tax year and cited sources. Ask, for example, *"Show me Germany's net salary for 60,000 a year."*

## Share or embed it

Each card comes with two links:

- a **share link** (`{{ site.sandbox_url }}/#share=1.…`) that opens the model in the
  [sandbox]({% link getting-started/sandbox.md %}), with the values you entered;
- an **embed URL** (`{{ site.sandbox_url }}/embed.html#e=1.…`) to put in an `<iframe>` on any web
  page.

The card's **Open in browser** button opens the share link with the values on screen right now. The
whole model rides in the link, so nothing has to be saved anywhere for it to work. A very large model
can be too long for a link; then there is none.

## On your own server

The card is part of the open `valem-mcp` server, so it isn't tied to valem.run:

- **The stdio jar** (see [Running the MCP server]({% link deployment/mcp-server.md %})) serves the card
  to any local MCP client that renders MCP Apps, such as VS Code.
- **`valem-web`'s `/mcp` endpoint** serves it over HTTP. To use it from the Claude or ChatGPT apps,
  the server must be reachable from the internet over HTTPS, and the chat apps' connector settings
  can't send an API key: put the endpoint behind a gateway that handles sign-in, or leave
  `valem.api.key` unset only on a server whose models aren't private.

Both offer `render_model` as above, plus **`show_model`** for the models the server keeps. Ask
*"show me the quote model"* and the assistant puts it in the chat as a card; every edit you make there
is saved to that model, so the assistant reads your values back with `get_state`, and the next time
the card opens it shows the model as it is then. Neither runs the hosted extras (share links and the
published calculators).

## `/mcp/app` or `/mcp`?

The sandbox runs two MCP servers for two different jobs.

| | `/mcp/app` (this page) | `/mcp` ([Connect your agent]({% link getting-started/connect-your-agent.md %})) |
|---|---|---|
| Where the model lives | In the chat, as a card | In a session on valem.run, shown in a browser tab |
| Setup | Add the URL | Add the URL, then pair the agent with a browser tab |
| State | None kept: each edit is recomputed from the spec the card holds | Kept in the paired session; you and the agent edit the same model |
| Tools | `render_model`, `explain_value`, `show_calculator`, `list_calculators`, `valem_spec_guide` | The full model tool set: create, mutate, evolve, explain, …, plus `render_model` and `show_model` for cards |
| Best for | Chat users who want a working calculator in the conversation | Developers iterating on a model with an agent |

Both are free to try on the public sandbox and need no Java or download.

## Limits on the public sandbox

The hosted server allows 60 requests a minute per IP, and 20 new or changed models an hour. A model
the server hasn't seen for an hour is forgotten; the card sends its spec again on the next edit, so
you won't notice. Treat anything you put in a public sandbox as public.

Full tool detail, including the card's result shape: [MCP tools reference]({% link reference/mcp-tools.md %}).
