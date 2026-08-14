// Serves and ships the built-in example ModelSpecs from their single source of truth.
//
// The specs are authored in exactly one place — `valem-ui/src/examples/*.json` — which is also what
// `BundledExamplesTest` globs, what `valem-mcp/pom.xml` copies into the MCP jar as the
// `valem://examples/{name}` resources, and what `CreatePanel` bundles via `import.meta.glob`.
//
// There used to be a second hand-maintained copy under `valem-ui/public/examples/` so that Vite's
// publicDir would serve them over HTTP for the sandbox's Example Picker. It drifted: by the time it
// was removed one file disagreed with its source and five of the console module's copies disagreed
// too, and nothing failed, because no test read more than one copy. This plugin replaces that copy
// with a build step, so there is nothing to keep in sync.
//
// Vite's own `publicDir` cannot do this job for the sandbox: sandbox-ui already points publicDir at
// the open repo's `valem-ui/public`, and a Vite config has only one. Hence a plugin, wired into both
// builds (see valem-ui/vite.config.ts and valem-sandbox-ui/vite.config.ts), covering dev-server
// requests and the build output alike.

import { readdirSync, readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** The one directory the example specs live in. */
export const EXAMPLES_SOURCE_DIR = path.resolve(HERE, '../src/examples');

/** URL prefix the Example Picker fetches from. */
export const EXAMPLES_URL_PREFIX = '/examples/';

/**
 * A bare spec file name and nothing else — no separators, no `..`, no query. Requests that do not
 * match fall through to the next middleware rather than reaching a filesystem lookup.
 */
const SAFE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*\.json$/;

/** Every example spec file name, sorted, so callers see a stable order. */
export function listExamples(sourceDir = EXAMPLES_SOURCE_DIR) {
  return readdirSync(sourceDir).filter(f => f.endsWith('.json')).sort();
}

/** Copies every example spec into `{outDir}/examples/`. Returns the names copied. */
export function copyExamplesTo(outDir, sourceDir = EXAMPLES_SOURCE_DIR) {
  const target = path.join(outDir, 'examples');
  mkdirSync(target, { recursive: true });
  const names = listExamples(sourceDir);
  for (const name of names) {
    writeFileSync(path.join(target, name), readFileSync(path.join(sourceDir, name)));
  }
  return names;
}

/**
 * Vite plugin: serve `/examples/*.json` in dev, and copy them into the bundle at build time.
 *
 * `writeBundle` (not `generateBundle`) so the files land after Vite's `emptyOutDir` has run — the
 * same reason the sandbox's other output plugins use it.
 */
export function copyExamplesPlugin(outDir, sourceDir = EXAMPLES_SOURCE_DIR) {
  return {
    name: 'valem-copy-examples',

    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = (req.url ?? '').split('?')[0];
        if (!url.startsWith(EXAMPLES_URL_PREFIX)) return next();
        const name = decodeURIComponent(url.slice(EXAMPLES_URL_PREFIX.length));
        if (!SAFE_NAME.test(name)) return next();
        const file = path.join(sourceDir, name);
        if (!existsSync(file)) return next();
        res.setHeader('Content-Type', 'application/json; charset=utf-8');
        res.end(readFileSync(file));
      });
    },

    writeBundle() {
      copyExamplesTo(outDir, sourceDir);
    },
  };
}
