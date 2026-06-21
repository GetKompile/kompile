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
import { CommonModule } from '@angular/common';
import { JudgementsPanelComponent } from '../judgements-panel/judgements-panel.component';
import { EnforcerDashboardComponent } from '../enforcer-dashboard/enforcer-dashboard.component';
import { ProjectEnforcerManagerComponent } from '../project-enforcer-manager/project-enforcer-manager.component';

/**
 * First-class "Enforcer" workspace. Surfaces the judge/enforcer that was previously buried under
 * Developer → Management, with a new Judgements view that tracks every decision made (and the raw
 * LLM judge transcript) so enforced sessions are no longer opaque.
 */
@Component({
  selector: 'app-enforcer-hub',
  standalone: true,
  imports: [CommonModule, JudgementsPanelComponent, EnforcerDashboardComponent, ProjectEnforcerManagerComponent],
  template: `
  <div class="eh">
    <div class="eh-tabs">
      <button class="eh-tab" [class.active]="tab === 'judgements'" (click)="tab = 'judgements'">Judgements</button>
      <button class="eh-tab" [class.active]="tab === 'sessions'" (click)="tab = 'sessions'">Sessions</button>
      <button class="eh-tab" [class.active]="tab === 'config'" (click)="tab = 'config'">Project Judge</button>
    </div>
    <div class="eh-content">
      <app-judgements-panel *ngIf="tab === 'judgements'"></app-judgements-panel>
      <app-enforcer-dashboard *ngIf="tab === 'sessions'"></app-enforcer-dashboard>
      <app-project-enforcer-manager *ngIf="tab === 'config'"></app-project-enforcer-manager>
    </div>
  </div>
  `,
  styles: [`
    .eh { display: flex; flex-direction: column; height: 100%; padding: 8px 12px; box-sizing: border-box; }
    .eh-tabs { display: flex; gap: 6px; border-bottom: 1px solid rgba(128,128,128,0.25); margin-bottom: 10px; }
    .eh-tab { cursor: pointer; border: none; background: transparent; color: inherit;
      padding: 8px 14px; font-size: 14px; border-bottom: 2px solid transparent; opacity: 0.7; }
    .eh-tab:hover { opacity: 1; }
    .eh-tab.active { opacity: 1; font-weight: 600; border-bottom-color: #3f51b5; }
    .eh-content { flex: 1; min-height: 0; overflow: auto; }
  `]
})
export class EnforcerHubComponent {
  tab: 'judgements' | 'sessions' | 'config' = 'judgements';
}
