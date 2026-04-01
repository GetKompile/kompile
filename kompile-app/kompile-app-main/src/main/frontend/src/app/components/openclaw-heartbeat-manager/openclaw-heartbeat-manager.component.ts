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
import { HeartbeatInfo, AgentDefinition } from '../../models/openclaw-models';
import { MatSnackBar } from '@angular/material/snack-bar';

@Component({
  selector: 'app-openclaw-heartbeat-manager',
  standalone: false,
  templateUrl: './openclaw-heartbeat-manager.component.html',
  styleUrls: ['./openclaw-heartbeat-manager.component.css']
})
export class OpenClawHeartbeatManagerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  heartbeats: HeartbeatInfo[] = [];
  agents: AgentDefinition[] = [];
  isCreating = false;
  
  heartbeatForm: FormGroup;
  displayedColumns = ['id', 'cron', 'agentId', 'sessionKey', 'nextFireTime', 'actions'];

  commonCronPresets = [
    { label: 'Every minute', value: '0 * * * * ?' },
    { label: 'Every hour', value: '0 0 * * * ?' },
    { label: 'Every day at 8am', value: '0 0 8 * * ?' },
    { label: 'Every Monday 9am', value: '0 0 9 ? * MON' },
    { label: 'First of month', value: '0 0 8 1 * ?' }
  ];

  constructor(
    private fb: FormBuilder,
    private openClawService: OpenClawService,
    private snackBar: MatSnackBar
  ) {
    this.heartbeatForm = this.fb.group({
      id: ['', Validators.required],
      cron: ['0 0 8 * * ?', Validators.required],
      agentId: ['jarvis', Validators.required],
      sessionKey: ['cron:morning'],
      message: ['Good morning! Give me a briefing for today.', Validators.required]
    });
  }

  ngOnInit(): void {
    this.loadHeartbeats();
    this.loadAgents();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadHeartbeats(): void {
    this.openClawService.getHeartbeats().subscribe({
      next: (heartbeats: HeartbeatInfo[]) => this.heartbeats = heartbeats,
      error: (error: Error) => this.snackBar.open('Failed to load heartbeats: ' + error.message, 'Close', { duration: 5000 })
    });
  }

  loadAgents(): void {
    this.openClawService.agents$
      .pipe(takeUntil(this.destroy$))
      .subscribe(agents => this.agents = agents);
  }

  createHeartbeat(): void {
    if (this.heartbeatForm.invalid) return;
    
    this.openClawService.createHeartbeat(this.heartbeatForm.value).subscribe({
      next: () => {
        this.snackBar.open('Heartbeat created', 'Close', { duration: 3000 });
        this.isCreating = false;
        this.loadHeartbeats();
      },
      error: (error) => this.snackBar.open('Failed to create heartbeat: ' + error.message, 'Close', { duration: 5000 })
    });
  }

  cancelHeartbeat(id: string): void {
    this.openClawService.cancelHeartbeat(id).subscribe({
      next: () => {
        this.snackBar.open('Heartbeat cancelled', 'Close', { duration: 3000 });
        this.loadHeartbeats();
      },
      error: (error) => this.snackBar.open('Failed to cancel heartbeat: ' + error.message, 'Close', { duration: 5000 })
    });
  }

  usePreset(preset: { label: string; value: string }): void {
    this.heartbeatForm.patchValue({ cron: preset.value });
  }

  generateId(): void {
    const id = 'heartbeat-' + Date.now().toString(36);
    this.heartbeatForm.patchValue({ id });
  }
}