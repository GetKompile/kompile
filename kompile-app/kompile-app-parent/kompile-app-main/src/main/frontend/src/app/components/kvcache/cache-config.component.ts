import { Component, OnInit, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheConfig, KVCacheProperties } from '../../models/kvcache-models';

@Component({
  standalone: true,
  selector: 'app-cache-config',
  imports: [
    CommonModule, FormsModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatSlideToggleModule, MatSnackBarModule, MatCardModule,
    MatDividerModule
  ],
  template: `
    <div class="cache-config">
      <!-- Global Settings -->
      <mat-card class="config-card">
        <mat-card-header>
          <mat-card-title>
            <mat-icon>settings</mat-icon> Global Settings
          </mat-card-title>
          <mat-card-subtitle>These settings are persisted and survive restarts</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <div class="settings-section">
            <h4>Cache Defaults</h4>
            <div class="form-grid">
              <mat-form-field>
                <mat-label>Default Cache Type</mat-label>
                <mat-select [(ngModel)]="props.defaultType">
                  <mat-option value="paged">Paged</mat-option>
                  <mat-option value="evictable">Evictable</mat-option>
                  <mat-option value="quantized">Quantized</mat-option>
                  <mat-option value="mla">MLA (Multi-Latent Attention)</mat-option>
                  <mat-option value="per-layer">Per-Layer</mat-option>
                  <mat-option value="turboquant">TurboQuant (Vector Quantized)</mat-option>
                </mat-select>
              </mat-form-field>

              <mat-form-field>
                <mat-label>Block Size</mat-label>
                <input matInput type="number" [(ngModel)]="props.blockSize">
              </mat-form-field>

              <mat-form-field>
                <mat-label>Max Batch Size</mat-label>
                <input matInput type="number" [(ngModel)]="props.maxBatchSize">
              </mat-form-field>

              <mat-form-field>
                <mat-label>Max Sequence Length</mat-label>
                <input matInput type="number" [(ngModel)]="props.maxSeqLen">
              </mat-form-field>

              <mat-form-field>
                <mat-label>Num KV Heads</mat-label>
                <input matInput type="number" [(ngModel)]="props.numKvHeads">
              </mat-form-field>

              <mat-form-field>
                <mat-label>Head Dimension</mat-label>
                <input matInput type="number" [(ngModel)]="props.headDim">
              </mat-form-field>

              <mat-form-field>
                <mat-label>Data Type</mat-label>
                <mat-select [(ngModel)]="props.dataType">
                  <mat-option value="FLOAT">FLOAT</mat-option>
                  <mat-option value="HALF">HALF (FP16)</mat-option>
                  <mat-option value="BFLOAT16">BFLOAT16</mat-option>
                </mat-select>
              </mat-form-field>

              <mat-form-field>
                <mat-label>Pool Size Factor</mat-label>
                <input matInput type="number" step="0.1" [(ngModel)]="props.poolSizeFactor">
              </mat-form-field>
            </div>
          </div>

          <mat-divider></mat-divider>

          <div class="settings-section">
            <h4>Eviction Settings</h4>
            <div class="form-grid">
              <mat-form-field>
                <mat-label>Eviction Policy</mat-label>
                <mat-select [(ngModel)]="props.evictionPolicy">
                  <mat-option value="h2o">H2O (Attention-based)</mat-option>
                  <mat-option value="streamingllm">StreamingLLM (Sink+Window)</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field>
                <mat-label>Token Budget</mat-label>
                <input matInput type="number" [(ngModel)]="props.tokenBudget">
              </mat-form-field>
            </div>
          </div>

          <mat-divider></mat-divider>

          <div class="settings-section">
            <h4>Quantization</h4>
            <div class="form-grid">
              <mat-form-field>
                <mat-label>Quantization Format</mat-label>
                <mat-select [(ngModel)]="props.quantFormat">
                  <mat-option value="INT8">INT8</mat-option>
                  <mat-option value="FP8_E4M3">FP8 (E4M3)</mat-option>
                  <mat-option value="FP8_E5M2">FP8 (E5M2)</mat-option>
                  <mat-option value="INT4">INT4</mat-option>
                </mat-select>
              </mat-form-field>
            </div>
          </div>

          <mat-divider></mat-divider>

          <div class="settings-section">
            <h4>TurboQuant Settings</h4>
            <p class="section-hint">Two-stage vector quantization with asymmetric attention (ICLR 2026). Used when cache type is "turboquant".</p>
            <div class="form-grid">
              <mat-form-field>
                <mat-label>Bits per Coordinate</mat-label>
                <mat-select [(ngModel)]="props.turboQuantBits">
                  <mat-option [value]="2">2-bit (~8x compression)</mat-option>
                  <mat-option [value]="3">3-bit (~5x compression)</mat-option>
                  <mat-option [value]="4">4-bit (~4x compression)</mat-option>
                </mat-select>
              </mat-form-field>
            </div>
          </div>

          <mat-divider></mat-divider>

          <div class="settings-section">
            <h4>Tiered Storage</h4>
            <div class="toggle-row">
              <mat-slide-toggle [(ngModel)]="props.tieredEnabled" color="primary">
                Enable Tiered Storage
              </mat-slide-toggle>
            </div>
            <div class="form-grid" *ngIf="props.tieredEnabled">
              <mat-form-field>
                <mat-label>GPU Pressure Threshold</mat-label>
                <input matInput type="number" step="0.01" [(ngModel)]="props.gpuPressureThreshold">
              </mat-form-field>
              <mat-form-field>
                <mat-label>Host Pool Max Blocks</mat-label>
                <input matInput type="number" [(ngModel)]="props.hostPoolMaxBlocks">
              </mat-form-field>
              <mat-form-field class="full-width">
                <mat-label>Disk Offload Path</mat-label>
                <input matInput [(ngModel)]="props.diskOffloadPath">
              </mat-form-field>
            </div>
          </div>

          <mat-divider></mat-divider>

          <div class="settings-section">
            <h4>Features</h4>
            <div class="feature-toggles">
              <div class="toggle-row">
                <mat-slide-toggle [(ngModel)]="props.checkpointEnabled" color="primary">
                  Enable Checkpoints
                </mat-slide-toggle>
              </div>
              <div class="form-grid" *ngIf="props.checkpointEnabled">
                <mat-form-field>
                  <mat-label>Max Checkpoints</mat-label>
                  <input matInput type="number" [(ngModel)]="props.maxCheckpoints">
                </mat-form-field>
                <mat-form-field>
                  <mat-label>Checkpoint Directory</mat-label>
                  <input matInput [(ngModel)]="props.checkpointDir">
                </mat-form-field>
              </div>

              <div class="toggle-row">
                <mat-slide-toggle [(ngModel)]="props.prefixCacheEnabled" color="primary">
                  Enable Prefix Cache
                </mat-slide-toggle>
              </div>
              <div class="form-grid" *ngIf="props.prefixCacheEnabled">
                <mat-form-field>
                  <mat-label>Max Prefix Entries</mat-label>
                  <input matInput type="number" [(ngModel)]="props.prefixCacheMaxEntries">
                </mat-form-field>
              </div>
            </div>
          </div>

          <mat-divider></mat-divider>

          <div class="settings-section">
            <h4>Statistics</h4>
            <div class="form-grid">
              <mat-form-field>
                <mat-label>Stats Window (seconds)</mat-label>
                <input matInput type="number" [(ngModel)]="props.statsWindowSeconds">
              </mat-form-field>
            </div>
          </div>
        </mat-card-content>
        <mat-card-actions>
          <button mat-raised-button color="primary" (click)="saveConfig()">
            <mat-icon>save</mat-icon> Save Settings
          </button>
          <button mat-button (click)="loadConfig()">
            <mat-icon>refresh</mat-icon> Reload
          </button>
        </mat-card-actions>
      </mat-card>

      <!-- Create Cache -->
      <mat-card class="config-card" *ngIf="props.enabled">
        <mat-card-header>
          <mat-card-title>
            <mat-icon>add_circle</mat-icon> Create New Cache
          </mat-card-title>
          <mat-card-subtitle>Create a cache instance using the settings above as defaults</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <div class="form-grid">
            <mat-form-field>
              <mat-label>Cache Name</mat-label>
              <input matInput [(ngModel)]="cacheName" placeholder="my-cache">
            </mat-form-field>
            <mat-form-field>
              <mat-label>Cache Type (override)</mat-label>
              <mat-select [(ngModel)]="cacheConfig.type">
                <mat-option [value]="null">Use Default ({{ props.defaultType }})</mat-option>
                <mat-option value="paged">Paged</mat-option>
                <mat-option value="evictable">Evictable</mat-option>
                <mat-option value="quantized">Quantized</mat-option>
                <mat-option value="mla">MLA</mat-option>
                <mat-option value="per-layer">Per-Layer</mat-option>
                <mat-option value="turboquant">TurboQuant (Vector Quantized)</mat-option>
              </mat-select>
            </mat-form-field>
          </div>
        </mat-card-content>
        <mat-card-actions>
          <button mat-raised-button color="accent" (click)="createCache()" [disabled]="!cacheName">
            <mat-icon>add</mat-icon> Create Cache
          </button>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [`
    .cache-config { max-width: 900px; }
    .config-card { margin-bottom: 16px; }
    .config-card mat-card-title { display: flex; align-items: center; gap: 8px; font-size: 16px; }
    .settings-section { padding: 12px 0; }
    .settings-section h4 { margin: 0 0 8px 0; color: #1565c0; font-size: 14px; font-weight: 500; }
    .section-hint { margin: 0 0 8px 0; font-size: 12px; color: #666; }
    .form-grid {
      display: grid; grid-template-columns: 1fr 1fr;
      gap: 8px 16px; padding: 4px 0;
    }
    .full-width { grid-column: 1 / -1; }
    .toggle-row { padding: 8px 0; }
    .feature-toggles { display: flex; flex-direction: column; gap: 4px; }
    mat-form-field { width: 100%; }
    mat-card-actions { display: flex; gap: 8px; padding: 8px 16px; }
    mat-divider { margin: 4px 0; }
  `]
})
export class CacheConfigComponent implements OnInit {
  @Output() configChanged = new EventEmitter<void>();

  props: KVCacheProperties = {
    enabled: false,
    defaultType: 'paged',
    blockSize: 64,
    maxBatchSize: 8,
    maxSeqLen: 4096,
    numKvHeads: 32,
    headDim: 128,
    dataType: 'FLOAT',
    poolSizeFactor: 1.2,
    evictionPolicy: 'h2o',
    tokenBudget: 2048,
    quantFormat: 'INT8',
    turboQuantBits: 3,
    tieredEnabled: false,
    gpuPressureThreshold: 0.10,
    hostPoolMaxBlocks: 1024,
    diskOffloadPath: '',
    prefixCacheEnabled: false,
    prefixCacheMaxEntries: 1024,
    checkpointEnabled: false,
    maxCheckpoints: 16,
    checkpointDir: '',
    statsWindowSeconds: 300
  };

  cacheName = '';
  cacheConfig: KVCacheConfig = {};

  constructor(private kvCacheService: KVCacheService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.loadConfig();
  }

  loadConfig(): void {
    this.kvCacheService.getConfig().subscribe({
      next: p => this.props = { ...p },
      error: () => {} // Use defaults
    });
  }

  saveConfig(): void {
    this.kvCacheService.updateConfig(this.props).subscribe({
      next: updated => {
        this.props = { ...updated };
        this.snackBar.open('Settings saved and persisted', 'Close', { duration: 2000 });
        this.configChanged.emit();
      },
      error: e => {
        this.snackBar.open('Failed to save: ' + (e.error?.error || e.message), 'Close', { duration: 3000 });
      }
    });
  }

  createCache(): void {
    if (!this.cacheName) return;
    this.kvCacheService.createCache(this.cacheName, this.cacheConfig).subscribe({
      next: summary => {
        this.snackBar.open(`Cache '${summary.name}' created (${summary.totalBlocks} blocks)`, 'Close', { duration: 3000 });
        this.cacheName = '';
        this.cacheConfig = {};
      },
      error: e => {
        const msg = e.error?.error || e.message;
        this.snackBar.open('Failed: ' + msg, 'Close', { duration: 3000 });
      }
    });
  }
}
