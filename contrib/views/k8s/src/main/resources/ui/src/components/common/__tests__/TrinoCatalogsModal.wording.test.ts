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

jest.mock('../../../api/client');

import { catalogSaveWording } from '../TrinoCatalogsModal';

describe('catalogSaveWording', () => {
  it('says a catalog saved to a GitOps release is committed to Git', () => {
    const wording = catalogSaveWording({ deploymentMode: 'FLUX_GITOPS' });
    expect(wording.gitOps).toBe(true);
    expect(wording.checkbox).toBe('commit to Git (survives restarts once Flux applies it)');
    expect(wording.saved('pg', { committedToGit: true, commandId: 'op-1' }))
      .toBe("Catalog 'pg' committed to Git — Flux applies it; follow the commit in Background operations");
    expect(wording.dropPersisted).toMatch(/removed from the release values in Git/);
  });

  it('asks the server to forget every catalog dropped from a GitOps release', () => {
    // A catalog committed to Git that Flux has not applied yet is listed as runtime-only (not persisted): dropped
    // only live, it would come back once Flux applies that commit.
    const wording = catalogSaveWording({ deploymentMode: 'FLUX_GITOPS' });
    expect(wording.forgetOnDrop({ persisted: false })).toBe(true);
    expect(wording.forgetOnDrop({ persisted: true })).toBe(true);
    expect(wording.dropUnsaved).toMatch(/Refused while Git holds a change of the release that Flux has not applied/);
  });

  it('forgets only the catalogs saved to a release deployed with Helm', () => {
    const wording = catalogSaveWording({ deploymentMode: 'DIRECT_HELM' });
    expect(wording.forgetOnDrop({ persisted: false })).toBe(false);
    expect(wording.forgetOnDrop({ persisted: true })).toBe(true);
    expect(wording.dropUnsaved).toBe('Dropped in Trino now.');
  });

  it('keeps "save to release" for a release deployed with Helm', () => {
    const wording = catalogSaveWording({ deploymentMode: 'DIRECT_HELM' });
    expect(wording.gitOps).toBe(false);
    expect(wording.checkbox).toBe('save to release (survives restarts)');
    expect(catalogSaveWording(null).checkbox).toBe('save to release (survives restarts)');
  });
});
