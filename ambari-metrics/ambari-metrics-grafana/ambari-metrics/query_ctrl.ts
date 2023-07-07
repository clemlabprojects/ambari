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
///<reference path="../../../headers/common.d.ts" />

import _ from 'lodash';
import kbn from 'app/core/utils/kbn';
import {QueryCtrl} from 'app/plugins/sdk';

export class AmbariMetricsQueryCtrl extends QueryCtrl {
    static templateUrl = 'partials/query.editor.html';
    aggregators: any;
    aggregator: any;
    errors: any;
    precisions: any;
    transforms: any;
    transform: any;
    precisionInit: any;
    suggestMetrics: any;
    suggestApps: any;
    suggestHosts: any;

    /** @ngInject **/
    constructor($scope, $injector) {
        super($scope, $injector);

        this.errors = this.validateTarget();
        this.aggregators = ['none','avg', 'sum', 'min', 'max'];
        this.precisions = ['default','seconds', 'minutes', 'hours', 'days'];
        this.transforms = ['none','rate'];

        if (!this.target.aggregator) {
            this.target.aggregator = 'avg';
        }

        this.precisionInit = function () {
           if (typeof this.target.precision == 'undefined') {
                this.target.precision = "default";
           }
        };

        this.transform = function () {
           if (typeof this.target.transform == 'undefined') {
                this.target.transform = "none";
           }
        };
        $scope.$watch('ctrl.target.app', function (newValue) {
            if (newValue === '') {
                $scope.ctrl.target.metric = '';
                $scope.ctrl.target.hosts = '';
            }
        });


        this.datasource.getAggregators().then((aggs) => {
            if (aggs.length !== 0) {
                this.aggregators = aggs;
            }
        });


        this.suggestMetrics = (query, callback) => {
            this.datasource.suggestMetrics(query, this.target.app)
                .then(this.getTextValues)
                .then(callback);
        };

        this.suggestApps = (query, callback) => {
            this.datasource.suggestApps(query)
                .then(this.getTextValues)
                .then(callback);
        };

        this.suggestHosts = (query, callback) => {
            this.datasource.suggestHosts(query, this.target.app)
                .then(this.getTextValues)
                .then(callback);
        };
    }

    targetBlur() {
        this.errors = this.validateTarget();
        this.refresh();
    }

    getTextValues(metricFindResult) {
        return _.map(metricFindResult, function(value) { return value.text; });
    }
    getCollapsedText () {
        var text = this.target.metric + ' on ' + this.target.app;
        return text;
    }

    validateTarget() {
        var errs: any = {};
        return errs;
    }

}
