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
import { Subject, takeUntil } from 'rxjs';
import { OpenClawService } from '../../services/openclaw.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';

@Component({
  selector: 'app-openclaw-permission-manager',
  standalone: false,
  templateUrl: './openclaw-permission-manager.component.html',
  styleUrls: ['./openclaw-permission-manager.component.css']
})
export class OpenClawPermissionManagerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  allowedCommands: string[] = [];
  deniedCommands: string[] = [];
  pendingCommands: string[] = [];
  
  newCommand: string = '';
  activeTab: 'allowed' | 'denied' | 'pending' = 'pending';
  activeTabIndex: number = 0;

  constructor(
    private openClawService: OpenClawService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadPermissions();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadPermissions(): void {
    this.openClawService.getPermissions().subscribe({
      next: (permissions) => {
        this.allowedCommands = permissions.allowed || [];
        this.deniedCommands = permissions.denied || [];
        this.pendingCommands = permissions.pending || [];
      },
      error: (error: Error) => this.snackBar.open('Failed to load permissions: ' + error.message, 'Close', { duration: 5000 })
    });
  }

  allowCommand(command: string): void {
    this.openClawService.allowCommand(command).subscribe({
      next: () => {
        this.snackBar.open(`Allowed: ${command}`, 'Close', { duration: 3000 });
        this.loadPermissions();
      },
      error: (error) => this.snackBar.open('Failed to allow command: ' + error.message, 'Close', { duration: 5000 })
    });
  }

  denyCommand(command: string): void {
    this.openClawService.denyCommand(command).subscribe({
      next: () => {
        this.snackBar.open(`Denied: ${command}`, 'Close', { duration: 3000 });
        this.loadPermissions();
      },
      error: (error) => this.snackBar.open('Failed to deny command: ' + error.message, 'Close', { duration: 5000 })
    });
  }

  allowCustomCommand(): void {
    if (this.newCommand.trim()) {
      this.allowCommand(this.newCommand.trim());
      this.newCommand = '';
    }
  }

  denyCustomCommand(): void {
    if (this.newCommand.trim()) {
      this.denyCommand(this.newCommand.trim());
      this.newCommand = '';
    }
  }

  getRiskLevel(command: string): 'low' | 'medium' | 'high' {
    const highRiskPatterns = ['rm -rf', 'sudo', 'chmod 777', 'mkfs', 'dd if=', '>/dev/'];
    const mediumRiskPatterns = ['apt', 'yum', 'dnf', 'pip install', 'npm install -g', 'kill', 'pkill'];
    
    for (const pattern of highRiskPatterns) {
      if (command.includes(pattern)) return 'high';
    }
    for (const pattern of mediumRiskPatterns) {
      if (command.includes(pattern)) return 'medium';
    }
    return 'low';
  }

  getRiskIcon(level: 'low' | 'medium' | 'high'): string {
    switch (level) {
      case 'high': return 'error';
      case 'medium': return 'warning';
      case 'low': return 'check_circle';
    }
  }

  getRiskColor(level: 'low' | 'medium' | 'high'): string {
    switch (level) {
      case 'high': return 'warn';
      case 'medium': return 'accent';
      case 'low': return 'primary';
    }
  }
}