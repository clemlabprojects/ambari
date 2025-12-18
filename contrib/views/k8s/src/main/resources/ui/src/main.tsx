// ui/src/main.tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import App from './App.tsx';
import './index.css';
import { ClusterStatusProvider } from './context/ClusterStatusContext.tsx';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      componentSize="small"
      theme={{
        token: {
          fontSize: 12,
          borderRadius: 4,
        },
      }}
    >
      <ClusterStatusProvider>
        <App />
      </ClusterStatusProvider>
    </ConfigProvider>
  </React.StrictMode>,
);
