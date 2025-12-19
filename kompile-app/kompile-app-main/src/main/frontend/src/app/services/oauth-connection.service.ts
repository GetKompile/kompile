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
import { Observable, BehaviorSubject, of, timer } from 'rxjs';
import { map, tap, catchError, switchMap, shareReplay } from 'rxjs/operators';
import { BaseService, backendUrl } from './base.service';
import {
  OAuthProviderInfo,
  OAuthConnection,
  OAuthConnectionStatus,
  AuthorizationUrlResponse,
  ConnectionHealthResponse
} from '../models/oauth-models';

@Injectable({
  providedIn: 'root'
})
export class OAuthConnectionService extends BaseService {

  private providersSubject = new BehaviorSubject<OAuthProviderInfo[]>([]);
  private connectionsSubject = new BehaviorSubject<OAuthConnection[]>([]);
  private loadingSubject = new BehaviorSubject<boolean>(false);

  /** Observable of available OAuth providers */
  public providers$ = this.providersSubject.asObservable();

  /** Observable of all connections */
  public connections$ = this.connectionsSubject.asObservable();

  /** Observable of loading state */
  public loading$ = this.loadingSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
    // Load providers and connections on service init
    this.loadProviders();
    this.loadConnections();
  }

  /**
   * Load available OAuth providers.
   */
  loadProviders(): Observable<OAuthProviderInfo[]> {
    return this.http.get<OAuthProviderInfo[]>(`${backendUrl}/oauth/providers`).pipe(
      tap(providers => this.providersSubject.next(providers)),
      catchError(err => {
        console.error('Failed to load OAuth providers:', err);
        return of([]);
      })
    );
  }

  /**
   * Load all OAuth connections.
   */
  loadConnections(): Observable<OAuthConnection[]> {
    this.loadingSubject.next(true);
    return this.http.get<OAuthConnection[]>(`${backendUrl}/oauth/connections`).pipe(
      tap(connections => {
        this.connectionsSubject.next(connections);
        this.loadingSubject.next(false);
      }),
      catchError(err => {
        console.error('Failed to load OAuth connections:', err);
        this.loadingSubject.next(false);
        return of([]);
      })
    );
  }

  /**
   * Get connection status for a specific provider.
   */
  getConnectionStatus(providerId: string): Observable<OAuthConnectionStatus> {
    return this.http.get<OAuthConnectionStatus>(`${backendUrl}/oauth/${providerId}/status`);
  }

  /**
   * Get provider info.
   */
  getProvider(providerId: string): Observable<OAuthProviderInfo | null> {
    return this.http.get<OAuthProviderInfo>(`${backendUrl}/oauth/providers/${providerId}`).pipe(
      catchError(err => {
        console.error(`Failed to get provider ${providerId}:`, err);
        return of(null);
      })
    );
  }

  /**
   * Initiate OAuth authorization flow.
   * Opens a popup window for the OAuth provider.
   */
  initiateAuth(providerId: string): Promise<boolean> {
    return new Promise((resolve, reject) => {
      // Get authorization URL
      this.http.get<AuthorizationUrlResponse>(`${backendUrl}/oauth/${providerId}/authorize`).subscribe({
        next: (response) => {
          if (!response.authorizationUrl) {
            reject(new Error('No authorization URL received'));
            return;
          }

          // Open OAuth popup
          const width = 600;
          const height = 700;
          const left = window.screenX + (window.outerWidth - width) / 2;
          const top = window.screenY + (window.outerHeight - height) / 2;

          const popup = window.open(
            response.authorizationUrl,
            `${providerId} Sign In`,
            `width=${width},height=${height},left=${left},top=${top},popup=1`
          );

          if (!popup) {
            reject(new Error('Popup blocked. Please allow popups for this site.'));
            return;
          }

          // Poll for popup close and check connection status
          const pollTimer = setInterval(() => {
            if (popup.closed) {
              clearInterval(pollTimer);
              // Reload connections to get updated status
              this.loadConnections().subscribe({
                next: (connections) => {
                  const connection = connections.find(c => c.providerId === providerId);
                  resolve(connection?.status === 'connected');
                },
                error: (err) => {
                  reject(err);
                }
              });
            }
          }, 500);

          // Timeout after 5 minutes
          setTimeout(() => {
            clearInterval(pollTimer);
            if (!popup.closed) {
              popup.close();
            }
            reject(new Error('Authentication timed out'));
          }, 300000);
        },
        error: (err) => {
          reject(err);
        }
      });
    });
  }

  /**
   * Disconnect from an OAuth provider.
   */
  disconnect(providerId: string): Observable<{ success: boolean }> {
    return this.http.delete<{ success: boolean }>(`${backendUrl}/oauth/${providerId}`).pipe(
      tap(() => {
        // Update local state
        const connections = this.connectionsSubject.value.map(c => {
          if (c.providerId === providerId) {
            return { ...c, status: 'disconnected' as const };
          }
          return c;
        });
        this.connectionsSubject.next(connections);
      })
    );
  }

  /**
   * Refresh access token for a provider.
   */
  refreshToken(providerId: string): Observable<OAuthConnectionStatus> {
    return this.http.post<OAuthConnectionStatus>(`${backendUrl}/oauth/${providerId}/refresh`, {}).pipe(
      tap(() => {
        // Reload connections to get updated status
        this.loadConnections().subscribe();
      })
    );
  }

  /**
   * Check connection health.
   */
  checkHealth(providerId: string): Observable<ConnectionHealthResponse> {
    return this.http.get<ConnectionHealthResponse>(`${backendUrl}/oauth/${providerId}/health`);
  }

  /**
   * Check if a provider is connected.
   */
  isConnected(providerId: string): boolean {
    const connection = this.connectionsSubject.value.find(c => c.providerId === providerId);
    return connection?.status === 'connected';
  }

  /**
   * Get connection for a specific provider.
   */
  getConnection(providerId: string): OAuthConnection | undefined {
    return this.connectionsSubject.value.find(c => c.providerId === providerId);
  }

  /**
   * Get providers that require OAuth for a specific source.
   */
  getProviderForSource(sourceId: string): OAuthProviderInfo | undefined {
    return this.providersSubject.value.find(p => p.relatedSources.includes(sourceId));
  }

  /**
   * Refresh all data.
   */
  refresh(): void {
    this.loadProviders().subscribe();
    this.loadConnections().subscribe();
  }
}
