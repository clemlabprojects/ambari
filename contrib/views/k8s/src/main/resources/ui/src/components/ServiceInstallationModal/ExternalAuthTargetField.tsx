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
import { Card, Form, Input, Select, Typography, Alert } from 'antd';
import type { ExternalAuthTargetFormField, ExternalServiceTarget } from '../../types/ServiceTypes';
import { useExternalAuthTarget } from './ExternalAuthTargetsContext';

/**
 * Renders the auth-mode dropdown + conditional Secret picker for one
 * `externalServiceTargets` entry. Self-contained: reads the entry from
 * {@link ExternalAuthTargetsContext} by its declared `target` key, finds the
 * declared {@code modeField} / {@code secretField} paths, and writes the
 * operator's choices through the parent antd Form instance.
 *
 * Renders nothing if:
 *   - The context doesn't carry the target (service.json forgot to declare it
 *     — log a warning, don't fail)
 *   - The conditional gate from the parent DynamicFormField already prevented
 *     this component from mounting (i.e. URL override is blank). That gate is
 *     enforced by the parent renderer, not this component.
 */
const ExternalAuthTargetField: React.FC<{ field: ExternalAuthTargetFormField }> = ({ field }) => {
    const form = Form.useFormInstance();
    const targetEntry = useExternalAuthTarget(field.target);

    // Mode field value drives the conditional secret picker below.
    // Path is dotted (e.g. "hive.externalAuthMode") — antd Form takes string[] for nested paths.
    const modeFieldPath = targetEntry?.modeField
        ? targetEntry.modeField.split('.')
        : undefined;
    const watchedMode = Form.useWatch(modeFieldPath as any, form);

    if (!targetEntry) {
        return (
            <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 12 }}
                message={`external-auth-target "${field.target}" is not declared in this service's externalServiceTargets map. Fix service.json.`}
            />
        );
    }

    // Build dropdown options from declared auth modes
    const modeOptions = Object.entries(targetEntry.authModes).map(([key, mode]: [string, any]) => ({
        value: key,
        label: mode.label ?? key,
    }));

    const chosenMode = watchedMode ? targetEntry.authModes[watchedMode] : undefined;
    const secretFieldPath = chosenMode?.secretField
        ? chosenMode.secretField.split('.')
        : undefined;

    return (
        <Card
            size="small"
            title={field.label || `External ${targetEntry.label} authentication`}
            style={{ marginBottom: 16, background: 'var(--inset)' }}
        >
            {field.help && (
                <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
                    {field.help}
                </Typography.Paragraph>
            )}
            <Form.Item
                name={modeFieldPath}
                label="Authentication method"
                rules={[{ required: true, message: 'Pick an authentication method for the external service.' }]}
                help={`Select how KDPS should authenticate to the external ${targetEntry.label}.`}
            >
                <Select
                    placeholder="Select an auth method..."
                    options={modeOptions}
                    allowClear
                />
            </Form.Item>

            {chosenMode && chosenMode.secretField && secretFieldPath && (
                <Form.Item
                    name={secretFieldPath}
                    label={`Credentials Secret (${watchedMode})`}
                    rules={[{ required: true, message: 'Reference an existing K8s Secret carrying these credentials.' }]}
                    help={renderSecretHelp(chosenMode)}
                >
                    <Input placeholder="my-external-creds" />
                </Form.Item>
            )}

            {chosenMode && !chosenMode.secretField && (
                <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
                    No credentials required for "{watchedMode}" — KDPS will deploy without authenticating
                    to the external service. Use this only for development setups where the foreign service
                    accepts anonymous access.
                </Typography.Paragraph>
            )}
        </Card>
    );
};

function renderSecretHelp(mode: ExternalServiceTarget['authModes'][string] | undefined): string {
    if (!mode || !mode.secretKeys || mode.secretKeys.length === 0) {
        return 'Reference an existing K8s Secret in the release namespace.';
    }
    return `Create the Secret ahead of deploy in the release namespace with these data keys: ${mode.secretKeys.join(', ')}. Then enter its name here.`;
}

export default ExternalAuthTargetField;
