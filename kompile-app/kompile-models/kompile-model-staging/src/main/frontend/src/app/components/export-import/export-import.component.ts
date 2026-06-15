/*
 *   Copyright 2025 Kompile Inc.
 */

import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { StagingService } from '../../services/staging.service';
import { ModelRegistry, ModelEntry, ExportRequest, ImportRequest } from '../../models/api-models';

@Component({
  selector: 'app-export-import',
  standalone: false,
  templateUrl: './export-import.component.html',
  styleUrls: ['./export-import.component.css']
})
export class ExportImportComponent implements OnInit {

  registry: ModelRegistry | null = null;
  isLoading = true;

  // Export state
  selectedModels: Set<string> = new Set();
  exportPath: string = '';
  isExporting = false;

  // Import state
  importPath: string = '';
  verifyChecksums: boolean = true;
  isImporting = false;

  constructor(
    private stagingService: StagingService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadRegistry();
  }

  loadRegistry(): void {
    this.isLoading = true;
    this.stagingService.getRegistry().subscribe({
      next: (registry) => {
        this.registry = registry;
        this.isLoading = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.isLoading = false;
        this.cdr.detectChanges();
      }
    });
  }

  getModels(): ModelEntry[] {
    return this.registry?.models ? Object.values(this.registry.models) : [];
  }

  toggleModelSelection(modelId: string): void {
    if (this.selectedModels.has(modelId)) {
      this.selectedModels.delete(modelId);
    } else {
      this.selectedModels.add(modelId);
    }
  }

  isSelected(modelId: string): boolean {
    return this.selectedModels.has(modelId);
  }

  selectAll(): void {
    this.getModels().forEach(m => this.selectedModels.add(m.model_id));
  }

  deselectAll(): void {
    this.selectedModels.clear();
  }

  exportModels(): void {
    if (this.selectedModels.size === 0) {
      this.showSnackbar('Please select at least one model to export', true);
      return;
    }
    if (!this.exportPath.trim()) {
      this.showSnackbar('Please enter an export path', true);
      return;
    }

    this.isExporting = true;
    const request: ExportRequest = {
      modelIds: Array.from(this.selectedModels),
      outputPath: this.exportPath.trim(),
      includeVocab: true
    };

    this.stagingService.exportModels(request).subscribe({
      next: (result) => {
        this.isExporting = false;
        if (result.success) {
          this.showSnackbar(`Exported ${result.modelCount} models to ${result.bundlePath}`);
        } else {
          this.showSnackbar('Export failed: ' + result.error, true);
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isExporting = false;
        this.showSnackbar('Export failed: ' + err.message, true);
        this.cdr.detectChanges();
      }
    });
  }

  importModels(): void {
    if (!this.importPath.trim()) {
      this.showSnackbar('Please enter a bundle path', true);
      return;
    }

    this.isImporting = true;
    const request: ImportRequest = {
      bundlePath: this.importPath.trim(),
      verifyChecksums: this.verifyChecksums
    };

    this.stagingService.importModels(request).subscribe({
      next: (result) => {
        this.isImporting = false;
        if (result.success) {
          this.showSnackbar(`Imported ${result.modelCount} models`);
          this.loadRegistry();
        } else {
          this.showSnackbar('Import failed: ' + result.error, true);
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isImporting = false;
        this.showSnackbar('Import failed: ' + err.message, true);
        this.cdr.detectChanges();
      }
    });
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
