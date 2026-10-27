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

import { onCluster } from '../client';

describe('onCluster', () => {
  it('leaves a path of the default cluster unchanged and names another cluster', () => {
    expect(onCluster('/discovery/keda')).toBe('/discovery/keda');
    expect(onCluster('/discovery/keda', 'default')).toBe('/discovery/keda');
    expect(onCluster('/discovery/keda', 'prod')).toBe('/discovery/keda?cluster=prod');
    expect(onCluster('/cluster/capabilities?fresh=1', 'prod')).toBe('/cluster/capabilities?fresh=1&cluster=prod');
  });
});
