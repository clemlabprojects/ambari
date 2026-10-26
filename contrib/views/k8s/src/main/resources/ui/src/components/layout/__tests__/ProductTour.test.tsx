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
import { fireEvent, render, screen } from '@testing-library/react';
import ProductTour, { markTourSeen, shouldStartTour, tourSteps } from '../ProductTour';

const ROUTES = ['/configuration', '/contexts', '/repositories', '/global-security', '/gitops-profiles', '/catalog', '/helm'];

/** The side menu as the layout renders it: hash links inside list entries. */
const Menu: React.FC<{ routes: string[] }> = ({ routes }) => (
  <div className="app-sider-menu-wrap">
    <ul>{routes.map(r => <li key={r}><a href={`#${r}`}>{r}</a></li>)}</ul>
  </div>
);

describe('the tour of the view', () => {
  beforeEach(() => localStorage.clear());

  it('opens by itself on the first visit of an administrator only', () => {
    expect(shouldStartTour(true)).toBe(true);
    expect(shouldStartTour(false)).toBe(false);
    expect(shouldStartTour(undefined)).toBe(false);
    markTourSeen();
    expect(shouldStartTour(true)).toBe(false);
  });

  it('shows administrators the seven steps, setup first, and operators only how to deploy and follow', () => {
    expect(tourSteps(true, true).map(s => s.route)).toEqual(ROUTES);
    expect(tourSteps(false, true).map(s => s.route)).toEqual(['/catalog', '/helm']);
    expect(tourSteps(false, true)[0].description).toMatch(/^An administrator has set up/);
    expect(tourSteps(true, false)[0].description).toMatch(/^KDPS is not connected yet: start here\./);
    expect(tourSteps(true, true)[0].description).not.toMatch(/not connected/);
  });

  it('points at each menu entry in turn and says which step it is', () => {
    const onClose = jest.fn();
    render(<><Menu routes={ROUTES} /><ProductTour open canConfigure connected onClose={onClose} /></>);
    expect(screen.getByText('Connect KDPS to your cluster')).toBeTruthy();
    expect(screen.getByText('1 of 7')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /next/i }));
    expect(screen.getByText('Point KDPS at your data platform')).toBeTruthy();
    expect(screen.getByText('2 of 7')).toBeTruthy();
  });

  it('leaves out a step whose page the menu does not show', () => {
    render(<><Menu routes={['/catalog', '/helm']} /><ProductTour open canConfigure connected onClose={() => undefined} /></>);
    expect(screen.getByText('Deploy a service')).toBeTruthy();
    expect(screen.getByText('1 of 2')).toBeTruthy();
  });
});
