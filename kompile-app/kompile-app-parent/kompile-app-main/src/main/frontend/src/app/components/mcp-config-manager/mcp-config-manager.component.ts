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
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import {
  ExternalMcpServerService,
  ExternalMcpServerConfig,
  ServerStatus,
  TransportType
} from '../../services/external-mcp-server.service';

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

  // Dialog state
  showServerDialog = false;
  showImportDialog = false;
  showDetailsDialog = false;
  isEditing = false;
  editingServer: ExternalMcpServerConfig | null = null;
  viewingServer: ExternalMcpServerConfig | null = null;
  viewingServerJson = '';
  importJson = '';

  // Environment variable editing (for STDIO servers)
  envKeys: string[] = [];
  envValues: string[] = [];

  // HTTP headers editing (for REST/SSE servers)
  headerKeys: string[] = [];
  headerValues: string[] = [];

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
    if (transportType === 'STDIO') {
      this.editingServer = this.mcpService.createDefaultConfig();
    } else if (transportType === 'REST') {
      this.editingServer = this.mcpService.createDefaultRestConfig();
    } else {
      this.editingServer = this.mcpService.createDefaultSseConfig();
    }
    this.isEditing = false;
    this.initEnvArrays();
    this.initHeaderArrays();
    this.showServerDialog = true;
  }

  editServer(server: ExternalMcpServerConfig): void {
    this.editingServer = {
      ...server,
      args: [...(server.args || [])],
      env: { ...(server.env || {}) },
      headers: { ...(server.headers || {}) }
    };
    this.isEditing = true;
    this.initEnvArrays();
    this.initHeaderArrays();
    this.showServerDialog = true;
  }

  saveServer(): void {
    if (!this.editingServer) return;

    const isStdio = this.mcpService.isStdio(this.editingServer);

    if (isStdio) {
      // Convert env arrays back to object for STDIO servers
      this.editingServer.env = {};
      for (let i = 0; i < this.envKeys.length; i++) {
        const key = this.envKeys[i].trim();
        if (key) {
          this.editingServer.env[key] = this.envValues[i] || '';
        }
      }
    } else {
      // Convert header arrays back to object for REST/SSE servers
      this.editingServer.headers = {};
      for (let i = 0; i < this.headerKeys.length; i++) {
        const key = this.headerKeys[i].trim();
        if (key) {
          this.editingServer.headers[key] = this.headerValues[i] || '';
        }
      }
    }

    const operation = this.isEditing
      ? this.mcpService.updateServer(this.editingServer.id, this.editingServer)
      : this.mcpService.addServer(this.editingServer);

    operation.subscribe({
      next: () => {
        this.showSuccess(`Server ${this.isEditing ? 'updated' : 'created'} successfully`);
        this.showServerDialog = false;
        this.editingServer = null;
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
    this.importJson = '';
    this.showImportDialog = true;
  }

  importConfig(): void {
    if (!this.importJson.trim()) return;

    this.mcpService.importConfig(this.importJson).subscribe({
      next: (result) => {
        this.showSuccess(result.message);
        this.showImportDialog = false;
        this.importJson = '';
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

  // ==================== Environment Variables (STDIO) ====================

  initEnvArrays(): void {
    if (this.editingServer?.env) {
      this.envKeys = Object.keys(this.editingServer.env);
      this.envValues = Object.values(this.editingServer.env);
    } else {
      this.envKeys = [];
      this.envValues = [];
    }
  }

  addEnvVar(): void {
    this.envKeys.push('');
    this.envValues.push('');
  }

  removeEnvVar(index: number): void {
    this.envKeys.splice(index, 1);
    this.envValues.splice(index, 1);
  }

  // ==================== HTTP Headers (REST/SSE) ====================

  initHeaderArrays(): void {
    if (this.editingServer?.headers) {
      this.headerKeys = Object.keys(this.editingServer.headers);
      this.headerValues = Object.values(this.editingServer.headers);
    } else {
      this.headerKeys = [];
      this.headerValues = [];
    }
  }

  addHeader(): void {
    this.headerKeys.push('');
    this.headerValues.push('');
  }

  removeHeader(index: number): void {
    this.headerKeys.splice(index, 1);
    this.headerValues.splice(index, 1);
  }

  // ==================== Args Editing ====================

  getArgsString(): string {
    return this.editingServer?.args?.join('\n') || '';
  }

  setArgsFromString(value: string): void {
    if (this.editingServer) {
      this.editingServer.args = value.split('\n').map(s => s.trim()).filter(s => s);
    }
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

  cancelServerDialog(): void {
    this.showServerDialog = false;
    this.editingServer = null;
  }

  cancelImportDialog(): void {
    this.showImportDialog = false;
    this.importJson = '';
  }

  // ==================== View Details ====================

  viewServerDetails(server: ExternalMcpServerConfig): void {
    this.viewingServer = server;
    this.viewingServerJson = this.formatSingleServerJson(server);
    this.showDetailsDialog = true;
  }

  cancelDetailsDialog(): void {
    this.showDetailsDialog = false;
    this.viewingServer = null;
    this.viewingServerJson = '';
  }

  copyServerJson(): void {
    navigator.clipboard.writeText(this.viewingServerJson).then(() => {
      this.showSuccess('Server configuration copied to clipboard');
    }).catch(err => {
      this.showError('Failed to copy: ' + err);
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
