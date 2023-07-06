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

import {async, ComponentFixture, TestBed} from '@angular/core/testing';
import {CUSTOM_ELEMENTS_SCHEMA} from '@angular/core';
import {StoreModule} from '@ngrx/store';
import {TooltipModule} from 'ngx-bootstrap';
import {MockHttpRequestModules, TranslationModules} from '@app/test-config.spec';
import {AppSettingsService, appSettings} from '@app/services/storage/app-settings.service';
import {AppStateService, appState} from '@app/services/storage/app-state.service';
import {ClustersService, clusters} from '@app/services/storage/clusters.service';
import {ComponentsService, components} from '@app/services/storage/components.service';
import {AuditLogsService, auditLogs} from '@app/services/storage/audit-logs.service';
import {AuditLogsFieldsService, auditLogsFields} from '@app/services/storage/audit-logs-fields.service';
import {AuditLogsGraphDataService, auditLogsGraphData} from '@app/services/storage/audit-logs-graph-data.service';
import {ServiceLogsService, serviceLogs} from '@app/services/storage/service-logs.service';
import {ServiceLogsFieldsService, serviceLogsFields} from '@app/services/storage/service-logs-fields.service';
import {
  ServiceLogsHistogramDataService, serviceLogsHistogramData
} from '@app/services/storage/service-logs-histogram-data.service';
import {HostsService, hosts} from '@app/services/storage/hosts.service';
import {ServiceLogsTruncatedService, serviceLogsTruncated} from '@app/services/storage/service-logs-truncated.service';
import {TabsService, tabs} from '@app/services/storage/tabs.service';
import {UtilsService} from '@app/services/utils.service';
import {LogsContainerService} from '@app/services/logs-container.service';
import {TabsComponent} from '@app/components/tabs/tabs.component';

import {LogsContainerComponent} from './logs-container.component';
import {ClusterSelectionService} from '@app/services/storage/cluster-selection.service';
import {RouterTestingModule} from '@angular/router/testing';
import {RoutingUtilsService} from '@app/services/routing-utils.service';
import {LogsFilteringUtilsService} from '@app/services/logs-filtering-utils.service';
import {NotificationService} from '@modules/shared/services/notification.service';
import {NotificationsService} from 'angular2-notifications/src/notifications.service';

import {LogsStateService} from '@app/services/storage/logs-state.service';

import * as auth from '@app/store/reducers/auth.reducers';
import * as userSettings from '@app/store/reducers/user-settings.reducers';
import { AuthService } from '@app/services/auth.service';
import { EffectsModule } from '@ngrx/effects';
import { AuthEffects } from '@app/store/effects/auth.effects';
import { UserSettingsEffects } from '@app/store/effects/user-settings.effects';
import { NotificationEffects } from '@app/store/effects/notification.effects';
import { UserSettingsService } from '@app/services/user-settings.service';

describe('LogsContainerComponent', () => {
  let component: LogsContainerComponent;
  let fixture: ComponentFixture<LogsContainerComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [
        LogsContainerComponent,
        TabsComponent
      ],
      imports: [
        RouterTestingModule,
        StoreModule.provideStore({
          appSettings,
          appState,
          clusters,
          components,
          auditLogs,
          auditLogsFields,
          auditLogsGraphData,
          serviceLogs,
          serviceLogsFields,
          serviceLogsHistogramData,
          tabs,
          hosts,
          serviceLogsTruncated,
          auth: auth.reducer,
          userSettings: userSettings.reducer
        }),
        EffectsModule.run(AuthEffects),
        EffectsModule.run(UserSettingsEffects),
        EffectsModule.run(NotificationEffects),
        ...TranslationModules,
        TooltipModule.forRoot(),
      ],
      providers: [
        ...MockHttpRequestModules,
        AppSettingsService,
        AppStateService,
        ClustersService,
        ComponentsService,
        AuditLogsService,
        AuditLogsFieldsService,
        AuditLogsGraphDataService,
        ServiceLogsService,
        ServiceLogsFieldsService,
        ServiceLogsHistogramDataService,
        HostsService,
        ServiceLogsTruncatedService,
        TabsService,
        UtilsService,
        LogsContainerService,
        ClusterSelectionService,
        RoutingUtilsService,
        LogsFilteringUtilsService,
        NotificationsService,
        NotificationService,
        LogsStateService,
        AuthService,
        UserSettingsService
      ],
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    })
    .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(LogsContainerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create component', () => {
    expect(component).toBeTruthy();
  });

  it('totalEventsFoundMessageParams should provide total count number', () => {
    expect(Object.keys(component.totalEventsFoundMessageParams)).toContain('totalCount');
  });

});
