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

// ui/src/main.tsx

import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import '@ant-design/v5-patch-for-react-19';
import App from './App.tsx';
import { AssistantStatusProvider } from './context/AssistantStatusContext.tsx';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      componentSize="small"
      theme={{
        token: {
          fontSize: 12,
          borderRadius: 6,
          colorPrimary: '#1677ff',
        },
        components: {
          Table: {
            cellFontSize: 12,
          },
        },
      }}
    >
      <AssistantStatusProvider>
        <App />
      </AssistantStatusProvider>
    </ConfigProvider>
  </React.StrictMode>,
);
