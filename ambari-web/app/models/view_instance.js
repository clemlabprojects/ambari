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

var App = require('app');

App.ViewInstance = Em.Object.extend({

  /**
   * @type {string}
   */
  iconPath: '',

  /**
   * @type {string}
   */
  label: '',

  /**
   * @type {boolean}
   */
  visible: false,

  /**
   * @type {string}
   */
  version: '',

  /**
   * @type {string}
   */
  description: '',

  /**
   * @type {string}
   */
  viewName: '',

  /**
   * @type {string}
   */
  shortUrl: '',

  /**
   * @type {string}
   */
  instanceName: '',

  /**
   * @type {string}
   */
  href: '',

  /**
   * @type {string}
   */
  internalAmbariUrl: function () {
    var shortUrl = this.get('shortUrl');
    var viewName = this.get('viewName');
    var version = this.get('version');
    var instanceName = this.get('instanceName');
    if(shortUrl) {
      return '#/main/view/' + viewName + '/' + shortUrl;
    }
    return '#/main/views/' + viewName + '/' + version + '/' + instanceName;
  }.property('shortUrl', 'viewName', 'version', 'instanceName'),

  /**
   * Some views render their own full-page chrome and read best standalone,
   * without the Ambari shell wrapped around their iframe. The menu opens these
   * in a new tab using their direct context-path URL ({@link href}) instead of
   * the in-shell route. Matched by view name (type), not instance label.
   * @type {boolean}
   */
  opensStandalone: function () {
    return ['K8S-VIEW'].indexOf(this.get('viewName')) !== -1;
  }.property('viewName')
});
