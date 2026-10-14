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

// ui/src/context/PermissionsContext.tsx
import React, { createContext, useState, useEffect } from 'react';
import type { UserPermissions } from '../types';
import { getCurrentUserPermissions } from '../api/client';
import { READ_ONLY_PERMISSIONS, toPermissions } from '../utils/permissions';

interface PermissionsContextType {
  permissions: UserPermissions | null;
  loading: boolean;
}

export const PermissionsContext = createContext<PermissionsContextType>({
  permissions: null,
  loading: true,
});

export const PermissionsProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [permissions, setPermissions] = useState<UserPermissions | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    getCurrentUserPermissions()
      .then((raw) => {
        if (!cancelled) setPermissions(toPermissions(raw));
      })
      .catch((err) => {
        console.warn('Could not read the KDPS role of the current user; showing a read-only view.', err);
        if (!cancelled) setPermissions(READ_ONLY_PERMISSIONS);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <PermissionsContext.Provider value={{ permissions, loading }}>
      {children}
    </PermissionsContext.Provider>
  );
};
