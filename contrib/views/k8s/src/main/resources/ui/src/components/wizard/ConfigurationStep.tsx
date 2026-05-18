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
import { Tabs, Empty, Typography } from 'antd';
import PropertyRenderer from './PropertyRenderer'; // (Reuse the one I gave you earlier)

interface ConfigurationStepProps {
  configs: any[];
  overrides: Record<string, any>;
  onChange: (overrides: Record<string, any>) => void;
  /**
   * Parent-owned map of password validity flags, keyed by `${configName}/${propName}`.
   * PropertyRenderer reports the boolean here whenever its local confirm state changes
   * the password's validity. The wizard reads this map to gate the Next button.
   *
   * We only lift the *boolean*, not the full confirm string — lifting the string
   * causes a re-render cascade through Tabs/PropertyRenderer that steals focus
   * from the confirm input (each keystroke re-renders the wizard).
   */
  passwordValidity: Record<string, boolean>;
  onPasswordValidityChange: (validity: Record<string, boolean>) => void;
}

const ConfigurationStep: React.FC<ConfigurationStepProps> = ({ configs, overrides, onChange, passwordValidity, onPasswordValidityChange }) => {
  if (!configs || configs.length === 0) return <Empty description="No stack configurations found." />;

  const handlePropChange = (configName: string, propName: string, value: any) => {
    const key = `${configName}/${propName}`;
    onChange({ ...overrides, [key]: value });
  };

  // Stable per-Property callback: only fires when validity *flips*, not on every key.
  // PropertyRenderer's effect debounces by comparing against lastReportedRef.
  const handleValidityChange = React.useCallback((configName: string, propName: string, valid: boolean) => {
    const key = `${configName}/${propName}`;
    onPasswordValidityChange({ ...passwordValidity, [key]: valid });
  }, [passwordValidity, onPasswordValidityChange]);

  const items = configs.map(cfg => ({
    key: cfg.name,
    label: cfg.name,
    children: (
        <div style={{ maxHeight: '60vh', overflowY: 'auto', paddingRight: 8 }}>
            <Typography.Paragraph type="secondary">{cfg.description}</Typography.Paragraph>
            {cfg.properties.map((prop: any) => (
                <PropertyRenderer
                    key={prop.name}
                    configName={cfg.name}
                    property={prop}
                    currentValue={overrides[`${cfg.name}/${prop.name}`]}
                    onChange={handlePropChange}
                    onPasswordValidityChange={handleValidityChange}
                />
            ))}
        </div>
    )
  }));

  return <Tabs items={items} type="card" />;
};

export default ConfigurationStep;