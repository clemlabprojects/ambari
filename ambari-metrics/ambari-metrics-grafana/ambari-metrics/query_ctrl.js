"use strict";
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
var __extends = (this && this.__extends) || (function () {
    var extendStatics = function (d, b) {
        extendStatics = Object.setPrototypeOf ||
            ({ __proto__: [] } instanceof Array && function (d, b) { d.__proto__ = b; }) ||
            function (d, b) { for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p]; };
        return extendStatics(d, b);
    }
    return function (d, b) {
        extendStatics(d, b);
        function __() { this.constructor = d; }
        d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
    };
})();
exports.__esModule = true;
///<reference path="../../../headers/common.d.ts" />
require("angular");
var lodash_1 = require("lodash");
var sdk_1 = require("app/plugins/sdk");
var AmbariMetricsQueryCtrl = /** @class */ (function (_super) {
    __extends(AmbariMetricsQueryCtrl, _super);
    /** @ngInject **/
    function AmbariMetricsQueryCtrl($scope, $injector) {
        var _this = _super.call(this, $scope, $injector) || this;
        _this.errors = _this.validateTarget(_this.target);
        _this.aggregators = ['none', 'avg', 'sum', 'min', 'max'];
        _this.precisions = ['default', 'seconds', 'minutes', 'hours', 'days'];
        _this.transforms = ['none', 'diff', 'rate'];
        _this.seriesAggregators = ['none', 'avg', 'sum', 'min', 'max'];
        if (!_this.target.aggregator) {
            _this.target.aggregator = 'avg';
        }
        _this.precisionInit = function () {
            if (typeof this.target.precision == 'undefined') {
                this.target.precision = "default";
            }
        };
        _this.transform = function () {
            if (typeof this.target.transform == 'undefined') {
                this.target.transform = "none";
            }
        };
        _this.seriesAggregator = function () {
            if (typeof $scope.target.seriesAggregator == 'undefined') {
                this.target.seriesAggregator = "none";
            }
        };
        $scope.$watch('target.app', function (newValue) {
            if (newValue === '') {
                this.target.metric = '';
                this.target.hosts = '';
                this.target.cluster = '';
            }
        });
        if (!_this.target.downsampleAggregator) {
            _this.target.downsampleAggregator = 'avg';
        }
        _this.datasource.getAggregators().then(function (aggs) {
            this.aggregators = aggs;
        });
        _this.suggestApps = function (query, callback) {
            _this.datasource.suggestApps(query)
                .then(_this.getTextValues)
                .then(callback);
        };
        _this.suggestClusters = function (query, callback) {
            _this.datasource.suggestClusters(_this.target.app)
                .then(_this.getTextValues)
                .then(callback);
        };
        _this.suggestHosts = function (query, callback) {
            _this.datasource.suggestHosts(_this.target.app, _this.target.cluster)
                .then(_this.getTextValues)
                .then(callback);
        };
        _this.suggestMetrics = function (query, callback) {
            _this.datasource.suggestMetrics(query, _this.target.app)
                .then(_this.getTextValues)
                .then(callback);
        };
        _this.suggestTagKeys = function (query, callback) {
            _this.datasource.metricFindQuery('tag_names(' + _this.target.metric + ')')
                .then(_this.getTextValues)
                .then(callback);
        };
        _this.suggestTagValues = function (query, callback) {
            _this.datasource.metricFindQuery('tag_values(' + _this.target.metric + ',' + _this.target.currentTagKey + ')')
                .then(_this.getTextValues)
                .then(callback);
        };
        _this.getTextValues = function (metricFindResult) {
            return metricFindResult.map(function (value) { return value.text; });
        };
        return _this;
    }
    AmbariMetricsQueryCtrl.prototype.targetBlur = function () {
        this.target.errors = this.validateTarget(this.target);
        this.refresh();
    };
    ;
    AmbariMetricsQueryCtrl.prototype.addTag = function () {
        if (!this.addTagMode) {
            this.addTagMode = true;
            return;
        }
        if (!this.target.tags) {
            this.target.tags = {};
        }
        this.target.errors = this.validateTarget(this.target);
        if (!this.target.errors.tags) {
            this.target.tags[this.target.currentTagKey] = this.target.currentTagValue;
            this.target.currentTagKey = '';
            this.target.currentTagValue = '';
            this.targetBlur();
        }
        this.addTagMode = false;
    };
    ;
    AmbariMetricsQueryCtrl.prototype.removeTag = function (key) {
        delete this.target.tags[key];
        this.targetBlur();
    };
    ;
    AmbariMetricsQueryCtrl.prototype.getCollapsedText = function () {
        var text = this.target.metric + ' on ' + this.target.app;
        return text;
    };
    ;
    AmbariMetricsQueryCtrl.prototype.validateTarget = function (target) {
        var errs = {};
        if (target.tags && lodash_1["default"].has(target.tags, target.currentTagKey)) {
            errs.tags = "Duplicate tag key '" + target.currentTagKey + "'.";
        }
        return errs;
    };
    AmbariMetricsQueryCtrl.templateUrl = 'partials/query.editor.html';
    return AmbariMetricsQueryCtrl;
}(sdk_1.QueryCtrl));
exports.AmbariMetricsQueryCtrl = AmbariMetricsQueryCtrl;
