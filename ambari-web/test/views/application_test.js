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

require('views/application');

var view,
  modals = [],
  removed = false,
  events = [
      {
      event: 'keyup',
      which: 27,
      key: 'Esc',
      html: '<div id="modal"><div class="modal-header"><span class="close"></span></div></div>',
      particle: '',
      length: 0
    },
    {
      event: 'keyup',
      keyCode: 27,
      key: 'Esc',
      html: '<div id="modal"><div class="modal-header"><span class="close"></span></div></div>',
      particle: '',
      length: 0
    },
    {
      event: 'keydown',
      which: 13,
      key: 'Enter',
      html: '<div id="modal"><div class="modal-footer"><span></span></div></div>',
      particle: 'not ',
      length: 1
    },
    {
      event: 'keydown',
      keyCode: 13,
      key: 'Enter',
      html: '<div id="modal"><div class="modal-footer"><span></span></div></div>',
      particle: 'not ',
      length: 1
    },
    {
      event: 'keyup',
      which: 27,
      key: 'Esc',
      html: '<div id="modal"><div class="modal-header"><span></span></div></div>',
      particle: 'not ',
      length: 1
    },
    {
      event: 'keyup',
      keyCode: 27,
      key: 'Esc',
      html: '<div id="modal"><div class="modal-header"><span></span></div></div>',
      particle: 'not ',
      length: 1
    },
    {
      event: 'keydown',
      which: 13,
      key: 'Enter',
      html: '<div id="modal"><div class="modal-footer"><span class="btn-success" disabled="disabled"></span></div></div>',
      particle: 'not ',
      length: 1
    },
    {
      event: 'keydown',
      keyCode: 13,
      key: 'Enter',
      html: '<div id="modal"><div class="modal-footer"><span class="btn-success" disabled="disabled"></span></div></div>',
      particle: 'not ',
      length: 1
    },
    {
      event: 'keydown',
      key: 'Enter',
      html: '<div id="modal"><div class="modal-footer"><span class="btn-success"></span></div></div>',
      particle: 'not ',
      length: 1
    },
    {
      event: 'keyup',
      key: 'Esc',
      html: '<div id="modal"><div class="modal-footer"><span class="close"></span></div></div>',
      particle: 'not ',
      length: 1
    }
  ];

describe.skip('App.ApplicationView', function () {

  before(function () {
    if($('#modal').length) {
      removed = true;
    }
    while($('#modal').length) {
      modals.push({
        modal: $('#modal'),
        parent: $('modal').parent()
      });
      $('#modal').remove();
    }
  });

  beforeEach(function () {
    view = App.ApplicationView.create({
      template: null
    });
  });

  afterEach(function () {
    $('#modal').remove();
  });

  after(function () {
    if (removed) {
      modals.forEach(function (item) {
        item.parent.append(item.modal);
      });
    }
  });

  describe('#kdpsView', function () {

    var routerState;

    beforeEach(function () {
      routerState = {loggedIn: true, views: []};
      sinon.stub(App.router, 'get', function (key) {
        if (key === 'loggedIn') {
          return routerState.loggedIn;
        }
        if (key === 'mainViewsController.visibleAmbariViews') {
          return routerState.views;
        }
        return undefined;
      });
    });

    afterEach(function () {
      App.router.get.restore();
    });

    function instance(version) {
      return Em.Object.create({viewName: 'K8S-VIEW', version: version, instanceName: 'K8S_VIEW_INSTANCE'});
    }

    it('is null when logged out or when the view is not deployed', function () {
      routerState.loggedIn = false;
      routerState.views = [instance('1.0.0.8')];
      expect(view.get('kdpsView')).to.be.null;
      routerState.loggedIn = true;
      routerState.views = [Em.Object.create({viewName: 'FILES', version: '1.0.0'})];
      view.propertyDidChange('kdpsView');
      expect(view.get('kdpsView')).to.be.null;
    });

    it('points at the newest deployed version when several are registered', function () {
      routerState.views = [instance('1.0.0.7'), instance('1.0.0.10'), instance('1.0.0.8')];
      view.propertyDidChange('kdpsView');
      expect(view.get('kdpsView.version')).to.equal('1.0.0.10');
    });
  });

  describe('.compareViewVersions', function () {
    it('compares dotted versions numerically', function () {
      expect(App.ApplicationView.compareViewVersions('1.0.0.10', '1.0.0.8')).to.be.above(0);
      expect(App.ApplicationView.compareViewVersions('1.0.0.8', '1.0.0.8')).to.equal(0);
      expect(App.ApplicationView.compareViewVersions('1.0.0', '1.0.0.1')).to.be.below(0);
      expect(App.ApplicationView.compareViewVersions('2.0.0-SNAPSHOT', '2.0.0')).to.not.equal(0);
    });
  });

  describe('#didInsertElement', function () {
    events.forEach(function (item) {
      it('should ' + item.particle + 'close modal window on ' + item.key + ' press', function () {
        $('body').append(item.html);
        $('span').click(function () {
          $('#modal').remove();
        });
        view.didInsertElement();
        var e = $.Event(item.event);
        e.which = item.which;
        e.keyCode = item.keyCode;
        $(document).trigger(e);
        expect($('#modal')).to.have.length(item.length);
      });
    });
  });

});
