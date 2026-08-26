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

const STORAGE_KEY = 'kdps.reduceMotion';

interface ReduceMotionContextType {
  reduceMotion: boolean;
  setReduceMotion: (v: boolean) => void;
  toggle: () => void;
}

const ReduceMotionContext = createContext<ReduceMotionContextType | undefined>(undefined);

/**
 * Holds the "reduce motion" preference. In a remote-desktop session (Citrix/VDI) the whole UI
 * is streamed as pixels over the network, so CSS animations/transitions turn into continuous
 * frame traffic and the UI feels laggy — even on a fast local browser it is smooth. When enabled
 * we zero antd's motion tokens + disable the click wave, and add a `reduce-motion` class on
 * <html> that the stylesheet uses to flatten transitions/animations globally.
 *
 * Default: the OS `prefers-reduced-motion` setting (so accessibility + many locked-down VDI
 * images get it automatically); the user can flip it and the choice is persisted per browser.
 */
export const ReduceMotionProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [reduceMotion, setState] = useState<boolean>(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'true') return true;
      if (stored === 'false') return false;
    } catch { /* ignore */ }
    try {
      return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch { return false; }
  });

  useEffect(() => {
    try {
      document.documentElement.classList.toggle('reduce-motion', reduceMotion);
    } catch { /* ignore */ }
  }, [reduceMotion]);

  const persist = (v: boolean) => { try { localStorage.setItem(STORAGE_KEY, String(v)); } catch { /* ignore */ } };
  const setReduceMotion = useCallback((v: boolean) => { persist(v); setState(v); }, []);
  const toggle = useCallback(() => setState(prev => { const next = !prev; persist(next); return next; }), []);

  return (
    <ReduceMotionContext.Provider value={{ reduceMotion, setReduceMotion, toggle }}>
      {children}
    </ReduceMotionContext.Provider>
  );
};

export const useReduceMotion = (): ReduceMotionContextType => {
  const ctx = useContext(ReduceMotionContext);
  if (!ctx) throw new Error('useReduceMotion must be used within a ReduceMotionProvider');
  return ctx;
};
