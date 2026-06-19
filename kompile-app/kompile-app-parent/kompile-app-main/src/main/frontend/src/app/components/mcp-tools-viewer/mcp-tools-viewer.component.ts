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

import { Component, OnInit } from '@angular/core';
import { McpService } from '../../services/mcp.service';
import { McpToolInfo } from '../../models/api-models';
import { HttpErrorResponse } from '@angular/common/http';

interface SchemaParam {
  name: string;
  type: string;
  required: boolean;
  description?: string;
}

@Component({
  standalone: false,
  selector: 'app-mcp-tools-viewer',
  templateUrl: './mcp-tools-viewer.component.html',
  styleUrls: ['./mcp-tools-viewer.component.css']
})
export class McpToolsViewerComponent implements OnInit {
  mcpTools: McpToolInfo[] = [];
  filteredTools: McpToolInfo[] = [];
  isLoading = false;
  errorMessage: string | null = null;
  searchQuery = '';
  sourceFilter = 'all';
  expandedTool: string | null = null;

  constructor(private mcpService: McpService) {}

  ngOnInit(): void {
    this.loadMcpTools();
  }

  loadMcpTools(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.mcpService.listTools().subscribe({
      next: (tools) => {
        this.mcpTools = tools;
        this.filterTools();
        this.isLoading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage = `Failed to load MCP tools: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  filterTools(): void {
    let tools = this.mcpTools;
    if (this.searchQuery) {
      const q = this.searchQuery.toLowerCase();
      tools = tools.filter(t =>
        t.name.toLowerCase().includes(q) ||
        t.description?.toLowerCase().includes(q)
      );
    }
    if (this.sourceFilter !== 'all') {
      tools = tools.filter(t => (t as any).source === this.sourceFilter);
    }
    this.filteredTools = tools;
  }

  toggleExpand(toolName: string): void {
    this.expandedTool = this.expandedTool === toolName ? null : toolName;
  }

  getSchemaParams(schema: any): SchemaParam[] {
    if (!schema?.properties) return [];
    const required = schema.required || [];
    return Object.entries(schema.properties).map(([name, prop]: [string, any]) => ({
      name,
      type: prop.type || 'string',
      required: required.includes(name),
      description: prop.description
    }));
  }

  getSourceIcon(tool: McpToolInfo): string {
    const src = (tool as any).source;
    switch (src) {
      case 'mcp_server': return 'dns';
      case 'rest_bridge': return 'http';
      default: return 'code';
    }
  }

  getSourceLabel(tool: McpToolInfo): string {
    const src = (tool as any).source;
    switch (src) {
      case 'mcp_server': return 'MCP Server';
      case 'rest_bridge': return 'REST Bridge';
      default: return 'Built-in';
    }
  }

  getSourceColor(tool: McpToolInfo): string {
    const src = (tool as any).source;
    switch (src) {
      case 'mcp_server': return '#7c3aed';
      case 'rest_bridge': return '#ea580c';
      default: return '#4f46e5';
    }
  }
}
