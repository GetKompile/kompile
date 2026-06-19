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
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgentTaskService, KclawTask } from '../../services/agent-task.service';

/**
 * Agent Tasks — submit a task (run an agent to do work) via the kclaw task runner and view the
 * saved output. Backed by {@link AgentTaskService} (/api/kclaw/tasks).
 */
@Component({
  selector: 'app-agent-tasks',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatTooltipModule
  ],
  templateUrl: './agent-tasks.component.html',
  styleUrls: ['./agent-tasks.component.css']
})
export class AgentTasksComponent implements OnInit, OnDestroy {

  tasks: KclawTask[] = [];
  selected: KclawTask | null = null;

  task = '';
  engine = 'react';
  agentId = '';
  model = '';
  channel = '';
  channelTarget = '';

  submitting = false;
  error = '';

  private poll: any = null;

  constructor(private taskService: AgentTaskService) {}

  ngOnInit(): void {
    this.refresh();
    this.poll = setInterval(() => {
      if (this.tasks.some(t => t.status === 'PENDING' || t.status === 'RUNNING')) {
        this.refresh();
      }
    }, 3000);
  }

  ngOnDestroy(): void {
    if (this.poll) {
      clearInterval(this.poll);
    }
  }

  refresh(): void {
    this.taskService.list().subscribe({
      next: t => {
        this.tasks = t;
        if (this.selected) {
          const updated = t.find(x => x.id === this.selected!.id);
          if (updated) {
            this.selected = updated;
          }
        }
      },
      error: e => this.error = 'Failed to load tasks: ' + (e?.message || e)
    });
  }

  submit(): void {
    if (!this.task.trim()) {
      return;
    }
    this.submitting = true;
    this.error = '';
    this.taskService.submit({
      engine: this.engine,
      task: this.task.trim(),
      agentId: this.agentId.trim() || undefined,
      model: this.model.trim() || undefined,
      channel: this.channel.trim() || undefined,
      channelTarget: this.channelTarget.trim() || undefined
    }).subscribe({
      next: t => {
        this.submitting = false;
        this.task = '';
        this.tasks.unshift(t);
        this.selected = t;
      },
      error: e => {
        this.submitting = false;
        this.error = 'Submit failed: ' + (e?.error?.error || e?.message || e);
      }
    });
  }

  select(t: KclawTask): void {
    this.taskService.get(t.id).subscribe({
      next: x => this.selected = x,
      error: () => this.selected = t
    });
  }

  remove(t: KclawTask, ev: Event): void {
    ev.stopPropagation();
    this.taskService.remove(t.id).subscribe({
      next: () => {
        this.tasks = this.tasks.filter(x => x.id !== t.id);
        if (this.selected?.id === t.id) {
          this.selected = null;
        }
      }
    });
  }

  statusClass(s: string): string {
    return 'status-' + (s || '').toLowerCase();
  }

  isRunning(s: string): boolean {
    return s === 'PENDING' || s === 'RUNNING';
  }
}
