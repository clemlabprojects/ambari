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

require('controllers/main/admin/serviceAccounts_controller');

App.AddKuduMasterWizardStep4Controller = App.HighAvailabilityProgressPageController.extend(App.WizardEnableDone, {

  name: 'addKuduMasterWizardStep4Controller',

  clusterDeployState: 'ADD_KUDU_MASTER',

  commands: ['installKuduMaster', 'reconfigureKudu', 'addKuduMaster', 'startKuduMaster', 'stopKuduTservers', 'startKuduTservers'],

  tasksMessagesPrefix: 'admin.addKuduMaster.wizard.step',

  installKuduMaster: function () {
    var hostName = this.get('content.kuduHosts.newKuduMaster');
    this.createInstallComponentTask('KUDU_MASTER', hostName, 'KUDU');
  },

  reconfigureKudu: function () {
    App.ajax.send({
      name: 'config.tags',
      sender: this,
      success: 'onLoadKuduConfigsTags',
      error: 'onTaskError'
    });
  },

  onLoadKuduConfigsTags: function (data) {
    var desiredConfigs = Em.getWithDefault(data, 'Clusters.desired_configs', {});
    var configTypes = ['kudu-master-env', 'kudu-tserver-env'];
    var urlParts = configTypes.filter(function (type) {
      return !!desiredConfigs[type];
    }).map(function (type) {
      return '(type=' + type + '&tag=' + desiredConfigs[type].tag + ')';
    });

    if (!urlParts.length) {
      this.onTaskError();
      return;
    }

    App.ajax.send({
      name: 'reassign.load_configs',
      sender: this,
      data: {
        urlParams: urlParts.join('|')
      },
      success: 'onLoadConfigs',
      error: 'onTaskError'
    });
  },

  onLoadConfigs: function (data) {
    var items = Em.getWithDefault(data, 'items', []);
    var masterConfig = items.findProperty('type', 'kudu-master-env');
    var tserverConfig = items.findProperty('type', 'kudu-tserver-env');

    if (!masterConfig || !masterConfig.properties || !tserverConfig || !tserverConfig.properties) {
      this.onTaskError();
      return;
    }

    var newMasterHost = this.get('content.kuduHosts.newKuduMaster');
    var masterRpcBindAddresses = masterConfig.properties.rpc_bind_addresses;
    var masterAddresses = masterConfig.properties.master_addresses;
    var tserverMasterAddrs = tserverConfig.properties.tserver_master_addrs || masterAddresses;

    masterConfig.properties.master_addresses = this.buildMasterAddresses(masterAddresses, newMasterHost, masterRpcBindAddresses);
    tserverConfig.properties.tserver_master_addrs = this.buildMasterAddresses(tserverMasterAddrs, newMasterHost, masterRpcBindAddresses);

    var note = Em.I18n.t('admin.addKuduMaster.step4.save.configuration.note').format(App.format.role('KUDU_MASTER', false));
    var configs = ['kudu-master-env', 'kudu-tserver-env'].map(function (type) {
      return {
        Clusters: {
          desired_config: this.reconfigureSites([type], data, note)
        }
      };
    }, this);

    App.ajax.send({
      name: 'common.service.multiConfigurations',
      sender: this,
      data: {
        configs: configs
      },
      success: 'onSaveConfigs',
      error: 'onTaskError'
    });
  },

  onSaveConfigs: function () {
    this.onTaskCompleted();
  },

  addKuduMaster: function () {
    App.ajax.send({
      name: 'service.item.executeCustomCommand',
      sender: this,
      data: {
        command: 'ADD_KUDU_MASTER',
        context: Em.I18n.t('admin.addKuduMaster.wizard.step4.addKuduMasterCommand.context'),
        hosts: this.get('content.kuduHosts.newKuduMaster'),
        serviceName: 'KUDU',
        componentName: 'KUDU_MASTER'
      },
      success: 'startPolling',
      error: 'onTaskError'
    });
  },

  startKuduMaster: function () {
    var hostName = this.get('content.kuduHosts.newKuduMaster');
    this.updateComponent('KUDU_MASTER', hostName, 'KUDU', 'Start', 1);
  },

  stopKuduTservers: function () {
    this.updateKuduComponentOnInstalledHosts('KUDU_TSERVER', 'Stop');
  },

  startKuduTservers: function () {
    this.updateKuduComponentOnInstalledHosts('KUDU_TSERVER', 'Start');
  },

  updateKuduComponentOnInstalledHosts: function (componentName, state) {
    var hosts = this.getInstalledHosts(componentName);
    if (!hosts.length) {
      this.onTaskCompleted();
      return;
    }
    this.updateComponent(componentName, hosts, 'KUDU', state, 1);
  },

  getInstalledHosts: function (componentName) {
    return App.HostComponent.find().filterProperty('componentName', componentName).filter(function (hostComponent) {
      var status = hostComponent.get('workStatus');
      return status !== 'INIT' && status !== 'INSTALL_FAILED';
    }).mapProperty('hostName').uniq();
  },

  extractRpcPort: function (rpcBindAddresses) {
    var value = (rpcBindAddresses || '').trim();
    if (!value) {
      return '7051';
    }
    if (value.indexOf(':') === -1) {
      return value;
    }
    return value.split(':').pop();
  },

  normalizeMasterAddress: function (address, defaultPort) {
    var value = (address || '').trim();
    if (!value) {
      return null;
    }

    if (value.indexOf(':') === -1) {
      value = value + ':' + defaultPort;
    }

    var splitAddress = value.split(':');
    var host = splitAddress.slice(0, splitAddress.length - 1).join(':').trim().toLowerCase();
    var port = (splitAddress[splitAddress.length - 1] || defaultPort).trim();

    if (!host) {
      return null;
    }

    return host + ':' + port;
  },

  buildMasterAddresses: function (rawMasterAddresses, newMasterHost, rpcBindAddresses) {
    var defaultPort = this.extractRpcPort(rpcBindAddresses);
    var addresses = [];

    (rawMasterAddresses || '').split(',').forEach(function (item) {
      var normalized = this.normalizeMasterAddress(item, defaultPort);
      if (normalized && addresses.indexOf(normalized) === -1) {
        addresses.push(normalized);
      }
    }, this);

    var newMasterAddress = this.normalizeMasterAddress(newMasterHost, defaultPort);
    if (newMasterAddress && addresses.indexOf(newMasterAddress) === -1) {
      addresses.push(newMasterAddress);
    }

    return addresses.join(',');
  }
});
