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
import { takeUntil, switchMap, filter } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import {
  McpServerBuilderService,
  McpServerConfig,
  McpToolConfig,
  McpResourceConfig,
  McpPromptConfig,
  TransportType,
  ToolImplementationType,
  ResourceType,
  ServerStatus,
  ParameterConfig,
  PromptArgument,
  PromptMessage
} from '../../services/mcp-server-builder.service';

@Component({
  standalone: false,
  selector: 'app-mcp-server-builder',
  templateUrl: './mcp-server-builder.component.html',
  styleUrls: ['./mcp-server-builder.component.css']
})
export class McpServerBuilderComponent implements OnInit, OnDestroy {

  // Server list
  servers: McpServerConfig[] = [];
  selectedServer: McpServerConfig | null = null;
  isLoading = false;
  isEditing = false;

  // Editing state
  editingServer: McpServerConfig | null = null;
  editingTool: McpToolConfig | null = null;
  editingResource: McpResourceConfig | null = null;
  editingPrompt: McpPromptConfig | null = null;

  // Configuration options
  transportTypes: TransportType[] = ['STDIO', 'SSE', 'STREAMABLE_HTTP', 'STATELESS_STREAMABLE_HTTP'];
  toolTypes: ToolImplementationType[] = ['HTTP_ENDPOINT', 'SCRIPT', 'JAVA_CLASS', 'BUILT_IN'];
  resourceTypes: ResourceType[] = ['STATIC', 'FILE', 'HTTP', 'DATABASE', 'DYNAMIC'];
  parameterTypes: string[] = ['string', 'number', 'boolean', 'object', 'array'];
  messageRoles = ['USER', 'ASSISTANT', 'SYSTEM'];
  contentTypes = ['TEXT', 'IMAGE', 'RESOURCE'];

  // UI state
  activeTab = 0; // 0=servers, 1=tools, 2=resources, 3=prompts
  showServerDialog = false;
  showToolDialog = false;
  showResourceDialog = false;
  showPromptDialog = false;
  showImportDialog = false;
  importJson = '';

  private destroy$ = new Subject<void>();

  constructor(
    private mcpService: McpServerBuilderService,
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

  // Server Operations
  loadServers(): void {
    this.isLoading = true;
    this.mcpService.listServers().subscribe({
      next: (servers) => {
        this.servers = servers;
        this.isLoading = false;
      },
      error: (err) => {
        this.showError('Failed to load servers: ' + err.message);
        this.isLoading = false;
      }
    });
  }

  refreshServerStatuses(): void {
    this.servers.forEach(server => {
      if (server.id) {
        this.mcpService.getServerStatus(server.id).subscribe({
          next: (status) => {
            server.status = status.status;
          }
        });
      }
    });
  }

  selectServer(server: McpServerConfig): void {
    this.selectedServer = server;
    this.activeTab = 1; // Switch to tools tab
  }

  createServer(): void {
    this.editingServer = this.mcpService.createDefaultConfig();
    this.isEditing = false;
    this.showServerDialog = true;
  }

  editServer(server: McpServerConfig): void {
    this.editingServer = { ...server };
    this.isEditing = true;
    this.showServerDialog = true;
  }

  saveServer(): void {
    if (!this.editingServer) return;

    const operation = this.isEditing && this.editingServer.id
      ? this.mcpService.updateServer(this.editingServer.id, this.editingServer)
      : this.mcpService.createServer(this.editingServer);

    operation.subscribe({
      next: (saved) => {
        this.showSuccess(`Server ${this.isEditing ? 'updated' : 'created'} successfully`);
        this.showServerDialog = false;
        this.editingServer = null;
        this.loadServers();
        if (!this.isEditing) {
          this.selectedServer = saved;
        }
      },
      error: (err) => {
        this.showError('Failed to save server: ' + (err.error?.error || err.message));
      }
    });
  }

  deleteServer(server: McpServerConfig): void {
    if (!server.id) return;

    const dialogData: ConfirmDialogData = {
      title: 'Delete Server',
      message: `Are you sure you want to delete server "${server.name}"?`,
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
        this.mcpService.deleteServer(server.id!).subscribe({
          next: () => {
            this.showSuccess('Server deleted successfully');
            if (this.selectedServer?.id === server.id) {
              this.selectedServer = null;
            }
            this.loadServers();
          },
          error: (err) => {
            this.showError('Failed to delete server: ' + (err.error?.error || err.message));
          }
        });
      });
  }

  startServer(server: McpServerConfig): void {
    if (!server.id) return;
    this.mcpService.startServer(server.id).subscribe({
      next: (updated) => {
        this.showSuccess(`Server "${server.name}" started`);
        server.status = updated.status;
      },
      error: (err) => {
        this.showError('Failed to start server: ' + (err.error?.error || err.message));
      }
    });
  }

  stopServer(server: McpServerConfig): void {
    if (!server.id) return;
    this.mcpService.stopServer(server.id).subscribe({
      next: (updated) => {
        this.showSuccess(`Server "${server.name}" stopped`);
        server.status = updated.status;
      },
      error: (err) => {
        this.showError('Failed to stop server: ' + (err.error?.error || err.message));
      }
    });
  }

  restartServer(server: McpServerConfig): void {
    if (!server.id) return;
    this.mcpService.restartServer(server.id).subscribe({
      next: (updated) => {
        this.showSuccess(`Server "${server.name}" restarted`);
        server.status = updated.status;
      },
      error: (err) => {
        this.showError('Failed to restart server: ' + (err.error?.error || err.message));
      }
    });
  }

  exportServer(server: McpServerConfig): void {
    if (!server.id) return;
    this.mcpService.exportConfig(server.id).subscribe({
      next: (json) => {
        const blob = new Blob([json], { type: 'application/json' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `mcp-server-${server.name}.json`;
        a.click();
        window.URL.revokeObjectURL(url);
      },
      error: (err) => {
        this.showError('Failed to export server: ' + err.message);
      }
    });
  }

  openImportDialog(): void {
    this.importJson = '';
    this.showImportDialog = true;
  }

  importServer(): void {
    if (!this.importJson.trim()) return;
    this.mcpService.importConfig(this.importJson).subscribe({
      next: () => {
        this.showSuccess('Server imported successfully');
        this.showImportDialog = false;
        this.importJson = '';
        this.loadServers();
      },
      error: (err) => {
        this.showError('Failed to import server: ' + (err.error?.error || err.message));
      }
    });
  }

  // Tool Operations
  createTool(): void {
    if (!this.selectedServer) return;
    this.editingTool = this.mcpService.createDefaultTool();
    this.showToolDialog = true;
  }

  editTool(tool: McpToolConfig): void {
    this.editingTool = { ...tool, parameters: [...(tool.parameters || [])] };
    this.showToolDialog = true;
  }

  saveTool(): void {
    if (!this.editingTool || !this.selectedServer?.id) return;

    // Check if we're editing an existing tool
    const existingIndex = this.selectedServer.tools.findIndex(t => t.name === this.editingTool!.name);

    if (existingIndex >= 0) {
      // Update existing tool
      this.selectedServer.tools[existingIndex] = this.editingTool;
      this.mcpService.updateServer(this.selectedServer.id, this.selectedServer).subscribe({
        next: (updated) => {
          this.showSuccess('Tool updated successfully');
          this.selectedServer = updated;
          this.showToolDialog = false;
          this.editingTool = null;
        },
        error: (err) => {
          this.showError('Failed to update tool: ' + (err.error?.error || err.message));
        }
      });
    } else {
      // Add new tool
      this.mcpService.addTool(this.selectedServer.id, this.editingTool).subscribe({
        next: (updated) => {
          this.showSuccess('Tool added successfully');
          this.selectedServer = updated;
          this.showToolDialog = false;
          this.editingTool = null;
        },
        error: (err) => {
          this.showError('Failed to add tool: ' + (err.error?.error || err.message));
        }
      });
    }
  }

  deleteTool(tool: McpToolConfig): void {
    if (!this.selectedServer?.id) return;

    const dialogData: ConfirmDialogData = {
      title: 'Delete Tool',
      message: `Are you sure you want to delete tool "${tool.name}"?`,
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
        this.mcpService.removeTool(this.selectedServer!.id!, tool.name).subscribe({
          next: (updated) => {
            this.showSuccess('Tool deleted successfully');
            this.selectedServer = updated;
          },
          error: (err) => {
            this.showError('Failed to delete tool: ' + (err.error?.error || err.message));
          }
        });
      });
  }

  addParameter(): void {
    if (!this.editingTool) return;
    this.editingTool.parameters.push({
      name: '',
      type: 'string',
      description: '',
      required: false
    });
  }

  removeParameter(index: number): void {
    if (!this.editingTool) return;
    this.editingTool.parameters.splice(index, 1);
  }

  // Resource Operations
  createResource(): void {
    if (!this.selectedServer) return;
    this.editingResource = this.mcpService.createDefaultResource();
    this.showResourceDialog = true;
  }

  editResource(resource: McpResourceConfig): void {
    this.editingResource = { ...resource };
    this.showResourceDialog = true;
  }

  saveResource(): void {
    if (!this.editingResource || !this.selectedServer?.id) return;

    const existingIndex = this.selectedServer.resources.findIndex(r => r.uri === this.editingResource!.uri);

    if (existingIndex >= 0) {
      this.selectedServer.resources[existingIndex] = this.editingResource;
      this.mcpService.updateServer(this.selectedServer.id, this.selectedServer).subscribe({
        next: (updated) => {
          this.showSuccess('Resource updated successfully');
          this.selectedServer = updated;
          this.showResourceDialog = false;
          this.editingResource = null;
        },
        error: (err) => {
          this.showError('Failed to update resource: ' + (err.error?.error || err.message));
        }
      });
    } else {
      this.mcpService.addResource(this.selectedServer.id, this.editingResource).subscribe({
        next: (updated) => {
          this.showSuccess('Resource added successfully');
          this.selectedServer = updated;
          this.showResourceDialog = false;
          this.editingResource = null;
        },
        error: (err) => {
          this.showError('Failed to add resource: ' + (err.error?.error || err.message));
        }
      });
    }
  }

  deleteResource(resource: McpResourceConfig): void {
    if (!this.selectedServer?.id) return;

    const dialogData: ConfirmDialogData = {
      title: 'Delete Resource',
      message: `Are you sure you want to delete resource "${resource.name}"?`,
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
        this.mcpService.removeResource(this.selectedServer!.id!, resource.uri).subscribe({
          next: (updated) => {
            this.showSuccess('Resource deleted successfully');
            this.selectedServer = updated;
          },
          error: (err) => {
            this.showError('Failed to delete resource: ' + (err.error?.error || err.message));
          }
        });
      });
  }

  // Prompt Operations
  createPrompt(): void {
    if (!this.selectedServer) return;
    this.editingPrompt = this.mcpService.createDefaultPrompt();
    this.showPromptDialog = true;
  }

  editPrompt(prompt: McpPromptConfig): void {
    this.editingPrompt = {
      ...prompt,
      arguments: [...(prompt.arguments || [])],
      messages: [...(prompt.messages || [])]
    };
    this.showPromptDialog = true;
  }

  savePrompt(): void {
    if (!this.editingPrompt || !this.selectedServer?.id) return;

    const existingIndex = this.selectedServer.prompts.findIndex(p => p.name === this.editingPrompt!.name);

    if (existingIndex >= 0) {
      this.selectedServer.prompts[existingIndex] = this.editingPrompt;
      this.mcpService.updateServer(this.selectedServer.id, this.selectedServer).subscribe({
        next: (updated) => {
          this.showSuccess('Prompt updated successfully');
          this.selectedServer = updated;
          this.showPromptDialog = false;
          this.editingPrompt = null;
        },
        error: (err) => {
          this.showError('Failed to update prompt: ' + (err.error?.error || err.message));
        }
      });
    } else {
      this.mcpService.addPrompt(this.selectedServer.id, this.editingPrompt).subscribe({
        next: (updated) => {
          this.showSuccess('Prompt added successfully');
          this.selectedServer = updated;
          this.showPromptDialog = false;
          this.editingPrompt = null;
        },
        error: (err) => {
          this.showError('Failed to add prompt: ' + (err.error?.error || err.message));
        }
      });
    }
  }

  deletePrompt(prompt: McpPromptConfig): void {
    if (!this.selectedServer?.id) return;

    const dialogData: ConfirmDialogData = {
      title: 'Delete Prompt',
      message: `Are you sure you want to delete prompt "${prompt.name}"?`,
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
        this.mcpService.removePrompt(this.selectedServer!.id!, prompt.name).subscribe({
          next: (updated) => {
            this.showSuccess('Prompt deleted successfully');
            this.selectedServer = updated;
          },
          error: (err) => {
            this.showError('Failed to delete prompt: ' + (err.error?.error || err.message));
          }
        });
      });
  }

  addPromptArgument(): void {
    if (!this.editingPrompt) return;
    this.editingPrompt.arguments.push({
      name: '',
      description: '',
      required: false
    });
  }

  removePromptArgument(index: number): void {
    if (!this.editingPrompt) return;
    this.editingPrompt.arguments.splice(index, 1);
  }

  addPromptMessage(): void {
    if (!this.editingPrompt) return;
    this.editingPrompt.messages.push({
      role: 'USER',
      contentType: 'TEXT',
      content: ''
    });
  }

  removePromptMessage(index: number): void {
    if (!this.editingPrompt) return;
    this.editingPrompt.messages.splice(index, 1);
  }

  // Helper methods
  getStatusColor(status: ServerStatus): string {
    switch (status) {
      case 'RUNNING': return 'green';
      case 'STOPPED': return 'gray';
      case 'STARTING':
      case 'STOPPING': return 'orange';
      case 'ERROR': return 'red';
      default: return 'gray';
    }
  }

  getStatusIcon(status: ServerStatus): string {
    switch (status) {
      case 'RUNNING': return 'play_circle';
      case 'STOPPED': return 'stop_circle';
      case 'STARTING':
      case 'STOPPING': return 'pending';
      case 'ERROR': return 'error';
      default: return 'help';
    }
  }

  cancelServerDialog(): void {
    this.showServerDialog = false;
    this.editingServer = null;
  }

  cancelToolDialog(): void {
    this.showToolDialog = false;
    this.editingTool = null;
  }

  cancelResourceDialog(): void {
    this.showResourceDialog = false;
    this.editingResource = null;
  }

  cancelPromptDialog(): void {
    this.showPromptDialog = false;
    this.editingPrompt = null;
  }

  cancelImportDialog(): void {
    this.showImportDialog = false;
    this.importJson = '';
  }

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 3000, panelClass: ['success-snackbar'] });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
  }
}
