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
import { repairActionState } from '../releaseRepairActions';

/**
 * A repair action the platform context of a release cannot perform is disabled with the backend's reason; the others,
 * and all of them before the backend has answered, stay enabled.
 */
describe('repairActionState', () => {
  const refusals = { keytabs: 'Kerberos for release analytics/trino does not come from the cluster this Ambari manages' };

  it('disables an action the backend refused, with its reason', () => {
    expect(repairActionState(refusals, 'keytabs')).toEqual({ disabled: true, reason: refusals.keytabs });
  });

  it('leaves an action the backend did not refuse enabled', () => {
    expect(repairActionState(refusals, 'oidc')).toEqual({ disabled: false });
  });

  it('leaves every action enabled before the backend has answered', () => {
    expect(repairActionState(undefined, 'ranger')).toEqual({ disabled: false });
  });
});
