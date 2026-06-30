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

import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';

export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'kdps.theme.mode';

interface ThemeModeContextType {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  toggle: () => void;
}

const ThemeModeContext = createContext<ThemeModeContextType | undefined>(undefined);

/**
 * Holds the light/dark preference. The preference is persisted per user and the
 * mode is mirrored onto <html data-theme="…"> so the custom (non-antd) chrome CSS
 * can switch its palette in lock-step with antd's theme algorithm. Defaults to
 * dark (the primary Clemlab "control plane" look).
 */
export const ThemeModeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [mode, setModeState] = useState<ThemeMode>(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'light' || stored === 'dark') return stored;
    } catch { /* ignore */ }
    return 'dark';
  });

  useEffect(() => {
    try { document.documentElement.setAttribute('data-theme', mode); } catch { /* ignore */ }
  }, [mode]);

  const persist = (m: ThemeMode) => { try { localStorage.setItem(STORAGE_KEY, m); } catch { /* ignore */ } };
  const setMode = useCallback((m: ThemeMode) => { persist(m); setModeState(m); }, []);
  const toggle = useCallback(() => setModeState(prev => {
    const next = prev === 'dark' ? 'light' : 'dark';
    persist(next);
    return next;
  }), []);

  return (
    <ThemeModeContext.Provider value={{ mode, setMode, toggle }}>
      {children}
    </ThemeModeContext.Provider>
  );
};

export const useThemeMode = (): ThemeModeContextType => {
  const ctx = useContext(ThemeModeContext);
  if (!ctx) throw new Error('useThemeMode must be used within a ThemeModeProvider');
  return ctx;
};
