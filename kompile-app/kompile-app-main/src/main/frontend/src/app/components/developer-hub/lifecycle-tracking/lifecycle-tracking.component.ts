import { Component, OnInit } from '@angular/core';
import { LifecycleTrackingService } from '../../../services/lifecycle-tracking.service';

@Component({
  selector: 'app-lifecycle-tracking',
  standalone: false,
  templateUrl: './lifecycle-tracking.component.html',
  styleUrls: ['./lifecycle-tracking.component.css']
})
export class LifecycleTrackingComponent implements OnInit {
  config: Record<string, any> | null = null;
  cacheStats: Record<string, any> | null = null;
  cleanupConfig: Record<string, any> | null = null;
  periodicConfig: Record<string, any> | null = null;
  loading = false;
  error: string | null = null;
  message: string | null = null;

  constructor(private svc: LifecycleTrackingService) {}

  ngOnInit(): void { this.loadAll(); }

  loadAll(): void {
    this.loading = true;
    this.error = null;
    Promise.all([
      this.svc.getConfig().toPromise(),
      this.svc.getAllCacheStats().toPromise(),
      this.svc.getCleanupConfig().toPromise(),
      this.svc.getPeriodicReportingConfig().toPromise()
    ]).then(([cfg, cache, cleanup, periodic]) => {
      this.config = cfg || null;
      this.cacheStats = cache || null;
      this.cleanupConfig = cleanup || null;
      this.periodicConfig = periodic || null;
      this.loading = false;
    }).catch(err => {
      this.error = err.error?.message || err.message || 'Failed to load';
      this.loading = false;
    });
  }

  toggleTracking(enabled: boolean): void {
    this.svc.setTracking(enabled).subscribe({
      next: () => { this.message = `Tracking ${enabled ? 'enabled' : 'disabled'}`; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  applyPreset(preset: string): void {
    this.svc.applyPreset(preset).subscribe({
      next: () => { this.message = `Preset '${preset}' applied`; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  printReport(): void {
    this.svc.printReport().subscribe({
      next: () => this.message = 'Report printed to server logs',
      error: err => this.error = err.message
    });
  }

  togglePeriodicReporting(enable: boolean): void {
    const obs = enable ? this.svc.enablePeriodicReporting() : this.svc.disablePeriodicReporting();
    obs.subscribe({
      next: () => { this.message = `Periodic reporting ${enable ? 'enabled' : 'disabled'}`; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  clearCache(type: string): void {
    let obs;
    switch (type) {
      case 'shape': obs = this.svc.clearShapeCache(); break;
      case 'tad': obs = this.svc.clearTadCache(); break;
      default: obs = this.svc.clearAllCaches();
    }
    obs.subscribe({
      next: () => { this.message = `${type || 'All'} cache(s) cleared`; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  triggerCleanup(): void {
    this.svc.triggerCleanup().subscribe({
      next: () => { this.message = 'Cleanup triggered'; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  toggleCleanup(enabled: boolean): void {
    this.svc.setCleanupEnabled(enabled).subscribe({
      next: () => { this.message = `Cleanup ${enabled ? 'enabled' : 'disabled'}`; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  getConfigKeys(): string[] {
    return this.config ? Object.keys(this.config) : [];
  }

  getCacheKeys(): string[] {
    return this.cacheStats ? Object.keys(this.cacheStats) : [];
  }

  getCleanupKeys(): string[] {
    return this.cleanupConfig ? Object.keys(this.cleanupConfig) : [];
  }

  formatValue(v: any): string {
    if (v == null) return '-';
    if (typeof v === 'object') return JSON.stringify(v);
    return String(v);
  }
}
