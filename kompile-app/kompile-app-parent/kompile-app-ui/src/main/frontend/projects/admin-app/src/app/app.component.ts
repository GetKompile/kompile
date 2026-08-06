/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component } from '@angular/core';
import { ShellNavItem } from '@shared/components/app-shell/app-shell.component';

/**
 * Admin console root. Unlike the two end-user apps this one owns the settings and model-staging
 * routes, so it passes both to the shell.
 *
 * showProjectExplorer is false: the explorer is an end-user project-browsing surface, and the
 * admin console has no /project route for it to navigate to.
 */
@Component({
  standalone: false,
  selector: 'app-root',
  template: `
    <app-shell
      persona="admin"
      [navItems]="navItems"
      settingsRoute="/settings"
      stagingRoute="/developer"
      [legacyKeyMap]="legacyKeyMap"
      [showProjectExplorer]="false">
    </app-shell>
  `
})
export class AppComponent {
  readonly navItems: ShellNavItem[] = [
    { label: 'Fact Sheets', route: '/fact-sheets', activeJobsBadge: true },
    { label: 'Graph',       route: '/graph' },
    { label: 'Developer',   route: '/developer' },
    { label: 'Agents',      route: '/agents' },
    { label: 'Enforcer',    route: '/enforcer' }
  ];

  /**
   * Tab keys emitted by IndexStatusBannerComponent, mapped to this app's routes. Model staging
   * and archive assembly both live inside the Developer hub, so both keys land there.
   */
  readonly legacyKeyMap: Record<string, string> = {
    sources:         '/fact-sheets',
    developer:       '/developer',
    kclaw:           '/agents',
    enforcer:        '/enforcer',
    archiveAssembly: '/developer'
  };
}
