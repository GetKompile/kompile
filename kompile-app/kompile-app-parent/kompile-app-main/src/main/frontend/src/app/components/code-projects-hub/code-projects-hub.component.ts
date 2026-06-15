/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Subscription, interval } from 'rxjs';
import { CodeProjectService, CodeProject, CreateCodeProjectRequest, IndexingProgress, ProjectSession } from '../../services/code-project.service';
import { WebSocketService } from '../../services/websocket.service';
import { CodeGraphBuilderComponent } from '../code-graph-builder/code-graph-builder.component';
import { DiffIndexBrowserComponent } from '../diff-index-browser/diff-index-browser.component';

@Component({
  selector: 'app-code-projects-hub',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTabsModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatChipsModule,
    MatCardModule,
    MatTooltipModule,
    MatMenuModule,
    MatDividerModule,
    MatSlideToggleModule,
    CodeGraphBuilderComponent,
    DiffIndexBrowserComponent
  ],
  templateUrl: './code-projects-hub.component.html',
  styleUrls: ['./code-projects-hub.component.css']
})
export class CodeProjectsHubComponent implements OnInit, OnDestroy {
  projects: CodeProject[] = [];
  activeProject: CodeProject | null = null;
  selectedProject: CodeProject | null = null;
  factSheet: any = null;

  // Create project dialog
  showCreateDialog = false;
  newProject: CreateCodeProjectRequest = {
    projectId: '',
    name: '',
    description: '',
    color: '#4caf50',
    icon: 'code',
    directories: []
  };
  newDirectoryPath = '';

  // Indexing progress
  indexingProgress: IndexingProgress | null = null;

  // Sessions
  projectSessions: ProjectSession[] = [];

  // Tab state
  activeTabIndex = 0;

  private subscriptions: Subscription[] = [];

  constructor(
    private projectService: CodeProjectService,
    private webSocketService: WebSocketService
  ) {}

  ngOnInit(): void {
    this.loadProjects();

    const projSub = this.projectService.projects$.subscribe(projects => {
      this.projects = projects;
    });
    this.subscriptions.push(projSub);

    const activeSub = this.projectService.activeProject$.subscribe(active => {
      this.activeProject = active;
      if (active && !this.selectedProject) {
        this.selectedProject = active;
        this.loadFactSheet(active.projectId);
        this.loadProjectSessions(active.projectId);
      }
    });
    this.subscriptions.push(activeSub);
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach(sub => sub.unsubscribe());
  }

  loadProjects(): void {
    this.projectService.loadProjects().subscribe({
      error: (err) => console.error('Failed to load code projects:', err)
    });
  }

  selectProject(project: CodeProject): void {
    this.selectedProject = project;
    this.loadFactSheet(project.projectId);
    this.loadIndexStatus(project.projectId);
    this.loadProjectSessions(project.projectId);
  }

  loadFactSheet(projectId: string): void {
    this.projectService.getFactSheet(projectId).subscribe({
      next: (sheet) => this.factSheet = sheet,
      error: () => this.factSheet = null
    });
  }

  loadIndexStatus(projectId: string): void {
    this.projectService.getIndexStatus(projectId).subscribe({
      next: (status) => {
        if (status.progressPercent !== undefined) {
          this.indexingProgress = status;
          if (!status.completed) {
            this.pollIndexProgress(projectId);
          }
        } else {
          this.indexingProgress = null;
        }
      },
      error: () => this.indexingProgress = null
    });
  }

  private pollIndexProgress(projectId: string): void {
    const pollSub = interval(1000).subscribe(() => {
      this.projectService.getIndexStatus(projectId).subscribe({
        next: (status) => {
          if (status.progressPercent !== undefined) {
            this.indexingProgress = status;
            if (status.completed) {
              pollSub.unsubscribe();
              this.loadProjects();
              this.loadFactSheet(projectId);
            }
          }
        },
        error: () => {
          pollSub.unsubscribe();
          this.indexingProgress = null;
        }
      });
    });
    this.subscriptions.push(pollSub);
  }

  // ── Create Project ─────────────────────────────────────────────

  openCreateDialog(): void {
    this.newProject = {
      projectId: '',
      name: '',
      description: '',
      color: '#4caf50',
      icon: 'code',
      directories: []
    };
    this.newDirectoryPath = '';
    this.showCreateDialog = true;
  }

  closeCreateDialog(): void {
    this.showCreateDialog = false;
  }

  addDirectory(): void {
    if (this.newDirectoryPath.trim()) {
      if (!this.newProject.directories) {
        this.newProject.directories = [];
      }
      this.newProject.directories.push({ path: this.newDirectoryPath.trim() });
      this.newDirectoryPath = '';
    }
  }

  removeDirectory(index: number): void {
    this.newProject.directories?.splice(index, 1);
  }

  generateProjectId(): void {
    if (this.newProject.name && !this.newProject.projectId) {
      this.newProject.projectId = this.newProject.name
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-|-$/g, '');
    }
  }

  createProject(): void {
    if (!this.newProject.projectId || !this.newProject.name) return;

    this.projectService.createProject(this.newProject).subscribe({
      next: (project) => {
        this.showCreateDialog = false;
        this.selectProject(project);
      },
      error: (err) => console.error('Failed to create project:', err)
    });
  }

  // ── Project Actions ────────────────────────────────────────────

  activateProject(project: CodeProject): void {
    this.projectService.activateProject(project.projectId).subscribe({
      error: (err) => console.error('Failed to activate project:', err)
    });
  }

  indexProject(project: CodeProject, forceReindex: boolean = false): void {
    this.projectService.indexProject(project.projectId, forceReindex).subscribe({
      next: () => {
        this.indexingProgress = {
          projectId: project.projectId,
          totalFiles: 0,
          filesProcessed: 0,
          filesSkipped: 0,
          entitiesFound: 0,
          relationsCreated: 0,
          errors: 0,
          completed: false,
          incremental: !forceReindex,
          progressPercent: 0
        };
        this.pollIndexProgress(project.projectId);
      },
      error: (err) => console.error('Failed to start indexing:', err)
    });
  }

  deleteProject(project: CodeProject): void {
    if (!confirm(`Delete project "${project.name}"? This will remove all indexed data.`)) return;

    this.projectService.deleteProject(project.projectId).subscribe({
      next: () => {
        if (this.selectedProject?.projectId === project.projectId) {
          this.selectedProject = null;
          this.factSheet = null;
        }
      },
      error: (err) => console.error('Failed to delete project:', err)
    });
  }

  // ── Sessions ───────────────────────────────────────────────────

  loadProjectSessions(projectId: string): void {
    this.projectService.getProjectSessions(projectId).subscribe({
      next: (sessions) => this.projectSessions = sessions,
      error: () => this.projectSessions = []
    });
  }

  getSourceIcon(source: string | null): string {
    switch (source) {
      case 'kompile': return 'terminal';
      case 'claude-code': return 'smart_toy';
      case 'codex': return 'psychology';
      case 'opencode': return 'code';
      case 'qwen': return 'auto_awesome';
      default: return 'chat';
    }
  }

  // ── Helpers ────────────────────────────────────────────────────

  getStateIcon(state: string): string {
    switch (state) {
      case 'INDEXED': return 'check_circle';
      case 'INDEXING': return 'sync';
      case 'FAILED': return 'error';
      case 'STALE': return 'update';
      default: return 'hourglass_empty';
    }
  }

  getStateColor(state: string): string {
    switch (state) {
      case 'INDEXED': return '#4caf50';
      case 'INDEXING': return '#2196f3';
      case 'FAILED': return '#f44336';
      case 'STALE': return '#ff9800';
      default: return '#9e9e9e';
    }
  }

  getLanguagesList(languages: string | null): string[] {
    if (!languages) return [];
    return languages.split(',').filter(l => l.trim());
  }

  formatDate(dateStr: string | null): string {
    if (!dateStr) return 'Never';
    const date = new Date(dateStr);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
  }
}
