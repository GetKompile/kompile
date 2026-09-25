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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { Component } from '@angular/core';
import { ShellNavItem } from '@shared/components/app-shell/app-shell.component';

/**
 * Chat app root. All chrome lives in the shared AppShellComponent; this supplies the tab bar
 * and the legacy-key routes for the chat persona.
 *
 * The settings gear is Chat-owned: it opens the CLI Session Configuration dialog in place
 * (through the chat view, without leaving it — the chat keeps streaming), with a menu entry
 * for the Model Staging connections page in a new tab.
 */
@Component({
  standalone: false,
  selector: 'app-root',
  template: `
    <app-shell
      persona="chat"
      [navItems]="navItems"
      [legacyKeyMap]="legacyKeyMap"
      [settingsRoute]="'/settings'"
      [settingsClick]="onSettingsClick"
      [showProjectExplorer]="true">
    </app-shell>
  `
})
export class AppComponent {
  readonly navItems: ShellNavItem[] = [
    { label: 'Chat',        route: '/chat',        exact: true },
    { label: 'Project',     route: '/project' },
    { label: 'Fact Sheets', route: '/fact-sheets', activeJobsBadge: true },
    { label: 'Graph',       route: '/graph' }
  ];

  /**
   * Tab keys emitted by IndexStatusBannerComponent and ProjectExplorerComponent, mapped to this
   * app's routes. Admin-only keys ('developer', 'kclaw', 'enforcer', 'archiveAssembly') are
   * absent on purpose — the shell warns rather than navigating nowhere.
   */
  readonly legacyKeyMap: Record<string, string> = {
    unifiedChat: '/chat',
    project:     '/project',
    sources:     '/fact-sheets'
  };

  /**
   * Header gear → the chat view's Session Configuration dialog, in place. The chat view
   * registers its opener under this window key while alive. Falls back to a new tab only
   * when the chat view is not mounted — never an in-place navigation, which would tear
   * down the chat stream.
   */
  readonly onSettingsClick = () => {
    const open = (window as any).__kompileOpenSessionConfig as (() => void) | undefined;
    if (open) {
      open();
      return;
    }
    const base = window.location.origin + window.location.pathname;
    window.open(`${base}#/settings`, '_blank', 'noopener');
  };
}
