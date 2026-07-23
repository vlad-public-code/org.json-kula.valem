export interface ModelInfo {
  id: string;
  version: string;
  derivationCount: number;
  metaDerivationCount: number;
  constraintCount: number;
  effectCount: number;
}

export interface ConstraintViolationItem {
  constraintId: string;
  message: string;
  policy: string;
}

export interface ViewDeltaComponent {
  id: string;
  type: string;
  bind?: string;
  value?: unknown;
  visible?: boolean;
  enabled?: boolean;
  readOnly?: boolean;
  required?: boolean;
}

export interface MutationResponse {
  success: boolean;
  mutatedPaths: string[];
  derivedUpdated: string[];
  flaggedConstraints: ConstraintViolationItem[];
  dispatchedEffects: DispatchedEffect[];
  traces: DerivationTrace[];
  viewDelta?: Record<string, ViewDeltaComponent>;
}

export interface DispatchedEffect {
  effectId: string;
  emit: string;
  payload: unknown;
}

export interface DerivationTrace {
  targetPath: string;
  expression: string;
  inputPaths: string[];
  result: unknown;
  error: string | null;
  timestamp: string;
}

export interface ChangeEvent {
  /** Frame discriminator — "mutation" (default when absent, older servers) or "spec-evolved". */
  kind?: 'mutation' | 'spec-evolved';
  modelId: string;
  mutatedPaths: string[];
  derivedUpdated: string[];
  // Objects, not ids — the broadcast carries the same {constraintId, message, policy} shape as a
  // mutation response. Typing this as string[] is what let LivePanel render the raw object as a
  // React child and blank the page once a flag constraint fired over a live WebSocket.
  flaggedConstraints: ConstraintViolationItem[];
  dispatchedEffects: DispatchedEffect[];
  /** Present on a spec-evolved frame: the new spec version. */
  version?: string;
}

export interface Snapshot {
  modelId: string;
  modelVersion: string;
  baseDoc: Record<string, unknown>;
  derivedCache: Record<string, unknown>;
  metaCache: Record<string, unknown>;
}

export interface ConstraintViolationBody {
  error: string;
  violations: ConstraintViolationItem[];
}

export interface SchemaViolationItem {
  path: string;
  keyword: string;
  message: string;
}

export interface SchemaViolationBody {
  error: string;
  violations: SchemaViolationItem[];
}

export interface WebFetchCall {
  url: string;
  responseCode: number;
  mediaType: string;
  rawLength: number;
  extractedLength: number;
}

export interface LlmInteraction {
  timestamp: string;
  prompt: string;
  response: string | null;
  errorMessage: string | null;
  durationMs: number;
  webFetchCalls: WebFetchCall[];
}

export interface SpecEvolution {
  upsertDerivations?: Array<{ path: string; expr: string }>;
  removeDerivations?: string[];
  upsertConstraints?: Array<{ id: string; expr: string; policy: string; path?: string }>;
  removeConstraints?: string[];
  upsertMetaDerivations?: Array<{ path: string; property: string; expr: string }>;
  removeMetaDerivations?: string[];
  upsertEffects?: Array<{ id: string; executor?: string; trigger: string; emit?: string; payload?: Record<string, string> }>;
  removeEffects?: string[];
  newSchema?: unknown;
  newViewDefinition?: unknown;
  newVersion?: string;
  /** Optimistic-concurrency precondition: apply only if the live version still matches. */
  expectedVersion?: string;
  // ── Schema tiers (mutually exclusive with newSchema in one evolution) ──
  /** Upsert reusable JSON Schema definitions by $defs name (fans out to every $ref usage). */
  upsertSchemaDefs?: Record<string, unknown>;
  removeSchemaDefs?: string[];
  /** Replace a schema node at a canonical data path (e.g. "$.order.items[*].qty") wholesale. */
  upsertSchemaNodes?: Array<{ path: string; schema: unknown; required?: boolean }>;
  removeSchemaNodes?: string[];
  // ── View tiers (mutually exclusive with newViewDefinition in one evolution) ──
  newDefaultView?: string;
  upsertViews?: unknown[];
  removeViews?: string[];
  upsertComponents?: Array<{ viewId: string; parentId?: string; beforeId?: string; component: unknown }>;
  removeComponents?: Array<{ viewId: string; componentId: string }>;
  // ── Constants tiers (mutually exclusive with newConstants in one evolution) ──
  upsertConstants?: Record<string, unknown>;
  removeConstants?: string[];
}
