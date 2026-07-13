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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// ui/src/App.tsx
import React from 'react';
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { PermissionsProvider } from './context/PermissionsContext';
import { NamespaceProvider } from './context/NamespaceContext';
import { useClusterStatus } from './context/ClusterStatusContext';
import AppLayout from './components/layout/AppLayout';
// All pages are lazy-loaded so the initial bundle is just the shell (layout +
// routing + contexts). This keeps heavy page deps out of the critical path —
// notably recharts (DashboardPage) and the chart/wizard code — so the app paints
// fast and each page's code arrives only when navigated to.
const RepositoriesPage = React.lazy(() => import('./pages/HelmRepositoriesPage'));
const GitRepositoriesPage = React.lazy(() => import('./pages/GitRepositoriesPage'));
const DashboardPage = React.lazy(() => import('./pages/DashboardPage'));
const NodesPage = React.lazy(() => import('./pages/NodesPage'));
const HelmReleasesPage = React.lazy(() => import('./pages/HelmReleasesPage'));
const ConfigurationPage = React.lazy(() => import('./pages/ConfigurationPage'));
const WorkloadsPage = React.lazy(() => import('./pages/WorkloadsPage'));
const CatalogPage = React.lazy(() => import('./pages/CatalogPage'));
const OperatorsPage = React.lazy(() => import('./pages/OperatorsPage'));
const TruststoresPage = React.lazy(() => import('./pages/TruststoresPage'));
const GlobalSecurityPage = React.lazy(() => import('./pages/GlobalSecurityPage'));
const GlobalConfigurationsPage = React.lazy(() => import('./pages/GlobalConfigurationsPage'));
const CertificateAuthoritiesPage = React.lazy(() => import('./pages/CertificateAuthoritiesPage'));
const ServiceWizardPage = React.lazy(() => import('./pages/ServiceWizardPage'));
const ContextsPage = React.lazy(() => import('./pages/ContextsPage'));
import { Spin } from 'antd';
import '@ant-design/v5-patch-for-react-19';

/** Shared fallback while a lazy page chunk loads. */
const PageFallback: React.FC = () => (
  <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 240 }}>
    <Spin size="large" />
  </div>
);

/**
 * Main routing logic. Renders a stripped layout for the first-run
 * configuration screen and the full sidebar shell otherwise.
 */
const AppRouter: React.FC = () => {
  const { status } = useClusterStatus();
  if (status === 'loading') {
    // Branded, theme-aware splash instead of a bare spinner on a white page.
    return (
      <div className="app-splash">
        <div className="app-splash-mark" style={{ background: 'var(--panel)' }}>
          <svg width="42" height="42" viewBox="0 0 64 64" fill="none" stroke="var(--accent)"
               strokeWidth={3} strokeLinejoin="round" strokeLinecap="round" aria-hidden="true">
            <path d="M32 10 L49.2 18.3 L53.4 36.9 L41.55 51.8 L22.45 51.8 L10.6 36.9 L14.8 18.3 Z"/>
            <path d="M32 32 L32 10 M32 32 L49.2 18.3 M32 32 L53.4 36.9 M32 32 L41.55 51.8 M32 32 L22.45 51.8 M32 32 L10.6 36.9 M32 32 L14.8 18.3"/>
            <circle cx="32" cy="32" r="7" fill="var(--accent)" stroke="none"/>
            <circle cx="32" cy="32" r="3" fill="var(--panel)" stroke="none"/>
          </svg>
        </div>
        <Spin />
        <div className="app-splash-text">Connecting to cluster…</div>
      </div>
    );
  }

  if (status === 'unconfigured') {
    // First-run (no cluster configured): rendered WITHOUT AppLayout, so it needs its own
    // scroll container — otherwise the ConfigurationPage's antd <Layout> overflows #root
    // (overflow:hidden) and the page can't scroll full-page. Same 100dvh flex-column +
    // min-height:0 + overflow:auto pattern as .main-scroll / the AppLayout config fallback.
    return (
      <div style={{ height: '100dvh', display: 'flex', flexDirection: 'column' }}>
        <div style={{ flex: '1 1 auto', minHeight: 0, overflow: 'auto' }}>
          <React.Suspense fallback={<PageFallback />}>
            <Routes>
              <Route path="/configuration" element={<ConfigurationPage />} />
              <Route path="*" element={<Navigate to="/configuration" replace />} />
            </Routes>
          </React.Suspense>
        </div>
      </div>
    );
  }

  return (
    <AppLayout>
      <React.Suspense fallback={<PageFallback />}>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/catalog" element={<CatalogPage />} />
          <Route path="/nodes" element={<NodesPage />} />
          <Route path="/helm" element={<HelmReleasesPage />} />
          <Route path="/services/:serviceName" element={<ServiceWizardPage />} />
          <Route path="/configuration" element={<ConfigurationPage />} />
          <Route path="/global-security" element={<GlobalSecurityPage />} />
          <Route path="/certificate-authorities" element={<CertificateAuthoritiesPage />} />
          <Route path="/truststores" element={<TruststoresPage />} />
          <Route path="/operators" element={<OperatorsPage />} />
          <Route path="/managed-configs" element={<GlobalConfigurationsPage />} />
          <Route path="/workloads" element={<WorkloadsPage />} />
          <Route path="/repositories" element={<RepositoriesPage />} />
          <Route path="/git-repositories" element={<GitRepositoriesPage />} />
          <Route path="/contexts" element={<ContextsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </React.Suspense>
    </AppLayout>
  );
};

const App: React.FC = () => {
  return (
    <PermissionsProvider>
      <NamespaceProvider>
        <HashRouter>
          <AppRouter />
        </HashRouter>
      </NamespaceProvider>
    </PermissionsProvider>
  );
};

export default App;
