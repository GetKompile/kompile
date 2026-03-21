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

import { Component, OnInit, OnDestroy, Optional } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import { OAuthConnectionService } from '../../services/oauth-connection.service';
import { OAuthSettingsService, ProviderSetupInfo } from '../../services/oauth-settings.service';
import { OAuthProviderInfo, OAuthConnection, PROVIDER_METADATA } from '../../models/oauth-models';
import { OAuthSettingsDialogComponent, DialogData } from '../oauth-settings-dialog/oauth-settings-dialog.component';

@Component({
  selector: 'app-connections-manager',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatChipsModule,
    MatDividerModule,
    MatDialogModule
  ],
  templateUrl: './connections-manager.component.html',
  styleUrls: ['./connections-manager.component.css']
})
export class ConnectionsManagerComponent implements OnInit, OnDestroy {
  providers: OAuthProviderInfo[] = [];
  connections: Map<string, OAuthConnection> = new Map();
  setupInfoMap: Map<string, ProviderSetupInfo> = new Map();
  loading = false;
  connectingProvider: string | null = null;
  private destroy$ = new Subject<void>();

  providerMetadata = PROVIDER_METADATA;

  constructor(
    private oauthService: OAuthConnectionService,
    private settingsService: OAuthSettingsService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog,
    @Optional() private route: ActivatedRoute | null
  ) {}

  ngOnInit(): void {
    // Subscribe to providers
    this.oauthService.providers$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(providers => {
      this.providers = providers;
    });

    // Subscribe to connections
    this.oauthService.connections$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(connections => {
      this.connections.clear();
      connections.forEach(c => this.connections.set(c.providerId, c));
    });

    // Subscribe to loading state
    this.oauthService.loading$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(loading => {
      this.loading = loading;
    });

    // Load data
    this.oauthService.loadProviders().subscribe();
    this.oauthService.loadConnections().subscribe();

    // Load setup info and settings
    this.settingsService.loadSetupInfo().subscribe(info => {
      info.forEach(i => this.setupInfoMap.set(i.providerId, i));
    });
    this.settingsService.loadSettings().subscribe();

    // Handle callback query params (only if route is available - when navigated via router)
    if (this.route) {
      this.route.queryParams.pipe(
        takeUntil(this.destroy$)
      ).subscribe(params => {
        if (params['success'] === 'true') {
          const provider = params['provider'];
          this.snackBar.open(
            `Successfully connected to ${provider}!`,
            'Close',
            { duration: 5000, panelClass: 'success-snackbar' }
          );
          // Refresh connections
          this.oauthService.loadConnections().subscribe();
        } else if (params['error']) {
          const error = params['error'];
          const description = params['error_description'] || '';
          this.snackBar.open(
            `Connection failed: ${error}${description ? ' - ' + description : ''}`,
            'Close',
            { duration: 8000, panelClass: 'error-snackbar' }
          );
        }
      });
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  getConnection(providerId: string): OAuthConnection | undefined {
    return this.connections.get(providerId);
  }

  isConnected(providerId: string): boolean {
    const connection = this.connections.get(providerId);
    return connection?.status === 'connected';
  }

  async connect(providerId: string): Promise<void> {
    this.connectingProvider = providerId;
    try {
      const success = await this.oauthService.initiateAuth(providerId);
      if (success) {
        this.snackBar.open('Successfully connected!', 'Close', { duration: 3000 });
      } else {
        this.snackBar.open('Connection was not completed', 'Close', { duration: 3000 });
      }
    } catch (error: any) {
      console.error('Connection error:', error);
      this.snackBar.open(
        `Connection failed: ${error.message || 'Unknown error'}`,
        'Close',
        { duration: 5000 }
      );
    } finally {
      this.connectingProvider = null;
    }
  }

  disconnect(providerId: string): void {
    const connection = this.connections.get(providerId);
    if (!connection) return;

    this.oauthService.disconnect(providerId).subscribe({
      next: () => {
        this.snackBar.open(
          `Disconnected from ${connection.providerDisplayName}`,
          'Close',
          { duration: 3000 }
        );
      },
      error: (error) => {
        console.error('Disconnect error:', error);
        this.snackBar.open('Failed to disconnect', 'Close', { duration: 3000 });
      }
    });
  }

  refreshToken(providerId: string): void {
    this.oauthService.refreshToken(providerId).subscribe({
      next: (status) => {
        if (status.connected && status.tokenValid) {
          this.snackBar.open('Token refreshed successfully', 'Close', { duration: 3000 });
        } else {
          this.snackBar.open('Token refresh failed - please reconnect', 'Close', { duration: 5000 });
        }
      },
      error: (error) => {
        console.error('Refresh error:', error);
        this.snackBar.open('Failed to refresh token', 'Close', { duration: 3000 });
      }
    });
  }

  checkHealth(providerId: string): void {
    this.oauthService.checkHealth(providerId).subscribe({
      next: (response) => {
        if (response.healthy) {
          this.snackBar.open('Connection is healthy!', 'Close', { duration: 3000 });
        } else {
          this.snackBar.open(`Connection issue: ${response.message}`, 'Close', { duration: 5000 });
        }
      },
      error: (error) => {
        console.error('Health check error:', error);
        this.snackBar.open('Health check failed', 'Close', { duration: 3000 });
      }
    });
  }

  getStatusColor(status: string): string {
    switch (status) {
      case 'connected': return 'primary';
      case 'expired': return 'warn';
      case 'error': return 'warn';
      case 'disconnected': return 'accent';
      default: return 'accent';
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'connected': return 'check_circle';
      case 'expired': return 'schedule';
      case 'error': return 'error';
      case 'disconnected': return 'link_off';
      default: return 'help';
    }
  }

  formatDate(dateString?: string): string {
    if (!dateString) return 'Never';
    const date = new Date(dateString);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
  }

  getProviderColor(providerId: string): string {
    return this.providerMetadata[providerId]?.color || '#666';
  }

  refresh(): void {
    this.oauthService.refresh();
  }

  openSettingsDialog(provider: OAuthProviderInfo): void {
    const setupInfo = this.setupInfoMap.get(provider.providerId);
    const settings = this.settingsService.getCachedSettings(provider.providerId);

    const dialogData: DialogData = {
      providerId: provider.providerId,
      displayName: provider.displayName,
      icon: provider.icon,
      color: this.getProviderColor(provider.providerId),
      settings: settings,
      setupInfo: setupInfo
    };

    const dialogRef = this.dialog.open(OAuthSettingsDialogComponent, {
      width: '600px',
      maxWidth: '95vw',
      disableClose: false,
      data: dialogData
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result?.saved || result?.deleted) {
        // Reload providers to get updated configuration status
        this.oauthService.loadProviders().subscribe();
        this.settingsService.loadSettings().subscribe();
      }
    });
  }
}
