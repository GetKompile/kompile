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
import { FactSheet, CreateFactSheetRequest } from './models/api-models';

// Define a type for the possible tab values
export type ActiveTabType = 'unifiedChat' | 'sources' | 'mcp' | 'orchestrator' | 'developer' | 'batchConfig' | 'subprocessConfig' | 'graph' | 'connections' | 'stagingConfig' | 'archiveAssembly';

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

  private subscriptions: Subscription[] = [];

  constructor(
    private configService: ConfigService,
    private factSheetService: FactSheetService
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

    // Load initial data
    this.loadFactSheets();
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
}
