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

import {TestBed, inject, async} from '@angular/core/testing';
import {HttpModule} from '@angular/http';
import {Observable} from 'rxjs/Observable';
import 'rxjs/add/operator/first';
import 'rxjs/add/operator/last';
import 'rxjs/add/operator/take';
import {StoreModule} from '@ngrx/store';
import {Store} from '@ngrx/store';
import {AppStore} from '@app/classes/models/store';
import {AppStateService, appState} from '@app/services/storage/app-state.service';
import {AuthService} from '@app/services/auth.service';
import {HttpClientService} from '@app/services/http-client.service';
import {RouterTestingModule} from '@angular/router/testing';
import {Routes} from '@angular/router';
import {Component} from '@angular/core';


import * as auth from '@app/store/reducers/auth.reducers';
import { isAuthorizedSelector } from '@app/store/selectors/auth.selectors';
import { LogInAction, LogOutAction, AuthorizedAction } from '@app/store/actions/auth.actions';

describe('AuthService', () => {

  const successResponse = {
      type: 'default',
      ok: true,
      url: '/',
      status: 200,
      statusText: 'OK',
      bytesLoaded: 100,
      totalBytes: 100,
      headers: null
    };
  const errorResponse = {
      type: 'error',
      ok: false,
      url: '/',
      status: 401,
      statusText: 'ERROR',
      bytesLoaded: 100,
      totalBytes: 100,
      headers: null
    };

  // Note: We add delay to help the isLoginInProgress test case.
  const httpServiceStub = {
    isError: false,
    request: function () {
      const isError = this.isError;
      return Observable.create(observer => observer.next(isError ? errorResponse : successResponse)).delay(1);
    },
    postFormData: function () {
      return this.request();
    },
    post: function () {
      return this.request();
    },
    get: function () {
      return this.request();
    }
  };

  beforeEach(() => {
    const testRoutes: Routes = [{
      path: 'login',
      component: Component,
      data: {
        breadcrumbs: 'login.title'
      }
    }];
    TestBed.configureTestingModule({
      imports: [
        HttpModule,
        StoreModule.provideStore({
          appState,
          auth: auth.reducer
        }),
        RouterTestingModule.withRoutes(testRoutes)
      ],
      providers: [
        AuthService,
        AppStateService,
        {provide: HttpClientService, useValue: httpServiceStub}
      ]
    });
  });

  it('should create service', inject([AuthService], (service: AuthService) => {
    expect(service).toBeTruthy();
  }));

  it('should return with Observable<Response> when login called', async(inject(
    [AuthService, Store],
    (authService: AuthService) => {
      const response = authService.login('test', 'test');
      expect(response instanceof Observable).toBe(true);
    }
  )));

  it('should return with Observable<Response> when logout called', async(inject(
    [AuthService, Store],
    (authService: AuthService) => {
      const response = authService.logout();
      expect(response instanceof Observable).toBe(true);
    }
  )));

  it('should return with Observable<Response> when checkAuthorizationState called', async(inject(
    [AuthService, Store],
    (authService: AuthService) => {
      const response = authService.checkAuthorizationState();
      expect(response instanceof Observable).toBe(true);
    }
  )));

});
