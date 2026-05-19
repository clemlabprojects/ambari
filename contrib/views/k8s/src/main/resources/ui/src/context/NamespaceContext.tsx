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

import React from 'react';

/** Sentinel for "no namespace filter". Pages handle this by listing every
 *  namespace or by surfacing a "pick a namespace" empty state. */
export const ALL_NAMESPACES = '*';

const STORAGE_KEY = 'kdps.namespace';

interface NamespaceCtx {
  /** Selected namespace, or {@link ALL_NAMESPACES}. Defaults to "*". */
  namespace: string;
  /** Persist and broadcast a namespace change. */
  setNamespace: (n: string) => void;
}

const Ctx = React.createContext<NamespaceCtx>({ namespace: ALL_NAMESPACES, setNamespace: () => {} });

export const NamespaceProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [namespace, setNamespaceState] = React.useState<string>(() => {
    try { return localStorage.getItem(STORAGE_KEY) || ALL_NAMESPACES; } catch { return ALL_NAMESPACES; }
  });
  const setNamespace = React.useCallback((n: string) => {
    setNamespaceState(n);
    try { localStorage.setItem(STORAGE_KEY, n); } catch { /* ignore */ }
  }, []);
  const value = React.useMemo(() => ({ namespace, setNamespace }), [namespace, setNamespace]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
};

export const useNamespace = () => React.useContext(Ctx);
