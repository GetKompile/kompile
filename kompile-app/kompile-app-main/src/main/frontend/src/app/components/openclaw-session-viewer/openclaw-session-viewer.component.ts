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
  selector: 'app-openclaw-session-viewer',
  standalone: false,
  templateUrl: './openclaw-session-viewer.component.html',
  styleUrls: ['./openclaw-session-viewer.component.css']
})
export class OpenClawSessionViewerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  sessions: string[] = [];
  selectedSession: string | null = null;
  sessionMessages: any[] = [];
  sessionTokenCount: number = 0;
  isLoading: boolean = false;
  
  searchTerm: string = '';
  filteredSessions: string[] = [];

  constructor(
    private openClawService: OpenClawService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadSessions();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadSessions(): void {
    this.isLoading = true;
    this.openClawService.getSessions().subscribe({
      next: (sessions: string[]) => {
        this.sessions = sessions;
        this.filteredSessions = sessions;
        this.isLoading = false;
      },
      error: (error: Error) => {
        this.snackBar.open('Failed to load sessions: ' + error.message, 'Close', { duration: 5000 });
        this.isLoading = false;
      }
    });
  }

  selectSession(sessionKey: string): void {
    this.selectedSession = sessionKey;
    this.isLoading = true;
    
    this.openClawService.getSessionHistory(sessionKey).subscribe({
      next: (data) => {
        this.sessionMessages = data.messages || [];
        this.sessionTokenCount = data.tokenCount || 0;
        this.isLoading = false;
      },
      error: (error) => {
        this.snackBar.open('Failed to load session history: ' + error.message, 'Close', { duration: 5000 });
        this.isLoading = false;
      }
    });
  }

  clearSession(sessionKey: string): void {
    this.openClawService.clearSession(sessionKey).subscribe({
      next: () => {
        this.snackBar.open('Session cleared', 'Close', { duration: 3000 });
        if (this.selectedSession === sessionKey) {
          this.selectedSession = null;
          this.sessionMessages = [];
          this.sessionTokenCount = 0;
        }
        this.loadSessions();
      },
      error: (error) => {
        this.snackBar.open('Failed to clear session: ' + error.message, 'Close', { duration: 5000 });
      }
    });
  }

  searchSessions(): void {
    if (!this.searchTerm) {
      this.filteredSessions = this.sessions;
    } else {
      this.filteredSessions = this.sessions.filter(s => 
        s.toLowerCase().includes(this.searchTerm.toLowerCase())
      );
    }
  }

  formatDate(timestamp: string): string {
    return new Date(timestamp).toLocaleString();
  }

  getMessageIcon(role: string): string {
    switch (role) {
      case 'USER': return 'person';
      case 'ASSISTANT': return 'smart_toy';
      case 'SYSTEM': return 'settings';
      case 'TOOL': return 'build';
      default: return 'message';
    }
  }
}