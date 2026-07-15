import { useEffect, useRef, useState } from 'react';
import { api, ApiError, streamGenerate, type LlmProgressEventData } from '../api';
import LlmProgressLog from './LlmProgressLog';

type Phase = 'input' | 'preview' | 'streaming' | 'generated';

interface Props {
  onModelCreated: (id: string) => void;
  onCancel: () => void;
}

export default function GeneratePanel({ onModelCreated, onCancel }: Props) {
  const [phase, setPhase] = useState<Phase>('input');
  const [modelId, setModelId] = useState('');
  const [domainDescription, setDomainDescription] = useState('');
  const [buildUI, setBuildUI] = useState(false);
  const [prompt, setPrompt] = useState('');
  const [specJson, setSpecJson] = useState('');
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Streaming state
  const [progressEvents, setProgressEvents] = useState<LlmProgressEventData[]>([]);
  const [streamDone, setStreamDone] = useState(false);
  const cancelRef = useRef<(() => void) | null>(null);

  // Cancel in-flight stream on unmount
  useEffect(() => () => { cancelRef.current?.(); }, []);

  const handlePreview = async () => {
    setLoading(true);
    setGenerateError(null);
    try {
      const res = await api.previewPrompt(modelId, domainDescription, buildUI);
      setPrompt(res.prompt);
      setPhase('preview');
    } catch (e) {
      setGenerateError(e instanceof ApiError ? e.body : String(e));
    } finally {
      setLoading(false);
    }
  };

  const handleGenerateDirect = () => {
    setGenerateError(null);
    setProgressEvents([]);
    setStreamDone(false);
    setPhase('streaming');
    const cancel = streamGenerate(
      modelId,
      domainDescription,
      buildUI,
      event => setProgressEvents(prev => [...prev, event]),
      result => {
        setStreamDone(true);
        if (result.valid && result.spec) {
          setSpecJson(JSON.stringify(result.spec, null, 2));
          setPhase('generated');
        } else if (!result.valid) {
          const errors = result.errors ?? [];
          const msg = errors.length > 0
            ? errors.map(e => `[${e.location}] ${e.message}`).join('\n')
            : 'Generation failed — no details available.';
          setGenerateError(msg);
          setPhase('streaming'); // stay on progress page to show logs + error
        }
      },
      err => {
        setStreamDone(true);
        setGenerateError(err);
      },
    );
    cancelRef.current = cancel;
  };

  // Single-call generate (from preview phase, user-edited prompt)
  const handleGenerate = async () => {
    setLoading(true);
    setGenerateError(null);
    try {
      const res = await api.generateFromPrompt(modelId, prompt);
      if (res.valid && res.spec) {
        setSpecJson(JSON.stringify(res.spec, null, 2));
        setPhase('generated');
      } else {
        const errors = Array.isArray(res.errors) ? res.errors as { location: string; message: string }[] : [];
        const msg = errors.length > 0
          ? errors.map(e => `[${e.location}] ${e.message}`).join('\n')
          : 'Generation failed — see raw response for details.';
        setGenerateError(msg);
      }
    } catch (e) {
      setGenerateError(e instanceof ApiError ? e.body : String(e));
    } finally {
      setLoading(false);
    }
  };

  const handleRegister = async () => {
    setLoading(true);
    setGenerateError(null);
    try {
      let spec: unknown;
      try {
        spec = JSON.parse(specJson);
      } catch {
        setGenerateError('Spec JSON is not valid — edit it first.');
        setLoading(false);
        return;
      }
      await api.createModel(spec);
      onModelCreated(modelId);
    } catch (e) {
      setGenerateError(e instanceof ApiError ? e.body : String(e));
    } finally {
      setLoading(false);
    }
  };

  const header = (
    <div style={{ padding: '12px 20px', background: 'var(--panel-bg)', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: 12 }}>
      <span style={{ fontWeight: 600, fontSize: 14 }}>Generate Model with LLM</span>
      <button className="btn btn-sm" onClick={onCancel} style={{ marginLeft: 'auto' }}>Cancel</button>
    </div>
  );

  if (phase === 'input') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        {header}
        <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
          {generateError && (
            <div className="banner banner-error" style={{ marginBottom: 12 }}>{generateError}</div>
          )}
          <div className="card">
            <div className="card-title">Model Description</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div>
                <label className="field-label" htmlFor="generate-model-id">Model ID</label>
                <input
                  id="generate-model-id"
                  type="text"
                  value={modelId}
                  onChange={e => setModelId(e.target.value)}
                  placeholder="e.g. order-processing"
                />
              </div>
              <div>
                <label className="field-label" htmlFor="generate-domain-description">Domain Description</label>
                <textarea
                  id="generate-domain-description"
                  value={domainDescription}
                  onChange={e => setDomainDescription(e.target.value)}
                  rows={8}
                  placeholder="Describe what this model should compute. Include fields, derivations, constraints, and any business rules."
                />
              </div>
              <div>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', userSelect: 'none' }}>
                  <input
                    type="checkbox"
                    checked={buildUI}
                    onChange={e => setBuildUI(e.target.checked)}
                  />
                  <span style={{ fontSize: 13 }}>Build UI for the model</span>
                </label>
              </div>
              <div className="btn-row">
                <button
                  className="btn btn-primary"
                  onClick={handleGenerateDirect}
                  disabled={loading || !modelId.trim() || !domainDescription.trim()}
                >
                  Generate →
                </button>
                <button
                  className="btn"
                  onClick={handlePreview}
                  disabled={loading || !modelId.trim() || !domainDescription.trim()}
                  title="Preview and edit the prompt before sending"
                >
                  {loading ? 'Building…' : 'Preview Prompt'}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (phase === 'preview') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        {header}
        <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
          {generateError && (
            <div className="banner banner-error" style={{ marginBottom: 12 }}>{generateError}</div>
          )}
          <div className="card">
            <div className="card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>Prompt — review and edit before sending</span>
              <button className="btn btn-sm" onClick={() => { setGenerateError(null); setPhase('input'); }}>← Back</button>
            </div>
            <div className="banner banner-info" style={{ marginBottom: 12 }}>
              This is the exact text that will be sent to LLM. Edit it to guide the output.
            </div>
            <textarea
              value={prompt}
              onChange={e => setPrompt(e.target.value)}
              rows={32}
              spellCheck={false}
              style={{ minHeight: 400 }}
            />
            <div className="btn-row" style={{ marginTop: 12 }}>
              <button
                className="btn btn-primary"
                onClick={handleGenerate}
                disabled={loading || !prompt.trim()}
              >
                {loading ? 'Asking LLM…' : 'Send to LLM →'}
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (phase === 'streaming') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        {header}
        <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
          {generateError && (
            <div className="banner banner-error" style={{ marginBottom: 12 }}>{generateError}</div>
          )}
          <div className="card">
            <div className="card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>Generating spec for <strong>{modelId}</strong></span>
              {!streamDone && (
                <button className="btn btn-sm" onClick={() => {
                  cancelRef.current?.();
                  setPhase('input');
                }}>Cancel</button>
              )}
              {streamDone && generateError && (
                <button className="btn btn-sm" onClick={() => { setGenerateError(null); setPhase('input'); }}>← Back</button>
              )}
            </div>
            <LlmProgressLog
              events={progressEvents}
              pendingLabel={streamDone ? undefined : 'Working…'}
            />
          </div>
        </div>
      </div>
    );
  }

  // generated phase
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {header}
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        {generateError && (
          <div className="banner banner-error" style={{ marginBottom: 12 }}>{generateError}</div>
        )}
        <div className="banner banner-success" style={{ marginBottom: 12 }}>
          Spec generated. Review and edit the JSON below, then register the model.
        </div>
        <div className="card">
          <div className="card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span>Generated Spec — editable</span>
            <div className="btn-row">
              <button
                className="btn btn-sm"
                onClick={() => { setGenerateError(null); setPhase('input'); }}
              >
                ← Re-generate
              </button>
              <button
                className="btn btn-primary btn-sm"
                onClick={handleRegister}
                disabled={loading}
              >
                {loading ? 'Registering…' : 'Register Model'}
              </button>
            </div>
          </div>
          <textarea
            value={specJson}
            onChange={e => setSpecJson(e.target.value)}
            rows={40}
            spellCheck={false}
            style={{ minHeight: 500 }}
          />
        </div>
      </div>
    </div>
  );
}
