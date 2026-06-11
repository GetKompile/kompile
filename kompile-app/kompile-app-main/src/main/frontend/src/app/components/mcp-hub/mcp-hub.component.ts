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
import { McpClientService, McpConnection, McpTool, McpResource, McpPrompt } from '../../services/mcp-client.service';

@Component({
  standalone: false,
  selector: 'app-mcp-hub',
  templateUrl: './mcp-hub.component.html',
  styleUrls: ['./mcp-hub.component.css']
})
export class McpHubComponent implements OnInit {
  activeSubTab: 'tools' | 'serverBuilder' | 'externalServers' | 'restBridge' | 'debugger' | 'toolManager' | 'promptTemplates' | 'clientConnections' = 'tools';

  // MCP Client state
  connections: McpConnection[] = [];
  connectionsLoading = false;
  selectedServer: string | null = null;
  serverTools: McpTool[] = [];
  serverResources: McpResource[] = [];
  serverPrompts: McpPrompt[] = [];
  serverDetailLoading = false;
  clientError: string | null = null;
  clientMessage: string | null = null;

  // Connect form
  showConnectForm = false;
  connectName = '';
  connectSseUrl = '';

  constructor(private mcpClient: McpClientService) {}

  ngOnInit(): void {}

  loadConnections(): void {
    this.connectionsLoading = true;
    this.clientError = null;
    this.mcpClient.listConnections().subscribe({
      next: r => {
        this.connections = r.connections || [];
        this.connectionsLoading = false;
      },
      error: () => {
        this.clientError = 'MCP client registry not available';
        this.connectionsLoading = false;
      }
    });
  }

  connectServer(): void {
    if (!this.connectName || !this.connectSseUrl) return;
    this.mcpClient.connect(this.connectName, this.connectSseUrl).subscribe({
      next: () => {
        this.showConnectForm = false;
        this.showClientMsg(`Connected: ${this.connectName}`);
        this.connectName = '';
        this.connectSseUrl = '';
        this.loadConnections();
      },
      error: e => this.clientError = e.error?.message || 'Connect failed'
    });
  }

  disconnectServer(name: string): void {
    this.mcpClient.disconnect(name).subscribe({
      next: () => {
        this.showClientMsg(`Disconnected: ${name}`);
        if (this.selectedServer === name) {
          this.selectedServer = null;
          this.serverTools = [];
          this.serverResources = [];
          this.serverPrompts = [];
        }
        this.loadConnections();
      }
    });
  }

  selectServer(name: string): void {
    this.selectedServer = name;
    this.serverDetailLoading = true;
    this.serverTools = [];
    this.serverResources = [];
    this.serverPrompts = [];

    this.mcpClient.listTools(name).subscribe({
      next: r => this.serverTools = r.tools || [],
      error: () => {}
    });
    this.mcpClient.listResources(name).subscribe({
      next: r => this.serverResources = r.resources || [],
      error: () => {}
    });
    this.mcpClient.listPrompts(name).subscribe({
      next: r => {
        this.serverPrompts = r.prompts || [];
        this.serverDetailLoading = false;
      },
      error: () => this.serverDetailLoading = false
    });
  }

  private showClientMsg(msg: string): void {
    this.clientMessage = msg;
    setTimeout(() => this.clientMessage = null, 4000);
  }
}
