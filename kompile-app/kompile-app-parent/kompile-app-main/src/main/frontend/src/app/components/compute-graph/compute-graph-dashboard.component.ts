import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { Subscription } from 'rxjs';
import { ComputeGraphService } from '../../services/compute-graph.service';
import { ComputeGraphStatus } from '../../models/compute-graph-models';
import { ComputeGraphConfigComponent } from './compute-graph-config.component';
import { ComputeGraphEditorComponent } from './compute-graph-editor.component';
import { ComputeGraphArtifactsComponent } from './compute-graph-artifacts.component';

@Component({
  standalone: true,
  selector: 'app-compute-graph-dashboard',
  imports: [
    CommonModule, MatTabsModule, MatIconModule, MatButtonModule,
    MatSlideToggleModule, MatSnackBarModule, MatChipsModule,
    ComputeGraphConfigComponent, ComputeGraphEditorComponent, ComputeGraphArtifactsComponent
  ],
  template: `
    <div class="dashboard-container">
      <!-- Header -->
      <div class="dashboard-header">
        <div class="header-left">
          <mat-icon class="header-icon">device_hub</mat-icon>
          <div>
            <h3>Compute Graph Engine</h3>
            <p class="header-sub">
              Build and execute node-based computation graphs with JavaScript, Python, Drools rules, and expressions
            </p>
          </div>
        </div>
        <div class="header-right" *ngIf="status">
          <mat-chip-set>
            <mat-chip [highlighted]="status.enabled" [color]="status.enabled ? 'primary' : 'warn'">
              Engine: {{status.enabled ? 'ON' : 'OFF'}}
            </mat-chip>
            <mat-chip *ngIf="status.scriptingEnabled" color="accent" highlighted>JS/Python</mat-chip>
            <mat-chip *ngIf="status.droolsEnabled" color="accent" highlighted>Drools</mat-chip>
          </mat-chip-set>
        </div>
      </div>

      <!-- Tabs -->
      <mat-tab-group class="graph-tabs">
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon>settings</mat-icon>&nbsp;Configuration
          </ng-template>
          <div class="tab-body">
            <app-compute-graph-config (configChanged)="refreshStatus()"></app-compute-graph-config>
          </div>
        </mat-tab>
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon>edit</mat-icon>&nbsp;Graph Editor
          </ng-template>
          <div class="tab-body">
            <app-compute-graph-editor></app-compute-graph-editor>
          </div>
        </mat-tab>
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon>inventory_2</mat-icon>&nbsp;Artifacts
          </ng-template>
          <div class="tab-body">
            <app-compute-graph-artifacts></app-compute-graph-artifacts>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    .dashboard-container { height: 100%; }
    .dashboard-header {
      display: flex; justify-content: space-between; align-items: center;
      padding: 16px 20px; border-bottom: 1px solid rgba(255,255,255,0.12);
    }
    .header-left { display: flex; align-items: center; gap: 12px; }
    .header-icon { font-size: 32px; width: 32px; height: 32px; color: #90caf9; }
    .header-left h3 { margin: 0; font-size: 18px; }
    .header-sub { margin: 2px 0 0; font-size: 12px; color: #999; }
    .header-right { display: flex; align-items: center; gap: 8px; }
    .tab-body { padding: 0; }
    .graph-tabs { height: calc(100% - 80px); }
  `]
})
export class ComputeGraphDashboardComponent implements OnInit, OnDestroy {
  status: ComputeGraphStatus | null = null;
  private sub?: Subscription;

  constructor(
    private computeGraphService: ComputeGraphService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.refreshStatus();
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  refreshStatus(): void {
    this.sub?.unsubscribe();
    this.sub = this.computeGraphService.getStatus().subscribe({
      next: (status) => this.status = status,
      error: () => {} // silently handle if backend not available
    });
  }
}
