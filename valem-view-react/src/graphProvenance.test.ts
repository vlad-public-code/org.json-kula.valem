import { describe, expect, it } from 'vitest';
import { buildGraphIndexes, createGraphProvenance, transitiveInputs, valueAtKey } from './graphProvenance';

// price = rate * hours; total = price + vat; vat = price * 0.2
const graph = {
  nodes: [
    { key: '$.rate', kind: 'BASE', label: 'rate', expression: null },
    { key: '$.hours', kind: 'BASE', label: 'hours', expression: null },
    { key: '$.price', kind: 'DERIVED', label: 'price', expression: 'rate * hours' },
    { key: '$.vat', kind: 'DERIVED', label: 'vat', expression: 'price * 0.2' },
    { key: '$.total', kind: 'DERIVED', label: 'total', expression: 'price + vat' },
  ],
  edges: [
    { from: '$.rate', to: '$.price' },
    { from: '$.hours', to: '$.price' },
    { from: '$.price', to: '$.vat' },
    { from: '$.price', to: '$.total' },
    { from: '$.vat', to: '$.total' },
  ],
};
const state = { rate: 45, hours: 4, price: 180, vat: 36, total: 216 };

describe('graph provenance', () => {
  it('explains a derived value from its direct inputs', () => {
    const info = createGraphProvenance(buildGraphIndexes(graph), state).explain('price');
    expect(info).toMatchObject({ path: '$.price', expression: 'rate * hours', value: 180 });
    expect(info!.inputs.map(i => [i.path, i.value])).toEqual([['$.rate', 45], ['$.hours', 4]]);
    expect(info!.baseInputs).toBeUndefined();   // the direct inputs already are the editable ones
  });

  it('adds the editable inputs a value ultimately comes from when an input is derived', () => {
    const info = createGraphProvenance(buildGraphIndexes(graph), state).explain('$.total');
    expect(info!.inputs.map(i => i.path)).toEqual(['$.price', '$.vat']);
    expect(info!.baseInputs!.map(i => i.path).sort()).toEqual(['$.hours', '$.rate']);
  });

  it('does not explain an input the user sets', () => {
    expect(createGraphProvenance(buildGraphIndexes(graph), state).explain('$.rate')).toBeNull();
  });

  it('explains an inline expression through the graph nodes it reads', () => {
    const info = createGraphProvenance(buildGraphIndexes(graph), state).explainExpression('$string(total) & " EUR"', 'Total', '216 EUR');
    expect(info!.inputs.map(i => i.path)).toEqual(['$.total']);
  });

  it('tolerates an empty graph and odd keys', () => {
    expect(createGraphProvenance(buildGraphIndexes(null), state).explain('$.total')).toBeNull();
    expect(valueAtKey(state, '$constraint:min')).toBeUndefined();
    expect(valueAtKey(state, '$.items[*].qty')).toBeUndefined();
    expect(transitiveInputs(new Map(), '$.x')).toEqual([]);
  });
});
