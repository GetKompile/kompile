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

import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { McpResourceConfig, ResourceType } from '@shared/services/mcp-server-builder.service';

export interface McpResourceDialogData {
  resource: McpResourceConfig;
  resourceTypes: ResourceType[];
}

@Component({
  selector: 'app-mcp-resource-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule],
  template: `
    <h3 mat-dialog-title>{{ resource.name ? 'Edit' : 'Create' }} Resource</h3>
    <mat-dialog-content>
      <div class="form-content">
        <mat-form-field appearance="outline">
          <mat-label>URI</mat-label>
          <input matInput [(ngModel)]="resource.uri" placeholder="custom://my-resource">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput [(ngModel)]="resource.name">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Description</mat-label>
          <textarea matInput [(ngModel)]="resource.description" rows="2"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>MIME Type</mat-label>
          <input matInput [(ngModel)]="resource.mimeType" placeholder="text/plain">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Resource Type</mat-label>
          <mat-select [(ngModel)]="resource.resourceType">
            <mat-option *ngFor="let type of data.resourceTypes" [value]="type">{{ type }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" *ngIf="resource.resourceType === 'STATIC'">
          <mat-label>Static Content</mat-label>
          <textarea matInput [(ngModel)]="resource.staticContent" rows="5"></textarea>
        </mat-form-field>
        <div class="toggles">
          <mat-slide-toggle [(ngModel)]="resource.supportsSubscription">Supports Subscription</mat-slide-toggle>
          <mat-slide-toggle [(ngModel)]="resource.enabled">Enabled</mat-slide-toggle>
        </div>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="primary" (click)="onSave()">Save</button>
    </mat-dialog-actions>
  `,
  styles: [`.form-content { display: flex; flex-direction: column; gap: 15px; min-width: 580px; max-width: 780px; }
    .form-content mat-form-field { width: 100%; }
    .toggles { display: flex; gap: 20px; flex-wrap: wrap; }`]
})
export class McpResourceDialogComponent {
  resource: McpResourceConfig;

  constructor(
    private dialogRef: MatDialogRef<McpResourceDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: McpResourceDialogData
  ) {
    this.resource = { ...data.resource };
  }

  onCancel(): void { this.dialogRef.close(null); }
  onSave(): void { this.dialogRef.close(this.resource); }
}
