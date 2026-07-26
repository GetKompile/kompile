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

import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/** A single editable key → value pair. */
export interface KvRow {
  key: string;
  value: string;
}

/**
 * Reusable key/value pair editor. Mutates the bound {@link rows} array in place so the host can
 * read it back directly (used for source properties, terminology glossary, pipeline options, and
 * route-rule metadata matchers — anything that maps to a {@code Map<String, ?>} on the backend).
 */
@Component({
  selector: 'app-key-value-editor',
  standalone: true,
  imports: [CommonModule, FormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  template: `
    <div class="kv-editor">
      <div class="kv-label" *ngIf="label">{{ label }}</div>
      <div class="kv-row" *ngFor="let row of rows; let i = index">
        <mat-form-field appearance="outline">
          <mat-label>{{ keyLabel }}</mat-label>
          <input matInput [(ngModel)]="row.key" [placeholder]="keyPlaceholder">
        </mat-form-field>
        <span class="kv-arrow">→</span>
        <mat-form-field appearance="outline">
          <mat-label>{{ valueLabel }}</mat-label>
          <input matInput [(ngModel)]="row.value" [placeholder]="valuePlaceholder">
        </mat-form-field>
        <button mat-icon-button color="warn" (click)="remove(i)" aria-label="Remove row">
          <mat-icon>delete</mat-icon>
        </button>
      </div>
      <button mat-stroked-button class="kv-add" (click)="add()">
        <mat-icon>add</mat-icon> {{ addLabel }}
      </button>
    </div>
  `,
  styles: [`
    .kv-editor { margin: 8px 0 12px; }
    .kv-label { font-size: 12px; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; }
    .kv-row { display: flex; gap: 8px; align-items: center; }
    .kv-row mat-form-field { flex: 1; }
    .kv-arrow { color: var(--text-tertiary); font-size: 16px; flex-shrink: 0; }
    .kv-add { margin-top: 4px; }
  `]
})
export class KeyValueEditorComponent {
  @Input() rows: KvRow[] = [];
  @Input() label = '';
  @Input() keyLabel = 'Key';
  @Input() valueLabel = 'Value';
  @Input() keyPlaceholder = '';
  @Input() valuePlaceholder = '';
  @Input() addLabel = 'Add entry';

  add(): void {
    this.rows.push({ key: '', value: '' });
  }

  remove(index: number): void {
    this.rows.splice(index, 1);
  }
}
