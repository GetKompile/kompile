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
 * Crawl manager root. All chrome lives in the shared AppShellComponent; this supplies the tab
 * bar and the legacy-key routes for the crawl persona.
 *
 * The settings gear is crawl-owned and configures this persona's model-staging dependency.
 * stagingRoute remains unset because the Model Staging application is independently distributed.
 */
@Component({
  standalone: false,
  selector: 'app-root',
  template: `
    <app-shell
      persona="crawl"
      [navItems]="navItems"
      [legacyKeyMap]="legacyKeyMap"
      [settingsRoute]="'/settings'"
      [showProjectExplorer]="true">
    </app-shell>
  `
})
export class AppComponent {
  readonly navItems: ShellNavItem[] = [
    { label: 'Crawl',       route: '/crawl' },
    { label: 'Fact Sheets', route: '/fact-sheets', activeJobsBadge: true },
    { label: 'Data',        route: '/data' },
    { label: 'Graph',       route: '/graph' }
  ];

  /**
   * Tab keys emitted by IndexStatusBannerComponent and ProjectExplorerComponent, mapped to this
   * app's routes. 'project' resolves to /data because this app's nearest project surface is the
   * Code Projects hub inside the Data tab; the chat app's /project page is not served here.
   */
  readonly legacyKeyMap: Record<string, string> = {
    sources: '/fact-sheets',
    tools:   '/data',
    project: '/data'
  };
}
