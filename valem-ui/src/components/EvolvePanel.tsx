import { useEffect, useRef, useState } from 'react';
import { api, ApiError, streamEvolveAi, type LlmProgressEventData } from '../api';
import type { SpecEvolution } from '../types';
import LlmProgressLog from './LlmProgressLog';

const TEMPLATE = JSON.stringify({
  upsertDerivations: [
    { path: '$.field.computed', expr: 'field.a + field.b' }
  ],
  newVersion: '1.1.0'
} satisfies SpecEvolution, null, 2);

interface Props { modelId: string; onEvolved?: () => void }

type AiPhase = 'input' | 'preview' | 'streaming';

export default function EvolvePanel({ modelId, onEvolved }: Props) {
  // Manual evolution
  const [input, setInput] = useState(TEMPLATE);
  const [result, setResult] = useState<{ id: string; version: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // AI-assisted evolution
  const [aiPhase, setAiPhase] = useState<AiPhase>('input');
  const [aiDescription, setAiDescription] = useState('');
  const [updateUI, setUpdateUI] = useState(false);
  const [aiPrompt, setAiPrompt] = useState('');
  const [aiError, setAiError] = useState<string | null>(null);
  const [aiLoading, setAiLoading] = useState(false);

  // Streaming state
  const [progressEvents, setProgressEvents] = useState<LlmProgressEventData[]>([]);
  const [streamDone, setStreamDone] = useState(false);
  const cancelRef = useRef<(() => void) | null>(null);

  useEffect(() => () => { cancelRef.current?.(); }, []);

  const handleEvolve = async () => {
    setError(null);
    setResult(null);
    setLoading(true);
    try {
      const evolution = JSON.parse(input) as SpecEvolution;
      const res = await api.evolveSpec(modelId, evolution);
      setResult(res);
      onEvolved?.();
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  const handleAiPreview = async () => {
    setAiError(null);
    setAiLoading(true);
    try {
      const currentSpec = await api.getSpec(modelId);
      const res = await api.previewEvolutionPrompt(modelId, currentSpec, aiDescription, updateUI);
      setAiPrompt(res.prompt);
      setAiPhase('preview');
    } catch (e) {
      setAiError(e instanceof ApiError ? e.body : String(e));
    } finally {
      setAiLoading(false);
    }
  };

  const handleAiGenerateDirect = () => {
    setAiError(null);
    setProgressEvents([]);
    setStreamDone(false);
    setAiPhase('streaming');
    const cancel = streamEvolveAi(
      modelId,
      aiDescription,
      event => setProgressEvents(prev => [...prev, event]),
      result => {
        setStreamDone(true);
        if ('version' in result && result.version) {
          setAiDescription('');
          setAiPhase('input');
          onEvolved?.();
        } else if ('error' in result) {
          setAiError(result.error);
        }
      },
      err => {
        setStreamDone(true);
        setAiError(err);
      },
    );
    cancelRef.current = cancel;
  };

  const handleAiGenerate = async () => {
    setAiError(null);
    setAiLoading(true);
    try {
      const res = await api.generateEvolutionFromPrompt(modelId, aiPrompt);
      if (res.valid && res.evolution) {
        setInput(JSON.stringify(res.evolution, null, 2));
        setAiPhase('input');
        setAiDescription('');
      } else {
        setAiError(res.error ?? 'Generation failed — see raw response for details.');
      }
    } catch (e) {
      setAiError(e instanceof ApiError ? e.body : String(e));
    } finally {
      setAiLoading(false);
    }
  };

  return (
    <>
      <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 16 }}>
        Incrementally update the model spec without losing live state.
        Existing base values carry forward; derived cache entries for removed derivations are dropped.
      </p>

      {/* AI-assisted section */}
      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-title">Generate Evolution with LLM</div>

        {aiPhase === 'input' && (
          <>
            {aiError && <div className="banner banner-error" style={{ marginBottom: 8 }}>{aiError}</div>}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div>
                <label className="field-label">Describe what to change</label>
                <textarea
                  value={aiDescription}
                  onChange={e => setAiDescription(e.target.value)}
                  rows={4}
                  placeholder="e.g. Add a discount percentage field and update the total to apply it. Add a constraint that discount can't exceed 50%."
                  spellCheck={false}
                />
              </div>
              <div>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', userSelect: 'none' }}>
                  <input
                    type="checkbox"
                    checked={updateUI}
                    onChange={e => setUpdateUI(e.target.checked)}
                  />
                  <span style={{ fontSize: 13 }}>Update UI for the model</span>
                </label>
              </div>
              <div className="btn-row">
                <button
                  className="btn btn-primary"
                  onClick={handleAiGenerateDirect}
                  disabled={aiLoading || !aiDescription.trim()}
                >
                  Generate with AI →
                </button>
                <button
                  className="btn"
                  onClick={handleAiPreview}
                  disabled={aiLoading || !aiDescription.trim()}
                  title="Preview and edit the prompt before sending"
                >
                  {aiLoading ? 'Building…' : 'Preview Prompt'}
                </button>
              </div>
            </div>
          </>
        )}

        {aiPhase === 'streaming' && (
          <>
            {aiError && <div className="banner banner-error" style={{ marginBottom: 8 }}>{aiError}</div>}
            <div style={{ marginBottom: 8, fontSize: 13, color: 'var(--text-muted)' }}>
              Generating evolution…
            </div>
            <LlmProgressLog
              events={progressEvents}
              pendingLabel={streamDone ? undefined : 'Working…'}
            />
            <div className="btn-row mt-8">
              {!streamDone ? (
                <button className="btn btn-sm" onClick={() => {
                  cancelRef.current?.();
                  setAiPhase('input');
                }}>Cancel</button>
              ) : aiError ? (
                <button className="btn btn-sm" onClick={() => { setAiError(null); setAiPhase('input'); }}>← Back</button>
              ) : null}
            </div>
          </>
        )}

        {aiPhase === 'preview' && (
          <>
            {aiError && <div className="banner banner-error" style={{ marginBottom: 8 }}>{aiError}</div>}
            <div className="banner banner-info" style={{ marginBottom: 8 }}>
              Review and edit the prompt before sending to LLM.
            </div>
            <textarea
              value={aiPrompt}
              onChange={e => setAiPrompt(e.target.value)}
              rows={20}
              spellCheck={false}
              style={{ minHeight: 300 }}
            />
            <div className="btn-row mt-8">
              <button
                className="btn btn-primary"
                onClick={handleAiGenerate}
                disabled={aiLoading || !aiPrompt.trim()}
              >
                {aiLoading ? 'Asking LLM…' : 'Send to LLM →'}
              </button>
              <button className="btn btn-sm" onClick={() => { setAiPhase('input'); setAiError(null); }}>
                ← Back
              </button>
            </div>
          </>
        )}
      </div>

      {/* Manual evolution */}
      <div className="card">
        <div className="card-title">Spec Evolution Diff</div>
        <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 8 }}>
          Supported keys: <code>upsertDerivations</code>, <code>removeDerivations</code>,{' '}
          <code>upsertConstraints</code>, <code>removeConstraints</code>,{' '}
          <code>upsertMetaDerivations</code>, <code>removeMetaDerivations</code>,{' '}
          <code>upsertEffects</code>, <code>removeEffects</code>,{' '}
          <code>newSchema</code>, <code>newViewDefinition</code>, <code>newVersion</code>.
        </p>
        <textarea
          value={input}
          onChange={e => setInput(e.target.value)}
          rows={18}
          spellCheck={false}
        />
        <div className="btn-row mt-8">
          <button className="btn btn-primary" onClick={handleEvolve} disabled={loading}>
            {loading ? 'Evolving…' : '▶ Apply Evolution'}
          </button>
          <button className="btn btn-sm" onClick={() => setInput(TEMPLATE)}>Reset</button>
        </div>
      </div>

      {error && <div className="banner banner-error">{error}</div>}
      {result && (
        <div className="banner banner-success">
          ✓ Spec evolved → version: <strong>{result.version}</strong>
        </div>
      )}

      <div className="card">
        <div className="card-title">Examples</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <ExampleSnippet
            label="Add derivation"
            value={JSON.stringify({ upsertDerivations: [{ path: '$.order.total', expr: 'order.subtotal + order.tax' }], newVersion: '1.1' }, null, 2)}
            onUse={setInput}
          />
          <ExampleSnippet
            label="Remove derivation"
            value={JSON.stringify({ removeDerivations: ['$.order.discount'], newVersion: '1.2' }, null, 2)}
            onUse={setInput}
          />
          <ExampleSnippet
            label="Add constraint"
            value={JSON.stringify({ upsertConstraints: [{ id: 'max-total', expr: 'order.total <= 10000', policy: 'ROLLBACK' }], newVersion: '1.3' }, null, 2)}
            onUse={setInput}
          />
        </div>
      </div>
    </>
  );
}

function ExampleSnippet({ label, value, onUse }: { label: string; value: string; onUse: (v: string) => void }) {
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.4px' }}>{label}</span>
        <button className="btn btn-sm" onClick={() => onUse(value)}>Use</button>
      </div>
      <pre style={{ fontFamily: 'var(--font-mono)', fontSize: 11, background: 'var(--main-bg)', border: '1px solid var(--border)', borderRadius: 4, padding: '8px 10px', overflow: 'auto' }}>{value}</pre>
    </div>
  );
}
