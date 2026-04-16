/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// ui/src/App.tsx

import React, { Suspense } from 'react';
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { Spin } from 'antd';
import AppLayout from './components/layout/AppLayout';
import { useAssistantStatus } from './context/AssistantStatusContext';

// Lazy-loaded pages
const QueryPage         = React.lazy(() => import('./pages/QueryPage'));
const SchemaPage        = React.lazy(() => import('./pages/SchemaPage'));
const HistoryPage       = React.lazy(() => import('./pages/HistoryPage'));
const ConfigurationPage = React.lazy(() => import('./pages/ConfigurationPage'));

const PageFallback = (
  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', padding: 40 }}>
    <Spin />
  </div>
);

const AppRouter: React.FC = () => {
  const { status } = useAssistantStatus();

  if (status === 'loading') {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100vh' }}>
        <Spin size="large" tip="Connecting to SQL Assistant…" />
      </div>
    );
  }

  return (
    <AppLayout>
      <Suspense fallback={PageFallback}>
        <Routes>
          <Route path="/"              element={<QueryPage />} />
          <Route path="/schema"        element={<SchemaPage />} />
          <Route path="/history"       element={<HistoryPage />} />
          <Route path="/configuration" element={<ConfigurationPage />} />
          <Route path="*"              element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </AppLayout>
  );
};

const App: React.FC = () => (
  <HashRouter>
    <AppRouter />
  </HashRouter>
);

export default App;
