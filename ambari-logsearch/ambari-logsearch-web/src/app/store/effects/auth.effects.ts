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
import { Response } from '@angular/http';
import { Actions, Effect } from '@ngrx/effects';
import { Observable } from 'rxjs/Observable';
import { Router } from '@angular/router';

import 'rxjs/add/observable/of';
import 'rxjs/add/operator/map';
import 'rxjs/add/operator/do';
import 'rxjs/add/operator/switchMap';
import 'rxjs/add/operator/catch';

import { AuthService } from '@app/services/auth.service';
import {
  AuthActionTypes,
  LogInAction,
  AuthorizedAction,
  UnauthorizedAction,
  AuthorizationErrorAction,
  AuthorizationTimeoutAction,
  ForbiddenAction,
  LoggedOutAction,
  LogoutErrorAction,
  HttpAuthorizationErrorResponseAction
} from '../actions/auth.actions';

import { AddNotificationAction } from '@app/store/actions/notification.actions';
import { NotificationType } from '@modules/shared/services/notification.service';


@Injectable()
export class AuthEffects {

  @Effect()
  CheckAuthorizationStatus: Observable<any> = this.actions$
    .ofType(AuthActionTypes.CHECK_AUTHORIZATION_STATUS)
    .switchMap(() => {
      return this.authService.checkAuthorizationState()
        .map((response: Response) => {
          return response.ok ? new AuthorizedAction({response}) : new UnauthorizedAction({response});
        })
        .catch((error) => {
          return Observable.of(new UnauthorizedAction({error}));
        });
    });

  @Effect()
  LogIn: Observable<any> = this.actions$
    .ofType(AuthActionTypes.LOGIN)
    .map((action: LogInAction) => action.payload)
    .switchMap(payload => {
      return this.authService.login(payload.username, payload.password)
        .map((response: Response) => {
          let message = '';
          switch (response.status) {
            case 401 :
              message = 'authorization.error.unauthorized';
            break;
            case 403 :
              message = 'authorization.error.forbidden';
            break;
            case 419 :
              message = 'authorization.error.authorizationTimeout';
            break;
          }
          const nextPayload = { message, response, user: {username: payload.username} };
          return response.ok ? new AuthorizedAction(nextPayload) : (
            response.status === 401 ? new UnauthorizedAction(nextPayload) : (
            response.status === 403 ? new ForbiddenAction(nextPayload) : new AuthorizationErrorAction(nextPayload)
            )
          );
        })
        .catch((error) => {
          return Observable.of(new AuthorizationErrorAction({error: error}));
        });
    });

  @Effect({ dispatch: false })
  Authorized: Observable<any> = this.actions$
    .ofType(AuthActionTypes.AUTHORIZED)
    .do(() => {
      if (this.router.url === '/login') {
        const url = this.authService.redirectUrl || '/';
        if (typeof url === 'string') {
          this.router.navigateByUrl(url);
        } else if (Array.isArray(url)) {
          this.router.navigate(url);
        }
      }
    });

  @Effect()
  LogOut: Observable<any> = this.actions$
    .ofType(AuthActionTypes.LOGOUT)
    .switchMap(payload => {
      return this.authService.logout()
        .map((response: Response) => {
          return response.ok ? new LoggedOutAction() : new LogoutErrorAction({response});
        })
        .catch((error) => {
          return Observable.of(new LogoutErrorAction({error: error}));
        });
    });

  @Effect({ dispatch: false })
  LoggedOut: Observable<any> = this.actions$
    .ofType(AuthActionTypes.LOGGED_OUT)
    .do(() => {
      window.location.reload(true);
    });

  @Effect()
  AuthorizationError: Observable<any> = this.actions$
    .ofType(AuthActionTypes.AUTHORIZATION_ERROR)
    .map((action: AuthorizationErrorAction) => action.payload)
    .switchMap((payload) => {
      const response: Response = payload.response;
      let message = 'authorization.error.authorizationError';
      if (response) {
        const body = response.json();
        message = body.message || message;
      }
      return Observable.of(
        new AddNotificationAction({
          type: NotificationType.ERROR,
          message: message
        })
      );
    });

  @Effect()
  HttpAuthorizationErrorReponse: Observable<any> = this.actions$
    .ofType(AuthActionTypes.HTTP_AUTHORIZATION_ERROR_RESPONSE)
    .map((action: HttpAuthorizationErrorResponseAction) => action.payload)
    .switchMap(payload => {
      const response = payload.response;
      let action;
      switch (response.status) {
        case 401 :
          action = new LoggedOutAction({response});
        break;
        case 403 :
          action = new ForbiddenAction({response});
        break;
        case 419 :
          action = new AuthorizationTimeoutAction({response});
        break;
      }
      return Observable.of(action);
    });

  @Effect()
  Forbidden: Observable<any> = this.actions$
    .ofType(AuthActionTypes.FORBIDDEN)
    .map((action: ForbiddenAction) => action.payload.response)
    .switchMap((response: Response) => Observable.of(
      new AddNotificationAction({
        type: NotificationType.ERROR,
        message: 'authorization.error.forbidden'
      })
    ));

  @Effect()
  AuthorizationTimeoutAction: Observable<any> = this.actions$
  .ofType(AuthActionTypes.AUTHORIZATION_TIMEOUT)
  .map((action: AuthorizationTimeoutAction) => action.payload.response)
  .switchMap((response: Response) => Observable.of(
    new AddNotificationAction({
      type: NotificationType.ERROR,
      message: 'authorization.error.authorizationTimeout'
    })
  ));

  constructor(
    private actions$: Actions,
    private authService: AuthService,
    private router: Router
  ) {}

}
