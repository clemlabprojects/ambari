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

import { Injectable } from '@angular/core';
import { Actions, Effect } from '@ngrx/effects';
import { Observable } from 'rxjs/Observable';
import { Response } from '@angular/http';

import { AuditLogReposActionTypes, SetAuditLogReposAction } from '../actions/audit-log-repos.actions';
import { HttpClientService } from '@app/services/http-client.service';
import { AuditLogRepo } from '../reducers/audit-log-repos.reducers';

@Injectable()
export class AuditLogReposEffects {

  @Effect()
  LoadAuditLogRepos: Observable<any> = this.actions$
    .ofType(AuditLogReposActionTypes.LOAD)
    .switchMap((): Observable<SetAuditLogReposAction> => {
      return this.httpClientService.get('auditLogsComponents')
        .map((response: Response): SetAuditLogReposAction => {
          const result = response.json();
          const components: AuditLogRepo[] = Object.keys(result).reduce(
            (auditLogsComponents: AuditLogRepo[], componentName: string): AuditLogRepo[] => {
              return [
                ...auditLogsComponents,
                {
                  name: componentName,
                  label: result[componentName]
                }
              ];
            },
            []
          );
          return new SetAuditLogReposAction(components);
        });
    });

  constructor(
    private actions$: Actions,
    private httpClientService: HttpClientService
  ) {}

}
