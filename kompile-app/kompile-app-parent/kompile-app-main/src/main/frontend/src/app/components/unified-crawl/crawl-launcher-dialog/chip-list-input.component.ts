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
import { COMMA, ENTER } from '@angular/cdk/keycodes';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatChipInputEvent, MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';

/**
 * Reusable token-list editor backed by Material chips. Replaces the comma-separated text inputs:
 * type a value and press Enter/comma to add a chip, click × to remove. Mutates the bound
 * {@link items} array in place so the host reads it back directly at submit time.
 */
@Component({
  selector: 'app-chip-list-input',
  standalone: true,
  imports: [CommonModule, MatFormFieldModule, MatChipsModule, MatIconModule],
  template: `
    <mat-form-field appearance="outline" class="full-width">
      <mat-label>{{ label }}</mat-label>
      <mat-chip-grid #grid [attr.aria-label]="label">
        <mat-chip-row *ngFor="let item of items" (removed)="remove(item)">
          {{ item }}
          <button matChipRemove [attr.aria-label]="'Remove ' + item">
            <mat-icon>cancel</mat-icon>
          </button>
        </mat-chip-row>
      </mat-chip-grid>
      <input [placeholder]="placeholder"
             [matChipInputFor]="grid"
             [matChipInputSeparatorKeyCodes]="separators"
             (matChipInputTokenEnd)="add($event)">
      <mat-hint *ngIf="hint">{{ hint }}</mat-hint>
    </mat-form-field>
  `,
  styles: [`
    .full-width { width: 100%; }
  `]
})
export class ChipListInputComponent {
  @Input() label = '';
  @Input() items: string[] = [];
  @Input() placeholder = 'Type and press Enter…';
  @Input() hint = '';

  readonly separators: number[] = [ENTER, COMMA];

  add(event: MatChipInputEvent): void {
    const value = (event.value || '').trim();
    if (value && !this.items.includes(value)) {
      this.items.push(value);
    }
    event.chipInput!.clear();
  }

  remove(item: string): void {
    const index = this.items.indexOf(item);
    if (index >= 0) {
      this.items.splice(index, 1);
    }
  }
}
