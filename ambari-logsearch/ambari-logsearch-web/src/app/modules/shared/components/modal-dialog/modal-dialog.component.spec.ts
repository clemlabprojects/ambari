/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import {StoreModule} from '@ngrx/store';
import {AppStateService, appState} from '@app/services/storage/app-state.service';
import {
  getCommonTestingBedConfiguration, MockHttpRequestModules,
  TranslationModules
} from '@app/test-config.spec';

import {NotificationsService} from 'angular2-notifications/src/notifications.service';
import {NotificationService} from '@modules/shared/services/notification.service';

import * as auth from '@app/store/reducers/auth.reducers';
import { EffectsModule } from '@ngrx/effects';
import { AuthEffects } from '@app/store/effects/auth.effects';
import { NotificationEffects } from '@app/store/effects/notification.effects';

import { ModalDialogComponent } from './modal-dialog.component';
import { RouterTestingModule } from '@angular/router/testing';

describe('ModalDialogComponent', () => {
  let component: ModalDialogComponent;
  let fixture: ComponentFixture<ModalDialogComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule(getCommonTestingBedConfiguration({
      imports: [
        ...TranslationModules,
        RouterTestingModule,
        StoreModule.provideStore({
          appState,
          auth: auth.reducer
        }),
        EffectsModule.run(AuthEffects),
        EffectsModule.run(NotificationEffects)
      ],
      providers: [AppStateService, NotificationsService, NotificationService],
      declarations: [ ModalDialogComponent ]
    }))
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(ModalDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
