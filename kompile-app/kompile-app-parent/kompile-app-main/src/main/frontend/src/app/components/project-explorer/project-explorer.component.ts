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

import { Component, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatBadgeModule } from '@angular/material/badge';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { FactSheetService } from '../../services/fact-sheet.service';
import { ProjectService, ProjectManifest, ProjectStatus } from '../../services/project.service';
import { FactSheet, CreateFactSheetRequest } from '../../models/api-models';

@Component({
  selector: 'app-project-explorer',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatDividerModule,
    MatBadgeModule,
    MatFormFieldModule,
    MatInputModule
  ],
  templateUrl: './project-explorer.component.html',
  styleUrls: ['./project-explorer.component.css']
})
export class ProjectExplorerComponent implements OnInit, OnDestroy {
  @Output() navigateToTab = new EventEmitter<string>();

  expanded = false;
  factSheets: FactSheet[] = [];
  activeFactSheet: FactSheet | null = null;
  projectName = '';
  projectStatus: ProjectStatus | null = null;

  showCreateDialog = false;
  newSheetName = '';
  newSheetDescription = '';

  private subscriptions: Subscription[] = [];

  constructor(
    private factSheetService: FactSheetService,
    private projectService: ProjectService
  ) {}

  ngOnInit(): void {
    const sheetsSub = this.factSheetService.sheets$.subscribe(sheets => {
      this.factSheets = sheets;
    });
    this.subscriptions.push(sheetsSub);

    const activeSub = this.factSheetService.activeSheet$.subscribe(sheet => {
      this.activeFactSheet = sheet;
    });
    this.subscriptions.push(activeSub);

    this.projectService.current().subscribe({
      next: (response) => {
        this.projectName = response.manifest?.name || 'Kompile Project';
        this.projectStatus = response.status;
      },
      error: () => {
        this.projectName = 'Kompile Project';
      }
    });
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach(sub => sub.unsubscribe());
  }

  toggle(): void {
    this.expanded = !this.expanded;
  }

  selectSheet(sheet: FactSheet): void {
    if (sheet.id === this.activeFactSheet?.id) return;
    this.factSheetService.activateSheet(sheet.id).subscribe();
  }

  openCreateDialog(): void {
    this.newSheetName = '';
    this.newSheetDescription = '';
    this.showCreateDialog = true;
  }

  cancelCreate(): void {
    this.showCreateDialog = false;
  }

  createSheet(): void {
    if (!this.newSheetName.trim()) return;
    const request: CreateFactSheetRequest = {
      name: this.newSheetName.trim(),
      description: this.newSheetDescription.trim() || undefined,
      color: '#1976d2',
      icon: 'folder'
    };
    this.factSheetService.createSheet(request).subscribe({
      next: (sheet) => {
        this.showCreateDialog = false;
        this.factSheetService.activateSheet(sheet.id).subscribe();
      }
    });
  }

  goToProject(): void {
    this.navigateToTab.emit('project');
  }

  goToFactSheets(): void {
    this.navigateToTab.emit('sources');
  }
}
