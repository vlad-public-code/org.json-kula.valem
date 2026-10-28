// The "Why is this number?" graph helpers (docs/sandbox/why-this-number.md) now live in
// valem-view-react, so the chat card and this app share one implementation; re-exported here for the
// existing imports.

export {
  buildGraphIndexes, valueAtKey, inlineInputPaths, transitiveInputs, createGraphProvenance,
} from 'valem-view-react';
export type { GraphIndexes } from 'valem-view-react';
