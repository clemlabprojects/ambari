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
import {
  async,
  ComponentFixture,
  TestBed,
  fakeAsync
} from '@angular/core/testing';

import {
  getCommonTestingBedConfiguration,
  TranslationModules
} from '@app/test-config.spec';
import { StoreModule } from '@ngrx/store';

import {
  appState,
  AppStateService
} from '@app/services/storage/app-state.service';
import { NotificationService } from '@modules/shared/services/notification.service';
import { NotificationsService } from 'angular2-notifications/src/notifications.service';
import { LogsFilteringUtilsService } from '@app/services/logs-filtering-utils.service';

import { FilterHistoryManagerComponent } from './filter-history-manager.component';
import { FilterUrlParamChange } from '@app/classes/models/filter-url-param-change.interface';
import { Router, Routes, NavigationEnd } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

describe('FilterHistoryManagerComponent', () => {
  let component: FilterHistoryManagerComponent;
  let fixture: ComponentFixture<FilterHistoryManagerComponent>;
  let router: Router;
  const getValueLabelTestCases = {
    level: [{
      input: 'ERROR',
      expectation: 'Error'
    }, {
      input: 'ERROR,FATAL',
      expectation: 'Error, Fatal'
    }],
    levels: [{
      input: 'ERROR',
      expectation: 'Error'
    }, {
      input: 'ERROR,FATAL',
      expectation: 'Error, Fatal'
    }],
    log_message: [{
      input: 'Exception',
      expectation: '"Exception"'
    }],
    type: [{
      input: 'infra_solr',
      expectation: 'Infra Solr'
    }],
    components: [{
      input: 'infra_solr',
      expectation: 'Infra Solr'
    }]
  };
  const componentNameLabes = {
    infra_solr: 'Infra Solr'
  };

  const getParametersFromUrlTestCases = {
    'logs/serviceLogs': {},
    'logs/serviceLogs;a=1;b=2': {a: '1', b: '2'},
    'logs/serviceLogs;a=1;b=2?c=3': {a: '1', b: '2'}
  };

  const getParameterDifferencesFromUrlsTestCases = [{
    previousUrl: 'logs/serviceLogs;a=1',
    currentUrl: 'logs/serviceLogs;a=1;b=2',
    expectation: [{
      type: 'add',
      name: 'b',
      from: undefined,
      to: '2'
    }]
  }, {
    previousUrl: 'logs/serviceLogs;a=1;b=2',
    currentUrl: 'logs/serviceLogs;a=1',
    expectation: [{
      type: 'remove',
      name: 'b',
      from: '2',
      to: undefined
    }]
  }, {
    previousUrl: 'logs/serviceLogs;a=1',
    currentUrl: 'logs/serviceLogs;a=2',
    expectation: [{
      type: 'change',
      name: 'a',
      from: '1',
      to: '2'
    }]
  }];

  const extractParametersFromUrlSegmentGroupUseCases = [{
    caseLabel: 'Single level parameters',
    url: 'logs/serviceLogs;a=1;b=2',
    expectation: {
      a: '1',
      b: '2'
    }
  }, {
    caseLabel: 'Multi level parameters',
    url: 'logs;a=1/serviceLogs;b=2',
    expectation: {
      a: '1',
      b: '2'
    }
  }];

  const routes: Routes = [
    {
      path: 'logs/:activeTab',
      component: FilterHistoryManagerComponent
    }
  ];

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      ...getCommonTestingBedConfiguration({
        imports: [
          RouterTestingModule.withRoutes(routes),
          ...TranslationModules,
          StoreModule.provideStore({
            appState
          })
        ],
        providers: [
          AppStateService,
          NotificationsService,
          NotificationService,
          LogsFilteringUtilsService
        ],
        declarations: [FilterHistoryManagerComponent]
      }),
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();
  }));

  beforeEach(() => {
    router = TestBed.get(Router);
    fixture = TestBed.createComponent(FilterHistoryManagerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call the router`s `navigateByUrl` method when calling `undo` with a FilterUrlParamChange item', () => {
    const path = '/logs/serviceLogs;_fhi=0';
    const historyItem: FilterUrlParamChange = {
      currentPath: path
    };
    spyOn(router, 'navigateByUrl').and.callThrough();
    component.undo(historyItem);
    expect(router.navigateByUrl).toHaveBeenCalledWith(path);
  });

  it('should navigate to the currentPath when calling `undo` with a FilterUrlParamChange item', fakeAsync(() => {
    const path = '/logs/serviceLogs;_fhi=0';
    const historyItem: FilterUrlParamChange = {
      currentPath: path
    };
    router.events.filter(event => event instanceof NavigationEnd).first().subscribe((event) => {
      expect(router.url).toEqual(path);
    });
    component.undo(historyItem);
  }));

  it('should call the router`s `navigateByUrl` method when calling `redo` with a FilterUrlParamChange item', () => {
    const path = '/logs/serviceLogs;_fhi=0';
    const historyItem: FilterUrlParamChange = {
      currentPath: path
    };
    spyOn(router, 'navigateByUrl').and.callThrough();
    component.redo(historyItem);
    expect(router.navigateByUrl).toHaveBeenCalledWith(path);
  });

  it('should navigate to the currentPath when calling `redo` with a FilterUrlParamChange item', fakeAsync(() => {
    const path = '/logs/serviceLogs;_fhi=0';
    const historyItem: FilterUrlParamChange = {
      currentPath: path
    };
    router.events.filter(event => event instanceof NavigationEnd).first().subscribe((event) => {
      expect(router.url).toEqual(path);
    });
    component.redo(historyItem);
  }));

  it(
    'should call the router`s `navigateByUrl` method when calling `navigateToFilterUrlParamChangeItem with a FilterUrlParamChange item',
    () => {
    const path = '/logs/serviceLogs;_fhi=0';
    const historyItem: FilterUrlParamChange = {
      currentPath: path
    };
    spyOn(router, 'navigateByUrl').and.callThrough();
    component.navigateToFilterUrlParamChangeItem(historyItem);
    expect(router.navigateByUrl).toHaveBeenCalledWith(path);
  });

  it(
    'should navigate to the currentPath when calling `navigateToFilterUrlParamChangeItem` with a FilterUrlParamChange item',
    fakeAsync(() => {
    const path = '/logs/serviceLogs;_fhi=0';
    const historyItem: FilterUrlParamChange = {
      currentPath: path
    };
    router.events.filter(event => event instanceof NavigationEnd).first().subscribe((event) => {
      expect(router.url).toEqual(path);
    });
    component.navigateToFilterUrlParamChangeItem(historyItem);
  }));

  describe('testing `getValueLabel`', () => {
    Object.keys(getValueLabelTestCases).forEach((key) => {
      const cases: {input: any, expectation: any}[] = getValueLabelTestCases[key];
      cases.forEach((currentCase: {input: any, expectation: any}) => {
        it(`should give correct value label for ${key} field when the value is ${currentCase.input}`, () => {
          component.componentsLabelsLocalCopy$.next(componentNameLabes);
          const valueLabel = component.getValueLabel(key, currentCase.input);
          expect(valueLabel).toEqual(currentCase.expectation);
        });
      });
    });
  });

  describe('testing `getParametersFromUrl`', () => {
    Object.keys(getParametersFromUrlTestCases).forEach((url: string) => {
      const expectation = getParametersFromUrlTestCases[url];
      Object.keys(expectation).forEach((paramKey) => {
        it(`should parse parameter ${paramKey} with value ${expectation[paramKey]}`, () => {
          const result = component.getParametersFromUrl(url);
          expect(result[paramKey]).toEqual(expectation[paramKey]);
        });
      });
    });
  });

  describe('testing `getParameterDifferencesFromUrls', () => {
    getParameterDifferencesFromUrlsTestCases.forEach((useCase) => {
      describe(`should return with correct diff for ${useCase.previousUrl} vs ${useCase.currentUrl}`, () => {
        const expectation = useCase.expectation;
        expectation.forEach((expectationDiff) => {
          it(`should find difference ${expectationDiff.type} - ${expectationDiff.from} -> ${expectationDiff.to}`, fakeAsync(() => {
            const diff = component.getParameterDifferencesFromUrls(useCase.currentUrl, useCase.previousUrl, 'serviceLogs');
            const found = diff.some((foundDiff) => (
              foundDiff.type === expectationDiff.type
              && foundDiff.from === expectationDiff.from
              && foundDiff.to === expectationDiff.to
              && foundDiff.name === expectationDiff.name
            ));
            expect(found).toEqual(true);
          }));
        });
      });
    });
  });

  describe('testing `extractParametersFromUrlSegmentGroup`', () => {
    extractParametersFromUrlSegmentGroupUseCases.forEach((useCase) => {
      it(`should find parameters for ${useCase.caseLabel}`, () => {
        const urlSegmentGroup = router.parseUrl(useCase.url);
        const foundParameters = component.extractParametersFromUrlSegmentGroup(urlSegmentGroup.root);
        Object.keys(useCase.expectation).forEach((paramKey) => {
          expect(foundParameters[paramKey]).toEqual(useCase.expectation[paramKey]);
        });
      });
    });
  });

});
