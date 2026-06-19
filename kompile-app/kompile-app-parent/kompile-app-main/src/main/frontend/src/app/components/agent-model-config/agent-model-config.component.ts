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
import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { AgentService, AgentModelInfo } from '../../services/agent.service';

@Component({
  selector: 'app-agent-model-config',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatSelectModule, MatFormFieldModule, MatInputModule,
    MatButtonModule, MatIconModule, MatChipsModule, MatProgressSpinnerModule,
    MatTooltipModule
  ],
  templateUrl: './agent-model-config.component.html',
  styleUrls: ['./agent-model-config.component.css']
})
export class AgentModelConfigComponent implements OnInit, OnDestroy {
  agents: AgentModelInfo[] = [];
  loading = false;
  saving: { [key: string]: boolean } = {};
  customModels: { [key: string]: string } = {};
  showCustomInput: { [key: string]: boolean } = {};

  private destroy$ = new Subject<void>();

  constructor(private agentService: AgentService) {}

  ngOnInit(): void {
    this.loadAgentModels(false);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadAgentModels(refresh: boolean): void {
    this.loading = true;
    this.agentService.getAllAgentModels(refresh).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (agents) => {
        this.agents = agents;
        this.loading = false;
        // Initialize custom model inputs with current values
        for (const agent of agents) {
          if (agent.currentModel && !agent.availableModels.includes(agent.currentModel)) {
            this.customModels[agent.agentName] = agent.currentModel;
            this.showCustomInput[agent.agentName] = true;
          }
        }
      },
      error: (err) => {
        console.error('Failed to load agent models:', err);
        this.loading = false;
      }
    });
  }

  onModelSelect(agent: AgentModelInfo, model: string | null): void {
    if (model === '__custom__') {
      this.showCustomInput[agent.agentName] = true;
      return;
    }
    this.showCustomInput[agent.agentName] = false;
    this.saveModel(agent.agentName, model);
  }

  onCustomModelSubmit(agentName: string): void {
    const model = this.customModels[agentName]?.trim();
    if (model) {
      this.saveModel(agentName, model);
    }
  }

  clearModel(agentName: string): void {
    this.showCustomInput[agentName] = false;
    this.customModels[agentName] = '';
    this.saveModel(agentName, null);
  }

  private saveModel(agentName: string, model: string | null): void {
    this.saving[agentName] = true;
    this.agentService.setAgentModel(agentName, model).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: () => {
        this.saving[agentName] = false;
        // Update local state
        const agent = this.agents.find(a => a.agentName === agentName);
        if (agent) {
          (agent as any).currentModel = model;
        }
      },
      error: (err) => {
        console.error(`Failed to set model for ${agentName}:`, err);
        this.saving[agentName] = false;
      }
    });
  }

  getSelectedValue(agent: AgentModelInfo): string | null {
    if (this.showCustomInput[agent.agentName]) {
      return '__custom__';
    }
    if (agent.currentModel && agent.availableModels.includes(agent.currentModel)) {
      return agent.currentModel;
    }
    return null;
  }

  trackByAgent(_: number, agent: AgentModelInfo): string {
    return agent.agentName;
  }
}
