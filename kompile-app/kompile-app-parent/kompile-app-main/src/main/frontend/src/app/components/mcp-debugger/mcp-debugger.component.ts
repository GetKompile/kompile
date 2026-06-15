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
import { McpService, ActionLogEntry, ToolInvocationResponse } from '../../services/mcp.service';
import { McpToolInfo } from '../../models/api-models';
import { Subject } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';

interface ToolArgument {
  name: string;
  type: string;
  value: any;
  required: boolean;
}

interface ExecutionResult {
  timestamp: Date;
  toolName: string;
  arguments: { [key: string]: any };
  result: any;
  error?: string;
  durationMs: number;
}

@Component({
  standalone: false,
  selector: 'app-mcp-debugger',
  templateUrl: './mcp-debugger.component.html',
  styleUrls: ['./mcp-debugger.component.css']
})
export class McpDebuggerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // Tool list
  tools: McpToolInfo[] = [];
  selectedTool: McpToolInfo | null = null;
  toolArguments: ToolArgument[] = [];

  // Execution state
  isExecuting = false;
  executionResults: ExecutionResult[] = [];
  currentResult: ExecutionResult | null = null;

  // Action log
  actionLog: ActionLogEntry[] = [];
  actionLogStats: any = null;
  showActionLog = false;
  actionLogFilter = {
    limit: 50,
    toolName: '',
    actionType: '',
    undoableOnly: false
  };

  // UI state
  isLoading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  // Action types for filter
  actionTypes = ['', 'READ', 'WRITE', 'DELETE', 'EXECUTE', 'CONFIG'];

  constructor(
    private mcpService: McpService,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadTools();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadTools(): void {
    this.isLoading = true;
    this.errorMessage = null;

    this.mcpService.listTools()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (tools) => {
          this.tools = tools;
          this.isLoading = false;
        },
        error: (err) => {
          this.errorMessage = `Failed to load tools: ${err.message}`;
          this.isLoading = false;
        }
      });
  }

  selectTool(tool: McpToolInfo): void {
    this.selectedTool = tool;
    this.toolArguments = this.parseInputSchema(tool.inputSchema);
    this.currentResult = null;
    this.clearMessages();
  }

  parseInputSchema(schema: any): ToolArgument[] {
    const args: ToolArgument[] = [];

    if (!schema || !schema.properties) {
      return args;
    }

    const required = schema.required || [];

    for (const [name, prop] of Object.entries(schema.properties)) {
      const propObj = prop as any;
      args.push({
        name,
        type: propObj.type || 'string',
        value: this.getDefaultValue(propObj.type),
        required: required.includes(name)
      });
    }

    return args;
  }

  getDefaultValue(type: string): any {
    switch (type) {
      case 'boolean':
        return false;
      case 'integer':
      case 'number':
        return null;
      case 'array':
        return [];
      case 'object':
        return {};
      default:
        return '';
    }
  }

  getArgumentValue(arg: ToolArgument): any {
    if (arg.value === '' || arg.value === null) {
      return undefined;
    }

    switch (arg.type) {
      case 'integer':
        return parseInt(arg.value, 10);
      case 'number':
        return parseFloat(arg.value);
      case 'boolean':
        return arg.value === true || arg.value === 'true';
      case 'array':
      case 'object':
        if (typeof arg.value === 'string') {
          try {
            return JSON.parse(arg.value);
          } catch {
            return arg.value;
          }
        }
        return arg.value;
      default:
        return arg.value;
    }
  }

  buildArguments(): { [key: string]: any } {
    const args: { [key: string]: any } = {};

    for (const arg of this.toolArguments) {
      const value = this.getArgumentValue(arg);
      if (value !== undefined && value !== '') {
        args[arg.name] = value;
      }
    }

    return args;
  }

  executeTool(): void {
    if (!this.selectedTool) {
      return;
    }

    this.isExecuting = true;
    this.clearMessages();
    const startTime = Date.now();

    const request = {
      toolName: this.selectedTool.name,
      arguments: this.buildArguments()
    };

    this.mcpService.invokeTool(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response: ToolInvocationResponse) => {
          const duration = Date.now() - startTime;
          const result: ExecutionResult = {
            timestamp: new Date(),
            toolName: request.toolName,
            arguments: request.arguments,
            result: response.result,
            error: response.error,
            durationMs: duration
          };

          this.currentResult = result;
          this.executionResults.unshift(result);

          // Keep only last 50 results
          if (this.executionResults.length > 50) {
            this.executionResults = this.executionResults.slice(0, 50);
          }

          this.isExecuting = false;
          this.successMessage = `Tool executed successfully in ${duration}ms`;
        },
        error: (err) => {
          const duration = Date.now() - startTime;
          const result: ExecutionResult = {
            timestamp: new Date(),
            toolName: request.toolName,
            arguments: request.arguments,
            result: null,
            error: err.message,
            durationMs: duration
          };

          this.currentResult = result;
          this.executionResults.unshift(result);
          this.isExecuting = false;
          this.errorMessage = `Execution failed: ${err.message}`;
        }
      });
  }

  // Action Log methods
  toggleActionLog(): void {
    this.showActionLog = !this.showActionLog;
    if (this.showActionLog) {
      this.loadActionLog();
      this.loadActionStats();
    }
  }

  loadActionLog(): void {
    this.mcpService.getActionLog(
      this.actionLogFilter.limit,
      this.actionLogFilter.toolName || undefined,
      this.actionLogFilter.actionType || undefined,
      this.actionLogFilter.undoableOnly
    )
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.result && response.result.actions) {
            this.actionLog = response.result.actions;
          }
        },
        error: (err) => {
          this.errorMessage = `Failed to load action log: ${err.message}`;
        }
      });
  }

  loadActionStats(): void {
    this.mcpService.getActionStats()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.result) {
            this.actionLogStats = response.result;
          }
        },
        error: (err) => {
          console.error('Failed to load action stats:', err);
        }
      });
  }

  undoAction(actionId: number): void {
    this.mcpService.undoAction(actionId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.result && response.result.status === 'success') {
            this.successMessage = `Action ${actionId} undone successfully`;
            this.loadActionLog();
          } else {
            this.errorMessage = response.result?.error || 'Undo failed';
          }
        },
        error: (err) => {
          this.errorMessage = `Undo failed: ${err.message}`;
        }
      });
  }

  undoLastAction(): void {
    this.mcpService.undoLastAction()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.result && response.result.status === 'success') {
            this.successMessage = `Last action undone successfully`;
            this.loadActionLog();
          } else {
            this.errorMessage = response.result?.error || 'Undo failed';
          }
        },
        error: (err) => {
          this.errorMessage = `Undo failed: ${err.message}`;
        }
      });
  }

  clearActionLog(): void {
    const dialogData: ConfirmDialogData = {
      title: 'Clear Action Log',
      message: 'Are you sure you want to clear all action logs? This cannot be undone.',
      confirmText: 'Clear All',
      confirmColor: 'warn',
      icon: 'delete_forever'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.mcpService.clearActionLog(true)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: (response) => {
              if (response.result && response.result.status === 'success') {
                this.successMessage = `Action log cleared (${response.result.entriesCleared} entries removed)`;
                this.actionLog = [];
                this.loadActionStats();
              } else {
                this.errorMessage = response.result?.error || 'Clear failed';
              }
            },
            error: (err) => {
              this.errorMessage = `Clear failed: ${err.message}`;
            }
          });
      });
  }

  // Utility methods
  clearMessages(): void {
    this.errorMessage = null;
    this.successMessage = null;
  }

  formatJson(obj: any): string {
    try {
      return JSON.stringify(obj, null, 2);
    } catch {
      return String(obj);
    }
  }

  formatTimestamp(timestamp: string): string {
    return new Date(timestamp).toLocaleString();
  }

  getToolsByCategory(): { [category: string]: McpToolInfo[] } {
    const categories: { [category: string]: McpToolInfo[] } = {};

    for (const tool of this.tools) {
      const category = this.getCategoryFromTool(tool);
      if (!categories[category]) {
        categories[category] = [];
      }
      categories[category].push(tool);
    }

    return categories;
  }

  getCategoryFromTool(tool: McpToolInfo): string {
    // First, check if tool has an explicit source (from backend)
    const toolAny = tool as any;
    if (toolAny.source === 'mcp_server') {
      return `MCP Server: ${toolAny.serverName || 'Unknown'}`;
    }
    if (toolAny.source === 'rest_bridge') {
      return `REST Bridge: ${toolAny.bridgeName || 'Unknown'}`;
    }

    // For built-in tools or tools without source, categorize by name
    return this.getCategoryFromToolName(tool.name);
  }

  getCategoryFromToolName(name: string): string {
    if (name.includes('rag') || name.includes('query') || name.includes('document')) {
      return 'RAG';
    } else if (name.includes('file') || name.includes('directory')) {
      return 'Filesystem';
    } else if (name.includes('model') || name.includes('embedding')) {
      return 'Model';
    } else if (name.includes('index')) {
      return 'Indexing';
    } else if (name.includes('config') || name.includes('setting') || name.includes('bean') || name.includes('profile')) {
      return 'Configuration';
    } else if (name.includes('action') || name.includes('undo')) {
      return 'Action Log';
    } else if (name.includes('session') || name.includes('chat')) {
      return 'Chat';
    } else if (name.includes('diagnostic') || name.includes('memory') || name.includes('system')) {
      return 'System';
    }
    return 'Other';
  }

  getSourceIcon(tool: McpToolInfo): string {
    const toolAny = tool as any;
    switch (toolAny.source) {
      case 'mcp_server': return 'dns';
      case 'rest_bridge': return 'http';
      case 'built_in': return 'code';
      default: return 'build';
    }
  }

  getSourceLabel(tool: McpToolInfo): string {
    const toolAny = tool as any;
    switch (toolAny.source) {
      case 'mcp_server': return `MCP Server: ${toolAny.serverName || 'Unknown'}`;
      case 'rest_bridge': return `REST Bridge: ${toolAny.bridgeName || 'Unknown'}`;
      case 'built_in': return 'Built-in';
      default: return 'Unknown';
    }
  }

  getSourceColor(tool: McpToolInfo): string {
    const toolAny = tool as any;
    switch (toolAny.source) {
      case 'mcp_server': return '#9c27b0'; // Purple
      case 'rest_bridge': return '#ff9800'; // Orange
      case 'built_in': return '#4caf50'; // Green
      default: return '#757575'; // Grey
    }
  }

  getCategoryIcon(category: string): string {
    // Handle dynamic category names from MCP servers and bridges
    if (category.startsWith('MCP Server:')) return 'dns';
    if (category.startsWith('REST Bridge:')) return 'http';

    switch (category) {
      case 'RAG': return 'search';
      case 'Filesystem': return 'folder';
      case 'Model': return 'psychology';
      case 'Indexing': return 'storage';
      case 'Configuration': return 'settings';
      case 'Action Log': return 'history';
      case 'Chat': return 'chat';
      case 'System': return 'computer';
      default: return 'extension';
    }
  }

  getActionTypeIcon(actionType: string): string {
    switch (actionType) {
      case 'READ': return 'visibility';
      case 'WRITE': return 'edit';
      case 'DELETE': return 'delete';
      case 'EXECUTE': return 'play_arrow';
      case 'CONFIG': return 'settings';
      default: return 'help';
    }
  }

  getActionTypeColor(actionType: string): string {
    switch (actionType) {
      case 'READ': return '#4caf50';
      case 'WRITE': return '#2196f3';
      case 'DELETE': return '#f44336';
      case 'EXECUTE': return '#ff9800';
      case 'CONFIG': return '#9c27b0';
      default: return '#757575';
    }
  }

  copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      this.successMessage = 'Copied to clipboard';
      setTimeout(() => this.clearMessages(), 2000);
    });
  }

  clearHistory(): void {
    this.executionResults = [];
    this.currentResult = null;
  }
}
