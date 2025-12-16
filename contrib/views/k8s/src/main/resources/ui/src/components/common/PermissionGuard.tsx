// ui/src/components/common/PermissionGuard.tsx
import React from 'react';
import { usePermissions } from '../../hooks/usePermissions';

type PermissionCheck = 'canWrite' | 'canConfigure';

interface PermissionGuardProps {
  children: React.ReactNode;
  requires: PermissionCheck;
  fallback?: React.ReactNode; // Élément à afficher si l'utilisateur n'a pas les droits
}

const PermissionGuard: React.FC<PermissionGuardProps> = React.memo(({ children, requires, fallback = null }) => {
  const { permissions, loading } = usePermissions();

  if (loading) {
    return null; // Ne rien afficher pendant le chargement des permissions
  }

  if (permissions && permissions[requires]) {
    return <>{children}</>;
  }

  return <>{fallback}</>;
});

PermissionGuard.displayName = 'PermissionGuard';

export default PermissionGuard;
