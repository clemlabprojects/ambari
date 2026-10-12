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

import { waitingForMerge } from '../operationOutcome';

describe('waitingForMerge', () => {
  it('reads what a GitOps deploy waiting for a merge ended with', () => {
    expect(waitingForMerge({
      result: {
        waitingForMerge: true,
        message: 'Waiting for branch flux-1 of https://git.example.org/gitops.git to be merged into branch main.',
        pullRequestUrl: 'https://git.example.org/gitops/pull/3',
      },
    })).toEqual({
      message: 'Waiting for branch flux-1 of https://git.example.org/gitops.git to be merged into branch main.',
      pullRequestUrl: 'https://git.example.org/gitops/pull/3',
    });
  });

  it('is null for an operation that waits for nothing', () => {
    expect(waitingForMerge(null)).toBeNull();
    expect(waitingForMerge({ result: undefined })).toBeNull();
    expect(waitingForMerge({ result: { waitingForMerge: false, message: 'x' } })).toBeNull();
  });

  it('offers only a web link to the pull request', () => {
    const pending = waitingForMerge({ result: { waitingForMerge: true, pullRequestUrl: 'javascript:alert(1)' } });
    expect(pending?.pullRequestUrl).toBeUndefined();
    expect(pending?.message).toMatch(/waits for a merge/);
  });
});
