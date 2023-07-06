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
import 'rxjs/add/operator/skip';
import 'rxjs/add/operator/mapTo';
import { Store } from '@ngrx/store';

import { AppStore } from '@app/classes/models/store';
import { HttpClientService } from '@app/services/http-client.service';

import { selectUserSettingsState } from '@app/store/selectors/user-settings.selectors';
import { 
  UserSettingsActions,
  LoadUserSettingsAction,
  LoadUserSettingsSuccessAction,
  LoadUserSettingsFailedAction,
  SetUserSettingsAction,
  SaveUserSettingsAction,
  SaveUserSettingsSuccessAction,
  SaveUserSettingsFailedAction,
  UserSettingsActionTypes 
} from '@app/store/actions/user-settings.actions';
import { AuthActionTypes } from '@app/store/actions/auth.actions';
import { UserSettingsState } from '@app/store/reducers/user-settings.reducers';
import { UserSettingsService } from '@app/services/user-settings.service';

import { AddNotificationAction, NotificationActions } from '@app/store/actions/notification.actions';
import { NotificationType } from '@modules/shared/services/notification.service';

@Injectable()
export class UserSettingsEffects {
  constructor(
    private actions$: Actions,
    private store: Store<AppStore>,
    private httpClient: HttpClientService,
    private userSettingsService: UserSettingsService
  ) {
    this.store.select(selectUserSettingsState).skip(2).subscribe((settings) => {
      this.onUserSettingsStateChanged(settings);
    });
  }

  onUserSettingsStateChanged = (userSettingsState) => {
    this.store.dispatch(new SaveUserSettingsAction(userSettingsState));
  }

  @Effect()
  authorizedAction: Observable<UserSettingsActions> = this.actions$
    .ofType(AuthActionTypes.AUTHORIZED)
    .mapTo( new LoadUserSettingsAction() );
  
  @Effect()
  loadAction: Observable<UserSettingsActions> = this.actions$
    .ofType(UserSettingsActionTypes.LOAD)
    .switchMap((action) => {
      return this.httpClient.get('userSettings').map((response) => {
        const responseBody = response.json();
        const state = this.userSettingsService.parseMetaDataToUserSettingsState(responseBody);
        return response.ok ? new LoadUserSettingsSuccessAction(state) : new LoadUserSettingsFailedAction(responseBody);
      });
    });
  
  @Effect()
  loadSuccessAction: Observable<UserSettingsActions> = this.actions$
    .ofType(UserSettingsActionTypes.LOAD_SUCCESS)
    .map((userSettings: LoadUserSettingsSuccessAction) => new SetUserSettingsAction(userSettings.payload));
    
  @Effect()
  saveAction: Observable<UserSettingsActions> = this.actions$
    .ofType(UserSettingsActionTypes.SAVE)
    .map((action) => {
      const settings: UserSettingsState = action.payload;
      const metaData = Object.keys(settings).reduce((currentMetaData, key: string) => ([
        ...currentMetaData,
        {
          name: key,
          value: settings[key].toString(),
          type: 'user_settings'
        }
      ]), []);
      return metaData;
    })
    .switchMap((userSettingsMetaData) => {
      return this.httpClient.post('userSettings', userSettingsMetaData).map((response) => {
        const result: UserSettingsState = <UserSettingsState>(response.json());
        return response.ok ? new SaveUserSettingsSuccessAction(result) : new SaveUserSettingsFailedAction(result);
      });
    });

    @Effect()
    saveSuccessAction: Observable<NotificationActions> = this.actions$
      .ofType(UserSettingsActionTypes.SAVE_SUCCESS)
      .map(() => new AddNotificationAction({
        type: NotificationType.SUCCESS,
        message: 'userSettings.saveAction.success'
      }));
    
    @Effect()
    saveFailedAction: Observable<NotificationActions> = this.actions$
      .ofType(UserSettingsActionTypes.SAVE_FAILED)
      .map(() => new AddNotificationAction({
        type: NotificationType.ERROR,
        message: 'userSettings.saveAction.error'
      }));

}