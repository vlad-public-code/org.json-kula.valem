import { useState, useEffect, useCallback } from 'react';
import { api } from './api';
import StatePanel from './components/StatePanel';
import MutatePanel from './components/MutatePanel';
import ExplainPanel from './components/ExplainPanel';
import SchemaPanel from './components/SchemaPanel';
import EvolvePanel from './components/EvolvePanel';
import LivePanel from './components/LivePanel';
import CreatePanel from './components/CreatePanel';
import GeneratePanel from './components/GeneratePanel';
import SpecPanel from './components/SpecPanel';
import ViewPanel from './components/ViewPanel';
import AiLogPanel from './components/AiLogPanel';

const TABS = ['state', 'mutate', 'explain', 'schema', 'evolve', 'live', 'spec', 'view'] as const;
type Tab = typeof TABS[number];

export default function App() {
  const [models, setModels] = useState<string[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<Tab>('state');
  const [showCreate, setShowCreate] = useState(false);
  const [showGenerate, setShowGenerate] = useState(false);
  const [showAiLog, setShowAiLog] = useState(false);
  const [specVersion, setSpecVersion] = useState(0);

  const loadModels = useCallback(async () => {
    try {
      const list = await api.listModels();
      setModels(list);
    } catch {
      // server may be down on startup
    }
  }, []);

  useEffect(() => { loadModels(); }, [loadModels]);

  const handleSelect = (id: string) => {
    setSelectedId(id);
    setShowCreate(false);
    setShowGenerate(false);
    setShowAiLog(false);
    setActiveTab('state');
  };

  const handleCreated = (id: string) => {
    loadModels();
    handleSelect(id);
  };

  const handleDeleted = () => {
    setSelectedId(null);
    setShowCreate(false);
    loadModels();
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = '';
    try {
      const text = await file.text();
      const spec = JSON.parse(text);
      const result = await api.createModel(spec);
      handleCreated(result.id);
    } catch (err) {
      alert(String(err));
    }
  };

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar-logo">◆ Valem</div>
        <div className="sidebar-section">Models</div>
        <nav className="model-list">
          {models.length === 0 && (
            <div style={{ padding: '8px 10px', fontSize: 12, color: 'var(--sidebar-muted)' }}>
              No models yet
            </div>
          )}
          {models.map(id => (
            <button
              key={id}
              className={`model-item ${selectedId === id && !showCreate && !showGenerate ? 'active' : ''}`}
              onClick={() => handleSelect(id)}
            >
              {id}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <button className="btn-sidebar new" onClick={() => { setShowGenerate(true); setShowCreate(false); setShowAiLog(false); setSelectedId(null); }}>
            ✦ Generate
          </button>
          <button className="btn-sidebar" onClick={() => { setShowAiLog(true); setShowGenerate(false); setShowCreate(false); setSelectedId(null); }}>
            ⌥ AI Log
          </button>
          <button className="btn-sidebar" onClick={() => { setShowCreate(true); setShowGenerate(false); setSelectedId(null); }}>
            + New Model
          </button>
          <label className="btn-sidebar" style={{ cursor: 'pointer' }}>
            ↑ Import
            <input type="file" accept=".json" style={{ display: 'none' }} onChange={handleImport} />
          </label>
          <button className="btn-sidebar" onClick={loadModels}>
            ↻ Refresh
          </button>
        </div>
      </aside>

      <div className="main">
        {showAiLog ? (
          <AiLogPanel onClose={() => setShowAiLog(false)} />
        ) : showGenerate ? (
          <GeneratePanel onModelCreated={handleCreated} onCancel={() => setShowGenerate(false)} />
        ) : showCreate ? (
          <CreatePanel onCreated={handleCreated} onCancel={() => setShowCreate(false)} />
        ) : selectedId ? (
          <>
            <nav className="tab-bar">
              {TABS.map(tab => (
                <button
                  key={tab}
                  className={`tab ${activeTab === tab ? 'active' : ''}`}
                  onClick={() => setActiveTab(tab)}
                >
                  {tab}
                </button>
              ))}
              <span className="model-badge">{selectedId}</span>
            </nav>
            <div className="panel">
              <div style={{ display: activeTab === 'state'   ? undefined : 'none' }}><StatePanel   modelId={selectedId} onDeleted={handleDeleted} /></div>
              <div style={{ display: activeTab === 'mutate'  ? undefined : 'none' }}><MutatePanel  modelId={selectedId} /></div>
              <div style={{ display: activeTab === 'explain' ? undefined : 'none' }}><ExplainPanel modelId={selectedId} /></div>
              <div style={{ display: activeTab === 'schema'  ? undefined : 'none' }}><SchemaPanel  modelId={selectedId} /></div>
              <div style={{ display: activeTab === 'evolve'  ? undefined : 'none' }}><EvolvePanel  modelId={selectedId} onEvolved={() => setSpecVersion(v => v + 1)} /></div>
              <div style={{ display: activeTab === 'live'    ? undefined : 'none' }}><LivePanel    modelId={selectedId} /></div>
              <div style={{ display: activeTab === 'spec'    ? undefined : 'none' }}><SpecPanel    modelId={selectedId} specVersion={specVersion} /></div>
              <div style={{ display: activeTab === 'view'    ? undefined : 'none' }}><ViewPanel    modelId={selectedId} /></div>
            </div>
          </>
        ) : (
          <div className="empty-state">
            <div className="empty-icon">◆</div>
            <h2>Valem DevTools</h2>
            <p>Select a model from the sidebar or create a new one.</p>
          </div>
        )}
      </div>
    </div>
  );
}
