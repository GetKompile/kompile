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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { environment } from '../environments/environment';
import { ConfigService } from './services/config.service';
import { FactSheetService } from './services/fact-sheet.service';
import { DocumentService } from './services/document.service';
import { WebSocketService } from './services/websocket.service';
import { MainPanelNavigationService } from './services/main-panel-navigation.service';
import { FactSheet, CreateFactSheetRequest, IngestProgressUpdate, IngestStatus } from './models/api-models';

// Define a type for the possible tab values
export type ActiveTabType = 'unifiedChat' | 'project' | 'sources' | 'tools' | 'developer' | 'kclaw';

@Component({
  standalone: false,
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  title = environment.appTitle; // Default from environment, will be updated from backend
  activeTab: ActiveTabType = 'unifiedChat'; // Use unified chat by default

  // Fact sheet state
  factSheets: FactSheet[] = [];
  activeFactSheet: FactSheet | null = null;
  showCreateFactSheetDialog = false;
  newFactSheetName = '';
  newFactSheetDescription = '';

  // Active jobs tracking for notification indicator
  activeJobsCount = 0;
  activeJobs: Map<string, IngestProgressUpdate> = new Map();

  private subscriptions: Subscription[] = [];

  constructor(
    private configService: ConfigService,
    private factSheetService: FactSheetService,
    private documentService: DocumentService,
    private webSocketService: WebSocketService,
    private mainPanelNavigationService: MainPanelNavigationService
  ) { }

  ngOnInit(): void {
    // Subscribe to config updates from the backend
    const configSub = this.configService.config$.subscribe(config => {
      this.title = config.appTitle;
    });
    this.subscriptions.push(configSub);

    // Subscribe to fact sheets
    const sheetsSub = this.factSheetService.sheets$.subscribe(sheets => {
      this.factSheets = sheets;
    });
    this.subscriptions.push(sheetsSub);

    // Subscribe to active fact sheet
    const activeSub = this.factSheetService.activeSheet$.subscribe(sheet => {
      this.activeFactSheet = sheet;
    });
    this.subscriptions.push(activeSub);

    const focusSub = this.mainPanelNavigationService.focusMainPanel$.subscribe(() => {
      this.activeTab = 'sources';
    });
    this.subscriptions.push(focusSub);

    // Load initial data
    this.loadFactSheets();

    // Load initial active jobs and subscribe to updates
    this.loadActiveJobs();
    this.subscribeToJobUpdates();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ACTIVE JOBS TRACKING
  // ═══════════════════════════════════════════════════════════════════════════════

  private loadActiveJobs(): void {
    this.documentService.getAllIngestTasks().subscribe({
      next: (tasks) => {
        this.activeJobs.clear();
        tasks.forEach(task => {
          if (this.isActiveJob(task)) {
            this.activeJobs.set(task.taskId, task);
          }
        });
        this.activeJobsCount = this.activeJobs.size;
      },
      error: (err) => console.error('Failed to load active jobs:', err)
    });
  }

  private subscribeToJobUpdates(): void {
    // Connect to WebSocket and subscribe to all task updates
    this.webSocketService.connect();

    const progressSub = this.webSocketService.subscribeToAllTasks().subscribe({
      next: (update) => {
        if (this.isActiveJob(update)) {
          this.activeJobs.set(update.taskId, update);
        } else {
          // Job completed or failed - remove from active
          this.activeJobs.delete(update.taskId);
        }
        this.activeJobsCount = this.activeJobs.size;
      },
      error: (err) => console.error('WebSocket error:', err)
    });
    this.subscriptions.push(progressSub);
  }

  private isActiveJob(update: IngestProgressUpdate): boolean {
    return update.status === IngestStatus.IN_PROGRESS ||
           update.status === IngestStatus.PENDING;
  }

  /** Get a summary of active jobs for tooltip */
  getActiveJobsSummary(): string {
    if (this.activeJobsCount === 0) {
      return 'No active jobs';
    }
    const jobs = Array.from(this.activeJobs.values());
    const summary = jobs.slice(0, 3).map(j => j.fileName).join(', ');
    if (jobs.length > 3) {
      return `${summary} +${jobs.length - 3} more`;
    }
    return summary;
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach(sub => sub.unsubscribe());
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FACT SHEET OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  loadFactSheets(): void {
    this.factSheetService.loadSheets().subscribe({
      next: () => {
        this.factSheetService.loadActiveSheet().subscribe();
      },
      error: (err) => console.error('Failed to load fact sheets:', err)
    });
  }

  selectFactSheet(sheet: FactSheet): void {
    if (sheet.id === this.activeFactSheet?.id) return;

    this.factSheetService.activateSheet(sheet.id).subscribe({
      error: (err) => console.error('Failed to activate fact sheet:', err)
    });
  }

  openCreateFactSheetDialog(): void {
    this.newFactSheetName = '';
    this.newFactSheetDescription = '';
    this.showCreateFactSheetDialog = true;
  }

  closeCreateFactSheetDialog(): void {
    this.showCreateFactSheetDialog = false;
  }

  createFactSheet(): void {
    if (!this.newFactSheetName.trim()) return;

    const request: CreateFactSheetRequest = {
      name: this.newFactSheetName.trim(),
      description: this.newFactSheetDescription.trim() || undefined,
      color: '#1976d2',
      icon: 'folder'
    };

    this.factSheetService.createSheet(request).subscribe({
      next: (sheet) => {
        this.showCreateFactSheetDialog = false;
        // Automatically activate the new sheet
        this.factSheetService.activateSheet(sheet.id).subscribe();
      },
      error: (err) => console.error('Failed to create fact sheet:', err)
    });
  }

  /**
   * Handle navigation from the index status banner.
   * Safely casts the tab name to the ActiveTabType.
   */
  handleBannerNavigation(tabName: string): void {
    const validTabs: ActiveTabType[] = ['unifiedChat', 'project', 'sources', 'tools', 'developer', 'kclaw'];
    if (validTabs.includes(tabName as ActiveTabType)) {
      this.activeTab = tabName as ActiveTabType;
    }
  }

  /**
   * Open the model staging configuration interface.
   * Called from the model status indicator.
   * Staging Manager is now under the Developer tab.
   */
  openModelStaging(): void {
    this.activeTab = 'developer';
  }
}
