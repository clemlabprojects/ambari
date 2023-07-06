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

import { Response } from '@angular/http';
import { Action } from '@ngrx/store';


export enum AuthActionTypes {
  LOGIN = '[Auth] Login',
  AUTHORIZED = '[Auth] Authorized',
  UNAUTHORIZED = '[Auth] Unauthorized',
  AUTHORIZATION_ERROR = '[Auth] Authorization Error',
  FORBIDDEN = '[Auth] Forbidden',
  LOGOUT = '[Auth] Logout',
  LOGGED_OUT = '[Auth] Logged out',
  LOGOUT_ERROR = '[Auth] Logout error',
  CHECK_AUTHORIZATION_STATUS = '[Auth] Check status',
  AUTHORIZATION_TIMEOUT = '[Auth] Authorization Timeout',
  HTTP_AUTHORIZATION_ERROR_RESPONSE = '[Auth] HTTP Authorization Error Response'
}

export class LogInAction implements Action {
  readonly type = AuthActionTypes.LOGIN;
  constructor(public payload: any) {}
}

export class LogOutAction implements Action {
  readonly type = AuthActionTypes.LOGOUT;
  constructor() {}
}

export class AuthorizedAction implements Action {
  readonly type = AuthActionTypes.AUTHORIZED;
  constructor(public payload: any) {}
}

export class UnauthorizedAction implements Action {
  readonly type = AuthActionTypes.UNAUTHORIZED;
  constructor(public payload: any) {}
}

export class AuthorizationErrorAction implements Action {
  readonly type = AuthActionTypes.AUTHORIZATION_ERROR;
  constructor(public payload: any) {}
}

export class ForbiddenAction implements Action {
  readonly type = AuthActionTypes.FORBIDDEN;
  constructor(public payload: {response?: Response, [key: string]: any}) {}
}

export class LoggedOutAction implements Action {
  readonly type = AuthActionTypes.LOGGED_OUT;
  constructor(public payload?: any) {}
}

export class LogoutErrorAction implements Action {
  readonly type = AuthActionTypes.LOGOUT_ERROR;
  constructor(public payload: any) {}
}

export class CheckAuthorizationStatusAction implements Action {
  readonly type = AuthActionTypes.CHECK_AUTHORIZATION_STATUS;
  constructor() {}
}

export class AuthorizationTimeoutAction implements Action {
  readonly type = AuthActionTypes.AUTHORIZATION_TIMEOUT;
  constructor(public payload: any) {}
}

export class HttpAuthorizationErrorResponseAction implements Action {
  readonly type = AuthActionTypes.HTTP_AUTHORIZATION_ERROR_RESPONSE;
  constructor(public payload: {response: Response}) {}
}

export type AuthActions =
  | LogInAction
  | LogOutAction
  | AuthorizedAction
  | UnauthorizedAction
  | ForbiddenAction
  | LoggedOutAction
  | LogoutErrorAction
  | HttpAuthorizationErrorResponseAction
  | CheckAuthorizationStatusAction;
