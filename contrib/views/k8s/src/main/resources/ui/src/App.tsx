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
import RepositoriesPage from './pages/HelmRepositoriesPage';
import GitRepositoriesPage from './pages/GitRepositoriesPage';
import DashboardPage from './pages/DashboardPage';
import NodesPage from './pages/NodesPage';
import HelmReleasesPage from './pages/HelmReleasesPage';
import ConfigurationPage from './pages/ConfigurationPage';
import WorkloadsPage from './pages/WorkloadsPage';
const CatalogPage = React.lazy(() => import('./pages/CatalogPage'));
const OperatorsPage = React.lazy(() => import('./pages/OperatorsPage'));
const TruststoresPage = React.lazy(() => import('./pages/TruststoresPage'));
const GlobalSecurityPage = React.lazy(() => import('./pages/GlobalSecurityPage'));
const GlobalConfigurationsPage = React.lazy(() => import('./pages/GlobalConfigurationsPage'));
const CertificateAuthoritiesPage = React.lazy(() => import('./pages/CertificateAuthoritiesPage'));
const ServiceWizardPage = React.lazy(() => import('./pages/ServiceWizardPage'));
import { Spin } from 'antd';
import '@ant-design/v5-patch-for-react-19';

/**
 * Main routing logic. Renders a stripped layout for the first-run
 * configuration screen and the full sidebar shell otherwise.
 */
const AppRouter: React.FC = () => {
  const { status } = useClusterStatus();
  if (status === 'loading') {
    return <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}><Spin size="large" tip="Loading Configuration..." /></div>;
  }

  if (status === 'unconfigured') {
    return (
      <Routes>
        <Route path="/configuration" element={<ConfigurationPage />} />
        <Route path="*" element={<Navigate to="/configuration" replace />} />
      </Routes>
    );
  }

  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/catalog" element={<React.Suspense fallback={<Spin />}><CatalogPage /></React.Suspense>} />
        <Route path="/nodes" element={<NodesPage />} />
        <Route path="/helm" element={<HelmReleasesPage />} />
        <Route path="/services/:serviceName" element={<React.Suspense fallback={<Spin />}><ServiceWizardPage /></React.Suspense>} />
        <Route path="/configuration" element={<ConfigurationPage />} />
        <Route path="/global-security" element={<React.Suspense fallback={<Spin />}><GlobalSecurityPage /></React.Suspense>} />
        <Route path="/certificate-authorities" element={<React.Suspense fallback={<Spin />}><CertificateAuthoritiesPage /></React.Suspense>} />
        <Route path="/truststores" element={<React.Suspense fallback={<Spin />}><TruststoresPage /></React.Suspense>} />
        <Route path="/operators" element={<React.Suspense fallback={<Spin />}><OperatorsPage /></React.Suspense>} />
        <Route path="/managed-configs" element={<React.Suspense fallback={<Spin />}><GlobalConfigurationsPage /></React.Suspense>} />
        <Route path="/workloads" element={<WorkloadsPage />} />
        <Route path="/repositories" element={<RepositoriesPage />} />
        <Route path="/git-repositories" element={<GitRepositoriesPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
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
