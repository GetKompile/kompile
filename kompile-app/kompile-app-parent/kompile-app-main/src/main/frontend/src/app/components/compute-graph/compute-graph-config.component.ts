import { Component, OnInit, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ComputeGraphService } from '../../services/compute-graph.service';
import { ComputeGraphConfig } from '../../models/compute-graph-models';

@Component({
  standalone: true,
  selector: 'app-compute-graph-config',
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule,
    MatSlideToggleModule, MatButtonModule, MatIconModule, MatDividerModule, MatSnackBarModule
  ],
  template: `
    <div class="config-container" *ngIf="config">
      <!-- Engine Settings -->
      <mat-card class="config-card">
        <mat-card-header>
          <mat-card-title>Engine Settings</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="toggle-row">
            <mat-slide-toggle [(ngModel)]="config.enabled" color="primary">
              Enable Compute Graph Engine
            </mat-slide-toggle>
          </div>
          <mat-divider></mat-divider>
          <div class="toggle-group">
            <div class="toggle-row">
              <mat-slide-toggle [(ngModel)]="config.scriptingEnabled" color="primary"
                                [disabled]="!config.enabled">
                Scripting Backend (JavaScript / Python)
              </mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <mat-slide-toggle [(ngModel)]="config.droolsEnabled" color="primary"
                                [disabled]="!config.enabled">
                Drools Rules Backend
              </mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <mat-slide-toggle [(ngModel)]="config.droolsInferenceEnabled" color="primary"
                                [disabled]="!config.enabled || !config.droolsEnabled">
                Drools Inference Engine (cross-node rule chaining)
              </mat-slide-toggle>
            </div>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Resource Limits -->
      <mat-card class="config-card">
        <mat-card-header>
          <mat-card-title>Default Resource Limits</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Max CPU Time (ms)</mat-label>
              <input matInput type="number" [(ngModel)]="config.defaultMaxCpuTimeMs" min="0">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Heap Memory (bytes)</mat-label>
              <input matInput type="number" [(ngModel)]="config.defaultMaxHeapMemoryBytes" min="0">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Stack Frames</mat-label>
              <input matInput type="number" [(ngModel)]="config.defaultMaxStackFrames" min="0">
            </mat-form-field>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Security Settings -->
      <mat-card class="config-card">
        <mat-card-header>
          <mat-card-title>Security Settings</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="toggle-group">
            <div class="toggle-row">
              <mat-slide-toggle [(ngModel)]="config.defaultAllowIO" color="warn">
                Allow File I/O
              </mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <mat-slide-toggle [(ngModel)]="config.defaultAllowNetwork" color="warn">
                Allow Network Access
              </mat-slide-toggle>
            </div>
            <div class="toggle-row">
              <mat-slide-toggle [(ngModel)]="config.defaultAllowHostAccess" color="warn">
                Allow Host Class Access
              </mat-slide-toggle>
            </div>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Drools Limits -->
      <mat-card class="config-card" *ngIf="config.droolsEnabled">
        <mat-card-header>
          <mat-card-title>Drools Rule Limits</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Max Rule Firings Per Node</mat-label>
              <input matInput type="number" [(ngModel)]="config.maxRuleFiringsPerNode" min="1">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Rule Firings Total</mat-label>
              <input matInput type="number" [(ngModel)]="config.maxRuleFiringsTotal" min="1">
            </mat-form-field>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Actions -->
      <div class="action-bar">
        <button mat-raised-button color="primary" (click)="saveConfig()">
          <mat-icon>save</mat-icon> Save Configuration
        </button>
        <button mat-stroked-button (click)="loadConfig()">
          <mat-icon>refresh</mat-icon> Reload
        </button>
      </div>
    </div>
  `,
  styles: [`
    .config-container { padding: 16px; max-width: 800px; }
    .config-card { margin-bottom: 16px; }
    .toggle-row { padding: 8px 0; }
    .toggle-group { padding: 8px 0; }
    .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding: 8px 0; }
    .action-bar { display: flex; gap: 12px; padding-top: 8px; }
    mat-divider { margin: 8px 0; }
  `]
})
export class ComputeGraphConfigComponent implements OnInit {
  config: ComputeGraphConfig | null = null;
  @Output() configChanged = new EventEmitter<void>();

  constructor(
    private computeGraphService: ComputeGraphService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadConfig();
  }

  loadConfig(): void {
    this.computeGraphService.getConfig().subscribe({
      next: (config) => this.config = config,
      error: (err) => this.snackBar.open('Failed to load config: ' + err.message, 'Dismiss', { duration: 3000 })
    });
  }

  saveConfig(): void {
    if (!this.config) return;
    this.computeGraphService.updateConfig(this.config).subscribe({
      next: (updated) => {
        this.config = updated;
        this.configChanged.emit();
        this.snackBar.open('Configuration saved', 'OK', { duration: 2000 });
      },
      error: (err) => this.snackBar.open('Failed to save config: ' + err.message, 'Dismiss', { duration: 3000 })
    });
  }
}
