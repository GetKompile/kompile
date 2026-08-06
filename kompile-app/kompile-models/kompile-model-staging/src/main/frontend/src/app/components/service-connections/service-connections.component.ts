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
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';
import { finalize, switchMap } from 'rxjs/operators';

import { StagingService, StagingSettings } from '../../services/staging.service';

interface ServiceEndpointsConfig {
  servingUrl: string;
}

/** Model Staging-owned UI for its independently deployable service dependencies. */
@Component({
  selector: 'app-service-connections',
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
    MatSlideToggleModule,
    MatSnackBarModule
  ],
  templateUrl: './service-connections.component.html',
  styleUrls: ['./service-connections.component.css']
})
export class ServiceConnectionsComponent implements OnInit {
  servingUrl = '';
  callbackUrl = '';
  autoReloadEnabled = true;
  callbackTimeoutMs = 30000;
  loading = false;
  saving = false;
  testing = false;
  error = '';
  testMessage = '';

  private settings: StagingSettings | null = null;

  constructor(
    private readonly staging: StagingService,
    private readonly http: HttpClient,
    private readonly snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loading = true;
    forkJoin({
      settings: this.staging.getSettings(),
      endpoints: this.http.get<ServiceEndpointsConfig>('/api/service-endpoints')
    })
      .pipe(finalize(() => this.loading = false))
      .subscribe({
        next: ({ settings, endpoints }) => {
          this.apply(settings);
          this.servingUrl = endpoints.servingUrl || '';
        },
        error: error => this.error = error?.message || 'Could not load service connections.'
      });
  }

  save(): void {
    const update = this.buildUpdate();
    const servingUrl = this.normalizeServingUrl(this.servingUrl);
    if (!update || !servingUrl) {
      return;
    }
    this.saving = true;
    this.error = '';
    this.testMessage = '';
    this.http.post<ServiceEndpointsConfig>('/api/service-endpoints', { servingUrl })
      .pipe(
        switchMap(endpoints => {
          this.servingUrl = endpoints.servingUrl;
          return this.staging.updateSettings(update);
        }),
        finalize(() => this.saving = false)
      )
      .subscribe({
        next: settings => {
          this.apply(settings);
          this.snackBar.open('Service connections saved', 'Close', { duration: 3000 });
        },
        error: error => this.error = error?.message || 'Could not save service connections.'
      });
  }

  testConnection(): void {
    const update = this.buildUpdate();
    const servingUrl = this.normalizeServingUrl(this.servingUrl);
    if (!update || !servingUrl) {
      return;
    }
    this.testing = true;
    this.error = '';
    this.testMessage = '';
    this.http.post<ServiceEndpointsConfig>('/api/service-endpoints', { servingUrl })
      .pipe(
        switchMap(endpoints => {
          this.servingUrl = endpoints.servingUrl;
          return this.staging.updateSettings(update);
        }),
        switchMap(settings => {
          this.apply(settings);
          return this.staging.testCallback();
        }),
        finalize(() => this.testing = false)
      )
      .subscribe({
        next: result => this.testMessage = result.success
          ? result.message
          : `Connection failed: ${result.message}`,
        error: error => this.error = error?.message || 'Could not test the callback connection.'
      });
  }

  private buildUpdate(): StagingSettings | null {
    if (!this.settings) {
      this.error = 'Settings have not loaded yet.';
      return null;
    }
    const callback = this.normalizeOptionalHttpBaseUrl(this.callbackUrl);
    if (callback === undefined) {
      this.error = 'Enter an HTTP(S) Admin base URL, or leave it blank to disable callbacks.';
      return null;
    }
    if (!Number.isFinite(this.callbackTimeoutMs) || this.callbackTimeoutMs <= 0) {
      this.error = 'Callback timeout must be greater than zero.';
      return null;
    }
    return {
      ...this.settings,
      callback_url: callback,
      auto_reload_enabled: this.autoReloadEnabled,
      callback_timeout_ms: Math.round(this.callbackTimeoutMs)
    };
  }

  private apply(settings: StagingSettings): void {
    this.settings = settings;
    this.callbackUrl = settings.callback_url || '';
    this.autoReloadEnabled = settings.auto_reload_enabled;
    this.callbackTimeoutMs = settings.callback_timeout_ms;
  }

  private normalizeServingUrl(value: string): string | undefined {
    const normalized = this.normalizeOptionalHttpBaseUrl(value);
    if (!normalized) {
      this.error = 'Enter the loopback HTTP(S) URL of the serving child.';
      return undefined;
    }
    const host = new URL(normalized).hostname;
    if (host !== 'localhost' && host !== '127.0.0.1' && host !== '::1' && host !== '[::1]') {
      this.error = 'Serving child URL must use localhost or a loopback address.';
      return undefined;
    }
    return normalized;
  }

  private normalizeOptionalHttpBaseUrl(value: string): string | null | undefined {
    if (!value || !value.trim()) {
      return null;
    }
    try {
      const url = new URL(value.trim());
      if ((url.protocol !== 'http:' && url.protocol !== 'https:') ||
          url.username || url.password || url.search || url.hash) {
        return undefined;
      }
      return url.toString().replace(/\/$/, '');
    } catch {
      return undefined;
    }
  }
}
