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
  RestMcpBridgeService,
  RestMcpBridgeConfig,
  EndpointMapping,
  BridgeDirection,
  BridgeStatus,
  AuthType,
  TransformType,
  AuthConfig,
  TransformConfig,
  ParameterDef,
  EndpointTestResult,
  DiscoveredTool
} from '../../services/rest-mcp-bridge.service';

@Component({
  standalone: false,
  selector: 'app-rest-mcp-bridge',
  templateUrl: './rest-mcp-bridge.component.html',
  styleUrls: ['./rest-mcp-bridge.component.css']
})
export class RestMcpBridgeComponent implements OnInit, OnDestroy {

  // Bridge list
  bridges: RestMcpBridgeConfig[] = [];
  selectedBridge: RestMcpBridgeConfig | null = null;
  isLoading = false;
  isEditing = false;

  // Editing state
  editingBridge: RestMcpBridgeConfig | null = null;
  editingMapping: EndpointMapping | null = null;

  // Discovery state
  discoverUrl = '';
  discoveredMappings: EndpointMapping[] = [];
  isDiscovering = false;
  discoveryMode: 'openapi' | 'probe' | 'builtin' = 'openapi';

  // Built-in tools state
  builtInTools: DiscoveredTool[] = [];
  builtInMappings: EndpointMapping[] = [];
  isLoadingBuiltIn = false;

  // Test state
  testInput = '{}';
  testResult: EndpointTestResult | null = null;
  isTesting = false;

  // Configuration options
  bridgeDirections: BridgeDirection[] = ['REST_TO_MCP', 'MCP_TO_REST'];
  authTypes: AuthType[] = ['NONE', 'API_KEY', 'BEARER', 'BASIC', 'OAUTH2'];
  transformTypes: TransformType[] = ['PASSTHROUGH', 'JSON_PATH', 'JMES_PATH', 'JAVASCRIPT', 'TEMPLATE', 'FIELD_MAPPING'];
  httpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'];
  parameterTypes = ['string', 'number', 'integer', 'boolean', 'array', 'object'];

  // UI state
  activeTab = 0; // 0=bridges, 1=mappings, 2=discover
  showBridgeDialog = false;
  showMappingDialog = false;
  showAuthDialog = false;
  showImportDialog = false;
  showTestDialog = false;
  importJson = '';

  private destroy$ = new Subject<void>();

  constructor(
    private bridgeService: RestMcpBridgeService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadBridges();
    // Poll for status updates every 5 seconds
    interval(5000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.refreshBridgeStatuses());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // Bridge Operations
  loadBridges(): void {
    this.isLoading = true;
    this.bridgeService.listBridges().subscribe({
      next: (bridges) => {
        this.bridges = bridges;
        this.isLoading = false;
      },
      error: (err) => {
        this.showError('Failed to load bridges: ' + err.message);
        this.isLoading = false;
      }
    });
  }

  refreshBridgeStatuses(): void {
    this.bridges.forEach(bridge => {
      if (bridge.id) {
        this.bridgeService.getBridgeStatus(bridge.id).subscribe({
          next: (status) => {
            bridge.status = status.status;
          }
        });
      }
    });
  }

  selectBridge(bridge: RestMcpBridgeConfig): void {
    this.selectedBridge = bridge;
    this.activeTab = 1; // Switch to mappings tab
  }

  createBridge(): void {
    this.editingBridge = this.bridgeService.createDefaultConfig();
    this.isEditing = false;
    this.showBridgeDialog = true;
  }

  editBridge(bridge: RestMcpBridgeConfig): void {
    this.editingBridge = JSON.parse(JSON.stringify(bridge)); // Deep copy
    this.isEditing = true;
    this.showBridgeDialog = true;
  }

  saveBridge(): void {
    if (!this.editingBridge) return;

    const operation = this.isEditing && this.editingBridge.id
      ? this.bridgeService.updateBridge(this.editingBridge.id, this.editingBridge)
      : this.bridgeService.createBridge(this.editingBridge);

    operation.subscribe({
      next: (saved) => {
        this.showSuccess(`Bridge ${this.isEditing ? 'updated' : 'created'} successfully`);
        this.showBridgeDialog = false;
        this.editingBridge = null;
        this.loadBridges();
        if (!this.isEditing) {
          this.selectedBridge = saved;
        }
      },
      error: (err) => {
        this.showError('Failed to save bridge: ' + (err.error?.error || err.message));
      }
    });
  }

  deleteBridge(bridge: RestMcpBridgeConfig): void {
    if (!bridge.id) return;

    const dialogData: ConfirmDialogData = {
      title: 'Delete Bridge',
      message: `Are you sure you want to delete bridge "${bridge.name}"?`,
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
        this.bridgeService.deleteBridge(bridge.id!).subscribe({
          next: () => {
            this.showSuccess('Bridge deleted successfully');
            if (this.selectedBridge?.id === bridge.id) {
              this.selectedBridge = null;
            }
            this.loadBridges();
          },
          error: (err) => {
            this.showError('Failed to delete bridge: ' + (err.error?.error || err.message));
          }
        });
      });
  }

  startBridge(bridge: RestMcpBridgeConfig): void {
    if (!bridge.id) return;
    this.bridgeService.startBridge(bridge.id).subscribe({
      next: (updated) => {
        this.showSuccess(`Bridge "${bridge.name}" started`);
        bridge.status = updated.status;
      },
      error: (err) => {
        this.showError('Failed to start bridge: ' + (err.error?.error || err.message));
      }
    });
  }

  stopBridge(bridge: RestMcpBridgeConfig): void {
    if (!bridge.id) return;
    this.bridgeService.stopBridge(bridge.id).subscribe({
      next: (updated) => {
        this.showSuccess(`Bridge "${bridge.name}" stopped`);
        bridge.status = updated.status;
      },
      error: (err) => {
        this.showError('Failed to stop bridge: ' + (err.error?.error || err.message));
      }
    });
  }

  syncBridge(bridge: RestMcpBridgeConfig): void {
    if (!bridge.id) return;
    this.bridgeService.syncBridge(bridge.id).subscribe({
      next: (updated) => {
        this.showSuccess(`Bridge "${bridge.name}" synced`);
        if (this.selectedBridge?.id === bridge.id) {
          this.selectedBridge = updated;
        }
        this.loadBridges();
      },
      error: (err) => {
        this.showError('Failed to sync bridge: ' + (err.error?.error || err.message));
      }
    });
  }

  exportBridge(bridge: RestMcpBridgeConfig): void {
    if (!bridge.id) return;
    this.bridgeService.exportConfig(bridge.id).subscribe({
      next: (json) => {
        const blob = new Blob([json], { type: 'application/json' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `mcp-bridge-${bridge.name}.json`;
        a.click();
        window.URL.revokeObjectURL(url);
      },
      error: (err) => {
        this.showError('Failed to export bridge: ' + err.message);
      }
    });
  }

  openImportDialog(): void {
    this.importJson = '';
    this.showImportDialog = true;
  }

  importBridge(): void {
    if (!this.importJson.trim()) return;
    this.bridgeService.importConfig(this.importJson).subscribe({
      next: () => {
        this.showSuccess('Bridge imported successfully');
        this.showImportDialog = false;
        this.importJson = '';
        this.loadBridges();
      },
      error: (err) => {
        this.showError('Failed to import bridge: ' + (err.error?.error || err.message));
      }
    });
  }

  // Mapping Operations
  createMapping(): void {
    if (!this.selectedBridge) return;
    this.editingMapping = this.bridgeService.createDefaultMapping();
    this.showMappingDialog = true;
  }

  editMapping(mapping: EndpointMapping): void {
    this.editingMapping = JSON.parse(JSON.stringify(mapping));
    this.showMappingDialog = true;
  }

  saveMapping(): void {
    if (!this.editingMapping || !this.selectedBridge?.id) return;

    const existingIndex = this.selectedBridge.mappings.findIndex(m => m.id === this.editingMapping!.id);

    if (existingIndex >= 0 && this.editingMapping.id) {
      // Update existing mapping
      this.bridgeService.updateMapping(this.selectedBridge.id, this.editingMapping.id, this.editingMapping).subscribe({
        next: (updated) => {
          this.showSuccess('Mapping updated successfully');
          this.selectedBridge = updated;
          this.showMappingDialog = false;
          this.editingMapping = null;
        },
        error: (err) => {
          this.showError('Failed to update mapping: ' + (err.error?.error || err.message));
        }
      });
    } else {
      // Add new mapping
      this.bridgeService.addMapping(this.selectedBridge.id, this.editingMapping).subscribe({
        next: (updated) => {
          this.showSuccess('Mapping added successfully');
          this.selectedBridge = updated;
          this.showMappingDialog = false;
          this.editingMapping = null;
        },
        error: (err) => {
          this.showError('Failed to add mapping: ' + (err.error?.error || err.message));
        }
      });
    }
  }

  deleteMapping(mapping: EndpointMapping): void {
    if (!this.selectedBridge?.id || !mapping.id) return;

    const dialogData: ConfirmDialogData = {
      title: 'Delete Mapping',
      message: 'Are you sure you want to delete this mapping?',
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
        this.bridgeService.removeMapping(this.selectedBridge!.id!, mapping.id!).subscribe({
          next: (updated) => {
            this.showSuccess('Mapping deleted successfully');
            this.selectedBridge = updated;
          },
          error: (err) => {
            this.showError('Failed to delete mapping: ' + (err.error?.error || err.message));
          }
        });
      });
  }

  toggleMapping(mapping: EndpointMapping): void {
    if (!this.selectedBridge?.id || !mapping.id) return;

    this.bridgeService.toggleMapping(this.selectedBridge.id, mapping.id).subscribe({
      next: (updated) => {
        this.selectedBridge = updated;
      },
      error: (err) => {
        this.showError('Failed to toggle mapping: ' + (err.error?.error || err.message));
      }
    });
  }

  // Discovery Operations
  discoverEndpoints(): void {
    if (!this.discoverUrl.trim()) return;

    this.isDiscovering = true;
    this.discoveredMappings = [];

    const discovery = this.discoveryMode === 'openapi'
      ? this.bridgeService.discoverFromOpenApi(this.discoverUrl)
      : this.bridgeService.probeEndpoints(this.discoverUrl);

    discovery.subscribe({
      next: (result) => {
        this.discoveredMappings = result.mappings;
        this.isDiscovering = false;
        if (result.mappings.length === 0) {
          this.showError('No endpoints discovered');
        } else {
          this.showSuccess(`Discovered ${result.count} endpoints`);
        }
      },
      error: (err) => {
        this.showError('Discovery failed: ' + (err.error?.error || err.message));
        this.isDiscovering = false;
      }
    });
  }

  addDiscoveredMapping(mapping: EndpointMapping): void {
    if (!this.selectedBridge?.id) {
      this.showError('Please select a bridge first');
      return;
    }

    this.bridgeService.addMapping(this.selectedBridge.id, mapping).subscribe({
      next: (updated) => {
        this.showSuccess('Mapping added to bridge');
        this.selectedBridge = updated;
        // Remove from discovered list
        const index = this.discoveredMappings.indexOf(mapping);
        if (index >= 0) {
          this.discoveredMappings.splice(index, 1);
        }
      },
      error: (err) => {
        this.showError('Failed to add mapping: ' + (err.error?.error || err.message));
      }
    });
  }

  addAllDiscoveredMappings(): void {
    if (!this.selectedBridge?.id) {
      this.showError('Please select a bridge first');
      return;
    }

    // Add mappings one by one
    this.discoveredMappings.forEach(mapping => {
      this.bridgeService.addMapping(this.selectedBridge!.id!, mapping).subscribe({
        next: (updated) => {
          this.selectedBridge = updated;
        }
      });
    });

    this.discoveredMappings = [];
    this.showSuccess('All mappings added to bridge');
  }

  // Built-in Tools Operations
  discoverBuiltInTools(): void {
    this.isLoadingBuiltIn = true;
    this.builtInTools = [];
    this.builtInMappings = [];

    this.bridgeService.discoverBuiltInTools().subscribe({
      next: (result) => {
        this.builtInTools = result.tools;
        this.builtInMappings = result.mappings;
        this.isLoadingBuiltIn = false;
        if (result.tools.length === 0) {
          this.showError('No built-in tools discovered');
        } else {
          this.showSuccess(`Discovered ${result.count} built-in tools`);
        }
      },
      error: (err) => {
        this.showError('Failed to discover built-in tools: ' + (err.error?.error || err.message));
        this.isLoadingBuiltIn = false;
      }
    });
  }

  createBuiltInToolsBridge(): void {
    this.isLoadingBuiltIn = true;

    this.bridgeService.createBuiltInToolsBridge().subscribe({
      next: (result) => {
        this.showSuccess(result.message);
        this.loadBridges();
        this.selectedBridge = result.bridge;
        this.isLoadingBuiltIn = false;
      },
      error: (err) => {
        this.showError('Failed to create built-in tools bridge: ' + (err.error?.error || err.message));
        this.isLoadingBuiltIn = false;
      }
    });
  }

  addBuiltInToolsToBridge(): void {
    if (!this.selectedBridge?.id) {
      this.showError('Please select a bridge first');
      return;
    }

    this.isLoadingBuiltIn = true;

    this.bridgeService.addBuiltInToolsToBridge(this.selectedBridge.id).subscribe({
      next: (result) => {
        this.showSuccess(`Added ${result.addedCount} built-in tools to bridge`);
        this.selectedBridge = result.bridge;
        this.isLoadingBuiltIn = false;
        this.loadBridges();
      },
      error: (err) => {
        this.showError('Failed to add built-in tools: ' + (err.error?.error || err.message));
        this.isLoadingBuiltIn = false;
      }
    });
  }

  addBuiltInMapping(mapping: EndpointMapping): void {
    if (!this.selectedBridge?.id) {
      this.showError('Please select a bridge first');
      return;
    }

    this.bridgeService.addMapping(this.selectedBridge.id, mapping).subscribe({
      next: (updated) => {
        this.showSuccess('Built-in tool mapping added to bridge');
        this.selectedBridge = updated;
        // Remove from built-in list
        const index = this.builtInMappings.indexOf(mapping);
        if (index >= 0) {
          this.builtInMappings.splice(index, 1);
        }
      },
      error: (err) => {
        this.showError('Failed to add mapping: ' + (err.error?.error || err.message));
      }
    });
  }

  refreshBuiltInTools(): void {
    this.isLoadingBuiltIn = true;

    this.bridgeService.refreshBuiltInToolDiscovery().subscribe({
      next: (result) => {
        this.builtInTools = result.tools;
        this.builtInMappings = result.mappings;
        this.isLoadingBuiltIn = false;
        this.showSuccess(`Refreshed: found ${result.count} built-in tools`);
      },
      error: (err) => {
        this.showError('Failed to refresh built-in tools: ' + (err.error?.error || err.message));
        this.isLoadingBuiltIn = false;
      }
    });
  }

  downloadOpenApiSpec(): void {
    this.bridgeService.getBuiltInToolsOpenApiSpec().subscribe({
      next: (spec) => {
        const blob = new Blob([JSON.stringify(spec, null, 2)], { type: 'application/json' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'kompile-tools-openapi.json';
        a.click();
        window.URL.revokeObjectURL(url);
      },
      error: (err) => {
        this.showError('Failed to download OpenAPI spec: ' + (err.error?.error || err.message));
      }
    });
  }

  // Test Operations
  openTestDialog(mapping: EndpointMapping): void {
    this.editingMapping = mapping;
    this.testInput = '{}';
    this.testResult = null;
    this.showTestDialog = true;
  }

  runTest(): void {
    if (!this.selectedBridge?.id || !this.editingMapping?.id) return;

    this.isTesting = true;
    this.testResult = null;

    let input: any;
    try {
      input = JSON.parse(this.testInput);
    } catch (e) {
      this.showError('Invalid JSON input');
      this.isTesting = false;
      return;
    }

    this.bridgeService.testMapping(this.selectedBridge.id, this.editingMapping.id, input).subscribe({
      next: (result) => {
        this.testResult = result;
        this.isTesting = false;
      },
      error: (err) => {
        this.testResult = {
          success: false,
          statusCode: 0,
          error: err.error?.error || err.message,
          durationMs: 0
        };
        this.isTesting = false;
      }
    });
  }

  // Auth Configuration
  openAuthDialog(): void {
    if (!this.editingBridge) return;
    this.showAuthDialog = true;
  }

  onAuthTypeChange(): void {
    if (!this.editingBridge?.authConfig) return;
    const type = this.editingBridge.authConfig.type;
    this.editingBridge.authConfig = this.bridgeService.createDefaultAuthConfig(type);
  }

  // Parameter Management
  addQueryParam(): void {
    if (!this.editingMapping?.restEndpoint) return;
    if (!this.editingMapping.restEndpoint.queryParams) {
      this.editingMapping.restEndpoint.queryParams = [];
    }
    this.editingMapping.restEndpoint.queryParams.push(this.bridgeService.createDefaultParameter());
  }

  removeQueryParam(index: number): void {
    if (!this.editingMapping?.restEndpoint?.queryParams) return;
    this.editingMapping.restEndpoint.queryParams.splice(index, 1);
  }

  addPathParam(): void {
    if (!this.editingMapping?.restEndpoint) return;
    if (!this.editingMapping.restEndpoint.pathParams) {
      this.editingMapping.restEndpoint.pathParams = [];
    }
    this.editingMapping.restEndpoint.pathParams.push(this.bridgeService.createDefaultParameter());
  }

  removePathParam(index: number): void {
    if (!this.editingMapping?.restEndpoint?.pathParams) return;
    this.editingMapping.restEndpoint.pathParams.splice(index, 1);
  }

  // Helper methods
  getStatusColor(status: BridgeStatus): string {
    return this.bridgeService.getStatusColor(status);
  }

  getStatusIcon(status: BridgeStatus): string {
    switch (status) {
      case 'RUNNING': return 'play_circle';
      case 'STOPPED': return 'stop_circle';
      case 'STARTING':
      case 'SYNCING': return 'pending';
      case 'ERROR': return 'error';
      default: return 'help';
    }
  }

  getDirectionIcon(direction: BridgeDirection): string {
    return direction === 'REST_TO_MCP' ? 'swap_horiz' : 'swap_vert';
  }

  getDirectionDisplayName(direction: BridgeDirection): string {
    return this.bridgeService.getDirectionDisplayName(direction);
  }

  getAuthTypeDisplayName(type: AuthType): string {
    return this.bridgeService.getAuthTypeDisplayName(type);
  }

  cancelBridgeDialog(): void {
    this.showBridgeDialog = false;
    this.editingBridge = null;
  }

  cancelMappingDialog(): void {
    this.showMappingDialog = false;
    this.editingMapping = null;
  }

  cancelAuthDialog(): void {
    this.showAuthDialog = false;
  }

  cancelImportDialog(): void {
    this.showImportDialog = false;
    this.importJson = '';
  }

  cancelTestDialog(): void {
    this.showTestDialog = false;
    this.testResult = null;
    this.editingMapping = null;
  }

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 3000, panelClass: ['success-snackbar'] });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
  }
}
