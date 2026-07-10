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
import { EndpointMapping, EndpointTestResult, RestMcpBridgeService } from '../../services/rest-mcp-bridge.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSnackBarModule } from '@angular/material/snack-bar';

export interface TestMappingDialogData {
  mapping: EndpointMapping;
  bridgeId: string;
}

@Component({
  selector: 'app-test-mapping-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSnackBarModule],
  template: `
    <h3 mat-dialog-title>Test Endpoint Mapping</h3>
    <mat-dialog-content>
      <div class="form-content">
        <div class="test-info">
          <p><strong>Tool:</strong> {{ data.mapping.mcpTool.name }}</p>
          <p><strong>Endpoint:</strong> {{ data.mapping.restEndpoint.method }} {{ data.mapping.restEndpoint.path }}</p>
        </div>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Test Input (JSON)</mat-label>
          <textarea matInput [(ngModel)]="testInput" rows="5" placeholder='{"param": "value"}'></textarea>
        </mat-form-field>
        <button mat-raised-button color="primary" (click)="runTest()" [disabled]="isTesting">
          <mat-icon>{{ isTesting ? 'hourglass_empty' : 'play_arrow' }}</mat-icon>
          {{ isTesting ? 'Testing...' : 'Run Test' }}
        </button>
        <div class="test-result" *ngIf="testResult">
          <div class="result-header" [class.success]="testResult.success" [class.error]="!testResult.success">
            <mat-icon>{{ testResult.success ? 'check_circle' : 'error' }}</mat-icon>
            <span>{{ testResult.success ? 'Success' : 'Failed' }}</span>
            <span class="status-code" *ngIf="testResult.statusCode">Status: {{ testResult.statusCode }}</span>
            <span class="duration">{{ testResult.durationMs }}ms</span>
          </div>
          <div class="result-body" *ngIf="testResult.response">
            <h5>Response:</h5>
            <pre>{{ testResult.response | json }}</pre>
          </div>
          <div class="result-error" *ngIf="testResult.error">
            <h5>Error:</h5>
            <pre>{{ testResult.error }}</pre>
          </div>
        </div>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onClose()">Close</button>
    </mat-dialog-actions>
  `,
  styles: [`.form-content { display: flex; flex-direction: column; gap: 15px; min-width: 580px; max-width: 780px; }
    .full-width { width: 100%; }
    .test-info p { margin: 4px 0; }
    .result-header { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 4px; }
    .result-header.success { background: #e8f5e9; color: #2e7d32; }
    .result-header.error { background: #ffebee; color: #c62828; }
    .status-code, .duration { font-size: 12px; color: inherit; opacity: 0.8; }
    .result-body, .result-error { margin-top: 8px; }
    .result-body h5, .result-error h5 { margin: 0 0 4px 0; }
    pre { background: var(--bg-body); padding: 8px; border-radius: 4px; overflow: auto; max-height: 200px; font-size: 12px; }`]
})
export class TestMappingDialogComponent {
  testInput = '{}';
  testResult: EndpointTestResult | null = null;
  isTesting = false;

  constructor(
    private dialogRef: MatDialogRef<TestMappingDialogComponent>,
    private bridgeService: RestMcpBridgeService,
    private snackBar: MatSnackBar,
    @Inject(MAT_DIALOG_DATA) public data: TestMappingDialogData
  ) {}

  runTest(): void {
    if (!this.data || !this.data.mapping.id) return;
    this.isTesting = true;
    this.testResult = null;

    let input: any;
    try {
      input = JSON.parse(this.testInput);
    } catch (e) {
      this.snackBar.open('Invalid JSON input', 'Close', { duration: 3000 });
      this.isTesting = false;
      return;
    }

    this.bridgeService.testMapping(this.data.bridgeId, this.data.mapping.id!, input).subscribe({
      next: (result) => {
        this.testResult = result;
        this.isTesting = false;
      },
      error: (err) => {
        this.testResult = {
          success: false,
          statusCode: 0,
          error: err.error?.error || err.message,
          durationMs: 0
        };
        this.isTesting = false;
      }
    });
  }

  // Expose data for template (needed because `data` is a parameter reference)
  get data2() { return this.data; }

  onClose(): void { this.dialogRef.close(); }
}
