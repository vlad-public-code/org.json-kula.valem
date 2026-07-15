/** Reusable inline spec for dev-UI e2e tests.
 *  price * qty → total (eager derivation)
 *  qty must be positive (rollback constraint)
 */
export const SIMPLE_SPEC = {
  version: '1.0.0',
  schema: {
    type: 'object',
    properties: {
      price: { type: 'number' },
      qty:   { type: 'number' },
      total: { type: 'number', readOnly: true },
    },
  },
  derivations: [
    { path: '$.total', expr: 'price * qty', evaluation: 'eager' },
  ],
  constraints: [
    { id: 'qty-positive', expr: 'qty > 0', message: 'Quantity must be positive', policy: 'rollback' },
  ],
  actions:         [],
  metaDerivations: [],
};
