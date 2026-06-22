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
import type { ExternalServiceTarget } from '../../types/ServiceTypes';

/**
 * React context that surfaces the current service definition's
 * `externalServiceTargets` map to deep descendants — specifically the
 * `external-auth-target` DynamicFormField renderer. Avoids drilling the service
 * def through every group-nesting layer.
 *
 * Providers:
 *   - {@link ServiceInstallationModal} (legacy single-modal deploy path)
 *   - {@link ServiceWizardPage}/InstallStep (multi-step wizard, the real flow)
 *
 * Both wrap their form trees with this provider so the auth-target field
 * resolves its `target` attribute regardless of which deploy path is active.
 */
export const ExternalAuthTargetsContext =
    React.createContext<Record<string, ExternalServiceTarget> | undefined>(undefined);

export const useExternalAuthTarget = (key: string | undefined): ExternalServiceTarget | undefined => {
    const map = React.useContext(ExternalAuthTargetsContext);
    if (!map || !key) return undefined;
    return map[key];
};
