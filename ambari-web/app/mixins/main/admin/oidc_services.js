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

/**
 * Restarts, stops and starts only the services that have OIDC clients, for the OIDC progress pages.
 * Mixed into a controller that also has App.wizardProgressPageControllerMixin.
 */
App.OidcServicesMixin = Em.Mixin.create({

  /**
   * Installed services that declare OIDC clients in the composite OIDC descriptor.
   * @returns {$.Deferred} resolved with the service names (empty when none or on error)
   */
  loadOidcServiceNames: function () {
    var dfd = $.Deferred();
    App.ajax.send({
      name: 'admin.oidc.descriptor.composite',
      sender: this,
      data: {
        clusterName: App.get('clusterName')
      },
      error: '_onOidcDescriptorError'
    }).done(function (data) {
      var services = Em.get(data || {}, 'OidcDescriptor.oidc_descriptor.services') || [];
      var installed = App.Service.find().mapProperty('serviceName');
      dfd.resolve(services.filter(function (service) {
        return installed.contains(service.name) && (service.clients || []).length > 0;
      }).mapProperty('name').uniq());
    }).fail(function () {
      dfd.resolve([]);
    });
    return dfd.promise();
  },

  _onOidcDescriptorError: Em.K,

  /**
   * Restart the components of the services with OIDC clients that need a restart; completes at once when none do.
   */
  restartOidcServices: function () {
    var self = this;
    this.loadOidcServiceNames().done(function (serviceNames) {
      if (!serviceNames.length) {
        self.onTaskCompleted();
        return;
      }
      App.ajax.send({
        name: 'admin.oidc.staleHostComponents',
        sender: self,
        data: {
          clusterName: App.get('clusterName'),
          services: serviceNames.join(',')
        },
        error: 'onTaskError'
      }).done(function (data) {
        if (!Em.get(data || {}, 'items.length')) {
          self.onTaskCompleted();
          return;
        }
        App.ajax.send({
          name: 'restart.custom.filter',
          sender: self,
          data: {
            context: Em.I18n.t('admin.oidc.restart.context'),
            filter: 'HostRoles/stale_configs=true&HostRoles/cluster_name=' + App.get('clusterName') +
              '&HostRoles/service_name.in(' + serviceNames.join(',') + ')'
          },
          success: 'startPolling',
          error: 'onTaskError'
        });
      });
    });
  },

  stopOidcServices: function () {
    var self = this;
    this.loadOidcServiceNames().done(function (serviceNames) {
      if (!serviceNames.length) {
        self.onTaskCompleted();
      } else {
        self.stopServices(serviceNames, true);
      }
    });
  },

  startOidcServices: function () {
    var self = this;
    this.loadOidcServiceNames().done(function (serviceNames) {
      if (!serviceNames.length) {
        self.onTaskCompleted();
      } else {
        self.startServices(true, serviceNames, true);
      }
    });
  }
});
