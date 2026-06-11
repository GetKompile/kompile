import { Component, OnInit, OnDestroy } from '@angular/core';
import { MemoryPoolService, MemoryWatchdogStatus } from '../../../services/memory-pool.service';

@Component({
  standalone: false,
  selector: 'app-memory-watchdog',
  templateUrl: './memory-watchdog.component.html',
  styleUrls: ['./memory-watchdog.component.css']
})
export class MemoryWatchdogComponent implements OnInit, OnDestroy {
  watchdogStatus: MemoryWatchdogStatus | null = null;
  poolConfig: any = null;
  poolStatus: any = null;
  loading = true;
  error: string | null = null;
  message: string | null = null;
  private refreshInterval: any;

  constructor(private memoryService: MemoryPoolService) {}

  ngOnInit(): void {
    this.loadAll();
    this.refreshInterval = setInterval(() => this.refreshStatus(), 5000);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) clearInterval(this.refreshInterval);
  }

  loadAll(): void {
    this.loading = true;
    this.refreshStatus();
    this.memoryService.getPoolConfig().subscribe({
      next: cfg => this.poolConfig = cfg,
      error: () => {}
    });
    this.memoryService.getPoolStatus().subscribe({
      next: s => this.poolStatus = s,
      error: () => {}
    });
  }

  refreshStatus(): void {
    this.memoryService.getWatchdogStatus().subscribe({
      next: s => {
        this.watchdogStatus = s;
        this.loading = false;
      },
      error: () => {
        this.error = 'Memory watchdog not available';
        this.loading = false;
      }
    });
  }

  toggleWatchdog(): void {
    if (!this.watchdogStatus) return;
    const newEnabled = !this.watchdogStatus.enabled;
    this.memoryService.setWatchdogEnabled(newEnabled).subscribe({
      next: r => {
        if (this.watchdogStatus) this.watchdogStatus.enabled = r.enabled;
        this.showMsg(r.message);
      }
    });
  }

  updateInterval(ms: number): void {
    this.memoryService.setWatchdogCheckInterval(ms).subscribe({
      next: r => this.showMsg(r.message)
    });
  }

  resetPoolConfig(): void {
    this.memoryService.resetPoolConfig().subscribe({
      next: r => {
        this.poolConfig = r.config;
        this.showMsg('Pool config reset');
      }
    });
  }

  getPoolKeys(): string[] {
    return this.poolStatus ? Object.keys(this.poolStatus) : [];
  }

  getPoolConfigKeys(): string[] {
    return this.poolConfig ? Object.keys(this.poolConfig) : [];
  }

  formatValue(val: any): string {
    if (val === null || val === undefined) return '-';
    if (typeof val === 'object') return JSON.stringify(val);
    return String(val);
  }

  getPressureClass(): string {
    if (!this.watchdogStatus) return '';
    if (this.watchdogStatus.memoryPressureDetected) return 'pressure-critical';
    if (this.watchdogStatus.currentMemoryUsagePercent > this.watchdogStatus.memoryThresholdPercent * 0.8) return 'pressure-warning';
    return 'pressure-ok';
  }

  private showMsg(msg: string): void {
    this.message = msg;
    setTimeout(() => this.message = null, 4000);
  }
}
