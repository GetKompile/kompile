import { Component, OnInit, OnDestroy } from '@angular/core';
import { ModelWarmupService } from '../../../services/model-warmup.service';
import { WeightCacheService } from '../../../services/weight-cache.service';

@Component({
  selector: 'app-model-warmup-cache',
  standalone: false,
  templateUrl: './model-warmup-cache.component.html',
  styleUrls: ['./model-warmup-cache.component.css']
})
export class ModelWarmupCacheComponent implements OnInit, OnDestroy {
  warmupConfig: Record<string, any> | null = null;
  warmupStatus: Record<string, any> | null = null;
  cacheConfig: Record<string, any> | null = null;
  cacheStatus: Record<string, any> | null = null;
  loading = false;
  error: string | null = null;
  message: string | null = null;
  private pollTimer: any;

  constructor(
    private warmupSvc: ModelWarmupService,
    private cacheSvc: WeightCacheService
  ) {}

  ngOnInit(): void {
    this.loadAll();
    this.pollTimer = setInterval(() => this.loadStatus(), 10000);
  }

  ngOnDestroy(): void {
    if (this.pollTimer) clearInterval(this.pollTimer);
  }

  loadAll(): void {
    this.loading = true;
    this.error = null;
    Promise.all([
      this.warmupSvc.getConfig().toPromise(),
      this.warmupSvc.getStatus().toPromise(),
      this.cacheSvc.getConfig().toPromise(),
      this.cacheSvc.getStatus().toPromise()
    ]).then(([wCfg, wStatus, cCfg, cStatus]) => {
      this.warmupConfig = wCfg || null;
      this.warmupStatus = wStatus || null;
      this.cacheConfig = cCfg || null;
      this.cacheStatus = cStatus || null;
      this.loading = false;
    }).catch(err => {
      this.error = err.error?.message || err.message || 'Failed to load';
      this.loading = false;
    });
  }

  loadStatus(): void {
    this.warmupSvc.getStatus().subscribe(s => this.warmupStatus = s);
    this.cacheSvc.getStatus().subscribe(s => this.cacheStatus = s);
  }

  triggerWarmup(serviceType?: string): void {
    const obs = serviceType ? this.warmupSvc.triggerService(serviceType) : this.warmupSvc.triggerAll();
    obs.subscribe({
      next: () => { this.message = `Warmup triggered${serviceType ? ' for ' + serviceType : ''}`; this.loadStatus(); },
      error: err => this.error = err.message
    });
  }

  resetWarmupConfig(): void {
    this.warmupSvc.resetConfig().subscribe({
      next: () => { this.message = 'Warmup config reset'; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  promoteModel(modelId: string): void {
    this.cacheSvc.promote(modelId).subscribe({
      next: () => { this.message = `${modelId} promoted`; this.loadStatus(); },
      error: err => this.error = err.message
    });
  }

  demoteModel(modelId: string): void {
    this.cacheSvc.demote(modelId).subscribe({
      next: () => { this.message = `${modelId} demoted`; this.loadStatus(); },
      error: err => this.error = err.message
    });
  }

  getKeys(obj: Record<string, any> | null): string[] {
    return obj ? Object.keys(obj) : [];
  }

  formatValue(v: any): string {
    if (v == null) return '-';
    if (typeof v === 'object') return JSON.stringify(v);
    return String(v);
  }

  getModelIds(): string[] {
    if (!this.cacheStatus) return [];
    const models = this.cacheStatus['models'] || this.cacheStatus['cachedModels'];
    if (Array.isArray(models)) return models.map((m: any) => typeof m === 'string' ? m : m.modelId || m.id);
    return [];
  }
}
