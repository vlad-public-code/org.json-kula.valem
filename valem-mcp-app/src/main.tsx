import { createRoot } from 'react-dom/client';
import { App } from '@modelcontextprotocol/ext-apps/app-with-deps';
import { Card } from './Card';
import './card.css';

// No StrictMode: its double effect run would connect the host bridge twice.
const app = new App({ name: 'Valem model card', version: '0.1.0' },
  { availableDisplayModes: ['inline', 'fullscreen'] }, { autoResize: true });
createRoot(document.getElementById('root')!).render(<Card app={app} />);
