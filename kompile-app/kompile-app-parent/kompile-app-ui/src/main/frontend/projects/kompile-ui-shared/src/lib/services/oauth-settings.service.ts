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

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, BehaviorSubject } from 'rxjs';
import { tap } from 'rxjs/operators';
import { backendUrl } from './base.service';

export interface OAuthProviderSettings {
  providerId: string;
  clientId?: string;
  clientSecret?: string;
  tenantId?: string;
  scopes?: string;
  configured: boolean;
  lastUpdated?: number;
}

export interface ProviderSetupInfo {
  providerId: string;
  displayName: string;
  description: string;
  consoleUrl: string;
  setupSteps: string[];
  callbackPath: string;
  defaultScopes: string[];
}

@Injectable({
  providedIn: 'root'
})
export class OAuthSettingsService {
  private apiUrl = `${backendUrl}/oauth/settings`;

  private settingsSubject = new BehaviorSubject<OAuthProviderSettings[]>([]);
  public settings$ = this.settingsSubject.asObservable();

  private setupInfoSubject = new BehaviorSubject<ProviderSetupInfo[]>([]);
  public setupInfo$ = this.setupInfoSubject.asObservable();

  private loadingSubject = new BehaviorSubject<boolean>(false);
  public loading$ = this.loadingSubject.asObservable();

  constructor(private http: HttpClient) {}

  /**
   * Load all OAuth provider settings.
   */
  loadSettings(): Observable<OAuthProviderSettings[]> {
    this.loadingSubject.next(true);
    return this.http.get<OAuthProviderSettings[]>(this.apiUrl).pipe(
      tap(settings => {
        this.settingsSubject.next(settings);
        this.loadingSubject.next(false);
      })
    );
  }

  /**
   * Get settings for a specific provider.
   */
  getSettings(providerId: string): Observable<OAuthProviderSettings> {
    return this.http.get<OAuthProviderSettings>(`${this.apiUrl}/${providerId}`);
  }

  /**
   * Save settings for a provider.
   */
  saveSettings(settings: OAuthProviderSettings): Observable<OAuthProviderSettings> {
    return this.http.post<OAuthProviderSettings>(
      `${this.apiUrl}/${settings.providerId}`,
      settings
    ).pipe(
      tap(saved => {
        const current = this.settingsSubject.value;
        const index = current.findIndex(s => s.providerId === saved.providerId);
        if (index >= 0) {
          current[index] = saved;
        } else {
          current.push(saved);
        }
        this.settingsSubject.next([...current]);
      })
    );
  }

  /**
   * Delete settings for a provider.
   */
  deleteSettings(providerId: string): Observable<{ success: boolean }> {
    return this.http.delete<{ success: boolean }>(`${this.apiUrl}/${providerId}`).pipe(
      tap(() => {
        const current = this.settingsSubject.value;
        const index = current.findIndex(s => s.providerId === providerId);
        if (index >= 0) {
          current[index] = { ...current[index], configured: false, clientId: undefined, clientSecret: undefined };
          this.settingsSubject.next([...current]);
        }
      })
    );
  }

  /**
   * Validate settings for a provider.
   */
  validateSettings(providerId: string): Observable<{ providerId: string; configured: boolean; message: string }> {
    return this.http.get<{ providerId: string; configured: boolean; message: string }>(
      `${this.apiUrl}/${providerId}/validate`
    );
  }

  /**
   * Load provider setup information.
   */
  loadSetupInfo(): Observable<ProviderSetupInfo[]> {
    return this.http.get<ProviderSetupInfo[]>(`${this.apiUrl}/setup-info`).pipe(
      tap(info => this.setupInfoSubject.next(info))
    );
  }

  /**
   * Get cached settings for a provider.
   */
  getCachedSettings(providerId: string): OAuthProviderSettings | undefined {
    return this.settingsSubject.value.find(s => s.providerId === providerId);
  }

  /**
   * Check if provider is configured.
   */
  isConfigured(providerId: string): boolean {
    const settings = this.getCachedSettings(providerId);
    return settings?.configured ?? false;
  }

  /**
   * Get the callback URL for a provider.
   */
  getCallbackUrl(providerId: string): string {
    const baseUrl = window.location.origin;
    return `${baseUrl}/api/oauth/${providerId}/callback`;
  }

  /**
   * Refresh settings.
   */
  refresh(): void {
    this.loadSettings().subscribe();
  }
}
