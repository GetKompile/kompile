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

import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule, MatDialog } from '@angular/material/dialog';
import { filter } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import { ClipboardModule, Clipboard } from '@angular/cdk/clipboard';

import { OAuthSettingsService, OAuthProviderSettings, ProviderSetupInfo } from '../../services/oauth-settings.service';

export interface DialogData {
  providerId: string;
  displayName: string;
  icon: string;
  color: string;
  settings?: OAuthProviderSettings;
  setupInfo?: ProviderSetupInfo;
}

@Component({
  selector: 'app-oauth-settings-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatDividerModule,
    MatExpansionModule,
    ClipboardModule,
    ConfirmDialogComponent
  ],
  templateUrl: './oauth-settings-dialog.component.html',
  styleUrls: ['./oauth-settings-dialog.component.css']
})
export class OAuthSettingsDialogComponent implements OnInit {
  form!: FormGroup;
  saving = false;
  showSecret = false;
  callbackUrl = '';

  constructor(
    private fb: FormBuilder,
    private settingsService: OAuthSettingsService,
    private snackBar: MatSnackBar,
    private clipboard: Clipboard,
    private dialog: MatDialog,
    public dialogRef: MatDialogRef<OAuthSettingsDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: DialogData
  ) {}

  ngOnInit(): void {
    this.callbackUrl = this.settingsService.getCallbackUrl(this.data.providerId);

    this.form = this.fb.group({
      clientId: [this.data.settings?.clientId || '', Validators.required],
      clientSecret: [this.data.settings?.clientSecret || '', Validators.required],
      tenantId: [this.data.settings?.tenantId || ''],
      scopes: [this.data.settings?.scopes || this.getDefaultScopes()]
    });

    // Show tenant ID field only for Microsoft
    if (this.data.providerId !== 'microsoft') {
      this.form.removeControl('tenantId');
    }
  }

  getDefaultScopes(): string {
    return this.data.setupInfo?.defaultScopes?.join(' ') || '';
  }

  toggleSecretVisibility(): void {
    this.showSecret = !this.showSecret;
  }

  copyToClipboard(text: string): void {
    this.clipboard.copy(text);
    this.snackBar.open('Copied to clipboard', 'Close', { duration: 2000 });
  }

  onSave(): void {
    if (!this.form.valid) {
      this.snackBar.open('Please fill in all required fields', 'Close', { duration: 3000 });
      return;
    }

    this.saving = true;
    const settings: OAuthProviderSettings = {
      providerId: this.data.providerId,
      clientId: this.form.value.clientId,
      clientSecret: this.form.value.clientSecret,
      tenantId: this.form.value.tenantId,
      scopes: this.form.value.scopes,
      configured: true
    };

    this.settingsService.saveSettings(settings).subscribe({
      next: (saved) => {
        this.saving = false;
        this.snackBar.open('Settings saved successfully', 'Close', { duration: 3000 });
        this.dialogRef.close({ saved: true, settings: saved });
      },
      error: (error) => {
        this.saving = false;
        console.error('Error saving settings:', error);
        this.snackBar.open('Failed to save settings', 'Close', { duration: 5000 });
      }
    });
  }

  onDelete(): void {
    const dialogData: ConfirmDialogData = {
      title: 'Remove Configuration',
      message: `Are you sure you want to remove the ${this.data.displayName} configuration?`,
      confirmText: 'Remove',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.saving = true;
        this.settingsService.deleteSettings(this.data.providerId).subscribe({
          next: () => {
            this.saving = false;
            this.snackBar.open('Configuration removed', 'Close', { duration: 3000 });
            this.dialogRef.close({ deleted: true });
          },
          error: (error) => {
            this.saving = false;
            console.error('Error deleting settings:', error);
            this.snackBar.open('Failed to remove configuration', 'Close', { duration: 5000 });
          }
        });
      });
  }

  onCancel(): void {
    this.dialogRef.close();
  }
}
