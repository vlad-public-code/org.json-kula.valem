// Wire types mirroring the Valem REST/WebSocket DTOs. Kept intentionally small and dependency-
// free; JSON values are typed as `unknown` where the runtime is schema-driven.

export type Json = unknown;

/** A map of canonical JSON Path address -> value, as accepted by POST /models/{id}/mutations. */
export type Mutations = Record<string, Json>;

/** One RFC 6902 JSON Patch operation, as accepted by POST /models/{id}/mutations/patch. */
export interface JsonPatchOp {
  op: 'add' | 'remove' | 'replace' | 'move' | 'copy' | 'test';
  path: string;
  value?: Json;
  from?: string;
}

/** GET /models/{id} */
export interface ModelInfo {
  id: string;
  version: string;
  derivationCount: number;
  metaDerivationCount: number;
  constraintCount: number;
  effectCount: number;
}

/** POST /models */
export interface CreateModelResponse {
  id: string;
  status: string;
}

export interface ConstraintViolation {
  constraintId: string;
  message: string;
  policy: string;
}

export interface DispatchedEffect {
  effectId: string;
  emit?: string;
  payload?: Json;
}

/** A derivation or constraint evaluation trace. */
export interface DerivationTrace {
  targetPath: string;
  expression: string;
  inputPaths: string[];
  result?: Json;
  constraintPassed?: boolean;
  errorMessage?: string;
}

/** Response body of the mutation endpoints. */
export interface MutationResponse {
  success: boolean;
  mutatedPaths: string[];
  derivedUpdated: string[];
  flaggedConstraints: ConstraintViolation[];
  dispatchedEffects: DispatchedEffect[];
  traces: DerivationTrace[];
  viewDelta?: Record<string, Json>;
}

/** One durable audit record from GET /models/{id}/audit. */
export interface AuditRecord {
  modelId: string;
  sequence: number;
  timestamp: string;
  modelVersion: string;
  source: string;
  mutations: Mutations;
  derivedUpdated: string[];
  flaggedConstraints: string[];
  dispatchedEffects: string[];
  traces: DerivationTrace[];
  /** SHA-256 of the preceding record (genesis for sequence 0) — tamper-evident chain link. */
  prevHash?: string;
  /** SHA-256 of this record's content bound to prevHash. */
  hash?: string;
}

/** Result of GET /models/{id}/audit/verify. */
export interface AuditVerification {
  valid: boolean;
  recordsChecked: number;
  firstBrokenSequence?: number;
  detail: string;
}

/** Filters for the audit query. */
export interface AuditQuery {
  path?: string;
  from?: string;
  to?: string;
  limit?: number;
}

/**
 * A WebSocket frame pushed to subscribers. `kind: "mutation"` (the default when absent, for older
 * servers) follows each committed mutation and carries the path/constraint/effect lists;
 * `kind: "spec-evolved"` follows a successful spec evolution and carries only the new `version` —
 * re-fetch the spec/view/state as the source of truth.
 */
export interface ChangeEvent {
  kind?: 'mutation' | 'spec-evolved';
  modelId: string;
  mutatedPaths: string[];
  derivedUpdated: string[];
  flaggedConstraints: ConstraintViolation[];
  dispatchedEffects: DispatchedEffect[];
  /** Present on a spec-evolved frame: the new spec version. */
  version?: string;
}

export interface Snapshot {
  modelId: string;
  modelVersion: string;
  baseDoc: Json;
  derivedCache: Record<string, Json>;
  metaCache: Record<string, Json>;
}
