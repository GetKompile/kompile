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

import { Component, DestroyRef, inject } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { StagingPairingService } from './services/staging-pairing.service';

@Component({
  selector: 'app-root',
  standalone: false,
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent {
  title = 'Kompile Model Staging';
  isMobile = false;
  pairingPanelOpen = false;
  pairingRequired = false;
  pairingTokenInput = '';
  pairingError = '';

  private readonly destroyRef = inject(DestroyRef);

  constructor(
    breakpointObserver: BreakpointObserver,
    readonly pairing: StagingPairingService
  ) {
    const mobileQuery = '(max-width: 768px)';
    this.isMobile = breakpointObserver.isMatched(mobileQuery);

    breakpointObserver.observe(mobileQuery)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(result => {
        this.isMobile = result.matches;
      });

    pairing.pairingRequired$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(required => {
        this.pairingRequired = required;
        if (required) {
          this.pairingPanelOpen = true;
        }
      });
  }

  get pairingActive(): boolean {
    return this.pairing.hasToken;
  }

  togglePairingPanel(): void {
    this.pairingPanelOpen = !this.pairingPanelOpen;
    this.pairingError = '';
  }

  savePairing(): void {
    if (!this.pairing.pair(this.pairingTokenInput)) {
      this.pairingError = 'Enter the non-empty token printed by the staging server.';
      return;
    }
    this.pairingTokenInput = '';
    this.pairingError = '';
    this.pairingPanelOpen = false;
  }

  clearPairing(): void {
    this.pairing.clear();
    this.pairingTokenInput = '';
    this.pairingError = '';
  }

  navItems = [
    { path: '/dashboard', icon: 'dashboard', label: 'Dashboard' },
    { path: '/catalog', icon: 'store', label: 'Model Catalog' },
    { path: '/download', icon: 'cloud_download', label: 'Download Model' },
    { path: '/convert', icon: 'transform', label: 'Convert Model' },
    { path: '/staging', icon: 'pending_actions', label: 'Staging' },
    { path: '/registry', icon: 'inventory_2', label: 'Registry' },
    { path: '/export-import', icon: 'import_export', label: 'Export/Import' },
    { path: '/archives', icon: 'archive', label: 'Archives' },
    { path: '/connections', icon: 'lan', label: 'Connections' },
    { path: '/config', icon: 'settings', label: 'Configuration' },
    { path: '/experiments', icon: 'science', label: 'Experiments' },
    { path: '/eval-datasets', icon: 'dataset', label: 'Eval Datasets' }
  ];
}
