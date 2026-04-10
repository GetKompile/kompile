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
import { MatDialog } from '@angular/material/dialog';
import { SystemDiagnosticsComponent } from '../system-diagnostics/system-diagnostics.component';
import { Nd4jEnvironmentComponent } from '../nd4j-environment/nd4j-environment.component';
import { Nd4jFrameworkComponent } from '../nd4j-framework/nd4j-framework.component';

@Component({
  standalone: false,
  selector: 'app-developer-hub',
  templateUrl: './developer-hub.component.html',
  styleUrls: ['./developer-hub.component.css']
})
export class DeveloperHubComponent implements OnInit, AfterViewInit {
  selectedTabIndex = 0;
  showDiagnosticsInline = false;

  // Keep track of the subtab index to set after view init
  private pendingSubtabIndex: number | null = null;

  // Reference to the System inner tab group for direct navigation
  @ViewChild('systemInnerTabs') systemInnerTabs?: MatTabGroup;

  constructor(
    private dialog: MatDialog,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    // Subscribe to query params for tab navigation
    this.route.queryParams.subscribe(params => {
      if (params['tab'] !== undefined) {
        const tabIndex = parseInt(params['tab'], 10);
        if (!isNaN(tabIndex) && tabIndex >= 0 && tabIndex <= 4) {
          this.selectedTabIndex = tabIndex;
        }
      }
      if (params['subtab'] !== undefined) {
        const subtabIndex = parseInt(params['subtab'], 10);
        if (!isNaN(subtabIndex) && subtabIndex >= 0) {
          this.pendingSubtabIndex = subtabIndex;
        }
      }
    });
  }

  ngAfterViewInit(): void {
    // Set the subtab index after the view has been initialized
    if (this.pendingSubtabIndex !== null) {
      // Use setTimeout to avoid ExpressionChangedAfterItHasBeenCheckedError
      setTimeout(() => {
        this.setSubtab(this.pendingSubtabIndex!);
        this.pendingSubtabIndex = null;
      }, 0);
    }
  }

  /**
   * Set the subtab index for the System tab (index 3).
   * Currently only supports System tab subtab navigation.
   */
  private setSubtab(index: number): void {
    // Only support System tab (index 4) for now, which has the Embedding Subprocess logs
    if (this.selectedTabIndex === 4 && this.systemInnerTabs) {
      this.systemInnerTabs.selectedIndex = index;
    }
  }

  onTabChange(event: MatTabChangeEvent): void {
    this.selectedTabIndex = event.index;
  }

  openDiagnosticsDialog(): void {
    this.dialog.open(SystemDiagnosticsComponent, {
      width: '900px',
      maxWidth: '95vw',
      maxHeight: '90vh',
      panelClass: 'diagnostics-dialog'
    });
  }

  openNd4jEnvironmentDialog(): void {
    this.dialog.open(Nd4jEnvironmentComponent, {
      width: '800px',
      maxWidth: '95vw',
      maxHeight: '90vh',
      panelClass: 'nd4j-environment-dialog'
    });
  }

  openNd4jFrameworkDialog(): void {
    this.dialog.open(Nd4jFrameworkComponent, {
      width: '800px',
      maxWidth: '95vw',
      maxHeight: '90vh',
      panelClass: 'nd4j-framework-dialog'
    });
  }

  toggleDiagnosticsInline(): void {
    this.showDiagnosticsInline = !this.showDiagnosticsInline;
  }
}
