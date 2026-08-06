# MCP directory listing for Valem — hosted, no jar

Valem runs as a **hosted MCP server** at `https://valem.run/mcp` (Streamable HTTP). An agent
connects over the network and drives a live [sandbox](https://valem.run) session — no jar to
download, no Java to install. This directory holds the one file that makes that discoverable: the
official-registry entry.

| File | Directory | What it is |
|---|---|---|
| [`server.json`](server.json) | **Official MCP registry** (`registry.modelcontextprotocol.io`) | The registry entry — reverse-DNS name `io.github.vlad-public-code/valem` with a `remotes` entry pointing at the hosted Streamable-HTTP endpoint. |

For Claude Code and Claude Desktop there is no file to ship — a remote MCP server is added by **URL**
(see below).

## How an agent connects (no jar)

Point any remote-capable MCP client at the endpoint, then pair it with a browser sandbox session:

```bash
# Claude Code — add the hosted server over HTTP
claude mcp add --transport http valem https://valem.run/mcp
```

- **Claude Desktop:** Settings → Connectors → Add custom connector → URL `https://valem.run/mcp`.
- **Any MCP client:** connect to `https://valem.run/mcp` (Streamable HTTP).

Then, in the agent, **call the `pair_browser` tool**. It returns a verification link and a confirmation
code; open the link, check the code matches, and click Approve in the sandbox. From then on the agent's
`create_model` / `mutate` / `get_state` / `explain` calls drive that browser session's models live. The
pure authoring tools (`validate_spec`, `eval_expression`, `test_spec`, `dry_run`) work before pairing.
See [Connect your agent](https://vlad-public-code.github.io/org.json-kula.valem/getting-started/connect-your-agent.html).

## Publish to the registry

```bash
# install the publisher CLI — github.com/modelcontextprotocol/registry
mcp-publisher login github     # OAuth proves ownership of the io.github.vlad-public-code/* namespace
mcp-publisher publish          # validates & submits server.json
```

The registry's `server.json` schema changes fairly often; `mcp-publisher` validates on publish, so run it
and fix any field it rejects rather than trusting this file blindly.

## Keep in sync

- **endpoint** — `remotes[0].url` must match the deployed sandbox origin. If the host moves off
  `valem.run`, update it here and re-publish.
- **version** — bump `version` on a meaningful change to the exposed surface, then re-publish.

## Self-hosting instead (the jar)

Prefer to run your own server or drive an embedded, in-memory instance? That's the stdio jar path —
`valem-mcp.jar`, `--url`, `--browser` — documented in
[Running the MCP server](https://vlad-public-code.github.io/org.json-kula.valem/deployment/mcp-server.html).
The hosted remote above is the zero-install default; the jar is for self-hosting, offline use, and
driving a private `valem-web` server.
