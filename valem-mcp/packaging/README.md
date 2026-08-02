# MCP directory listings for `valem-mcp`

Packaging manifests that let Valem be *found and installed* from the places assistants and their
users already look for MCP servers. This is distribution, not code: the server itself is
[`valem-mcp`](../), and these files only describe how to launch it.

| File | Directory / installer | What it is |
|---|---|---|
| [`server.json`](server.json) | **Official MCP registry** (`registry.modelcontextprotocol.io`) | The canonical registry entry — reverse-DNS name `io.github.vlad-public-code/valem`, pointing at the `.mcpb` bundle attached to the GitHub release. |
| [`manifest.json`](manifest.json) | **Claude Desktop extensions** (MCP Bundle / `.mcpb`, formerly `.dxt`) | The bundle manifest. Zipped together with `valem-mcp.jar` it becomes a one-click `valem-mcp.mcpb` a user installs in Claude Desktop. |
| [`smithery.yaml`](smithery.yaml) | **[Smithery](https://smithery.ai)** | The Smithery local-stdio listing — launch command + a config schema for the optional remote-server URL / API key. |

All three describe the **same** stdio server: `java -jar valem-mcp.jar`, embedded and in-memory by
default, or driving a remote `valem-web` server when given a URL (via `--url` / `VALEM_URL`) and
optional API key (`VALEM_API_KEY`). See [Running the MCP
server](https://vlad-public-code.github.io/org.json-kula.valem/deployment/mcp-server.html).

## Build the bundle

The registry entry and the Claude Desktop extension both distribute one artifact — an `.mcpb` bundle
(a zip of `manifest.json` + the jar):

```bash
# 1. build the shaded jar
mvn install -pl valem-core,valem-service,valem-view -q
mvn package -pl valem-mcp -DskipTests
cp valem-mcp/target/valem-mcp-*.jar valem-mcp/packaging/valem-mcp.jar

# 2. pack the bundle (npx @anthropic-ai/mcpb, formerly @anthropic-ai/dxt)
cd valem-mcp/packaging
npx @anthropic-ai/mcpb pack . valem-mcp.mcpb
```

Attach both `valem-mcp.jar` and `valem-mcp.mcpb` to the GitHub release so the download URLs in
`server.json` and the docs resolve.

## Publish

- **MCP registry:** fill `packages[0].fileSha256` in `server.json` with the released bundle's SHA-256
  (`sha256sum valem-mcp.mcpb`), then publish with the [`mcp-publisher`
  CLI](https://github.com/modelcontextprotocol/registry) (GitHub auth proves ownership of the
  `io.github.vlad-public-code/*` namespace).
- **Claude Desktop:** submit `valem-mcp.mcpb` through the Claude Desktop extensions directory
  submission flow; users can also install the `.mcpb` file directly.
- **Smithery:** connect the GitHub repo at [smithery.ai/new](https://smithery.ai/new); Smithery reads
  `smithery.yaml`. (Smithery is set to look here — see `valem-mcp` module root for the pointer.)

## Keep in sync when releasing

These files are hand-maintained. On each release, bump in lockstep:

- **version** — `server.json` (`version` + `packages[0].version`) and `manifest.json` (`version`) must
  match the released jar/tag.
- **artifact URL** — `server.json` `packages[0].identifier` points at
  `releases/download/v<version>/valem-mcp.mcpb`.
- **fileSha256** — recompute for the new bundle; never leave the `REPLACE_WITH_...` placeholder in a
  published entry.
- **tool list** — the `tools` hint in `manifest.json` is a discovery aid, not the source of truth; the
  authoritative surface is [`reference/mcp-tools.md`](../../docs/reference/mcp-tools.md). Keep it
  roughly current but don't let it drift into a second spec.
