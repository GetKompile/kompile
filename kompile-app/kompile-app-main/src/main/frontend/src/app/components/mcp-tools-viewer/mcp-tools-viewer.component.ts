/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit } from '@angular/core';
import { McpService } from '../../services/mcp.service';
import { McpToolInfo } from '../../models/api-models';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
  standalone: false,
  selector: 'app-mcp-tools-viewer',
  templateUrl: './mcp-tools-viewer.component.html',
  styleUrls: ['./mcp-tools-viewer.component.css']
})
export class McpToolsViewerComponent implements OnInit {
  mcpTools: McpToolInfo[] = [];
  isLoading: boolean = false;
  errorMessage: string | null = null;

  constructor(private mcpService: McpService) { }

  ngOnInit(): void {
    this.loadMcpTools();
  }

  loadMcpTools(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.mcpService.listTools().subscribe({
      next: (tools) => {
        this.mcpTools = tools;
        this.isLoading = false;
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage = `Failed to load MCP tools: ${err.message}`;
        this.isLoading = false;
      }
    });
  }
}
