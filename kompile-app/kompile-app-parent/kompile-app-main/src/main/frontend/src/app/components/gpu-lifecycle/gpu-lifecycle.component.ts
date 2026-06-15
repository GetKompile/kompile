import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTableModule } from '@angular/material/table';
import { Subscription, interval } from 'rxjs';
import { GpuLifecycleService, BudgetInfo } from '../../services/gpu-lifecycle.service';

interface DeviceDisplay {
  name: string;
  index: number;
  totalMb: number;
  usedMb: number;
  freeMb: number;
  utilization: number;
}

interface JobDisplay {
  jobId: string;
  serviceType: string;
  device: string;
  heldForMs: number;
  description: string;
}

interface BudgetEntry {
  serviceType: string;
  budgetMb: number;
  priority: number;
  hasReservation: boolean;
  editing: boolean;
  editBudgetMb: number;
  editPriority: number;
}

@Component({
  selector: 'app-gpu-lifecycle',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatTableModule
  ],
  templateUrl: './gpu-lifecycle.component.html',
  styleUrls: ['./gpu-lifecycle.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GpuLifecycleComponent implements OnInit, OnDestroy {
  loading = true;
  devices: DeviceDisplay[] = [];
  jobs: JobDisplay[] = [];
  budgets: BudgetEntry[] = [];
  jobColumns = ['jobId', 'serviceType', 'device', 'heldFor', 'description', 'actions'];

  private refreshSub?: Subscription;

  constructor(
    private gpuService: GpuLifecycleService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadAll();
    this.refreshSub = interval(5000).subscribe(() => this.loadAll());
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  private loadAll(): void {
    this.gpuService.getDevices().subscribe({
      next: (data) => {
        if (data?.devices && Array.isArray(data.devices)) {
          this.devices = data.devices.map((d: any) => ({
            name: d.name || 'Unknown',
            index: d.cudaRuntimeIndex ?? d.index ?? 0,
            totalMb: Math.round((d.totalMemory || 0) / (1024 * 1024)),
            usedMb: Math.round(((d.totalMemory || 0) - (d.freeMemory || 0)) / (1024 * 1024)),
            freeMb: Math.round((d.freeMemory || 0) / (1024 * 1024)),
            utilization: d.totalMemory > 0
              ? Math.round(((d.totalMemory - (d.freeMemory || 0)) / d.totalMemory) * 100)
              : 0
          }));
        }
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.devices = [];
        this.loading = false;
        this.cdr.markForCheck();
      }
    });

    this.gpuService.getJobs().subscribe({
      next: (data) => {
        if (data?.jobs && Array.isArray(data.jobs)) {
          this.jobs = data.jobs.map((j: any) => ({
            jobId: j.jobId,
            serviceType: j.serviceType,
            device: j.device,
            heldForMs: j.heldForMs || 0,
            description: j.description || ''
          }));
        }
        this.cdr.markForCheck();
      },
      error: () => {
        this.jobs = [];
        this.cdr.markForCheck();
      }
    });

    this.gpuService.getBudgets().subscribe({
      next: (data) => {
        this.budgets = Object.entries(data).map(([key, val]) => ({
          serviceType: key,
          budgetMb: val.budgetMb,
          priority: val.priority,
          hasReservation: val.hasReservation,
          editing: false,
          editBudgetMb: val.budgetMb,
          editPriority: val.priority
        }));
        this.cdr.markForCheck();
      },
      error: () => {
        this.budgets = [];
        this.cdr.markForCheck();
      }
    });
  }

  forceRelease(jobId: string): void {
    this.gpuService.forceReleaseJob(jobId).subscribe({
      next: () => {
        this.snackBar.open(`GPU hold released for job: ${jobId}`, 'OK', { duration: 3000 });
        this.loadAll();
      },
      error: (err) => {
        this.snackBar.open('Failed to release: ' + (err.error?.message || err.message), 'OK', { duration: 5000 });
      }
    });
  }

  saveBudget(entry: BudgetEntry): void {
    this.gpuService.updateBudget(entry.serviceType, entry.editBudgetMb).subscribe({
      next: () => {
        this.gpuService.updatePriority(entry.serviceType, entry.editPriority).subscribe({
          next: () => {
            entry.budgetMb = entry.editBudgetMb;
            entry.priority = entry.editPriority;
            entry.editing = false;
            this.snackBar.open(`Budget updated for ${entry.serviceType}`, 'OK', { duration: 3000 });
            this.cdr.markForCheck();
          },
          error: (err) => {
            this.snackBar.open('Failed to update priority: ' + err.message, 'OK', { duration: 5000 });
          }
        });
      },
      error: (err) => {
        this.snackBar.open('Failed to update budget: ' + err.message, 'OK', { duration: 5000 });
      }
    });
  }

  formatDuration(ms: number): string {
    if (ms < 1000) return ms + 'ms';
    if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
    return (ms / 60000).toFixed(1) + 'min';
  }

  getUtilizationColor(pct: number): string {
    if (pct >= 90) return 'warn';
    if (pct >= 70) return 'accent';
    return 'primary';
  }
}
