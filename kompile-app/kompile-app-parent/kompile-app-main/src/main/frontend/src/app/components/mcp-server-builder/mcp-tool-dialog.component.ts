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
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { McpToolConfig, ToolImplementationType, ParameterConfig } from '../../services/mcp-server-builder.service';

export interface McpToolDialogData {
  tool: McpToolConfig;
  toolTypes: ToolImplementationType[];
  parameterTypes: string[];
}

@Component({
  selector: 'app-mcp-tool-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule, MatCheckboxModule],
  template: `
    <h3 mat-dialog-title>{{ tool.name ? 'Edit' : 'Create' }} Tool</h3>
    <mat-dialog-content>
      <div class="form-content">
        <mat-form-field appearance="outline">
          <mat-label>Tool Name</mat-label>
          <input matInput [(ngModel)]="tool.name" placeholder="my_tool">
          <mat-hint>Use snake_case for tool names</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Description</mat-label>
          <textarea matInput [(ngModel)]="tool.description" rows="2"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Implementation Type</mat-label>
          <mat-select [(ngModel)]="tool.implementationType">
            <mat-option *ngFor="let type of data.toolTypes" [value]="type">{{ type }}</mat-option>
          </mat-select>
        </mat-form-field>
        <div class="config-section" *ngIf="tool.implementationType === 'HTTP_ENDPOINT'">
          <h4>HTTP Configuration</h4>
          <mat-form-field appearance="outline">
            <mat-label>URL</mat-label>
            <input matInput [(ngModel)]="tool.httpConfig!.url" placeholder="http://localhost:8080/api/endpoint">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Method</mat-label>
            <mat-select [(ngModel)]="tool.httpConfig!.method">
              <mat-option value="GET">GET</mat-option>
              <mat-option value="POST">POST</mat-option>
              <mat-option value="PUT">PUT</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Timeout (ms)</mat-label>
            <input matInput type="number" [(ngModel)]="tool.httpConfig!.timeoutMs">
          </mat-form-field>
        </div>
        <div class="params-section">
          <h4>Parameters <button mat-icon-button (click)="addParameter()"><mat-icon>add</mat-icon></button></h4>
          <div class="param-row" *ngFor="let param of tool.parameters; let i = index">
            <mat-form-field appearance="outline">
              <mat-label>Name</mat-label>
              <input matInput [(ngModel)]="param.name">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Type</mat-label>
              <mat-select [(ngModel)]="param.type">
                <mat-option *ngFor="let t of data.parameterTypes" [value]="t">{{ t }}</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Description</mat-label>
              <input matInput [(ngModel)]="param.description">
            </mat-form-field>
            <mat-checkbox [(ngModel)]="param.required">Required</mat-checkbox>
            <button mat-icon-button color="warn" (click)="removeParameter(i)">
              <mat-icon>delete</mat-icon>
            </button>
          </div>
        </div>
        <mat-slide-toggle [(ngModel)]="tool.enabled">Enabled</mat-slide-toggle>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="primary" (click)="onSave()">Save</button>
    </mat-dialog-actions>
  `,
  styles: [`.form-content { display: flex; flex-direction: column; gap: 15px; min-width: 580px; max-width: 780px; }
    .form-content mat-form-field { width: 100%; }
    .config-section, .params-section { border: 1px solid var(--border-color); border-radius: 8px; padding: 15px; background: var(--bg-body); }
    .config-section h4, .params-section h4 { margin: 0 0 15px 0; font-size: 14px; font-weight: 500; display: flex; align-items: center; gap: 10px; }
    .param-row { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
    .param-row mat-form-field { flex: 1; min-width: 120px; }`]
})
export class McpToolDialogComponent {
  tool: McpToolConfig;

  constructor(
    private dialogRef: MatDialogRef<McpToolDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: McpToolDialogData
  ) {
    this.tool = { ...data.tool, parameters: [...(data.tool.parameters || [])] };
  }

  addParameter(): void {
    this.tool.parameters.push({ name: '', type: 'string', description: '', required: false });
  }

  removeParameter(index: number): void {
    this.tool.parameters.splice(index, 1);
  }

  onCancel(): void { this.dialogRef.close(null); }
  onSave(): void { this.dialogRef.close(this.tool); }
}
