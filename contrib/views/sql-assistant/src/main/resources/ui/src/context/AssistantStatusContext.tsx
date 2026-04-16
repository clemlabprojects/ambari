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

// ui/src/context/AssistantStatusContext.tsx

import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { getHealth, getViewConfig } from '../api/client';
import type { HealthResponse, ViewConfig } from '../types';

type Status = 'loading' | 'ready' | 'unconfigured' | 'error';

interface AssistantStatusContextType {
  status: Status;
  health: HealthResponse | null;
  viewConfig: ViewConfig | null;
  error: string | null;
  refresh: () => Promise<void>;
  setStatus: (s: Status) => void;
}

const AssistantStatusContext = createContext<AssistantStatusContextType | null>(null);

export const AssistantStatusProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [status, setStatus] = useState<Status>('loading');
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [viewConfig, setViewConfig] = useState<ViewConfig | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setStatus('loading');
    setError(null);
    try {
      const vc = await getViewConfig();
      setViewConfig(vc);
      if (!vc.configured) {
        setStatus('unconfigured');
        return;
      }
      const h = await getHealth();
      setHealth(h);
      setStatus(h.status === 'error' ? 'error' : 'ready');
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      // A 502 / network error means the semantic service is not running
      setError(msg);
      setStatus('error');
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return (
    <AssistantStatusContext.Provider
      value={{ status, health, viewConfig, error, refresh, setStatus }}
    >
      {children}
    </AssistantStatusContext.Provider>
  );
};

export const useAssistantStatus = (): AssistantStatusContextType => {
  const ctx = useContext(AssistantStatusContext);
  if (!ctx) throw new Error('useAssistantStatus must be used inside AssistantStatusProvider');
  return ctx;
};
