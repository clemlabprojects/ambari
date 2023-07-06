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

import { Component, ViewChild } from '@angular/core';
import { Observable } from 'rxjs/Observable';
import 'rxjs/add/operator/finally';
import 'rxjs/add/operator/combineLatest';

import { Store } from '@ngrx/store';
import { AppStore } from '@app/classes/models/store';
import {
  isLoginInProgressSelector,
  isCheckingAuthStatusInProgressSelector,
  selectAuthMessage
} from '@app/store/selectors/auth.selectors';
import { LogInAction } from '@app/store/actions/auth.actions';


import { FormGroup } from '@angular/forms';


@Component({
  selector: 'login-form',
  templateUrl: './login-form.component.html',
  styleUrls: ['./login-form.component.less']
})
export class LoginFormComponent {

  username: string;

  password: string;

  authorizationMessage$: Observable<string> = this.store.select(selectAuthMessage);
  isLoginInProgress$: Observable<boolean> = this.store.select(isLoginInProgressSelector);
  isCheckingAuthStatusInProgress$: Observable<boolean> = this.store.select(isCheckingAuthStatusInProgressSelector);

  isInputDisabled$: Observable<boolean> = Observable.combineLatest(this.isLoginInProgress$, this.isCheckingAuthStatusInProgress$)
    .map(([loginInProgress, checkAuthInProgress]) => loginInProgress || checkAuthInProgress);

  errorMessage: string;

  @ViewChild('loginForm')
  loginForm: FormGroup;

  constructor(
    private store: Store<AppStore>
  ) {}

  login() {
    this.store.dispatch(new LogInAction({
      username: this.username,
      password: this.password
    }));
  }

}
