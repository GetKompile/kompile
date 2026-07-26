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
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ProjectStoreService, ProjectStoreConfig, RemoteProject } from '@shared/services/project-store.service';

/**
 * "Project Store" tab: configure the Kompile-managed external store pointer, browse the projects
 * it advertises, and clone one into the local workspace.
 */
@Component({
  selector: 'app-project-store-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatTooltipModule,
    MatSnackBarModule
  ],
  templateUrl: './project-store-panel.component.html',
  styleUrls: ['./project-store-panel.component.css']
})
export class ProjectStorePanelComponent implements OnInit {
  config: ProjectStoreConfig = { configured: false };
  urlInput = '';
  gitXet = true;
  savingConfig = false;

  projects: RemoteProject[] = [];
  loadingProjects = false;
  projectsError: string | null = null;
  cloningSlug: string | null = null;

  constructor(private store: ProjectStoreService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.loadConfig();
  }

  loadConfig(): void {
    this.store.getConfig().subscribe({
      next: (cfg) => {
        this.config = cfg || { configured: false };
        this.urlInput = cfg?.url || '';
        this.gitXet = cfg?.gitXet !== false;
        if (this.config.configured) {
          this.loadProjects();
        }
      },
      error: () => { /* leave unconfigured — the panel prompts for a URL */ }
    });
  }

  saveConfig(): void {
    if (!this.urlInput.trim()) {
      return;
    }
    this.savingConfig = true;
    this.store.setConfig({ url: this.urlInput.trim(), gitXet: this.gitXet }).subscribe({
      next: (cfg) => {
        this.config = cfg;
        this.savingConfig = false;
        this.snackBar.open('Project store saved', 'Dismiss', { duration: 2500, panelClass: 'snackbar-success' });
        if (cfg.configured) {
          this.loadProjects();
        } else {
          this.projects = [];
        }
      },
      error: (err) => {
        this.savingConfig = false;
        this.snackBar.open(this.errMsg(err, 'Failed to save store config'), 'Dismiss',
          { duration: 4000, panelClass: 'snackbar-error' });
      }
    });
  }

  loadProjects(): void {
    this.loadingProjects = true;
    this.projectsError = null;
    this.store.listProjects().subscribe({
      next: (projects) => {
        this.projects = projects || [];
        this.loadingProjects = false;
      },
      error: (err) => {
        this.projectsError = this.errMsg(err, 'Failed to reach the project store');
        this.loadingProjects = false;
      }
    });
  }

  cloneProject(project: RemoteProject): void {
    this.cloningSlug = project.slug;
    this.store.clone(project.namespace, project.slug).subscribe({
      next: (res) => {
        this.cloningSlug = null;
        this.snackBar.open(`Cloned ${res.fullName} → ${res.path}`, 'Dismiss',
          { duration: 6000, panelClass: 'snackbar-success' });
      },
      error: (err) => {
        this.cloningSlug = null;
        this.snackBar.open(this.errMsg(err, 'Clone failed'), 'Dismiss',
          { duration: 6000, panelClass: 'snackbar-error' });
      }
    });
  }

  iconFor(repoType?: string): string {
    switch ((repoType || '').toLowerCase()) {
      case 'model': return 'smart_toy';
      case 'dataset': return 'dataset';
      case 'space': return 'rocket_launch';
      default: return 'folder_special';
    }
  }

  // The Kompile GlobalExceptionHandler returns { error, message, ... } under err.error.
  private errMsg(err: any, fallback: string): string {
    return err?.error?.message || err?.message || fallback;
  }
}
