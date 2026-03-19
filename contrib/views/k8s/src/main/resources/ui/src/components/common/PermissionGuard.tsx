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
