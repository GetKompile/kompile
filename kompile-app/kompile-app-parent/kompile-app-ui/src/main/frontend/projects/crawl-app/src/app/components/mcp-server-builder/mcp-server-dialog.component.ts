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
import { McpServerConfig, TransportType } from '@shared/services/mcp-server-builder.service';

export interface McpServerDialogData {
  server: McpServerConfig;
  isEditing: boolean;
  transportTypes: TransportType[];
}

@Component({
  selector: 'app-mcp-server-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule],
  template: `
    <h3 mat-dialog-title>{{ data.isEditing ? 'Edit' : 'Create' }} MCP Server</h3>
    <mat-dialog-content>
      <div class="form-content" *ngIf="server">
        <mat-form-field appearance="outline">
          <mat-label>Server Name</mat-label>
          <input matInput [(ngModel)]="server.name" placeholder="My MCP Server">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Version</mat-label>
          <input matInput [(ngModel)]="server.version" placeholder="1.0.0">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Description</mat-label>
          <textarea matInput [(ngModel)]="server.description" rows="3"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Transport Type</mat-label>
          <mat-select [(ngModel)]="server.transportType">
            <mat-option *ngFor="let type of data.transportTypes" [value]="type">{{ type }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" *ngIf="server.transportType !== 'STDIO'">
          <mat-label>Port</mat-label>
          <input matInput type="number" [(ngModel)]="server.port" min="1" max="65535">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Base Path</mat-label>
          <input matInput [(ngModel)]="server.basePath" placeholder="/mcp">
        </mat-form-field>
        <div class="toggles">
          <mat-slide-toggle [(ngModel)]="server.loggingEnabled">Enable Logging</mat-slide-toggle>
          <mat-slide-toggle [(ngModel)]="server.completionsEnabled">Enable Completions</mat-slide-toggle>
        </div>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="primary" (click)="onSave()">Save</button>
    </mat-dialog-actions>
  `,
  styles: [`.form-content { display: flex; flex-direction: column; gap: 15px; min-width: 400px; }
    .form-content mat-form-field { width: 100%; }
    .toggles { display: flex; gap: 20px; flex-wrap: wrap; }`]
})
export class McpServerDialogComponent {
  server: McpServerConfig;

  constructor(
    private dialogRef: MatDialogRef<McpServerDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: McpServerDialogData
  ) {
    this.server = { ...data.server };
  }

  onCancel(): void { this.dialogRef.close(null); }
  onSave(): void { this.dialogRef.close(this.server); }
}
