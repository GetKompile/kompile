import { Component, OnInit, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TritonCacheService, TritonCacheStats, TritonCacheBundle } from '../../services/triton-cache.service';

@Component({
  selector: 'app-triton-cache',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule
  ],
  templateUrl: './triton-cache.component.html',
  styleUrls: ['./triton-cache.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TritonCacheComponent implements OnInit {
  loading = true;
  stats: TritonCacheStats | null = null;
  bundles: TritonCacheBundle[] = [];
  bundleColumns = ['filename', 'modelId', 'size', 'lastModified', 'actions'];
  exportModelId = '';

  constructor(
    private tritonService: TritonCacheService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  private loadData(): void {
    this.loading = true;
    this.cdr.markForCheck();

    this.tritonService.getStatus().subscribe({
      next: (stats) => {
        this.stats = stats;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.stats = null;
        this.loading = false;
        this.cdr.markForCheck();
      }
    });

    this.tritonService.listBundles().subscribe({
      next: (bundles) => {
        this.bundles = bundles;
        this.cdr.markForCheck();
      },
      error: () => {
        this.bundles = [];
        this.cdr.markForCheck();
      }
    });
  }

  exportCache(): void {
    if (!this.exportModelId.trim()) {
      this.snackBar.open('Enter a model ID to export', 'OK', { duration: 3000 });
      return;
    }
    this.tritonService.exportCache(this.exportModelId.trim()).subscribe({
      next: (res) => {
        this.snackBar.open(res.message || 'Cache exported', 'OK', { duration: 3000 });
        this.loadData();
      },
      error: (err) => {
        this.snackBar.open('Export failed: ' + (err.error?.message || err.message), 'OK', { duration: 5000 });
      }
    });
  }

  importBundle(modelId: string): void {
    this.tritonService.importCache(modelId).subscribe({
      next: (res) => {
        this.snackBar.open(res.message || 'Cache imported', 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.snackBar.open('Import failed: ' + (err.error?.message || err.message), 'OK', { duration: 5000 });
      }
    });
  }

  clearAll(): void {
    this.tritonService.invalidateAll().subscribe({
      next: () => {
        this.snackBar.open('All in-memory Triton modules invalidated', 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.snackBar.open('Clear failed: ' + (err.error?.message || err.message), 'OK', { duration: 5000 });
      }
    });
  }

  formatSize(mb: number): string {
    if (mb >= 1024) return (mb / 1024).toFixed(1) + ' GB';
    return mb.toFixed(1) + ' MB';
  }
}
