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
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Subject, takeUntil } from 'rxjs';
import { OpenClawService } from '../../services/openclaw.service';
import { AgentDefinition } from '../../models/openclaw-models';
import { MatSnackBar } from '@angular/material/snack-bar';

@Component({
  selector: 'app-openclaw-agent-manager',
  standalone: false,
  templateUrl: './openclaw-agent-manager.component.html',
  styleUrls: ['./openclaw-agent-manager.component.css']
})
export class OpenClawAgentManagerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  agents: AgentDefinition[] = [];
  selectedAgent: AgentDefinition | null = null;
  isEditing = false;
  isNewAgent = false;
  
  agentForm: FormGroup;
  availableTools = ['run_command', 'save_memory', 'search_memory', 'rag_query'];
  
  displayedColumns = ['name', 'description', 'tools', 'maxSteps', 'isDefault', 'actions'];

  constructor(
    private fb: FormBuilder,
    private openClawService: OpenClawService,
    private snackBar: MatSnackBar
  ) {
    this.agentForm = this.createForm();
  }

  ngOnInit(): void {
    this.openClawService.agents$
      .pipe(takeUntil(this.destroy$))
      .subscribe(agents => this.agents = agents);
    
    this.openClawService.getAgents().subscribe();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private createForm(): FormGroup {
    return this.fb.group({
      name: ['', Validators.required],
      description: [''],
      systemPrompt: ['', Validators.required],
      tools: [[]],
      maxSteps: [20, [Validators.min(1), Validators.max(50)]],
      isDefault: [false]
    });
  }

  selectAgent(agent: AgentDefinition): void {
    this.selectedAgent = agent;
    this.isEditing = true;
    this.isNewAgent = false;
    
    this.agentForm.patchValue({
      name: agent.name,
      description: agent.description || '',
      systemPrompt: agent.systemPrompt,
      tools: agent.tools || [],
      maxSteps: agent.maxSteps,
      isDefault: agent.isDefault
    });
  }

  newAgent(): void {
    this.selectedAgent = {
      name: '',
      systemPrompt: '',
      tools: [],
      maxSteps: 20,
      isDefault: false
    };
    this.isEditing = true;
    this.isNewAgent = true;
    this.agentForm.reset({
      name: '',
      description: '',
      systemPrompt: '',
      tools: [],
      maxSteps: 20,
      isDefault: false
    });
  }

  saveAgent(): void {
    if (this.agentForm.invalid) return;
    
    const formValue = this.agentForm.value;
    const agent: AgentDefinition = {
      ...formValue,
      name: formValue.name.trim()
    };
    
    if (this.isNewAgent) {
      this.openClawService.createAgent(agent).subscribe({
        next: () => {
          this.snackBar.open('Agent created', 'Close', { duration: 3000 });
          this.cancelEdit();
        },
        error: (err) => this.snackBar.open('Error: ' + err.message, 'Close', { duration: 5000 })
      });
    } else if (this.selectedAgent) {
      this.openClawService.updateAgent(this.selectedAgent.name, agent).subscribe({
        next: () => {
          this.snackBar.open('Agent updated', 'Close', { duration: 3000 });
          this.cancelEdit();
        },
        error: (err) => this.snackBar.open('Error: ' + err.message, 'Close', { duration: 5000 })
      });
    }
  }

  deleteAgent(agent: AgentDefinition): void {
    if (!confirm(`Delete agent "${agent.name}"?`)) return;
    
    this.openClawService.deleteAgent(agent.name).subscribe({
      next: () => {
        this.snackBar.open('Agent deleted', 'Close', { duration: 3000 });
        if (this.selectedAgent?.name === agent.name) {
          this.cancelEdit();
        }
      },
      error: (err) => this.snackBar.open('Error: ' + err.message, 'Close', { duration: 5000 })
    });
  }

  cancelEdit(): void {
    this.selectedAgent = null;
    this.isEditing = false;
    this.isNewAgent = false;
    this.agentForm.reset();
  }

  getToolLabel(tool: string): string {
    const labels: Record<string, string> = {
      'run_command': 'Shell Commands',
      'save_memory': 'Save Memory',
      'search_memory': 'Search Memory',
      'rag_query': 'RAG Query'
    };
    return labels[tool] || tool;
  }
}
