/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { finalize } from 'rxjs/operators';

interface ServiceEndpointsConfig {
  stagingUrl: string;
}

/**
 * Chat-owned connection settings.
 *
 * The end-user Chat package persists its Model Staging dependency through the
 * managed service-endpoints.json surface shared with the CLI. It deliberately
 * does not use Spring properties, environment-specific Angular builds, or
 * browser-local storage.
 */
@Component({
  selector: 'app-chat-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSnackBarModule
  ],
  templateUrl: './chat-settings.component.html',
  styleUrls: ['./chat-settings.component.css']
})
export class ChatSettingsComponent implements OnInit {
  stagingUrl = 'http://localhost:8090';
  loading = false;
  saving = false;
  error = '';

  constructor(private readonly http: HttpClient, private readonly snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.loading = true;
    this.error = '';
    this.http.get<ServiceEndpointsConfig>('/api/service-endpoints')
      .pipe(finalize(() => this.loading = false))
      .subscribe({
        next: config => this.stagingUrl = config.stagingUrl,
        error: error => this.error = error?.error?.error || 'Could not load service connections.'
      });
  }

  save(): void {
    const normalized = this.normalizeHttpBaseUrl(this.stagingUrl);
    if (!normalized) {
      this.error = 'Enter an HTTP(S) Model Staging base URL.';
      return;
    }

    this.saving = true;
    this.error = '';
    this.http.post<ServiceEndpointsConfig>('/api/service-endpoints', { stagingUrl: normalized })
      .pipe(finalize(() => this.saving = false))
      .subscribe({
        next: config => {
          this.stagingUrl = config.stagingUrl;
          this.snackBar.open('Model Staging connection saved', 'Close', { duration: 3000 });
        },
        error: error => this.error = error?.error?.error || 'Could not save service connections.'
      });
  }

  private normalizeHttpBaseUrl(value: string): string | null {
    try {
      const url = new URL((value || '').trim());
      if ((url.protocol !== 'http:' && url.protocol !== 'https:') ||
          url.username || url.password || url.search || url.hash) {
        return null;
      }
      return url.toString().replace(/\/$/, '');
    } catch {
      return null;
    }
  }
}
