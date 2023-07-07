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
System.register(['lodash', 'app/plugins/sdk'], function(exports_1) {
    var __extends = (this && this.__extends) || function (d, b) {
        for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
        function __() { this.constructor = d; }
        d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
    };
    var lodash_1, sdk_1;
    var AmbariMetricsQueryCtrl;
    return {
        setters:[
            function (lodash_1_1) {
                lodash_1 = lodash_1_1;
            },
            function (sdk_1_1) {
                sdk_1 = sdk_1_1;
            }],
        execute: function() {
            AmbariMetricsQueryCtrl = (function (_super) {
                __extends(AmbariMetricsQueryCtrl, _super);
                /** @ngInject **/
                function AmbariMetricsQueryCtrl($scope, $injector) {
                    var _this = this;
                    _super.call(this, $scope, $injector);
                    this.errors = this.validateTarget();
                    this.aggregators = ['none', 'avg', 'sum', 'min', 'max'];
                    this.precisions = ['default', 'seconds', 'minutes', 'hours', 'days'];
                    this.transforms = ['none', 'rate'];
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
                    this.datasource.getAggregators().then(function (aggs) {
                        if (aggs.length !== 0) {
                            _this.aggregators = aggs;
                        }
                    });
                    this.suggestMetrics = function (query, callback) {
                        _this.datasource.suggestMetrics(query, _this.target.app)
                            .then(_this.getTextValues)
                            .then(callback);
                    };
                    this.suggestApps = function (query, callback) {
                        _this.datasource.suggestApps(query)
                            .then(_this.getTextValues)
                            .then(callback);
                    };
                    this.suggestHosts = function (query, callback) {
                        _this.datasource.suggestHosts(query, _this.target.app)
                            .then(_this.getTextValues)
                            .then(callback);
                    };
                }
                AmbariMetricsQueryCtrl.prototype.targetBlur = function () {
                    this.errors = this.validateTarget();
                    this.refresh();
                };
                AmbariMetricsQueryCtrl.prototype.getTextValues = function (metricFindResult) {
                    return lodash_1.default.map(metricFindResult, function (value) { return value.text; });
                };
                AmbariMetricsQueryCtrl.prototype.getCollapsedText = function () {
                    var text = this.target.metric + ' on ' + this.target.app;
                    return text;
                };
                AmbariMetricsQueryCtrl.prototype.validateTarget = function () {
                    var errs = {};
                    return errs;
                };
                AmbariMetricsQueryCtrl.templateUrl = 'partials/query.editor.html';
                return AmbariMetricsQueryCtrl;
            })(sdk_1.QueryCtrl);
            exports_1("AmbariMetricsQueryCtrl", AmbariMetricsQueryCtrl);
        }
    }
});
//# sourceMappingURL=query_ctrl.js.map