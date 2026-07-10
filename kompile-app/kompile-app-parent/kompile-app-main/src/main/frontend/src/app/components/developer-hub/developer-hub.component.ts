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

import { Component, OnInit, ViewChild, AfterViewInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { MatTabChangeEvent, MatTabGroup } from '@angular/material/tabs';
import { TrainingJobHistory } from '../../services/training-history.service';

/**
 * Outer section keys recognised in ?section= query params.
 * "staging" is a shortcut: selects Management → Model & Staging inner tab.
 */
type DeveloperSection = 'testers' | 'debuggers' | 'management' | 'sdk' | 'system' | 'staging';

const SECTION_TO_OUTER_INDEX: Record<DeveloperSection, number> = {
  testers:    0,
  debuggers:  1,
  management: 2,
  staging:    2,   // staging = Management outer tab
  sdk:        3,
  system:     4,
};

@Component({
  standalone: false,
  selector: 'app-developer-hub',
  templateUrl: './developer-hub.component.html',
  styleUrls: ['./developer-hub.component.css']
})
export class DeveloperHubComponent implements OnInit, AfterViewInit {
  selectedTabIndex = 0;

  /** Job selected for the Training Dashboard tab. Set by routing or future inter-tab wiring. */
  selectedTrainingJob: TrainingJobHistory | null = null;

  // Management inner tab group: pending index to set after view init.
  private pendingManagementSubtabIndex: number | null = null;

  // System inner tab group: pending index to set after view init.
  private pendingSystemSubtabIndex: number | null = null;

  /** Index of the "Model & Staging" tab within the Management inner tab group. */
  private readonly MGMT_MODEL_STAGING_INDEX = 2; // Ingest & Jobs has 3 tabs (0,1,2 not there)
  // Management group order after restructuring:
  // Ingest & Jobs group: Index & Search Status(0), Ingest History(1), Resumable Jobs(2), Processing Settings(3), Pipeline Schedules(4)
  // Models & GPU group: Model & Staging(5), VLM Management(6), GPU Management(7), Model Admission(8), Model Warmup & Cache(9), Training History(10)
  // Ops & Monitoring group: Job Scheduler(11), Monitors(12)
  // So "Model & Staging" = index 5 in the flat inner list
  private readonly MGMT_STAGING_TAB_INDEX = 5;

  @ViewChild('managementInnerTabs') managementInnerTabs?: MatTabGroup;
  @ViewChild('systemInnerTabs') systemInnerTabs?: MatTabGroup;

  constructor(
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.route.queryParams.subscribe(params => {
      // ?section= key navigation
      const sectionParam = params['section'] as DeveloperSection | undefined;
      if (sectionParam && sectionParam in SECTION_TO_OUTER_INDEX) {
        this.selectedTabIndex = SECTION_TO_OUTER_INDEX[sectionParam];
        if (sectionParam === 'staging') {
          this.pendingManagementSubtabIndex = this.MGMT_STAGING_TAB_INDEX;
        }
      }

      // Legacy numeric ?tab= support (keep backward compat)
      if (params['tab'] !== undefined && sectionParam === undefined) {
        const tabIndex = parseInt(params['tab'], 10);
        if (!isNaN(tabIndex) && tabIndex >= 0 && tabIndex <= 4) {
          this.selectedTabIndex = tabIndex;
        }
      }
      if (params['subtab'] !== undefined) {
        const subtabIndex = parseInt(params['subtab'], 10);
        if (!isNaN(subtabIndex) && subtabIndex >= 0) {
          this.pendingSystemSubtabIndex = subtabIndex;
        }
      }
    });
  }

  ngAfterViewInit(): void {
    if (this.pendingManagementSubtabIndex !== null) {
      const idx = this.pendingManagementSubtabIndex;
      this.pendingManagementSubtabIndex = null;
      setTimeout(() => {
        if (this.managementInnerTabs) {
          this.managementInnerTabs.selectedIndex = idx;
        }
      }, 0);
    }
    if (this.pendingSystemSubtabIndex !== null) {
      const idx = this.pendingSystemSubtabIndex;
      this.pendingSystemSubtabIndex = null;
      setTimeout(() => {
        if (this.systemInnerTabs) {
          this.systemInnerTabs.selectedIndex = idx;
        }
      }, 0);
    }
  }

  onTabChange(event: MatTabChangeEvent): void {
    this.selectedTabIndex = event.index;
  }
}
