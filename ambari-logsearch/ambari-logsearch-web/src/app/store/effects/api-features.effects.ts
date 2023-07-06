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
import { Store } from '@ngrx/store';
import 'rxjs/add/operator/mapTo';

import { AppStore } from '@app/classes/models/store';
import { HttpClientService } from '@app/services/http-client.service';

import { AddNotificationAction, NotificationActions } from '@app/store/actions/notification.actions';
import { NotificationType } from '@modules/shared/services/notification.service';

import { 
  ApiFeaturesActions,
  LoadSuccessApiFeaturesAction,
  LoadFailedApiFeaturesAction,
  SetApiFeaturesAction,
  ApiFeaturesActionTypes
} from '@app/store/actions/api-features.actions';
import { ApiFeatureSet } from '@app/store/reducers/api-features.reducers';

@Injectable()
export class ApiFeaturesEffects {
  constructor(
    private actions$: Actions,
    private store: Store<AppStore>,
    private httpClient: HttpClientService
  ) {}

  @Effect()
  loadAction: Observable<ApiFeaturesActions> = this.actions$
    .ofType(ApiFeaturesActionTypes.LOAD)
    .switchMap((action) => {
      return this.httpClient.get('apiFeatures').map((response) => {
        const responseBody = response.json();
        return response.ok ? new LoadSuccessApiFeaturesAction(responseBody) : new LoadFailedApiFeaturesAction(responseBody);
      });
    });

  @Effect()
  loadSuccessAction: Observable<ApiFeaturesActions> = this.actions$
    .ofType(ApiFeaturesActionTypes.LOAD_SUCCESS)
    .map((action: LoadSuccessApiFeaturesAction): ApiFeatureSet => action.payload)
    .map((apiFeatures: ApiFeatureSet) => new SetApiFeaturesAction(apiFeatures));
    
  @Effect()
  loadFailedAction: Observable<NotificationActions> = this.actions$
    .ofType(ApiFeaturesActionTypes.LOAD_FAILED)
    .mapTo(new AddNotificationAction({
      type: NotificationType.ERROR,
      message: 'apiFeatures.loadFailed'
    }));

}