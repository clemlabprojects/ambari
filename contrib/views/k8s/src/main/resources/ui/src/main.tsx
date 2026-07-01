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

// ui/src/main.tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, theme as antdTheme } from 'antd';
import App from './App.tsx';
import './fonts.css';
import './index.css';
import { ClusterStatusProvider } from './context/ClusterStatusContext.tsx';
import { ThemeModeProvider, useThemeMode } from './context/ThemeModeContext.tsx';

/** Clemlab brand faces (self-hosted in fonts.css), with safe system fallbacks. */
const FONT_SANS =
  "'Outfit', -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, Roboto, sans-serif";
const FONT_MONO =
  "'Source Code Pro', ui-monospace, 'SF Mono', Menlo, Consolas, monospace";

/**
 * antd theme driven by the light/dark preference. The algorithm flips every antd
 * component (Layout, Menu, Table, Card, Modal, forms…) between light and dark in
 * one switch; the Clemlab palette (mint #00d9a8/#00ffbc, navy surfaces) is applied
 * as tokens on top. The custom chrome CSS reads the same mode via <html data-theme>.
 */
const ThemedRoot: React.FC = () => {
  const { mode } = useThemeMode();
  const dark = mode === 'dark';
  return (
    <ConfigProvider
      componentSize="small"
      theme={{
        algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: {
          fontFamily: FONT_SANS,
          fontFamilyCode: FONT_MONO,
          fontSize: 12,
          borderRadius: 8,
          borderRadiusLG: 10,
          borderRadiusSM: 6,
          wireframe: false,
          // Clemlab mint — deeper in light mode for contrast on white.
          colorPrimary: dark ? '#00d9a8' : '#00b88f',
          colorInfo: dark ? '#00d9a8' : '#00b88f',
          colorLink: dark ? '#2de2c0' : '#008e6e',
          colorSuccess: dark ? '#34d399' : '#16a34a',
          colorWarning: dark ? '#fbbf24' : '#d97706',
          colorError: dark ? '#fb7185' : '#e11d48',
          colorBgLayout: dark ? '#0c111b' : '#f5f7fa',
          ...(dark
            ? { colorBgBase: '#0c111b', colorTextBase: '#e8edf4', colorBorder: '#283041', colorBorderSecondary: '#1d2433' }
            : { colorTextHeading: '#0f1722', colorBorder: '#e4e9f0', colorBorderSecondary: '#eef1f5' }),
        },
        components: {
          Layout: dark
            ? { bodyBg: '#0c111b', headerBg: '#121a2a', siderBg: '#121a2a' }
            : { bodyBg: '#f5f7fa', headerBg: '#ffffff', siderBg: '#fafbfd' },
          Menu: dark
            ? { itemSelectedBg: 'rgba(0,217,168,0.14)', itemSelectedColor: '#00ffbc', itemHoverBg: 'rgba(0,217,168,0.08)', itemBorderRadius: 6 }
            : { itemSelectedBg: '#e9fbf5', itemSelectedColor: '#008e6e', itemHoverBg: '#f0faf6', itemBorderRadius: 6 },
          Table: dark
            ? { headerBg: '#11161f', headerColor: '#8a97a9', headerSplitColor: 'transparent', borderColor: '#1d2433', rowHoverBg: 'rgba(0,217,168,0.06)', headerBorderRadius: 8, fontSize: 13, cellPaddingBlockSM: 10, cellPaddingInlineSM: 14 }
            : { headerBg: '#f7f9fc', headerColor: '#5b6675', headerSplitColor: 'transparent', borderColor: '#eef1f5', rowHoverBg: '#f5f8ff', headerBorderRadius: 8, fontSize: 13, cellPaddingBlockSM: 10, cellPaddingInlineSM: 14 },
          Card: { borderRadiusLG: 10, headerFontSize: 15 },
          Modal: { borderRadiusLG: 12, titleFontSize: 16 },
          Button: { borderRadius: 8, fontWeight: 500, primaryShadow: 'none', defaultShadow: 'none' },
          Input: { borderRadius: 8 },
          Select: { borderRadius: 8 },
          Tag: { borderRadiusSM: 6 },
          Segmented: { borderRadius: 8 },
          Tabs: { titleFontSize: 14 },
          Tooltip: { borderRadius: 6 },
          Collapse: { borderRadiusLG: 8 },
        },
      }}
    >
      <ClusterStatusProvider>
        <App />
      </ClusterStatusProvider>
    </ConfigProvider>
  );
};

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeModeProvider>
      <ThemedRoot />
    </ThemeModeProvider>
  </React.StrictMode>,
);
