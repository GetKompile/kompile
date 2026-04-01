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

import { Component, OnInit, OnDestroy, Inject, Optional } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import {
  DiagnosticService,
  DiagnosticReport,
  DiagnosticCategory,
  DiagnosticCheck,
  DiagnosticStatus,
  EditableSetting
} from '../../services/diagnostic.service';
import { FactSheetService } from '../../services/fact-sheet.service';

@Component({
  selector: 'app-system-diagnostics',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatExpansionModule,
    MatDividerModule,
    MatChipsModule,
    MatDialogModule,
    MatInputModule,
    MatFormFieldModule,
    MatSnackBarModule
  ],
  templateUrl: './system-diagnostics.component.html',
  styleUrls: ['./system-diagnostics.component.css']
})
export class SystemDiagnosticsComponent implements OnInit, OnDestroy {
  report: DiagnosticReport | null = null;
  loading = false;
  error: string | null = null;
  isModal = false;

  // Inline editing state
  editingCheckId: string | null = null;
  editValues: { [key: string]: string } = {};
  savingCheckId: string | null = null;

  private destroy$ = new Subject<void>();

  constructor(
    private diagnosticService: DiagnosticService,
    private factSheetService: FactSheetService,
    private snackBar: MatSnackBar,
    @Optional() private dialogRef: MatDialogRef<SystemDiagnosticsComponent>,
    @Optional() @Inject(MAT_DIALOG_DATA) public data: any
  ) {
    this.isModal = !!dialogRef;
  }

  ngOnInit(): void {
    // Load active fact sheet for editing capabilities
    this.factSheetService.loadActiveSheet()
      .pipe(takeUntil(this.destroy$))
      .subscribe();
    this.runDiagnostics();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  runDiagnostics(): void {
    this.loading = true;
    this.error = null;

    this.diagnosticService.runDiagnostics()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (report) => {
          this.report = report;
          this.loading = false;
        },
        error: (err) => {
          this.error = err?.message || 'Failed to run diagnostics';
          this.loading = false;
        }
      });
  }

  close(): void {
    if (this.dialogRef) {
      this.dialogRef.close();
    }
  }

  getStatusIcon(status: DiagnosticStatus): string {
    switch (status) {
      case 'pass': return 'check_circle';
      case 'warning': return 'warning';
      case 'fail': return 'error';
      default: return 'help_outline';
    }
  }

  getStatusClass(status: DiagnosticStatus): string {
    return `status-${status}`;
  }

  getOverallStatusMessage(): string {
    if (!this.report) return '';

    switch (this.report.overallStatus) {
      case 'pass':
        return 'All systems operational';
      case 'warning':
        return `${this.report.warningChecks} warning(s) detected`;
      case 'fail':
        return `${this.report.failedChecks} issue(s) require attention`;
      default:
        return 'Status unknown';
    }
  }

  getPrerequisiteIcon(ready: boolean): string {
    return ready ? 'check_circle' : 'cancel';
  }

  getPrerequisiteClass(ready: boolean): string {
    return ready ? 'prereq-ready' : 'prereq-missing';
  }

  formatTimestamp(timestamp: string): string {
    return new Date(timestamp).toLocaleString();
  }

  getCategoryProgress(category: DiagnosticCategory): number {
    const total = category.checks.length;
    if (total === 0) return 100;
    return Math.round((category.passCount / total) * 100);
  }

  exportReport(): void {
    if (!this.report) return;

    const reportJson = JSON.stringify(this.report, null, 2);
    const blob = new Blob([reportJson], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `kompile-diagnostics-${new Date().toISOString().split('T')[0]}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }

  // ========== Inline Editing Methods ==========

  /**
   * Start editing a check's value
   */
  startEditing(check: DiagnosticCheck): void {
    if (!check.editable) return;
    this.editingCheckId = check.id;
    // Initialize with current value or empty string
    this.editValues[check.id] = check.value || '';
  }

  /**
   * Cancel editing
   */
  cancelEditing(): void {
    this.editingCheckId = null;
  }

  /**
   * Check if a specific check is currently being edited
   */
  isEditing(checkId: string): boolean {
    return this.editingCheckId === checkId;
  }

  /**
   * Check if a specific check is currently being saved
   */
  isSaving(checkId: string): boolean {
    return this.savingCheckId === checkId;
  }

  /**
   * Save the edited value
   */
  saveEdit(check: DiagnosticCheck): void {
    if (!check.editable) return;

    const newValue = this.editValues[check.id]?.trim();
    if (!newValue) {
      this.snackBar.open('Please enter a value', 'Close', { duration: 3000 });
      return;
    }

    // Get active fact sheet to update
    const activeSheet = this.factSheetService.getActiveSheet();
    if (!activeSheet) {
      this.snackBar.open('No active fact sheet to update', 'Close', { duration: 3000 });
      return;
    }

    this.savingCheckId = check.id;

    // Build update request based on the setting key
    const updateRequest: any = {};
    updateRequest[check.editable.settingKey] = newValue;

    this.factSheetService.updateSheet(activeSheet.id, updateRequest)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`${check.editable!.label} updated successfully`, 'Close', {
            duration: 3000,
            panelClass: 'success-snackbar'
          });
          this.editingCheckId = null;
          this.savingCheckId = null;
          // Refresh diagnostics to show updated value
          this.runDiagnostics();
        },
        error: (err) => {
          this.snackBar.open(`Failed to update: ${err.message}`, 'Close', {
            duration: 5000,
            panelClass: 'error-snackbar'
          });
          this.savingCheckId = null;
        }
      });
  }

  /**
   * Handle Enter key to save
   */
  onEditKeydown(event: KeyboardEvent, check: DiagnosticCheck): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      this.saveEdit(check);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      this.cancelEditing();
    }
  }
}
