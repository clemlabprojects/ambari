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

import { TestBed, inject } from '@angular/core/testing';

import {
  getCommonTestingBedConfiguration,
  TranslationModules
} from '@app/test-config.spec';

import { AppStateService } from '@app/services/storage/app-state.service';

import {NotificationService} from '@modules/shared/services/notification.service';
import {NotificationsService} from 'angular2-notifications/src/notifications.service';

import { LogIndexFilterService } from './log-index-filter.service';
import { RouterTestingModule } from '@angular/router/testing';

describe('LogIndexFilterService', () => {
  beforeEach(() => {
    TestBed.configureTestingModule(getCommonTestingBedConfiguration({
      imports: [
        ...TranslationModules,
        RouterTestingModule,
      ],
      providers: [
        AppStateService,
        LogIndexFilterService,
        NotificationService,
        NotificationsService
      ]
    }));
  });

  it('should be created', inject([LogIndexFilterService], (service: LogIndexFilterService) => {
    expect(service).toBeTruthy();
  }));
});
