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
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';

import { Observable } from 'rxjs/Observable';
import { StoreModule } from '@ngrx/store';
import { EffectsModule } from '@ngrx/effects';

import { TranslationModules, MockHttpRequestModules } from '@app/test-config.spec';
import { AppStateService } from '@app/services/storage/app-state.service';
import { NotificationService } from '@modules/shared/services/notification.service';
import { NotificationsService } from 'angular2-notifications/src/notifications.service';
import { UserSettingsService } from '@app/services/user-settings.service';
import { HostsService, hosts } from '@app/services/storage/hosts.service';
import * as userSettings from '@app/store/reducers/user-settings.reducers';
import { NotificationEffects } from '@app/store/effects/notification.effects';
import { UserSettingsEffects } from '@app/store/effects/user-settings.effects';
import { TimeZoneMapInputComponent } from '@app/modules/shared/components/time-zone-map-input/time-zone-map-input.component';

import { UserSettingsComponent } from './user-settings.component';

describe('UserSettingsComponent', () => {
  let component: UserSettingsComponent;
  let fixture: ComponentFixture<UserSettingsComponent>;
  let queryParams = {};
  let queryParams$;


  beforeEach(async(() => {
    
    TestBed.configureTestingModule({
      imports: [ 
        ...TranslationModules,
        RouterTestingModule,
        FormsModule,
        ReactiveFormsModule,
        StoreModule.provideStore({
          hosts,
          userSettings: userSettings.reducer
        }),
        EffectsModule.run(UserSettingsEffects),
        EffectsModule.run(NotificationEffects)
      ],
      providers: [
        ...MockHttpRequestModules,
        AppStateService,
        NotificationsService,
        NotificationService,
        UserSettingsService
      ],
      declarations: [ UserSettingsComponent, TimeZoneMapInputComponent ],
      schemas: [ CUSTOM_ELEMENTS_SCHEMA ]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(UserSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

});
