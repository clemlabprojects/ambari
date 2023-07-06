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

import { Observable } from 'rxjs/Observable';

import { HttpClientService } from '@app/services/http-client.service';
import { AppStateService } from '@app/services/storage/app-state.service';
import { Subscription } from 'rxjs/Subscription';

/**
 * This service meant to be a single place where the authorization should happen.
 */
@Injectable()
export class AuthService {

  private subscriptions: Subscription[] = [];

  /**
   * A string set by any service or component (mainly from AuthGuard service) to redirect the application after the
   * authorization done.
   * @type string
   */
  redirectUrl: string | string[];

  constructor(
    private httpClient: HttpClientService,
    private appState: AppStateService
  ) {}

  /**
   * The single entry point to request a login action.
   * @param {string} username
   * @param {string} password
   * @returns {Observable<Response>}
   */
  login(username: string, password: string): Observable<Response> {
    return this.httpClient.postFormData('login', {
      username: username,
      password: password
    });
  }

  /**
   * The single unique entry point to request a logout action
   * @returns {Observable<Response>}
   */
  logout(): Observable<Response> {
    return this.httpClient.get('logout');
  }

  checkAuthorizationState(): Observable<Response> {
    return this.httpClient.get('status');
  }

}
