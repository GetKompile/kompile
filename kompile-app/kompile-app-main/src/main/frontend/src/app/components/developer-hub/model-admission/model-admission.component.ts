import { Component, OnInit, OnDestroy } from '@angular/core';
import {
  ModelAdmissionService,
  ModelAdmissionConfig,
  ModelAdmissionStatus,
  AdmittedModel
} from '../../../services/model-admission.service';

@Component({
  standalone: false,
  selector: 'app-model-admission',
  templateUrl: './model-admission.component.html',
  styleUrls: ['./model-admission.component.css']
})
export class ModelAdmissionComponent implements OnInit, OnDestroy {
  config: ModelAdmissionConfig | null = null;
  status: ModelAdmissionStatus | null = null;
  loading = true;
  saving = false;
  error: string | null = null;
  actionMessage: string | null = null;
  private refreshInterval: any;

  constructor(public admissionService: ModelAdmissionService) {}

  ngOnInit(): void {
    this.loadAll();
    this.refreshInterval = setInterval(() => this.loadStatus(), 5000);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) clearInterval(this.refreshInterval);
  }

  loadAll(): void {
    this.loading = true;
    this.error = null;
    this.admissionService.getConfig().subscribe({
      next: cfg => {
        this.config = cfg;
        this.loadStatus();
      },
      error: err => {
        this.error = 'Failed to load admission config';
        this.loading = false;
      }
    });
  }

  loadStatus(): void {
    this.admissionService.getStatus().subscribe({
      next: s => {
        this.status = s;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });
  }

  saveConfig(): void {
    if (!this.config) return;
    this.saving = true;
    this.admissionService.updateConfig(this.config).subscribe({
      next: cfg => {
        this.config = cfg;
        this.saving = false;
        this.showAction('Configuration saved');
      },
      error: () => {
        this.saving = false;
        this.error = 'Failed to save config';
      }
    });
  }

  resetConfig(): void {
    this.saving = true;
    this.admissionService.resetConfig().subscribe({
      next: cfg => {
        this.config = cfg;
        this.saving = false;
        this.showAction('Configuration reset to defaults');
      },
      error: () => {
        this.saving = false;
      }
    });
  }

  loadModel(modelId: string): void {
    this.admissionService.loadModel(modelId).subscribe({
      next: r => this.showAction(`Load initiated: ${modelId}`),
      error: e => this.showAction(`Load failed: ${e.error?.error || 'Unknown error'}`)
    });
  }

  unloadModel(modelId: string): void {
    this.admissionService.unloadModel(modelId).subscribe({
      next: () => {
        this.showAction(`Unloaded: ${modelId}`);
        this.loadStatus();
      }
    });
  }

  demoteModel(modelId: string): void {
    this.admissionService.demoteModel(modelId).subscribe({
      next: () => {
        this.showAction(`Demoted to CPU: ${modelId}`);
        this.loadStatus();
      }
    });
  }

  promoteModel(modelId: string): void {
    this.admissionService.promoteModel(modelId).subscribe({
      next: () => {
        this.showAction(`Promoted to GPU: ${modelId}`);
        this.loadStatus();
      }
    });
  }

  getGpuModels(): AdmittedModel[] {
    return this.status?.models?.filter(m => m.state === 'GPU_HOT') || [];
  }

  getCpuModels(): AdmittedModel[] {
    return this.status?.models?.filter(m => m.state === 'CPU_WARM') || [];
  }

  getLoadingModels(): AdmittedModel[] {
    return this.status?.models?.filter(m => m.state === 'LOADING') || [];
  }

  getGpuUtilization(): number {
    if (!this.status || !this.config) return 0;
    const reserveBytes = this.config.memory_reserve_bytes || 0;
    const usedBytes = this.status.totalGpuMemoryUsedBytes || 0;
    const total = usedBytes + reserveBytes;
    return total > 0 ? (usedBytes / total) * 100 : 0;
  }

  formatDate(iso: string): string {
    if (!iso) return '-';
    return new Date(iso).toLocaleString();
  }

  private showAction(msg: string): void {
    this.actionMessage = msg;
    setTimeout(() => this.actionMessage = null, 4000);
  }
}
