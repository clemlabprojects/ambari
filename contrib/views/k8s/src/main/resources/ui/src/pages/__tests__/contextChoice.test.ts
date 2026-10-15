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

import { contextChoiceNeeded } from '../contextChoice';

// A deploy wired to the wrong platform takes its Kerberos realm, Ranger and Hive from it: with a choice to make, the
// wizard does not make it silently.
describe('contextChoiceNeeded', () => {
  it('asks on a new install with several contexts', () => {
    expect(contextChoiceNeeded(false, 2)).toBe(true);
  });
  it('does not ask with one context or none, nor on an upgrade', () => {
    expect(contextChoiceNeeded(false, 1)).toBe(false);
    expect(contextChoiceNeeded(false, 0)).toBe(false);
    expect(contextChoiceNeeded(true, 3)).toBe(false);
  });
});
