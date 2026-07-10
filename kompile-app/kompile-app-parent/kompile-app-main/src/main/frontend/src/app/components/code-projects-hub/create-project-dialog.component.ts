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
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { CreateCodeProjectRequest } from '../../services/code-project.service';

@Component({
  selector: 'app-create-project-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatChipsModule],
  template: `
    <h2 mat-dialog-title>Create Code Project</h2>
    <mat-dialog-content>
      <div class="form-content">
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Project Name</mat-label>
          <input matInput [(ngModel)]="project.name" (blur)="generateProjectId()" placeholder="My Project">
        </mat-form-field>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Project ID</mat-label>
          <input matInput [(ngModel)]="project.projectId" placeholder="my-project">
          <mat-hint>Unique identifier (auto-generated from name)</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Description</mat-label>
          <textarea matInput [(ngModel)]="project.description" rows="2" placeholder="What is this project?"></textarea>
        </mat-form-field>
        <div class="directories-section">
          <h3>Source Directories</h3>
          <div class="add-directory-row">
            <mat-form-field appearance="outline" class="dir-input">
              <mat-label>Directory Path</mat-label>
              <input matInput [(ngModel)]="newDirectoryPath" placeholder="/path/to/source" (keyup.enter)="addDirectory()">
            </mat-form-field>
            <button mat-icon-button color="primary" (click)="addDirectory()" [disabled]="!newDirectoryPath.trim()">
              <mat-icon>add_circle</mat-icon>
            </button>
          </div>
          <div class="directory-chips">
            <mat-chip-set>
              <mat-chip *ngFor="let dir of project.directories; let i = index" (removed)="removeDirectory(i)">
                {{ dir.path }}
                <mat-icon matChipRemove>cancel</mat-icon>
              </mat-chip>
            </mat-chip-set>
          </div>
        </div>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Tags</mat-label>
          <input matInput [(ngModel)]="project.tags" placeholder="java,backend,microservice">
        </mat-form-field>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="primary" (click)="onCreate()" [disabled]="!project.projectId || !project.name">
        Create Project
      </button>
    </mat-dialog-actions>
  `,
  styles: [`.form-content { display: flex; flex-direction: column; gap: 12px; min-width: 460px; }
    .full-width { width: 100%; }
    .directories-section h3 { margin: 4px 0 8px 0; font-size: 14px; font-weight: 500; }
    .add-directory-row { display: flex; align-items: center; gap: 8px; }
    .dir-input { flex: 1; }
    .directory-chips { margin-top: 8px; }`]
})
export class CreateProjectDialogComponent {
  project: CreateCodeProjectRequest = {
    projectId: '',
    name: '',
    description: '',
    color: '#4caf50',
    icon: 'code',
    directories: []
  };
  newDirectoryPath = '';

  constructor(private dialogRef: MatDialogRef<CreateProjectDialogComponent>) {}

  generateProjectId(): void {
    if (this.project.name && !this.project.projectId) {
      this.project.projectId = this.project.name
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-|-$/g, '');
    }
  }

  addDirectory(): void {
    if (this.newDirectoryPath.trim()) {
      if (!this.project.directories) this.project.directories = [];
      this.project.directories.push({ path: this.newDirectoryPath.trim() });
      this.newDirectoryPath = '';
    }
  }

  removeDirectory(index: number): void {
    this.project.directories?.splice(index, 1);
  }

  onCancel(): void { this.dialogRef.close(null); }

  onCreate(): void {
    if (!this.project.projectId || !this.project.name) return;
    this.dialogRef.close(this.project);
  }
}
