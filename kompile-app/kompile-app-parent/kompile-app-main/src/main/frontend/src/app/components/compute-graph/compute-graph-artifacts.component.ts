import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ComputeGraphService } from '../../services/compute-graph.service';
import { ComputeArtifact } from '../../models/compute-graph-models';

@Component({
  standalone: true,
  selector: 'app-compute-graph-artifacts',
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule,
    MatButtonModule, MatIconModule, MatTableModule, MatSnackBarModule
  ],
  template: `
    <div class="artifacts-container">
      <mat-card>
        <mat-card-header>
          <mat-card-title>Artifact Browser</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="search-row">
            <mat-form-field appearance="outline" class="search-field">
              <mat-label>Execution ID</mat-label>
              <input matInput [(ngModel)]="executionId" placeholder="Enter execution ID to browse artifacts"
                     (keyup.enter)="loadArtifacts()">
            </mat-form-field>
            <button mat-raised-button color="primary" (click)="loadArtifacts()" [disabled]="!executionId">
              <mat-icon>search</mat-icon> Load
            </button>
            <button mat-stroked-button color="warn" (click)="deleteArtifacts()"
                    [disabled]="!executionId || artifacts.length === 0">
              <mat-icon>delete</mat-icon> Delete All
            </button>
          </div>

          <div *ngIf="loaded && artifacts.length === 0" class="empty-state">
            <mat-icon>inbox</mat-icon>
            <p>No artifacts found for this execution.</p>
          </div>

          <table mat-table [dataSource]="artifacts" *ngIf="artifacts.length > 0" class="artifacts-table">
            <ng-container matColumnDef="nodeId">
              <th mat-header-cell *matHeaderCellDef>Node ID</th>
              <td mat-cell *matCellDef="let a">{{a.nodeId}}</td>
            </ng-container>
            <ng-container matColumnDef="name">
              <th mat-header-cell *matHeaderCellDef>Name</th>
              <td mat-cell *matCellDef="let a">{{a.name}}</td>
            </ng-container>
            <ng-container matColumnDef="contentType">
              <th mat-header-cell *matHeaderCellDef>Content Type</th>
              <td mat-cell *matCellDef="let a">{{a.contentType || '-'}}</td>
            </ng-container>
            <ng-container matColumnDef="sizeBytes">
              <th mat-header-cell *matHeaderCellDef>Size</th>
              <td mat-cell *matCellDef="let a">{{formatSize(a.sizeBytes)}}</td>
            </ng-container>
            <ng-container matColumnDef="data">
              <th mat-header-cell *matHeaderCellDef>Data</th>
              <td mat-cell *matCellDef="let a">
                <pre class="data-preview" *ngIf="a.data">{{a.data | json}}</pre>
                <span *ngIf="!a.data" class="no-data">-</span>
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
          </table>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .artifacts-container { padding: 16px; max-width: 960px; }
    .search-row { display: flex; gap: 12px; align-items: flex-start; }
    .search-field { flex: 1; }
    .empty-state { text-align: center; padding: 32px; color: #999; }
    .empty-state mat-icon { font-size: 48px; width: 48px; height: 48px; }
    .artifacts-table { width: 100%; }
    .data-preview {
      max-width: 300px; max-height: 100px; overflow: auto; font-size: 11px;
      font-family: var(--font-family-monospace, monospace);
      background: #1e1e1e; color: #d4d4d4; padding: 4px 8px; border-radius: 3px;
      white-space: pre-wrap;
    }
    .no-data { color: #666; }
  `]
})
export class ComputeGraphArtifactsComponent {
  executionId = '';
  artifacts: ComputeArtifact[] = [];
  loaded = false;
  displayedColumns = ['nodeId', 'name', 'contentType', 'sizeBytes', 'data'];

  constructor(
    private computeGraphService: ComputeGraphService,
    private snackBar: MatSnackBar
  ) {}

  loadArtifacts(): void {
    if (!this.executionId) return;
    this.computeGraphService.getArtifacts(this.executionId).subscribe({
      next: (artifacts) => {
        this.artifacts = artifacts;
        this.loaded = true;
      },
      error: (err) => this.snackBar.open('Failed to load artifacts: ' + err.message, 'Dismiss', { duration: 3000 })
    });
  }

  deleteArtifacts(): void {
    if (!this.executionId) return;
    this.computeGraphService.deleteArtifacts(this.executionId).subscribe({
      next: () => {
        this.artifacts = [];
        this.snackBar.open('Artifacts deleted', 'OK', { duration: 2000 });
      },
      error: (err) => this.snackBar.open('Failed to delete: ' + err.message, 'Dismiss', { duration: 3000 })
    });
  }

  formatSize(bytes: number | undefined): string {
    if (!bytes) return '-';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }
}
