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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject, interval } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '@shared/components/confirm-dialog/confirm-dialog.component';
import {
  ExternalMcpServerService,
  ExternalMcpServerConfig,
  ServerStatus,
  TransportType
} from '@shared/services/external-mcp-server.service';
import { ExtMcpServerDialogComponent, ExtMcpServerDialogData } from './ext-mcp-server-dialog.component';
import { ExtMcpImportDialogComponent } from './ext-mcp-import-dialog.component';
import { ExtMcpDetailsDialogComponent, ExtMcpDetailsDialogData, EXT_MCP_DETAILS_EDIT_ACTION } from './ext-mcp-details-dialog.component';

@Component({
  standalone: false,
  selector: 'app-mcp-config-manager',
  templateUrl: './mcp-config-manager.component.html',
  styleUrls: ['./mcp-config-manager.component.css']
})
export class McpConfigManagerComponent implements OnInit, OnDestroy {

  // Server list
  servers: ExternalMcpServerConfig[] = [];
  isLoading = false;

  // View mode: 'list' or 'json'
  viewMode: 'list' | 'json' = 'list';

  // JSON editor state
  jsonConfig = '';
  jsonError = '';
  jsonValid = true;

  private destroy$ = new Subject<void>();

  constructor(
    private mcpService: ExternalMcpServerService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadServers();
    // Poll for status updates every 5 seconds
    interval(5000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.refreshServerStatuses());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ==================== Server List Operations ====================

  loadServers(): void {
    this.isLoading = true;
    this.mcpService.listServers().subscribe({
      next: (servers) => {
        this.servers = servers;
        this.isLoading = false;
        this.updateJsonFromServers();
      },
      error: (err) => {
        this.showError('Failed to load servers: ' + (err.error?.error || err.message));
        this.isLoading = false;
      }
    });
  }

  refreshServerStatuses(): void {
    this.servers.forEach(server => {
      this.mcpService.getServerStatus(server.id).subscribe({
        next: (status) => {
          server.status = status.status;
          server.pid = status.pid;
          server.errorMessage = status.errorMessage;
        }
      });
    });
  }

  // ==================== Server CRUD ====================

  createServer(transportType: TransportType = 'STDIO'): void {
    let server: ExternalMcpServerConfig;
    if (transportType === 'STDIO') {
      server = this.mcpService.createDefaultConfig();
    } else if (transportType === 'REST') {
      server = this.mcpService.createDefaultRestConfig();
    } else {
      server = this.mcpService.createDefaultSseConfig();
    }
    this.openAddServerDialog(server);
  }

  editServer(server: ExternalMcpServerConfig): void {
    this.openAddServerDialog(server);
  }

  openAddServerDialog(server: ExternalMcpServerConfig): void {
    const isEditing = !!server.id && this.servers.some(s => s.id === server.id);
    const envKeys = server.env ? Object.keys(server.env) : [];
    const envValues = server.env ? Object.values(server.env) : [];
    const headerKeys = server.headers ? Object.keys(server.headers) : [];
    const headerValues = server.headers ? Object.values(server.headers) : [];

    const data: ExtMcpServerDialogData = { server, isEditing, envKeys, envValues, headerKeys, headerValues };
    this.dialog.open(ExtMcpServerDialogComponent, { data, width: '700px' })
      .afterClosed()
      .pipe(takeUntil(this.destroy$))
      .subscribe((result: ExternalMcpServerConfig | null) => {
        if (result) {
          this.saveServer(result, isEditing);
        }
      });
  }

  saveServer(server: ExternalMcpServerConfig, isEditing: boolean): void {
    const operation = isEditing
      ? this.mcpService.updateServer(server.id, server)
      : this.mcpService.addServer(server);

    operation.subscribe({
      next: () => {
        this.showSuccess(`Server ${isEditing ? 'updated' : 'created'} successfully`);
        this.loadServers();
      },
      error: (err) => {
        this.showError('Failed to save server: ' + (err.error?.error || err.message));
      }
    });
  }

  deleteServer(server: ExternalMcpServerConfig): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete Server',
      message: `Are you sure you want to delete server "${server.id}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.mcpService.deleteServer(server.id).subscribe({
          next: () => {
            this.showSuccess('Server deleted successfully');
            this.loadServers();
          },
          error: (err) => {
            this.showError('Failed to delete server: ' + (err.error?.error || err.message));
          }
        });
      });
  }

  // ==================== Server Lifecycle ====================

  startServer(server: ExternalMcpServerConfig): void {
    this.mcpService.startServer(server.id).subscribe({
      next: (updated) => {
        this.showSuccess(`Server "${server.id}" started`);
        server.status = updated.status;
        server.pid = updated.pid;
      },
      error: (err) => {
        this.showError('Failed to start server: ' + (err.error?.error || err.message));
      }
    });
  }

  stopServer(server: ExternalMcpServerConfig): void {
    this.mcpService.stopServer(server.id).subscribe({
      next: (updated) => {
        this.showSuccess(`Server "${server.id}" stopped`);
        server.status = updated.status;
        server.pid = undefined;
      },
      error: (err) => {
        this.showError('Failed to stop server: ' + (err.error?.error || err.message));
      }
    });
  }

  restartServer(server: ExternalMcpServerConfig): void {
    this.mcpService.restartServer(server.id).subscribe({
      next: (updated) => {
        this.showSuccess(`Server "${server.id}" restarted`);
        server.status = updated.status;
        server.pid = updated.pid;
      },
      error: (err) => {
        this.showError('Failed to restart server: ' + (err.error?.error || err.message));
      }
    });
  }

  // ==================== JSON Editor ====================

  switchToJsonView(): void {
    this.viewMode = 'json';
    this.updateJsonFromServers();
  }

  switchToListView(): void {
    this.viewMode = 'list';
  }

  updateJsonFromServers(): void {
    this.jsonConfig = this.mcpService.formatConfigJson(this.servers);
    this.jsonError = '';
    this.jsonValid = true;
  }

  validateJson(): void {
    this.mcpService.validateConfig(this.jsonConfig).subscribe({
      next: (result) => {
        this.jsonValid = result.valid;
        this.jsonError = result.errors ? result.errors.join('\n') : '';
        if (result.valid) {
          this.showSuccess('Configuration is valid');
        }
      },
      error: (err) => {
        this.jsonValid = false;
        this.jsonError = err.error?.error || err.message;
      }
    });
  }

  applyJsonConfig(): void {
    const dialogData: ConfirmDialogData = {
      title: 'Replace Configuration',
      message: 'This will replace all server configurations. Continue?',
      confirmText: 'Replace',
      confirmColor: 'warn',
      icon: 'warning'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.mcpService.replaceConfig(this.jsonConfig).subscribe({
          next: (result) => {
            this.showSuccess(result.message);
            this.loadServers();
          },
          error: (err) => {
            this.showError('Failed to apply configuration: ' + (err.error?.error || err.message));
          }
        });
      });
  }

  // ==================== Import/Export ====================

  openImportDialog(): void {
    this.dialog.open(ExtMcpImportDialogComponent)
      .afterClosed()
      .pipe(takeUntil(this.destroy$))
      .subscribe((json: string | null) => {
        if (json) {
          this.importConfig(json);
        }
      });
  }

  importConfig(json: string): void {
    this.mcpService.importConfig(json).subscribe({
      next: (result) => {
        this.showSuccess(result.message);
        this.loadServers();
      },
      error: (err) => {
        this.showError('Failed to import: ' + (err.error?.error || err.message));
      }
    });
  }

  exportConfig(): void {
    this.mcpService.exportConfig().subscribe({
      next: (json) => {
        const blob = new Blob([json], { type: 'application/json' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'mcp-config.json';
        a.click();
        window.URL.revokeObjectURL(url);
      },
      error: (err) => {
        this.showError('Failed to export: ' + err.message);
      }
    });
  }

  copyToClipboard(): void {
    navigator.clipboard.writeText(this.jsonConfig).then(() => {
      this.showSuccess('Configuration copied to clipboard');
    }).catch(err => {
      this.showError('Failed to copy: ' + err);
    });
  }

  pasteFromClipboard(): void {
    navigator.clipboard.readText().then(text => {
      this.jsonConfig = text;
      this.validateJson();
    }).catch(err => {
      this.showError('Failed to paste: ' + err);
    });
  }

  // ==================== UI Helpers ====================

  getStatusColor(status?: ServerStatus): string {
    switch (status) {
      case 'RUNNING': return 'green';
      case 'STOPPED': return 'gray';
      case 'STARTING':
      case 'STOPPING': return 'orange';
      case 'ERROR': return 'red';
      default: return 'gray';
    }
  }

  getStatusIcon(status?: ServerStatus): string {
    switch (status) {
      case 'RUNNING': return 'play_circle';
      case 'STOPPED': return 'stop_circle';
      case 'STARTING':
      case 'STOPPING': return 'pending';
      case 'ERROR': return 'error';
      default: return 'help';
    }
  }

  getTransportIcon(server: ExternalMcpServerConfig): string {
    if (this.mcpService.isStdio(server)) {
      return 'terminal';
    } else if (this.mcpService.isSse(server)) {
      return 'stream';
    } else {
      return 'http';
    }
  }

  getTransportLabel(server: ExternalMcpServerConfig): string {
    return server.transportType || 'STDIO';
  }

  getCommandDisplay(server: ExternalMcpServerConfig): string {
    if (this.mcpService.isStdio(server)) {
      const parts = [server.command, ...(server.args || [])];
      const display = parts.join(' ');
      return display.length > 60 ? display.substring(0, 57) + '...' : display;
    } else {
      const url = server.url || '';
      return url.length > 60 ? url.substring(0, 57) + '...' : url;
    }
  }

  isStdioServer(server: ExternalMcpServerConfig): boolean {
    return this.mcpService.isStdio(server);
  }

  isRestServer(server: ExternalMcpServerConfig): boolean {
    return this.mcpService.isRest(server);
  }

  isSseServer(server: ExternalMcpServerConfig): boolean {
    return this.mcpService.isSse(server);
  }

  // ==================== View Details ====================

  viewServerDetails(server: ExternalMcpServerConfig): void {
    const data: ExtMcpDetailsDialogData = { server, serverJson: this.formatSingleServerJson(server) };
    this.dialog.open(ExtMcpDetailsDialogComponent, { data, width: '780px' })
      .afterClosed()
      .pipe(takeUntil(this.destroy$))
      .subscribe((result: string | null) => {
        if (result === EXT_MCP_DETAILS_EDIT_ACTION) {
          this.openAddServerDialog(server);
        }
      });
  }

  formatSingleServerJson(server: ExternalMcpServerConfig): string {
    const transportType = server.transportType || 'STDIO';
    const serverConfig: any = {
      transportType
    };

    if (this.mcpService.isStdio(server)) {
      // STDIO configuration
      serverConfig.command = server.command;
      serverConfig.args = server.args || [];
      serverConfig.env = server.env || {};
    } else {
      // REST/SSE configuration
      serverConfig.url = server.url;
      serverConfig.headers = server.headers || {};
      serverConfig.connectionTimeout = server.connectionTimeout;
      serverConfig.requestTimeout = server.requestTimeout;
      serverConfig.verifySsl = server.verifySsl;
      if (server.sseEndpoint) {
        serverConfig.sseEndpoint = server.sseEndpoint;
      }
      if (server.messagesEndpoint) {
        serverConfig.messagesEndpoint = server.messagesEndpoint;
      }
    }

    if (server.description) {
      serverConfig.description = server.description;
    }

    const config: any = {
      mcpServers: {
        [server.id]: serverConfig
      }
    };

    return JSON.stringify(config, null, 2);
  }

  objectKeys(obj: any): string[] {
    return obj ? Object.keys(obj) : [];
  }

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 3000, panelClass: ['success-snackbar'] });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
  }
}
