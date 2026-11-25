/*
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
// The SVG files are bundled by Vite; here each is its own name.
jest.mock('../trino.svg', () => 'trino.svg');
jest.mock('../superset.svg', () => 'superset.svg');
jest.mock('../sql-assistant.svg', () => 'sql-assistant.svg');
jest.mock('../openmetadata.svg', () => 'openmetadata.svg');
jest.mock('../generic.svg', () => 'generic.svg');

import { serviceIcon, SERVICE_ICONS, DEFAULT_SERVICE_ICON } from '../index';
import { API_BASE_URL } from '../../../api/client';

describe('serviceIcon', () => {
  it('keeps the logo of a built-in service, whatever its definition says', () => {
    expect(serviceIcon('trino', { addon: { icon: true } })).toBe(SERVICE_ICONS.TRINO);
  });

  it('asks the backend for the icon of an add-on that ships one', () => {
    expect(serviceIcon('minio_gw', { addon: { icon: true } })).toBe(`${API_BASE_URL}/services/MINIO_GW/icon`);
  });

  it('gives the generic logo to an add-on without an icon and to an unknown service', () => {
    expect(serviceIcon('MINIO_GW', { addon: { icon: false } })).toBe(DEFAULT_SERVICE_ICON);
    expect(serviceIcon('CUSTOM')).toBe(DEFAULT_SERVICE_ICON);
    expect(serviceIcon(undefined)).toBe(DEFAULT_SERVICE_ICON);
  });
});
