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

import { User } from '../../classes/models/user';

import { AuthActionTypes, AuthActions } from '../actions/auth.actions';

export enum AuthorizationStatuses {
  UNAUTHORIZED = 'Unauthorized', // no authorization attempt yet
  NOT_AUTHORIZED = 'Not Authorized', // there were authorization attempt(s) but it was unsuccessful
  CHEKCING_AUTHORIZATION_STATUS = 'Checking Authorization Status', // checking if the user already authenticated (eg. refreshed the page)
  FORBIDDEN = 'Forbidden', // no access to Log Search
  LOGGING_IN = 'Logging In', // login in progress
  AUTHORIZED = 'Authorized', // authorized
  LOGGING_OUT = 'Logging Out', // logout in progress
  LOGGED_OUT = 'Logged Out', // logged out, the user was authorized before
  LOGOUT_ERROR = 'Logout Error', // logged out, the user was authorized before
  AUTHORIZATION_ERROR = 'Authorization Error' // there were some server error during the authorization
};

export interface State {
  status: AuthorizationStatuses; // the status of the authorization
  code?: number; // the last status code of the authorization response
  user?: User | null; // the user model from the app or from the server
  message?: string | null; // message from the server after the authentication attempt
}

export const initialState: State = {
  status: null,
  user: null
};

export function reducer(state = initialState, action: AuthActions): State {
switch (action.type) {
    case AuthActionTypes.LOGIN: {
      return {
        ...state,
        status: AuthorizationStatuses.LOGGING_IN
      };
    }
    case AuthActionTypes.CHECK_AUTHORIZATION_STATUS: {
      return {
        ...state,
        status: AuthorizationStatuses.CHEKCING_AUTHORIZATION_STATUS
      };
    }
    case AuthActionTypes.AUTHORIZED: {
      const payload = action.payload;
      return {
        ...state,
        status: AuthorizationStatuses.AUTHORIZED,
        message: payload.message || '',
        user: payload.user || null
      };
    }
    case AuthActionTypes.UNAUTHORIZED: {
      const payload = action.payload;
      return {
        ...state,
        message: payload.message || '',
        status: AuthorizationStatuses.UNAUTHORIZED
      };
    }
    case AuthActionTypes.FORBIDDEN: {
      const payload = action.payload;
      return {
        ...state,
        status: AuthorizationStatuses.FORBIDDEN,
        message: payload.message || ''
      };
    }
    case AuthActionTypes.LOGOUT: {
      return {
        ...state,
        status: AuthorizationStatuses.LOGGING_OUT
      };
    }
    case AuthActionTypes.LOGGED_OUT: {
      return {
        ...state,
        status: AuthorizationStatuses.LOGGED_OUT
      };
    }
    case AuthActionTypes.LOGOUT_ERROR: {
      const payload = action.payload;
      return {
        ...state,
        status: AuthorizationStatuses.LOGOUT_ERROR,
        message: payload.message || ''
      };
    }
    default: {
      return state;
    }
  }
};

export const getStatus = (state: State): AuthorizationStatuses => state.status;
export const getMessage = (state: State): string => (state.message || '');
export const isAuthorized = (status: AuthorizationStatuses): boolean => AuthorizationStatuses.AUTHORIZED === status;
export const isLoginInProgress = (status: AuthorizationStatuses): boolean => AuthorizationStatuses.LOGGING_IN === status;
export const isLoggedOut = (status: AuthorizationStatuses): boolean => AuthorizationStatuses.LOGGED_OUT === status;
export const isCheckingAuthInProgress = (status: AuthorizationStatuses): boolean => (
  AuthorizationStatuses.CHEKCING_AUTHORIZATION_STATUS === status
);
