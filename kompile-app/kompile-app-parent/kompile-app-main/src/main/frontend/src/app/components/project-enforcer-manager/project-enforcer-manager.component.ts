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
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatSortModule } from '@angular/material/sort';
import { HttpClient } from '@angular/common/http';
import { BaseService, backendUrl } from '../../services/base.service';
import {
  EnforcerService,
  EnforcerJudgeConfig,
  EnforcerConfigResponse,
  EnforcerMetricsSummary,
  EnforcerMetricsDetail,
  MetricHistoryEvent
} from '../../services/enforcer.service';
import { CodingProject } from '../../services/project.service';

@Component({
  selector: 'app-project-enforcer-manager',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatChipsModule,
    MatSlideToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatDividerModule,
    MatTabsModule,
    MatTableModule,
    MatSortModule
  ],
  templateUrl: './project-enforcer-manager.component.html',
  styleUrls: ['./project-enforcer-manager.component.css']
})
export class ProjectEnforcerManagerComponent implements OnInit {

  codingProjects: CodingProject[] = [];
  selectedProject: CodingProject | null = null;
  editConfig: EnforcerJudgeConfig | null = null;
  configSource: 'code-project' | 'project' | 'none' = 'none';

  // Config editing helpers
  newKeyword = '';
  newTool = '';

  // Session creation
  showSessionForm = false;
  sessionProject: CodingProject | null = null;
  sessionAgent = 'claude';
  sessionRules = '';
  sessionMaxCorrections = 2;

  // Metrics
  metricsData: EnforcerMetricsSummary[] = [];
  selectedMetricsDetail: EnforcerMetricsDetail | null = null;
  metricsLoading = false;

  // State
  loading = false;
  errorMessage = '';

  // Map of projectId -> config status
  private configStatuses: Map<string, 'code-project' | 'project' | 'none'> = new Map();

  constructor(
    private http: HttpClient,
    private enforcerService: EnforcerService
  ) {}

  ngOnInit(): void {
    this.loadProjects();
  }

  loadProjects(): void {
    this.loading = true;
    this.http.get<any>(`${backendUrl}/project`).subscribe({
      next: response => {
        const manifest = response?.manifest;
        this.codingProjects = manifest?.codingProjects || [];
        this.loading = false;
        // Load config status for each project
        for (const project of this.codingProjects) {
          if (project.id) {
            this.loadConfigStatus(project.id);
          }
        }
      },
      error: err => {
        this.errorMessage = 'Failed to load projects: ' + (err.error?.message || err.message);
        this.loading = false;
      }
    });
  }

  private loadConfigStatus(projectId: string): void {
    this.enforcerService.getCodeProjectConfig(projectId).subscribe({
      next: response => {
        this.configStatuses.set(projectId, response.source);
      },
      error: () => {
        this.configStatuses.set(projectId, 'none');
      }
    });
  }

  selectProject(project: CodingProject): void {
    this.selectedProject = project;
    this.showSessionForm = false;
    if (project.id) {
      this.loading = true;
      this.enforcerService.getCodeProjectConfig(project.id).subscribe({
        next: response => {
          this.configSource = response.source;
          this.editConfig = response.config || this.defaultConfig();
          this.loading = false;
        },
        error: () => {
          this.configSource = 'none';
          this.editConfig = this.defaultConfig();
          this.loading = false;
        }
      });
    }
  }

  saveConfig(): void {
    if (!this.selectedProject?.id || !this.editConfig) return;
    this.enforcerService.saveCodeProjectConfig(this.selectedProject.id, this.editConfig).subscribe({
      next: () => {
        this.configSource = 'code-project';
        this.configStatuses.set(this.selectedProject!.id!, 'code-project');
        this.errorMessage = '';
      },
      error: err => {
        this.errorMessage = 'Failed to save config: ' + (err.error?.message || err.message);
      }
    });
  }

  deleteConfig(): void {
    if (!this.selectedProject?.id) return;
    this.enforcerService.deleteCodeProjectConfig(this.selectedProject.id).subscribe({
      next: () => {
        this.configStatuses.set(this.selectedProject!.id!, 'none');
        this.configSource = 'none';
        this.editConfig = this.defaultConfig();
      },
      error: err => {
        this.errorMessage = 'Failed to delete config: ' + (err.error?.message || err.message);
      }
    });
  }

  startSessionForProject(project: CodingProject): void {
    this.sessionProject = project;
    this.showSessionForm = true;
    this.selectedProject = null;
    this.editConfig = null;

    // Pre-load config to populate session form defaults
    if (project.id) {
      this.enforcerService.getCodeProjectConfig(project.id).subscribe({
        next: response => {
          const cfg = response.config;
          if (cfg) {
            this.sessionAgent = cfg.agent || 'claude';
            this.sessionMaxCorrections = cfg.maxCorrections ?? 2;
            this.sessionRules = cfg.inlineRules || '';
            // Build rules from banned keywords if no inline rules
            if (!this.sessionRules && cfg.bannedKeywords?.length) {
              this.sessionRules = cfg.bannedKeywords.map(k => 'BAN: ' + k).join('\n');
            }
          }
        }
      });
    }
  }

  createSession(): void {
    if (!this.sessionProject || !this.sessionRules) return;
    this.enforcerService.createSession({
      agentName: this.sessionAgent,
      rules: this.sessionRules,
      maxCorrections: this.sessionMaxCorrections,
      workingDirectory: this.sessionProject.rootPath,
      codingProjectId: this.sessionProject.id || undefined
    }).subscribe({
      next: () => {
        this.showSessionForm = false;
        this.errorMessage = '';
      },
      error: err => {
        this.errorMessage = 'Failed to create session: ' + (err.error?.message || err.message);
      }
    });
  }

  // ── Metrics ────────────────────────────────────────────────────

  loadAllMetrics(): void {
    this.metricsLoading = true;
    this.enforcerService.getAllMetrics().subscribe({
      next: data => {
        this.metricsData = data;
        this.metricsLoading = false;
      },
      error: err => {
        this.errorMessage = 'Failed to load metrics: ' + (err.error?.message || err.message);
        this.metricsLoading = false;
      }
    });
  }

  viewAgentDetail(row: EnforcerMetricsSummary): void {
    this.metricsLoading = true;
    this.enforcerService.getProjectAgentMetrics(row.codingProjectId, row.agentName).subscribe({
      next: detail => {
        this.selectedMetricsDetail = detail;
        this.metricsLoading = false;
      },
      error: err => {
        this.errorMessage = 'Failed to load detail: ' + (err.error?.message || err.message);
        this.metricsLoading = false;
      }
    });
  }

  closeMetricsDetail(): void {
    this.selectedMetricsDetail = null;
  }

  getScoreColor(score: number): string {
    if (score >= 0.8) return '#4caf50';
    if (score >= 0.5) return '#ff9800';
    return '#f44336';
  }

  formatScore(score: number): string {
    return (score * 100).toFixed(1) + '%';
  }

  // ── Keyword/tool management ────────────────────────────────────

  addKeyword(): void {
    if (!this.newKeyword || !this.editConfig) return;
    if (!this.editConfig.bannedKeywords) this.editConfig.bannedKeywords = [];
    if (!this.editConfig.bannedKeywords.includes(this.newKeyword)) {
      this.editConfig.bannedKeywords.push(this.newKeyword);
    }
    this.newKeyword = '';
  }

  removeKeyword(keyword: string): void {
    if (!this.editConfig?.bannedKeywords) return;
    this.editConfig.bannedKeywords = this.editConfig.bannedKeywords.filter(k => k !== keyword);
  }

  addTool(): void {
    if (!this.newTool || !this.editConfig) return;
    if (!this.editConfig.bannedTools) this.editConfig.bannedTools = [];
    if (!this.editConfig.bannedTools.includes(this.newTool)) {
      this.editConfig.bannedTools.push(this.newTool);
    }
    this.newTool = '';
  }

  removeTool(tool: string): void {
    if (!this.editConfig?.bannedTools) return;
    this.editConfig.bannedTools = this.editConfig.bannedTools.filter(t => t !== tool);
  }

  // ── UI helpers ─────────────────────────────────────────────────

  getConfigStatusIcon(project: CodingProject): string {
    const status = this.configStatuses.get(project.id || '');
    if (status === 'code-project') return 'gavel';
    if (status === 'project') return 'account_tree';
    return 'settings_suggest';
  }

  getConfigStatusColor(project: CodingProject): string {
    const status = this.configStatuses.get(project.id || '');
    if (status === 'code-project') return '#4caf50';
    if (status === 'project') return '#ff9800';
    return '#9e9e9e';
  }

  getConfigLabel(project: CodingProject): string {
    const status = this.configStatuses.get(project.id || '');
    if (status === 'code-project') return 'judge configured';
    if (status === 'project') return 'inherited config';
    return 'no config';
  }

  private defaultConfig(): EnforcerJudgeConfig {
    return {
      agent: 'claude',
      maxCorrections: 2,
      keywordMode: true,
      archiveDiffs: true,
      autoRollbackOnViolation: true,
      semanticMode: 'wordnet',
      semanticThreshold: 0.78,
      primaryLanguage: 'java',
      bannedKeywords: [],
      bannedTools: [],
      bannedCommands: []
    };
  }
}
