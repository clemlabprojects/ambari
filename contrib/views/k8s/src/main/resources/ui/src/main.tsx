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
import { ReduceMotionProvider, useReduceMotion } from './context/ReduceMotionContext.tsx';

/**
 * Interface and code faces (self-hosted in fonts.css), with safe system fallbacks. IBM Plex reads clearly at console
 * sizes, keeps digits and look-alike letters apart, and gives text and code one family; the Clemlab brand face (Outfit)
 * is kept for the KDPS wordmark only (AppLayout.css).
 */
const FONT_SANS =
  "'IBM Plex Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, Roboto, sans-serif";
const FONT_MONO =
  "'IBM Plex Mono', ui-monospace, 'SF Mono', Menlo, Consolas, monospace";

/**
 * antd theme driven by the light/dark preference. The algorithm flips every antd
 * component (Layout, Menu, Table, Card, Modal, forms…) between light and dark in
 * one switch; the Clemlab palette (mint #00d9a8/#00ffbc, navy surfaces) is applied
 * as tokens on top. The custom chrome CSS reads the same mode via <html data-theme>.
 */
const ThemedRoot: React.FC = () => {
  const { mode } = useThemeMode();
  const { reduceMotion } = useReduceMotion();
  const dark = mode === 'dark';
  return (
    <ConfigProvider
      // Fields and buttons at antd's standard size (32px high), easier to read and to hit; dense tables and toolbars
      // ask for "small" themselves.
      componentSize="middle"
      // In reduce-motion mode, disable the click ripple and collapse antd's motion durations to
      // near-zero — the biggest source of continuous repaints (costly over Citrix/VDI). The
      // reduce-motion class on <html> (see index.css) flattens the rest globally.
      wave={{ disabled: reduceMotion }}
      theme={{
        algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: {
          ...(reduceMotion ? { motionDurationFast: '0s', motionDurationMid: '0s', motionDurationSlow: '0s' } : {}),
          fontFamily: FONT_SANS,
          fontFamilyCode: FONT_MONO,
          // One type scale from a 14px base (antd derives the headings from it); three weights only: 400 for text,
          // 500 for navigation and tags, 600 for labels, buttons, titles and strong text.
          fontSize: 14,
          fontWeightStrong: 600,
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
            : { colorTextHeading: '#0f1722', colorBorder: '#cbd3df', colorBorderSecondary: '#dbe1ea' }),
        },
        components: {
          Layout: dark
            ? { bodyBg: '#0c111b', headerBg: '#121a2a', siderBg: '#121a2a' }
            : { bodyBg: '#f5f7fa', headerBg: '#ffffff', siderBg: '#fafbfd' },
          Menu: dark
            ? { itemSelectedBg: 'rgba(0,217,168,0.14)', itemSelectedColor: '#00ffbc', itemHoverBg: 'rgba(0,217,168,0.08)', itemBorderRadius: 6 }
            : { itemSelectedBg: '#e9fbf5', itemSelectedColor: '#008e6e', itemHoverBg: '#f0faf6', itemBorderRadius: 6 },
          Table: dark
            ? { headerBg: '#11161f', headerColor: '#8a97a9', headerSplitColor: 'transparent', borderColor: '#1d2433', rowHoverBg: 'rgba(0,217,168,0.06)', headerBorderRadius: 8, fontSize: 14, cellPaddingBlockSM: 10, cellPaddingInlineSM: 14 }
            : { headerBg: '#f7f9fc', headerColor: '#5b6675', headerSplitColor: 'transparent', borderColor: '#eef1f5', rowHoverBg: '#f5f8ff', headerBorderRadius: 8, fontSize: 14, cellPaddingBlockSM: 10, cellPaddingInlineSM: 14 },
          Card: { borderRadiusLG: 10, headerFontSize: 16 },
          Modal: { borderRadiusLG: 12, titleFontSize: 17 },
          Button: { borderRadius: 8, fontWeight: 600, primaryShadow: 'none', defaultShadow: 'none' },
          Form: { labelFontSize: 14 },
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
      <ReduceMotionProvider>
        <ThemedRoot />
      </ReduceMotionProvider>
    </ThemeModeProvider>
  </React.StrictMode>,
);
