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

import { Component, Input, OnInit, OnDestroy, Inject } from '@angular/core';
import { CommonModule, DOCUMENT } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatTabsModule } from '@angular/material/tabs';
import { MatDialog } from '@angular/material/dialog';

import { ConfigService } from '../../services/config.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { DocumentService } from '../../services/document.service';
import { WebSocketService } from '../../services/websocket.service';
import { MainPanelNavigationService } from '../../services/main-panel-navigation.service';
import { ThemeService } from '../../services/theme.service';
import { FactSheet, CreateFactSheetRequest, IngestProgressUpdate, IngestStatus } from '../../models/api-models';
import { BrandingComponent } from '../branding/branding.component';
import { IndexStatusBannerComponent } from '../index-status-banner/index-status-banner.component';
import { ModelStatusIndicatorComponent } from '../model-status-indicator/model-status-indicator.component';
import { ProjectExplorerComponent } from '../project-explorer/project-explorer.component';
import {
  CreateFactSheetDialogComponent,
  CreateFactSheetDialogResult
} from '../create-fact-sheet-dialog/create-fact-sheet-dialog.component';

/** One entry in the shell's top-level tab bar. */
export interface ShellNavItem {
  /** Visible tab text. */
  label: string;
  /** Router path, e.g. '/chat'. */
  route: string;
  /** Match the route exactly rather than by prefix (used for '/chat' so it does not stay lit). */
  exact?: boolean;
  /** Show the running-ingest-jobs badge on this tab. Set it on whichever tab owns ingestion. */
  activeJobsBadge?: boolean;
}

/**
 * The application chrome shared by every persona app: branding, model status, the fact-sheet
 * selector, the theme toggle, the tab bar, the project explorer and the router outlet.
 *
 * Everything here is persona-neutral except the tab bar and the two chrome buttons that jump to
 * admin surfaces, so those are inputs. Each app's AppComponent supplies its own `navItems` and
 * leaves `settingsRoute` / `stagingRoute` unset when it does not host those routes — a button
 * pointing at a route the app never declared would silently navigate nowhere.
 *
 * `legacyKeyMap` translates the tab-key strings still emitted by IndexStatusBannerComponent and
 * ProjectExplorerComponent ('sources', 'project', 'archiveAssembly', …) into this app's router
 * paths. Keys absent from an app's map are warned about rather than dropped, which is how a
 * persona that genuinely cannot service a key stays visible instead of failing silently.
 */
@Component({
  standalone: true,
  selector: 'app-shell',
  imports: [
    CommonModule,
    RouterModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatDividerModule,
    MatTabsModule,
    BrandingComponent,
    IndexStatusBannerComponent,
    ModelStatusIndicatorComponent,
    ProjectExplorerComponent
  ],
  templateUrl: './app-shell.component.html',
  styleUrls: ['./app-shell.component.css']
})
export class AppShellComponent implements OnInit, OnDestroy {
  /** Tab bar contents, in display order. */
  @Input({ required: true }) navItems: ShellNavItem[] = [];

  /** Route behind the header gear icon, or null when this app hosts no settings surface. */
  @Input() settingsRoute: string | null = null;

  /** Where ModelStatusIndicator's "open staging" action goes, or null to make it inert. */
  @Input() stagingRoute: string | null = null;

  /** Legacy tab-key -> router path for this app. */
  @Input() legacyKeyMap: Record<string, string> = {};

  /** The project explorer sidebar is end-user navigation; admin surfaces can switch it off. */
  @Input() showProjectExplorer = true;

  // Fact sheet state
  factSheets: FactSheet[] = [];
  activeFactSheet: FactSheet | null = null;

  // Active jobs tracking for notification indicator
  activeJobsCount = 0;
  activeJobs: Map<string, IngestProgressUpdate> = new Map();

  /**
   * routerLinkActiveOptions bindings. Held as stable instances rather than object literals in the
   * template so change detection does not hand RouterLinkActive a fresh object every cycle.
   */
  readonly exactMatch = { exact: true };
  readonly prefixMatch = { exact: false };

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

    // MainPanelNavigationService: focus whichever route this app treats as its main panel.
    const focusSub = this.mainPanelNavigationService.focusMainPanel$.subscribe(() => {
      const target = this.legacyKeyMap['sources'];
      if (target) {
        this.router.navigate([target]);
      }
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
   * Maps legacy tab keys to this app's router paths via `legacyKeyMap`.
   * Unknown keys are console.warned but never silently dropped.
   */
  handleBannerNavigation(tabName: string): void {
    const path = this.legacyKeyMap[tabName];
    if (path) {
      this.router.navigate([path]);
    } else {
      console.warn(`handleBannerNavigation: unknown key "${tabName}" — no route mapped in this app`);
    }
  }

  /**
   * Model-status-indicator's "open staging" action. Model staging is an admin surface, so an
   * app that does not host it leaves `stagingRoute` null and the action is a no-op.
   */
  openModelStaging(): void {
    if (this.stagingRoute) {
      this.router.navigate([this.stagingRoute]);
    }
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
