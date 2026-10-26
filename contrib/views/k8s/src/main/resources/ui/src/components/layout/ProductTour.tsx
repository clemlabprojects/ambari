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

import React from 'react';
import { Tour } from 'antd';

const SEEN_KEY = 'kdps.tour.seen';

/** Whether the tour opens by itself: on the first visit of a KDPS administrator, in this browser. */
export function shouldStartTour(canConfigure: boolean | undefined): boolean {
  if (!canConfigure) return false;
  try { return localStorage.getItem(SEEN_KEY) !== 'true'; } catch { return false; }
}

/** Remembers, in this browser, that the tour was seen: it no longer opens by itself. */
export function markTourSeen(): void {
  try { localStorage.setItem(SEEN_KEY, 'true'); } catch { /* storage unavailable: it may open again */ }
}

interface TourStep {
  /** The menu entry the step points at. */
  route: string;
  title: string;
  description: string;
  /** Shown to KDPS administrators only: a setup step. */
  setup: boolean;
}

/** The steps, in the order the view is set up and then used. */
export function tourSteps(canConfigure: boolean, connected: boolean): TourStep[] {
  const steps: TourStep[] = [
    { route: '/configuration', setup: true, title: 'Connect KDPS to your cluster',
      description: (connected ? '' : 'KDPS is not connected yet: start here. ')
        + 'Upload a kubeconfig or sign in to OpenShift. Everything else needs this first.' },
    { route: '/contexts', setup: true, title: 'Point KDPS at your data platform',
      description: 'The cluster this Ambari manages is there by default. Add a Cloudera or another external platform to deploy services against it, with its Kerberos, Ranger and Hive.' },
    { route: '/repositories', setup: true, title: 'Tell KDPS where the charts are',
      description: 'Add the registry or Helm repository your services are installed from, with its credentials if it needs them.' },
    { route: '/global-security', setup: true, title: 'Decide how users sign in',
      description: 'Choose LDAP, Active Directory or OIDC once, for every service you deploy. Truststores and Certificate Authorities hold your company certificates.' },
    { route: '/gitops-profiles', setup: true, title: 'Deploy through Git, if you want to',
      description: 'Save your Git repository on the Git Repositories page, then describe here the files your Argo CD or CI expects, and preview them before any deploy.' },
    { route: '/catalog', setup: false, title: 'Deploy a service',
      description: (canConfigure ? '' : 'An administrator has set up the cluster, the charts and the sign-in. ')
        + 'Pick Trino, Superset, OpenMetadata… The wizard fills in what is set up, and lets you install with Helm or write the release to Git.' },
    { route: '/helm', setup: false, title: 'Follow and manage what runs',
      description: 'See the status of each release, upgrade it, and run its Ranger and catalog actions. The Operations button, at the top right, shows the steps and logs of every deploy.' },
  ];
  return steps.filter(s => canConfigure || !s.setup);
}

/** The menu entry of a route, to point at; undefined when the menu does not show it. */
function menuEntry(route: string): HTMLElement | null {
  const link = document.querySelector<HTMLElement>(`.app-sider-menu-wrap a[href$="#${route}"], .app-sider-menu-wrap a[href="${route}"]`);
  return (link?.closest('li') as HTMLElement | null) || link;
}

interface Props {
  open: boolean;
  canConfigure: boolean;
  connected: boolean;
  onClose: () => void;
}

/** A short tour of the view: one menu entry per step, saying what to do there. It changes nothing. */
const ProductTour: React.FC<Props> = ({ open, canConfigure, connected, onClose }) => {
  const [steps, setSteps] = React.useState<TourStep[]>([]);
  const [current, setCurrent] = React.useState(0);
  // The menu is looked up once it is on the page, and the tour starts again from its first step on each opening.
  React.useEffect(() => {
    if (!open) return;
    // A step whose entry the menu does not show (a page this account cannot open) is left out.
    setSteps(tourSteps(canConfigure, connected).filter(s => menuEntry(s.route)));
    setCurrent(0);
  }, [open, canConfigure, connected]);
  return (
    <Tour
      open={open && steps.length > 0}
      current={current}
      onChange={setCurrent}
      steps={steps.map(s => ({
        title: s.title, description: s.description, target: () => menuEntry(s.route) as HTMLElement, placement: 'right',
      }))}
      onClose={onClose}
      onFinish={onClose}
      indicatorsRender={(at, total) => <span>{at + 1} of {total}</span>}
    />
  );
};

export default ProductTour;
