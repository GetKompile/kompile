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

import { Component, OnInit, OnDestroy, Inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';
import { environment } from '../environments/environment';
import { ConfigService } from './services/config.service';
import { FactSheetService } from './services/fact-sheet.service';
import { DocumentService } from './services/document.service';
import { WebSocketService } from './services/websocket.service';
import { MainPanelNavigationService } from './services/main-panel-navigation.service';
import { ThemeService } from './services/theme.service';
import { MatDialog } from '@angular/material/dialog';
import { FactSheet, CreateFactSheetRequest, IngestProgressUpdate, IngestStatus } from './models/api-models';
import { CreateFactSheetDialogComponent, CreateFactSheetDialogResult } from './components/create-fact-sheet-dialog/create-fact-sheet-dialog.component';

/**
 * Maps legacy tab-key strings (emitted by index-status-banner and project-explorer)
 * to canonical router paths.
 *
 * Keys emitted today:
 *   index-status-banner: 'sources', 'archiveAssembly'
 *   project-explorer:    'project', 'sources'
 *   (hypothetical):      'unifiedChat', 'tools', 'developer', 'kclaw', 'enforcer'
 *
 * 'archiveAssembly' → '/developer' because ArchiveAssemblyComponent is mounted inside
 * model-staging.component.html which lives under DeveloperHub. Sub-tab selection within
 * DeveloperHub is a later wave (no deep-link mechanism exists there yet).
 */
const LEGACY_KEY_MAP: Record<string, string> = {
  unifiedChat:     '/chat',
  project:         '/project',
  sources:         '/fact-sheets',
  tools:           '/data',
  developer:       '/developer',
  kclaw:           '/agents',
  enforcer:        '/enforcer',
  archiveAssembly: '/developer',  // ArchiveAssembly is a sub-panel of DeveloperHub/ModelStaging
};

@Component({
  standalone: false,
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  title = environment.appTitle;

  // Fact sheet state
  factSheets: FactSheet[] = [];
  activeFactSheet: FactSheet | null = null;

  // Active jobs tracking for notification indicator
  activeJobsCount = 0;
  activeJobs: Map<string, IngestProgressUpdate> = new Map();

  private subscriptions: Subscription[] = [];

  constructor(
    private router: Router,
    private configService: ConfigService,
    private factSheetService: FactSheetService,
    private documentService: DocumentService,
    private webSocketService: WebSocketService,
    private mainPanelNavigationService: MainPanelNavigationService,
    private themeService: ThemeService,
    private dialog: MatDialog,
    @Inject(DOCUMENT) private document: Document
  ) { }

  ngOnInit(): void {
    // Subscribe to config updates from the backend
    const configSub = this.configService.config$.subscribe(config => {
      this.title = config.appTitle;
      this.updateFavicon(config.faviconUrl);
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

    // MainPanelNavigationService: navigate to /fact-sheets (replaces old activeTab='sources')
    const focusSub = this.mainPanelNavigationService.focusMainPanel$.subscribe(() => {
      this.router.navigate(['/fact-sheets']);
    });
    this.subscriptions.push(focusSub);

    // Load initial data
    this.loadFactSheets();

    // Load initial active jobs and subscribe to updates
    this.loadActiveJobs();
    this.subscribeToJobUpdates();
  }

  /**
   * Apply the (white-labelable) branding favicon at runtime.
   */
  private updateFavicon(url?: string): void {
    if (!url) { return; }
    const head = this.document.head;
    let link = head.querySelector<HTMLLinkElement>("link[rel='icon'][type='image/svg+xml']");
    if (!link) {
      link = this.document.createElement('link');
      link.setAttribute('rel', 'icon');
      link.setAttribute('type', 'image/svg+xml');
      head.appendChild(link);
    }
    if (link.getAttribute('href') !== url) {
      link.setAttribute('href', url);
    }
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
    this.webSocketService.connect();

    const progressSub = this.webSocketService.subscribeToAllTasks().subscribe({
      next: (update) => {
        if (this.isActiveJob(update)) {
          this.activeJobs.set(update.taskId, update);
        } else {
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
    const dialogRef = this.dialog.open(CreateFactSheetDialogComponent, {
      width: '460px'
    });
    dialogRef.afterClosed().pipe(filter(Boolean)).subscribe((result: CreateFactSheetDialogResult) => {
      const request: CreateFactSheetRequest = {
        name: result.name.trim(),
        description: result.description.trim() || undefined,
        color: '#1976d2',
        icon: 'folder'
      };
      this.factSheetService.createSheet(request).subscribe({
        next: (sheet) => {
          this.factSheetService.activateSheet(sheet.id).subscribe();
        },
        error: (err) => console.error('Failed to create fact sheet:', err)
      });
    });
  }

  /**
   * Handle navigation from the index-status-banner and project-explorer.
   * Maps legacy tab keys to router paths via LEGACY_KEY_MAP.
   * Unknown keys are console.warned but never silently dropped.
   */
  handleBannerNavigation(tabName: string): void {
    const path = LEGACY_KEY_MAP[tabName];
    if (path) {
      this.router.navigate([path]);
    } else {
      console.warn(`handleBannerNavigation: unknown key "${tabName}" — no route mapped`);
    }
  }

  /**
   * Open model staging — now navigates to /developer.
   * Called from model-status-indicator (openStaging) output.
   */
  openModelStaging(): void {
    this.router.navigate(['/developer']);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // THEME
  // ═══════════════════════════════════════════════════════════════════════════════

  get isDark(): boolean {
    return this.themeService.isDark;
  }

  toggleTheme(): void {
    this.themeService.toggle();
  }
}
