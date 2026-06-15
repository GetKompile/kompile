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
import { HttpClient } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BaseService } from '../../services/base.service';

interface AgentStatus {
  id: string;
  name: string;
  description: string;
  configFile: string;
  injected: boolean;
  injectedProject: boolean;
  injectedHome: boolean;
  projectConfigExists: boolean;
  homeConfigExists: boolean;
  hasBackup: boolean;
}

interface InjectionStatus {
  workingDir: string;
  agents: AgentStatus[];
  totalAgents: number;
  injectedCount: number;
}

interface CliCommands {
  inject: string;
  profiles: { name: string; description: string; toolCount: string }[];
  schemaLevels: { name: string; description: string }[];
}

@Component({
  standalone: false,
  selector: 'app-mcp-cli-injection',
  templateUrl: './mcp-cli-injection.component.html',
  styleUrls: ['./mcp-cli-injection.component.css']
})
export class McpCliInjectionComponent extends BaseService implements OnInit {
  status: InjectionStatus | null = null;
  cliCommands: CliCommands | null = null;
  selectedProfile = 'full';
  isLoading = false;
  expandedAgent: string | null = null;
  agentConfig: any = null;

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar
  ) {
    super();
  }

  ngOnInit(): void {
    this.loadStatus();
    this.loadCliCommands();
  }

  loadStatus(): void {
    this.isLoading = true;
    this.http.get<InjectionStatus>(`${this.backendUrl}/mcp/cli-injection/status`).subscribe({
      next: (status) => {
        this.status = status;
        this.isLoading = false;
      },
      error: () => {
        this.isLoading = false;
      }
    });
  }

  loadCliCommands(): void {
    this.http.get<CliCommands>(`${this.backendUrl}/mcp/cli-injection/cli-command?profile=${this.selectedProfile}`).subscribe({
      next: (commands) => {
        this.cliCommands = commands;
      }
    });
  }

  toggleAgent(agentId: string): void {
    if (this.expandedAgent === agentId) {
      this.expandedAgent = null;
      this.agentConfig = null;
      return;
    }
    this.expandedAgent = agentId;
    this.http.get<any>(`${this.backendUrl}/mcp/cli-injection/config/${agentId}`).subscribe({
      next: (config) => {
        this.agentConfig = config;
      }
    });
  }

  getAgentIcon(agentId: string): string {
    switch (agentId) {
      case 'claude': return 'smart_toy';
      case 'codex': return 'code';
      case 'gemini': return 'auto_awesome';
      case 'opencode': return 'terminal';
      case 'qwen': return 'psychology';
      default: return 'extension';
    }
  }

  getStatusColor(agent: AgentStatus): string {
    if (agent.injected) return '#16a34a';
    if (agent.projectConfigExists || agent.homeConfigExists) return '#ea580c';
    return '#9ca3af';
  }

  copyCommand(command: string): void {
    navigator.clipboard.writeText(command).then(() => {
      this.snackBar.open('Copied to clipboard', 'Close', { duration: 2000 });
    });
  }

  onProfileChange(): void {
    this.loadCliCommands();
  }
}
