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
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FactSheetManagerComponent } from '../fact-sheet-manager/fact-sheet-manager.component';

/**
 * Route wrapper for FactSheetManagerComponent.
 * Intercepts the (openModelStaging) output and navigates to /developer,
 * preserving the same behaviour as the old openModelStaging() handler in AppComponent.
 * Standalone so it can directly import the standalone FactSheetManagerComponent.
 */
@Component({
  standalone: true,
  selector: 'app-fact-sheet-page',
  imports: [CommonModule, FactSheetManagerComponent],
  template: `<app-fact-sheet-manager (openModelStaging)="onOpenModelStaging()"></app-fact-sheet-manager>`
})
export class FactSheetPageComponent {
  constructor(private router: Router) {}

  onOpenModelStaging(): void {
    this.router.navigate(['/developer']);
  }
}
