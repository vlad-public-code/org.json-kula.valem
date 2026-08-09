---
title: Connect your agent (MCP pairing)
parent: Getting started
nav_order: 4
description: "Pair an AI agent with a browser tab so both drive one shared, live Valem model."
---

# Connect your agent
{: .no_toc }

Pair the Valem MCP server with a browser tab and both ends drive **one shared, live session**: the
agent authors and evolves the model, you enter data and watch it react, neither side copy-pastes
anything.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## Why pair

Without pairing, an agent that builds you a model has to hand it over as text — you paste a spec into
something, run it, and report back what happened. Paired, the loop closes:

- A model the agent creates **appears in your browser immediately**.
- You enter data in the rendered view; the agent reads exactly what you entered with `get_state` and
  `explain`, and gets a push notification when you change something.
- An agent-authored `evolve_spec` makes the browser **re-fetch and re-render on its own**.
- The agent still validates and dry-runs specs locally before touching the shared session.

This is the fastest way to iterate on a domain model with an agent: it writes the rules, you exercise
them, both of you are looking at the same live object.

## What you need

- An MCP client: Claude Code, Claude Desktop, or any other.
- The [hosted sandbox]({{ site.sandbox_url }}) — it hosts the MCP server itself.

That's the whole list. There is **nothing to download and no Java to install**: the sandbox serves the
MCP surface over Streamable HTTP at `{{ site.sandbox_url }}/mcp`, so your client connects to a URL and
no process runs on your machine. (Pairing with *your own* `valem-web` deployment does need the jar —
see [below](#pairing-with-your-own-deployment-the-jar).)

## 1. Add the MCP server

For **Claude Code**:

```bash
claude mcp add --transport http valem {{ site.sandbox_url }}/mcp
```

For **Claude Desktop**: Settings → Connectors → *Add custom connector*, with the URL
`{{ site.sandbox_url }}/mcp`. Any other remote-capable MCP client connects to that same URL.

The `pair_browser` tool now appears alongside the model tools — it exists **only** in browser-pairing
mode.

### Pairing with your own deployment (the jar)
{: .no_toc }

Driving your own `valem-web` instead of the hosted sandbox means running the stdio server locally:
grab **Java 21+** and the `valem-mcp` jar
([latest release]({{ site.gh_repo }}/releases/latest/download/valem-mcp.jar), or build from source),
and register it with `--url <your-host> --browser`:

```bash
claude mcp add valem -- \
  java -jar /absolute/path/to/valem-mcp.jar --url https://valem.internal --browser
```

The same shape in `claude_desktop_config.json`, for Claude Desktop or any client using it:

```json
{
  "mcpServers": {
    "valem": {
      "command": "java",
      "args": [
        "-jar", "/absolute/path/to/valem-mcp.jar",
        "--url", "https://valem.internal", "--browser"
      ]
    }
  }
}
```

Restart the client. Everything from step 2 on is identical — it is the same handshake either way, only
the transport differs. Embedded and offline modes are covered in
[Running the MCP server]({% link deployment/mcp-server.md %}).

## 2. Ask the agent to pair

Just ask ("pair with my browser"), or have it call `pair_browser` directly. The MCP mints a pairing
on the host and gives you a **verification link** plus a short **confirmation code**.

## 3. Open the link and approve

The link opens an **Approve** screen. Normally the agent shows you the *complete* link
([RFC 8628's `verification_uri_complete`](https://www.rfc-editor.org/rfc/rfc8628#section-3.3.1)),
which fills the confirmation code in for you — check it matches the code the agent printed, then
approve. If your host serves only the plain link, type the code yourself.

If the first `pair_browser` call reported `"pending"`, the agent calls it again — it resumes the
*same* pairing rather than minting a new one. Once you've approved it returns
`{"status":"paired", ...}` and every model tool now drives the shared session. The browser shows a
**paired** badge.

## Working in a paired session

- **Model ids stay yours.** `create_model({"id":"loan", ...})` then `mutate("loan", ...)` — the host
  namespaces ids per session internally, and the MCP tracks that transparently. `list_models` is
  scoped to your paired session, not the whole host.
- **Authoring tools stay local.** `validate_spec`, `eval_expression`, `test_spec`, and `dry_run` are
  pure functions of their inputs and always run against the local core, so the agent can vet a
  candidate spec offline before pushing it. They need no pairing at all.
- **The agent gets pushed changes.** A model's live state is a subscribable MCP resource
  (`valem://state/<modelId>`); after `resources/subscribe`, every committed mutation — including the
  ones *you* make in the browser — emits a `notifications/resources/updated`, so the agent re-reads
  instead of polling.
- **Session lost?** If the host evicts the session (idle timeout, redeploy) mid-loop, the browser's
  local recovery copy rebuilds the model and re-running `pair_browser` re-establishes the live link.

Full tool-by-tool detail: [MCP tools reference]({% link reference/mcp-tools.md %}).

## How the handshake works, and what protects it

The flow has the shape of a [device authorization grant (RFC 8628)](https://www.rfc-editor.org/rfc/rfc8628):
the MCP mints a pairing, you approve it in a browser you control, the MCP collects the session.

A pairing is one-time and short-lived (a few minutes), and rests on three things:

1. **Polling is gated by a `deviceSecret`** known only to the MCP process, so only the process that
   minted a pairing can collect its session.
2. **The `pairCode` in the verification link is 128 bits**, so the link cannot be guessed.
3. **A human clicks Approve in their own browser.** That is the control that matters; no amount of
   link handling replaces it.

The `userCode` is a second factor on top. In the complete link it rides in the URL *fragment*, which
browsers never transmit, so it cannot reach a server access log; the page reads it, then strips it
from the address bar and history before you approve. The server never reveals it to the browser on
its own (the peek endpoint returns only an expiry), and wrong-code attempts are capped.

Be aware of what it does *not* buy you: the agent receives the link and the code together and shows
you both, so if the agent's transcript leaks, both halves leak with it. The second factor protects
the channels that leak a URL *alone* — browser history, `Referer`, a pasted link.

{: .warning }
Pair with a host you trust, and treat anything you type into a paired public sandbox as public. For
private work, pair against your own [`valem-web` deployment]({% link deployment/web-api.md %}).

## Other ways to run the MCP server

Pairing is one of three modes. If you don't need a browser in the loop:

| Mode | Flag | State |
|---|---|---|
| Embedded (default) | *(none)* | In-memory, dies with the process — zero config, offline. |
| Remote | `--url <base>` + API key | The durable, shared models of a `valem-web` server. |
| Remote with browser | `--url <base> --browser` | One shared session with a paired browser tab. |

See [Running the MCP server]({% link deployment/mcp-server.md %}) for all three.
