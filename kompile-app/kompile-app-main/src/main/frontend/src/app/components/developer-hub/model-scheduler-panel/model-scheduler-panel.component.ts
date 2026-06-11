import { Component, OnInit, OnDestroy } from '@angular/core';
import { ModelSchedulerService, ModelSchedulerConfig, ModelSchedulerStatus } from '../../../services/model-scheduler.service';

@Component({
  standalone: false,
  selector: 'app-model-scheduler-panel',
  templateUrl: './model-scheduler-panel.component.html',
  styleUrls: ['./model-scheduler-panel.component.css']
})
export class ModelSchedulerPanelComponent implements OnInit, OnDestroy {
  config: ModelSchedulerConfig | null = null;
  status: ModelSchedulerStatus | null = null;
  loading = true;
  saving = false;
  error: string | null = null;
  message: string | null = null;
  private refreshInterval: any;

  constructor(private schedulerService: ModelSchedulerService) {}

  ngOnInit(): void {
    this.loadAll();
    this.refreshInterval = setInterval(() => this.loadStatus(), 5000);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) clearInterval(this.refreshInterval);
  }

  loadAll(): void {
    this.loading = true;
    this.schedulerService.getConfig().subscribe({
      next: cfg => {
        this.config = cfg;
        this.loadStatus();
      },
      error: () => {
        this.error = 'Model scheduler not available';
        this.loading = false;
      }
    });
  }

  loadStatus(): void {
    this.schedulerService.getStatus().subscribe({
      next: s => {
        this.status = s;
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  saveConfig(): void {
    if (!this.config) return;
    this.saving = true;
    this.schedulerService.updateConfig(this.config).subscribe({
      next: cfg => {
        this.config = cfg;
        this.saving = false;
        this.showMsg('Config saved');
      },
      error: () => {
        this.saving = false;
        this.error = 'Failed to save';
      }
    });
  }

  resetConfig(): void {
    this.saving = true;
    this.schedulerService.resetConfig().subscribe({
      next: cfg => {
        this.config = cfg;
        this.saving = false;
        this.showMsg('Reset to defaults');
      },
      error: () => this.saving = false
    });
  }

  getConfigKeys(): string[] {
    return this.config ? Object.keys(this.config) : [];
  }

  getStatusKeys(obj: any): string[] {
    return obj ? Object.keys(obj) : [];
  }

  formatValue(val: any): string {
    if (val === null || val === undefined) return '-';
    if (typeof val === 'object') return JSON.stringify(val);
    return String(val);
  }

  private showMsg(msg: string): void {
    this.message = msg;
    setTimeout(() => this.message = null, 4000);
  }
}
