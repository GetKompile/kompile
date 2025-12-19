/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { StagingService } from '../../services/staging.service';
import { CatalogModel, ModelCatalog, getModelTypeIcon } from '../../models/api-models';

@Component({
  selector: 'app-model-catalog',
  standalone: false,
  templateUrl: './model-catalog.component.html',
  styleUrls: ['./model-catalog.component.css']
})
export class ModelCatalogComponent implements OnInit {

  catalog: ModelCatalog | null = null;
  isLoading = true;
  error: string | null = null;

  // Track which models are being staged
  stagingModels: Set<string> = new Set();

  constructor(
    private stagingService: StagingService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadCatalog();
  }

  loadCatalog(): void {
    this.isLoading = true;
    this.error = null;

    this.stagingService.getCatalog().subscribe({
      next: (catalog) => {
        this.catalog = catalog;
        this.isLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.error = err.message;
        this.isLoading = false;
        this.cdr.detectChanges();
      }
    });
  }

  stageModel(model: CatalogModel, autoPromote: boolean = false): void {
    if (this.stagingModels.has(model.id)) {
      return; // Already staging
    }

    this.stagingModels.add(model.id);

    this.stagingService.stageFromCatalog(model.id, autoPromote).subscribe({
      next: (stagingInfo) => {
        this.stagingModels.delete(model.id);
        this.showSnackbar(`Started staging ${model.id}`);
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.stagingModels.delete(model.id);
        this.showSnackbar(`Failed to stage ${model.id}: ${err.message}`, true);
        this.cdr.detectChanges();
      }
    });
  }

  isStaging(modelId: string): boolean {
    return this.stagingModels.has(modelId);
  }

  getEncoders(): CatalogModel[] {
    return this.catalog?.encoders || [];
  }

  getCrossEncoders(): CatalogModel[] {
    return this.catalog?.crossEncoders || [];
  }

  getModelTypeIcon = getModelTypeIcon;

  getSourceIcon(source: string): string {
    switch (source.toLowerCase()) {
      case 'huggingface':
        return 'hub';
      case 'github':
        return 'code';
      default:
        return 'cloud';
    }
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
