import { Component, OnInit, OnDestroy, Input } from '@angular/core';
import { Subject } from 'rxjs/Subject';
import { Observable } from 'rxjs/Observable';
import { Store } from '@ngrx/store';
import { AppStore } from '@app/classes/models/store';
import {
  selectActiveFilterHistoryChangesUndoItems,
  selectActiveFilterHistoryChangesRedoItems,
  selectActiveFilterHistoryChanges,
  selectActiveFilterHistoryChangeIndex
} from '@app/store/selectors/filter-history.selectors';
import { FilterUrlParamChange } from '@app/classes/models/filter-url-param-change.interface';
import { Router, UrlTree, UrlSegmentGroup } from '@angular/router';
import {
  LogsFilteringUtilsService,
  defaultUrlParamsForFiltersByLogsType,
  UrlParamDifferences,
  UrlParamsDifferenceType
} from '@app/services/logs-filtering-utils.service';
import { selectActiveLogsType } from '@app/store/selectors/app-state.selectors';
import { LogsType } from '@app/classes/string';
import { TranslateService } from '@ngx-translate/core';
import { selectComponentsLabels } from '@app/store/selectors/components.selectors';
import { selectDefaultAuditLogsFields } from '@app/store/selectors/audit-logs-fields.selectors';
import { selectServiceLogsFieldState } from '@app/store/selectors/service-logs-fields.selectors';
import { BehaviorSubject } from 'rxjs/BehaviorSubject';
import { ListItem } from '@app/classes/list-item';

import * as moment from 'moment';
import { SearchBoxParameter } from '@app/classes/filtering';
import { LogField } from '@app/classes/object';

export const urlParamsActionType = {
  clusters: 'multiple',
  timeRange: 'single',
  components: 'multiple',
  levels: 'multiple',
  hosts: 'multiple',
  sortingKey: 'single',
  sortingType: 'single',
  pageSize: 'single',
  page: 'single',
  query: 'query',
  users: 'multiple'
};

@Component({
  selector: 'filter-history-manager',
  templateUrl: './filter-history-manager.component.html',
  styleUrls: ['./filter-history-manager.component.less']
})
export class FilterHistoryManagerComponent implements OnInit, OnDestroy {

  @Input()
  labelSeparator = ' | ';

  activeLogsType$: Observable<LogsType> = this.store.select(selectActiveLogsType);

  componentsLabels$: Observable<{[key: string]: string}> = this.store.select(selectComponentsLabels);
  componentsLabelsLocalCopy$: BehaviorSubject<{[key: string]: string}> = new BehaviorSubject({});

  activeHistoryChangeIndex$: Observable<number> = this.store.select(selectActiveFilterHistoryChangeIndex);

  activeHistoryItems$: Observable<FilterUrlParamChange[]> = this.store.select(selectActiveFilterHistoryChanges);
  hasActiveHistoryItems$: Observable<boolean> = this.activeHistoryItems$
    .map(items => items && items.length > 0).startWith(false);
  activeHistoryItemLabels$: Observable<{[key: string]: string}[]> = Observable.combineLatest(
    this.activeHistoryItems$,
    this.activeLogsType$,
    this.componentsLabels$ // this is just to recalculate the labels when the components arrived
  ).map(result => this.mapHistoryItemsToHistoryItemLabels(result));
  activeHistoryListItems$: Observable<ListItem[]> = Observable.combineLatest(
    this.activeHistoryItemLabels$.map((items) => this.mapHistoryItemLabelsToListItems(items, this.labelSeparator)),
    this.store.select(selectActiveFilterHistoryChangeIndex)
  ).map(([listItems, changeIndex]: [ListItem[], number]): ListItem[] => listItems.map((item, index) => {
    item.cssClass = index === changeIndex ? 'active' : (index === 0 ? 'initial' : '');
    return item;
  }));

  activeUndoHistoryItems$: Observable<FilterUrlParamChange[]> = this.store.select(selectActiveFilterHistoryChangesUndoItems);
  hasActiveUndoHistoryItems$: Observable<boolean> = this.activeUndoHistoryItems$
    .map(items => items && items.length > 0).startWith(false);
  activeUndoHistoryListItems$: Observable<ListItem[]> = Observable.combineLatest(
    this.activeHistoryListItems$,
    this.store.select(selectActiveFilterHistoryChangeIndex)
  ).map(([listItems, activeChangeIndex]: [ListItem[], number]): ListItem[] => listItems.slice(0, activeChangeIndex).reverse());

  activeRedoHistoryItems$: Observable<FilterUrlParamChange[]> = this.store.select(selectActiveFilterHistoryChangesRedoItems);
  hasActiveRedoHistoryItems$: Observable<boolean> = this.activeRedoHistoryItems$
    .map(items => items && items.length > 0).startWith(false);
  activeRedoHistoryListItems$: Observable<ListItem[]> = Observable.combineLatest(
    this.activeHistoryListItems$,
    this.store.select(selectActiveFilterHistoryChangeIndex)
  ).map(([listItems, activeChangeIndex]: [ListItem[], number]): ListItem[] => listItems.slice(activeChangeIndex + 1));

  activeQueryFieldsLabels$: Observable<{[key: string]: string}> = Observable.combineLatest(
    this.store.select(selectServiceLogsFieldState),
    this.store.select(selectDefaultAuditLogsFields),
    this.activeLogsType$
  ).map(
    (
      [serviceLogsFields, auditLogsFields, activeLogsType]: [LogField[], LogField[], LogsType]
    ) => activeLogsType === 'serviceLogs' ? serviceLogsFields : auditLogsFields
  ).map(
    (fields: LogField[]) => fields ? fields.reduce(
      (fieldLabels: {[key: string]: string}, field: LogField): {[key: string]: string} => ({
        ...fieldLabels,
        [field.name]: field.label || field.name
      }),
      {}
    ) : []
  );
  activeQueryFieldsLocalCopy$: BehaviorSubject<{[key: string]: string}> = new BehaviorSubject({});

  destroyed$ = new Subject();

  constructor(
    private store: Store<AppStore>,
    private router: Router,
    private logsFilteringUtilsService: LogsFilteringUtilsService,
    private translateService: TranslateService
  ) { }

  ngOnInit() {
    this.componentsLabels$.takeUntil(this.destroyed$).subscribe(componentsLabels => this.componentsLabelsLocalCopy$.next(componentsLabels));
    this.activeQueryFieldsLabels$.takeUntil(this.destroyed$).subscribe(fieldLabels => this.activeQueryFieldsLocalCopy$.next(fieldLabels));

    this.activeHistoryItemLabels$.takeUntil(this.destroyed$).map((historyLabels) => {
      return historyLabels.map((historyLabel) => {
        return {
          value: historyLabel.url,
          label: Object.keys(historyLabel.labels).map((url) => historyLabel.labels[url]).join(this.labelSeparator)
        };
      });
    });
  }

  ngOnDestroy() {
    this.destroyed$.next(true);
  }

  onListItemClick(item: ListItem) {
    this.navigateToFilterUrlParamChangeItem({
      currentPath: item.value
    });
  }

  navigateToFilterUrlParamChangeItem = (item: FilterUrlParamChange) => {
    if (item) {
      this.router.navigateByUrl(item.currentPath);
    }
  }

  undo(item?: FilterUrlParamChange): void {
    ( item ?
      Observable.of(item)
      : this.activeUndoHistoryItems$.map((changes: FilterUrlParamChange[]) => changes[changes.length - 1])
    ).first().subscribe(this.navigateToFilterUrlParamChangeItem);
  }

  redo(item?) {
    ( item ?
      Observable.of(item)
      : this.activeRedoHistoryItems$.map((changes: FilterUrlParamChange[]) => changes[0])
    ).first().subscribe(this.navigateToFilterUrlParamChangeItem);
  }

  getValueLabel(paramName, value) {
    switch (paramName) {
      case 'level':
      case 'levels': {
        return value.toLowerCase().split(',').map(level => level[0].toUpperCase() + level.slice(1)).join(', ');
      }
      case 'log_message': {
        return `"${value}"`;
      }
      case 'type': // query
      case 'components': {
        const componentLabels = this.componentsLabelsLocalCopy$.getValue();
        return value.split(/,/g).map((component) => `${componentLabels[component] || component}`).join(', ');
      }
      default: {
        return value;
      }
    }
  }

  private _getMultipleUrlParamDifferenceLabel(difference: UrlParamDifferences): string {

    const fieldLabelTranslateKey: string =  /^timeRange/.test(difference.name) ? 'timeRange' : difference.name;
    const fieldLabel: string = this.translateService.instant(`filterHistory.paramNames.${fieldLabelTranslateKey}`);

    const actionLabelTranslateKey: UrlParamsDifferenceType = difference.to ? UrlParamsDifferenceType.CHANGE : UrlParamsDifferenceType.CLEAR;

    const valueLabel = difference.to ? this.getValueLabel(fieldLabelTranslateKey, difference.to) : '';

    return this.translateService.instant(`filterHistory.${urlParamsActionType[difference.name]}.changeLabel.${actionLabelTranslateKey}`, {
      fieldLabel,
      valueLabel
    });
  }

  private _getTimeRangeUrlParamDifferenceLabel(
    differences: UrlParamDifferences[],
    parameters: {[key: string]: any},
    dateTimeFormat: string
  ): string | undefined {

    let timeRangeTypeValue: string = parameters.timeRangeType.toLowerCase();

    if (!timeRangeTypeValue) {
      return undefined;
    }

    const timeRangeUnitValue: string = parameters.timeRangeUnit;

    const timeRangeIntervalValue = parameters.timeRangeInterval;

    let valueLabel: string;
    const typeLabel = this.translateService.instant(`filterHistory.timeRange.type.${timeRangeTypeValue}`);
    const unitLabel = this.translateService.instant(`filterHistory.timeRange.unit.${timeRangeUnitValue}`);
    const fieldLabel = this.translateService.instant(`filterHistory.paramNames.timeRange`);

    if (timeRangeTypeValue.toLowerCase() === 'custom') {
      const timeRangeStart = differences.find(diff => diff.name === 'timeRangeStart');
      const timeRangeStartValue: string = timeRangeStart && moment(timeRangeStart.to).format(dateTimeFormat);
      const timeRangeEnd = differences.find(diff => diff.name === 'timeRangeEnd');
      const timeRangeEndValue: string = timeRangeEnd && moment(timeRangeEnd.to).format(dateTimeFormat);
      valueLabel = this.translateService.instant(`filterHistory.timeRange.valueLabel.${timeRangeTypeValue}`, {
        valueStart: timeRangeStartValue,
        valueEnd: timeRangeEndValue
      });
    } else {
      if (timeRangeTypeValue.toLowerCase() === 'current' && timeRangeUnitValue === 'd') {
        timeRangeTypeValue = 'today';
      }
      if (timeRangeTypeValue.toLowerCase() === 'past' && timeRangeUnitValue === 'd') {
        timeRangeTypeValue = 'yesterday';
      }
      valueLabel = this.translateService.instant(`filterHistory.timeRange.valueLabel.${timeRangeTypeValue}`, {
        typeLabel,
        unitLabel: parseInt(timeRangeIntervalValue, 10) > 1 ? unitLabel[1] : unitLabel[0],
        value: timeRangeIntervalValue || ''
      });
    }

    return this.translateService.instant(`filterHistory.timeRange.changeLabel`, { valueLabel, fieldLabel });
  }

  private _getQueryUrlParamDifferenceLabel(query): string | undefined {
    const fromQuery: SearchBoxParameter[] | null = query.from ? JSON.parse(query.from) : null;
    const toQuery: SearchBoxParameter[] | null = query.to ? JSON.parse(query.to) : null;
    let addedQueries, removedQueries;

    if (fromQuery && !toQuery) {
      return this.translateService.instant(`filterHistory.query.changeLabel.clear`);
    } else if (!fromQuery && toQuery) {
      addedQueries = toQuery;
    } else {
      addedQueries = toQuery.filter((queryItem) => fromQuery.findIndex((fromQueryItem) => (
        fromQueryItem.name === queryItem.name && fromQueryItem.value === queryItem.value
      )));
      removedQueries = fromQuery.filter((queryItem) => toQuery.findIndex((toQueryItem) => (
        toQueryItem.name === queryItem.name && toQueryItem.value === queryItem.value
      )));
    }

    const addedLabels = addedQueries ? addedQueries.reduce((labels: string[], addQuery: SearchBoxParameter): string[] => {
      const queryLabel = this.translateService.instant('filterHistory.query.changeLabel.add', {
        queryType: this.translateService.instant(`filterHistory.query.type.${addQuery.isExclude ? 'exclude' : 'include'}`),
        fieldLabel: this.activeQueryFieldsLocalCopy$.getValue()[addQuery.name] || addQuery.name,
        valueLabel: this.getValueLabel(addQuery.name, addQuery.value)
      });
      return queryLabel ? [...labels, queryLabel] : labels;
    }, []) : [];

    const removedLabels = removedQueries ? removedQueries.reduce((labels: string[], removedQuery: SearchBoxParameter): string[] => {
      const queryLabel = this.translateService.instant('filterHistory.query.changeLabel.remove', {
        queryType: this.translateService.instant(`filterHistory.query.type.${removedQuery.isExclude ? 'exclude' : 'include'}`),
        fieldLabel: this.activeQueryFieldsLocalCopy$.getValue()[removedQuery.name] || removedQuery.name,
        valueLabel: this.getValueLabel(removedQuery.name, removedQuery.value)
      });
      return queryLabel ? [...labels, queryLabel] : labels;
    }, []) : [];

    return [...addedLabels, ...removedLabels].join(this.labelSeparator);
  }

  extractParametersFromUrlSegmentGroup(group: UrlSegmentGroup): {[key: string]: string} {
    return {
      ...group.segments.reduce((segmentParams, segment) => ({...segmentParams, ...segment.parameters}), {}),
      ...Object.keys(group.children).reduce(
        (segmentsParams, key): {[key: string]: string} => {
          return {
            ...segmentsParams,
            ...this.extractParametersFromUrlSegmentGroup(group.children[key])
          };
        },
        {}
      )
    };
  }

  getParametersFromUrl(url: string): {[key: string]: string} {
    const urlTree: UrlTree = this.router.parseUrl(url);
    return this.extractParametersFromUrlSegmentGroup(urlTree.root);
  }

  getParameterDifferencesFromUrls(currentPath: string, previousPath: string, logsType: LogsType): UrlParamDifferences[] {
    const currentParameters = this.getParametersFromUrl(currentPath);
    const previousParameters = previousPath ? this.getParametersFromUrl(previousPath) : {};
    return this.logsFilteringUtilsService.getUrlParamsDifferences(
      {
        ...defaultUrlParamsForFiltersByLogsType[logsType],
        ...previousParameters
      },
      {
        ...defaultUrlParamsForFiltersByLogsType[logsType],
        ...currentParameters
      }
    );
  }

  getHistoryItemChangeLabels(
    item: FilterUrlParamChange,
    logsType:  LogsType,
    isInitial: boolean
  ): {url: string, labels: {[key: string]: string}} {
    if (isInitial) {
      return {
        url: item.currentPath,
        labels: {
          'initial': this.translateService.instant(`filterHistory.initialState`)
        }
      };
    }
    const parameterDifferences = this.getParameterDifferencesFromUrls(item.currentPath, item.previousPath, logsType);
    const differenciesLabels = parameterDifferences.reduce((labels: {[key: string]: string}, change): {[key: string]: string} => {
      const changeKey = /^timeRange/.test(change.name) ? 'timeRange' : change.name;
      if (labels[changeKey] !== undefined || urlParamsActionType[changeKey] === undefined) {
        return labels;
      }
      let changeLabel: string;
      if (/^timeRange/.test(change.name)) { // create time range label
        changeLabel = this._getTimeRangeUrlParamDifferenceLabel(
          parameterDifferences,
          this.getParametersFromUrl(item.currentPath),
          this.translateService.instant(`filterHistory.timeRange.dateTimeFormat`)
        );
      } else if (change.name === 'query') { // create query label
        changeLabel = this._getQueryUrlParamDifferenceLabel(change);
      } else {
        changeLabel = this._getMultipleUrlParamDifferenceLabel(change);
      }
      return changeLabel ? {
        ...labels,
        [changeKey]: changeLabel
      } : labels;
    }, {});
    return {
      url: item.currentPath,
      labels: differenciesLabels
    };
  }

  mapHistoryItemsToHistoryItemLabels(
    [items, activeLogsType, components]: [FilterUrlParamChange[], LogsType, {[key: string]: string}]
  ): {[key: string]: any}[] {
    return (items || []).map((item, index): {[key: string]: any} => this.getHistoryItemChangeLabels(item, activeLogsType, index === 0));
  }

  mapHistoryItemLabelsToListItems(historyLabels, labelSeparator = this.labelSeparator) {
    return historyLabels.map((historyLabel) => {
      return {
        value: historyLabel.url,
        label: Object.keys(historyLabel.labels).map((url) => historyLabel.labels[url]).join(labelSeparator)
      };
    });
  }

}
