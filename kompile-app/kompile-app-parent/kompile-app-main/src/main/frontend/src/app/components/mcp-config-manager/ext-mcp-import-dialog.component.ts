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
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

@Component({
  selector: 'app-ext-mcp-import-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h3 mat-dialog-title>Import MCP Configuration</h3>
    <mat-dialog-content>
      <p class="hint">Paste a Claude Desktop configuration JSON. Servers will be merged with existing configurations.</p>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>JSON Configuration</mat-label>
        <textarea matInput [(ngModel)]="importJson" rows="15" placeholder='{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "."],
      "env": {}
    }
  }
}'></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="primary" (click)="onImport()" [disabled]="!importJson.trim()">Import</button>
    </mat-dialog-actions>
  `,
  styles: [`.full-width { width: 100%; min-width: 450px; } .hint { color: var(--text-secondary); font-size: 14px; margin: 0 0 12px; }`]
})
export class ExtMcpImportDialogComponent {
  importJson = '';

  constructor(private dialogRef: MatDialogRef<ExtMcpImportDialogComponent>) {}

  onCancel(): void { this.dialogRef.close(null); }
  onImport(): void {
    if (!this.importJson.trim()) return;
    this.dialogRef.close(this.importJson);
  }
}
