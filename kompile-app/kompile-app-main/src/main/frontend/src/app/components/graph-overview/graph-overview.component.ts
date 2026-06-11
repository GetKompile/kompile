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

import {
  Component,
  OnInit,
  OnDestroy,
  Output,
  EventEmitter,
  ChangeDetectorRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatTreeModule, MatTreeNestedDataSource } from '@angular/material/tree';
import { NestedTreeControl } from '@angular/cdk/tree';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatDialogModule } from '@angular/material/dialog';
import { Subject, takeUntil } from 'rxjs';

import { GraphService } from '../../services/graph.service';
import { NamedGraph, CreateNamedGraphRequest } from '../../models/graph-models';

@Component({
  selector: 'app-graph-overview',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatTreeModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatInputModule,
    MatFormFieldModule,
    MatSelectModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatChipsModule,
    MatDividerModule,
    MatDialogModule
  ],
  templateUrl: './graph-overview.component.html',
  styleUrls: ['./graph-overview.component.css']
})
export class GraphOverviewComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  @Output() graphSelected = new EventEmitter<NamedGraph>();

  treeControl = new NestedTreeControl<NamedGraph>(node => node.childGraphs);
  dataSource = new MatTreeNestedDataSource<NamedGraph>();

  loading = false;
  searchQuery = '';
  selectedGraph: NamedGraph | null = null;

  // Inline create form
  showCreateForm = false;
  createForm: FormGroup;
  creating = false;

  // Move action
  movingGraph: NamedGraph | null = null;
  moveTargetId = '';
  allGraphIds: string[] = [];

  constructor(
    private graphService: GraphService,
    private snackBar: MatSnackBar,
    private fb: FormBuilder,
    private cdr: ChangeDetectorRef
  ) {
    this.createForm = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(1)]],
      description: [''],
      ontologyType: [''],
      parentGraphId: ['']
    });
  }

  ngOnInit(): void {
    this.loadGraphs();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  hasChild = (_: number, node: NamedGraph) =>
    ((node.childGraphCount ?? 0) > 0) || ((node.childGraphs?.length ?? 0) > 0);

  loadGraphs(query?: string): void {
    this.loading = true;
    this.graphService.getNamedGraphs(query || undefined)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (graphs) => {
          // Build top-level tree: graphs without a parentGraphId are roots
          const rootGraphs = graphs.filter(g => !g.parentGraphId);
          this.dataSource.data = rootGraphs;
          this.allGraphIds = graphs.map(g => g.graphId);
          this.loading = false;
          this.cdr.detectChanges();
        },
        error: () => {
          this.loading = false;
          this.dataSource.data = [];
        }
      });
  }

  onSearchChange(): void {
    this.loadGraphs(this.searchQuery.trim() || undefined);
  }

  toggleNode(node: NamedGraph, event: Event): void {
    event.stopPropagation();
    if (this.treeControl.isExpanded(node)) {
      this.treeControl.collapse(node);
    } else {
      // Lazy-load children if not populated yet
      if ((node.childGraphCount ?? 0) > 0 && !(node.childGraphs?.length)) {
        this.graphService.getChildGraphs(node.graphId)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: (children) => {
              node.childGraphs = children;
              this.dataSource.data = [...this.dataSource.data];
              this.treeControl.expand(node);
            },
            error: () => this.treeControl.expand(node)
          });
      } else {
        this.treeControl.expand(node);
      }
    }
  }

  selectGraph(graph: NamedGraph): void {
    this.selectedGraph = graph;
    this.movingGraph = null;
  }

  viewGraph(graph: NamedGraph): void {
    this.graphSelected.emit(graph);
  }

  // ── Create ────────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm = !this.showCreateForm;
    if (!this.showCreateForm) {
      this.createForm.reset();
    }
  }

  submitCreateForm(): void {
    if (this.createForm.invalid) return;

    this.creating = true;
    const raw = this.createForm.value;
    const request: CreateNamedGraphRequest = {
      name: raw.name.trim(),
      description: raw.description?.trim() || undefined,
      ontologyType: raw.ontologyType?.trim() || undefined,
      parentGraphId: raw.parentGraphId?.trim() || undefined
    };

    this.graphService.createNamedGraph(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (created) => {
          this.snackBar.open(`Graph "${created.name}" created`, 'Dismiss', { duration: 3000 });
          this.creating = false;
          this.showCreateForm = false;
          this.createForm.reset();
          this.loadGraphs();
        },
        error: () => {
          this.snackBar.open('Failed to create graph', 'Dismiss', { duration: 3000 });
          this.creating = false;
        }
      });
  }

  // ── Delete ────────────────────────────────────────────────────────────────

  deleteGraph(graph: NamedGraph, event: Event): void {
    event.stopPropagation();
    if (!confirm(`Delete graph "${graph.name}"? This cannot be undone.`)) return;

    this.graphService.deleteNamedGraph(graph.graphId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`Graph "${graph.name}" deleted`, 'Dismiss', { duration: 3000 });
          if (this.selectedGraph?.graphId === graph.graphId) {
            this.selectedGraph = null;
          }
          this.loadGraphs();
        },
        error: () => {
          this.snackBar.open('Failed to delete graph', 'Dismiss', { duration: 3000 });
        }
      });
  }

  // ── Move ──────────────────────────────────────────────────────────────────

  startMove(graph: NamedGraph, event: Event): void {
    event.stopPropagation();
    this.movingGraph = graph;
    this.moveTargetId = graph.parentGraphId || '';
  }

  confirmMove(): void {
    if (!this.movingGraph) return;
    const newParent = this.moveTargetId.trim() || null;

    this.graphService.moveGraph(this.movingGraph.graphId, newParent)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Graph moved successfully', 'Dismiss', { duration: 2000 });
          this.movingGraph = null;
          this.loadGraphs();
        },
        error: () => {
          this.snackBar.open('Failed to move graph', 'Dismiss', { duration: 3000 });
        }
      });
  }

  cancelMove(): void {
    this.movingGraph = null;
    this.moveTargetId = '';
  }
}
