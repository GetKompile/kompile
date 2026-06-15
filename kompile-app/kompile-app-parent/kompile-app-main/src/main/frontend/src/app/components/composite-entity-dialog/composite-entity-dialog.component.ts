/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit, OnDestroy, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { Subject, takeUntil } from 'rxjs';

import { GraphService } from '../../services/graph.service';
import { GraphNode, CreateCompositeEntityRequest } from '../../models/graph-models';

export interface CompositeEntityDialogData {
  /** Optional pre-selected parent node */
  parentNode?: GraphNode;
  /** Available nodes to choose from as parent */
  availableNodes?: GraphNode[];
}

export interface MetadataEntry {
  key: string;
  value: string;
}

@Component({
  selector: 'app-composite-entity-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSliderModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDividerModule
  ],
  templateUrl: './composite-entity-dialog.component.html',
  styleUrls: ['./composite-entity-dialog.component.css']
})
export class CompositeEntityDialogComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  form: FormGroup;
  saving = false;
  metadataEntries: MetadataEntry[] = [];

  availableNodes: GraphNode[] = [];

  constructor(
    public dialogRef: MatDialogRef<CompositeEntityDialogComponent, GraphNode>,
    @Inject(MAT_DIALOG_DATA) public data: CompositeEntityDialogData,
    private fb: FormBuilder,
    private graphService: GraphService,
    private snackBar: MatSnackBar
  ) {
    this.form = this.fb.group({
      title: ['', [Validators.required, Validators.minLength(1)]],
      description: [''],
      externalId: [''],
      parentNodeId: [data?.parentNode?.nodeId || ''],
      confidence: [0.8]
    });

    this.availableNodes = data?.availableNodes || [];
  }

  ngOnInit(): void {
    // If no available nodes were pre-supplied, load from the service
    if (!this.availableNodes.length) {
      this.graphService.getNodes(undefined, undefined, 200)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (nodes) => { this.availableNodes = nodes; },
          error: () => {}
        });
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ── Metadata key-value editor ─────────────────────────────────────────────

  addMetadataEntry(): void {
    this.metadataEntries.push({ key: '', value: '' });
  }

  removeMetadataEntry(index: number): void {
    this.metadataEntries.splice(index, 1);
  }

  private buildMetadataMap(): Record<string, any> | undefined {
    if (!this.metadataEntries.length) return undefined;
    const map: Record<string, any> = {};
    for (const entry of this.metadataEntries) {
      if (entry.key.trim()) {
        map[entry.key.trim()] = entry.value;
      }
    }
    return Object.keys(map).length ? map : undefined;
  }

  // ── Save ──────────────────────────────────────────────────────────────────

  save(): void {
    if (this.form.invalid) return;

    this.saving = true;
    const raw = this.form.value;

    const request: CreateCompositeEntityRequest = {
      title: raw.title.trim(),
      externalId: raw.externalId?.trim() || `composite-${Date.now()}`,
      description: raw.description?.trim() || undefined,
      parentNodeId: raw.parentNodeId?.trim() || undefined,
      confidence: raw.confidence,
      metadata: this.buildMetadataMap()
    };

    this.graphService.createCompositeEntity(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (node) => {
          this.saving = false;
          this.dialogRef.close(node);
        },
        error: () => {
          this.saving = false;
          this.snackBar.open('Failed to create composite entity', 'Dismiss', { duration: 3000 });
        }
      });
  }

  cancel(): void {
    this.dialogRef.close();
  }

  formatConfidence(value: number): string {
    return (value * 100).toFixed(0) + '%';
  }
}
