// ui/src/App.tsx
import React from 'react';
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { PermissionsProvider } from './context/PermissionsContext';
import { useClusterStatus } from './context/ClusterStatusContext';
import AppLayout from './components/layout/AppLayout';
import RepositoriesPage from './pages/HelmRepositoriesPage';
import DashboardPage from './pages/DashboardPage';
import NodesPage from './pages/NodesPage';
import HelmReleasesPage from './pages/HelmReleasesPage';
import ConfigurationPage from './pages/ConfigurationPage';
import WorkloadsPage from './pages/WorkloadsPage';
const GlobalSecurityPage = React.lazy(() => import('./pages/GlobalSecurityPage'));
const GlobalConfigurationsPage = React.lazy(() => import('./pages/GlobalConfigurationsPage'));
const ServiceWizardPage = React.lazy(() => import('./pages/ServiceWizardPage'));
import { Spin} from 'antd';
import '@ant-design/v5-patch-for-react-19';
/**
 * This component contains the main routing logic.
 * It renders different sets of routes based on the cluster connection status.
 */
const AppRouter: React.FC = () => {
  const {status } = useClusterStatus();
  if (status === 'loading') {
    return <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}><Spin size="large" tip="Loading Configuration..." /></div>;
  }

  // if (status === 'error') {
  //    return <Alert message="Backend Connection Error" description={error || "Could not contact the view service."} type="error" showIcon style={{margin: 24}}/>;
  // }

  // If the view is not configured, render ONLY the configuration page.
  // This router is isolated and does not use the main AppLayout.
  if (status === 'unconfigured') {
    return (
      <Routes>
        <Route path="/configuration" element={<ConfigurationPage />} />
        {/* Redirect any other URL to the configuration page */}
        <Route path="*" element={<Navigate to="/configuration" replace />} />
      </Routes>
    );
  }

  // If the view is connected, render the full application with its layout.
  
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/nodes" element={<NodesPage />} />
        <Route path="/helm" element={<HelmReleasesPage />} />
        <Route path="/services/:serviceName" element={<React.Suspense fallback={<Spin />}><ServiceWizardPage /></React.Suspense>} />
        <Route path="/configuration" element={<ConfigurationPage />} />
        <Route path="/global-security" element={<React.Suspense fallback={<Spin />}><GlobalSecurityPage /></React.Suspense>} />
        <Route path="/managed-configs" element={<React.Suspense fallback={<Spin />}><GlobalConfigurationsPage /></React.Suspense>} />
        <Route path="/workloads" element={<WorkloadsPage />} />
        <Route path="/repositories" element={<RepositoriesPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppLayout>
  );
};

const App: React.FC = () => {
  return (
    <PermissionsProvider>
      <HashRouter>
        <AppRouter />
      </HashRouter>
    </PermissionsProvider>
  );
};

export default App;
