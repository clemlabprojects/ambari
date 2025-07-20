// ui/src/main.tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.tsx';
import './index.css';
import { ClusterStatusProvider } from './context/ClusterStatusContext.tsx';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ClusterStatusProvider>
      <App />
    </ClusterStatusProvider>
  </React.StrictMode>,
);
