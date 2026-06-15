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

import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpClientModule } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDialogModule } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { Subject, interval } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { backendUrl } from '../../services/base.service';

export interface KompileComponent {
  id: string;
  status: 'Running' | 'Starting' | 'Stopped' | 'Unknown';
  port?: number;
  pid?: number;
  description?: string;
  uptime?: string;
}

@Component({
  selector: 'app-component-manager',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    HttpClientModule,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatTableModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatFormFieldModule,
    MatInputModule,
    MatDialogModule,
    MatDividerModule
  ],
  templateUrl: './component-manager.component.html',
  styleUrls: ['./component-manager.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ComponentManagerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  components: KompileComponent[] = [];
  loading = false;
  actionInProgress: { [id: string]: boolean } = {};

  readonly tableColumns = ['id', 'status', 'port', 'pid', 'actions'];

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadComponents();
    interval(15000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.loadComponents());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadComponents(): void {
    this.loading = true;
    this.cdr.markForCheck();

    this.http.get<KompileComponent[]>(`${backendUrl}/system/components`).subscribe({
      next: (components) => {
        this.components = components;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.components = [];
        this.loading = false;
        this.cdr.markForCheck();
        this.snackBar.open(
          'Failed to load components: ' + (err.error?.message || err.message || 'Unknown error'),
          'Dismiss',
          { duration: 5000 }
        );
      }
    });
  }

  start(component: KompileComponent): void {
    this.setActionInProgress(component.id, true);
    this.http.post<KompileComponent>(`${backendUrl}/system/components/${component.id}/start`, {}).subscribe({
      next: () => {
        this.setActionInProgress(component.id, false);
        this.snackBar.open(`Component "${component.id}" started`, 'OK', { duration: 3000 });
        this.loadComponents();
      },
      error: (err) => {
        this.setActionInProgress(component.id, false);
        this.snackBar.open(
          `Failed to start "${component.id}": ` + (err.error?.message || err.message || 'Unknown error'),
          'Dismiss',
          { duration: 5000 }
        );
      }
    });
  }

  stop(component: KompileComponent): void {
    this.setActionInProgress(component.id, true);
    this.http.post<KompileComponent>(`${backendUrl}/system/components/${component.id}/stop`, {}).subscribe({
      next: () => {
        this.setActionInProgress(component.id, false);
        this.snackBar.open(`Component "${component.id}" stopped`, 'OK', { duration: 3000 });
        this.loadComponents();
      },
      error: (err) => {
        this.setActionInProgress(component.id, false);
        this.snackBar.open(
          `Failed to stop "${component.id}": ` + (err.error?.message || err.message || 'Unknown error'),
          'Dismiss',
          { duration: 5000 }
        );
      }
    });
  }

  restart(component: KompileComponent): void {
    this.setActionInProgress(component.id, true);
    this.http.post<KompileComponent>(`${backendUrl}/system/components/${component.id}/restart`, {}).subscribe({
      next: () => {
        this.setActionInProgress(component.id, false);
        this.snackBar.open(`Component "${component.id}" restarted`, 'OK', { duration: 3000 });
        this.loadComponents();
      },
      error: (err) => {
        this.setActionInProgress(component.id, false);
        this.snackBar.open(
          `Failed to restart "${component.id}": ` + (err.error?.message || err.message || 'Unknown error'),
          'Dismiss',
          { duration: 5000 }
        );
      }
    });
  }

  isActionInProgress(id: string): boolean {
    return !!this.actionInProgress[id];
  }

  isRunning(component: KompileComponent): boolean {
    return component.status === 'Running';
  }

  isStopped(component: KompileComponent): boolean {
    return component.status === 'Stopped' || component.status === 'Unknown';
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'Running':  return 'status-running';
      case 'Starting': return 'status-starting';
      case 'Stopped':  return 'status-stopped';
      default:         return 'status-unknown';
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'Running':  return 'check_circle';
      case 'Starting': return 'hourglass_empty';
      case 'Stopped':  return 'cancel';
      default:         return 'help_outline';
    }
  }

  private setActionInProgress(id: string, inProgress: boolean): void {
    this.actionInProgress = { ...this.actionInProgress, [id]: inProgress };
    this.cdr.markForCheck();
  }
}
