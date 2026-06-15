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

import { Component, Input, OnChanges, OnInit, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTreeModule, MatTreeNestedDataSource } from '@angular/material/tree';
import { NestedTreeControl } from '@angular/cdk/tree';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatTableModule } from '@angular/material/table';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatMenuModule } from '@angular/material/menu';
import { EnrichmentService } from '../../services/enrichment.service';
import { EntityCategory } from '../../models/api-models';

interface CategoryTreeNode extends EntityCategory {
  children: CategoryTreeNode[];
}

@Component({
  selector: 'app-category-manager',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatTreeModule, MatIconModule, MatButtonModule, MatCardModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatChipsModule,
    MatCheckboxModule, MatProgressSpinnerModule, MatSnackBarModule,
    MatTooltipModule, MatDividerModule, MatTableModule, MatProgressBarModule,
    MatMenuModule
  ],
  template: `
    <!-- Toolbar -->
    <div class="toolbar">
      <button mat-stroked-button (click)="startCreate(null)">
        <mat-icon>add</mat-icon> Add Category
      </button>

      <!-- Auto-Label: Direct (LLM) -->
      <button mat-stroked-button (click)="runDirectAutoLabel()" [disabled]="autoLabeling"
              matTooltip="Auto-label uncategorized entities using LLM">
        <mat-icon>auto_awesome</mat-icon> Auto-Label
      </button>

      <!-- Auto-Label: Agent-based -->
      <button mat-stroked-button [matMenuTriggerFor]="agentMenu" [disabled]="autoLabeling"
              matTooltip="Pick a KClaw agent to scan and label entities">
        <mat-icon>smart_toy</mat-icon> Label via Agent
        <mat-icon>arrow_drop_down</mat-icon>
      </button>
      <mat-menu #agentMenu="matMenu">
        <button mat-menu-item *ngIf="agents.length === 0" disabled>
          <mat-icon>warning</mat-icon> No agents available
        </button>
        <button mat-menu-item *ngFor="let agent of agents" (click)="runAgentAutoLabel(agent.name)">
          <mat-icon>smart_toy</mat-icon>
          <span>{{ agent.name }}</span>
          <span class="agent-desc" *ngIf="agent.description"> - {{ agent.description }}</span>
        </button>
      </mat-menu>

      <span class="spacer"></span>
      <span class="cat-count" *ngIf="categories.length > 0">
        {{ categories.length }} categories ({{ userDefinedCount }} user-defined)
      </span>
    </div>

    <mat-progress-bar *ngIf="autoLabeling" mode="indeterminate"></mat-progress-bar>
    <div class="auto-label-status" *ngIf="autoLabeling">
      {{ autoLabelMessage }}
    </div>

    <div class="manager-layout">
      <!-- Category Tree -->
      <div class="tree-panel">
        <mat-tree [dataSource]="dataSource" [treeControl]="treeControl" class="category-tree">
          <mat-nested-tree-node *matTreeNodeDef="let node">
            <li>
              <div class="tree-node" [class.selected]="selectedCategory?.categoryId === node.categoryId"
                   (click)="selectCategory(node)">
                <button mat-icon-button *ngIf="node.children?.length" (click)="treeControl.toggle(node); $event.stopPropagation()">
                  <mat-icon>{{ treeControl.isExpanded(node) ? 'expand_more' : 'chevron_right' }}</mat-icon>
                </button>
                <span *ngIf="!node.children?.length" class="tree-spacer"></span>
                <span class="color-dot" [style.background]="node.color || '#9e9e9e'"></span>
                <span class="cat-label">{{ node.label }}</span>
                <span class="source-badge" [class.user]="node.source === 'USER_DEFINED'" [class.auto]="node.source === 'AUTO_DISCOVERED'">
                  {{ node.source === 'USER_DEFINED' ? 'user' : 'auto' }}
                </span>
                <span class="entity-count-badge" *ngIf="node.entityCount">({{ node.entityCount }})</span>
                <button mat-icon-button (click)="startEdit(node); $event.stopPropagation()" matTooltip="Edit">
                  <mat-icon>settings</mat-icon>
                </button>
                <button mat-icon-button (click)="deleteCategory(node); $event.stopPropagation()" matTooltip="Delete">
                  <mat-icon>close</mat-icon>
                </button>
              </div>
              <ul *ngIf="treeControl.isExpanded(node)">
                <ng-container matTreeNodeOutlet></ng-container>
              </ul>
            </li>
          </mat-nested-tree-node>
        </mat-tree>

        <div class="empty-state" *ngIf="categories.length === 0 && !loading">
          No categories defined. Add one or run enrichment to auto-discover.
        </div>
      </div>

      <!-- Edit / Create Panel -->
      <div class="edit-panel" *ngIf="editing">
        <mat-card>
          <mat-card-header>
            <mat-card-title>{{ isNew ? 'New Category' : 'Edit Category' }}</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <mat-form-field class="full-width">
              <mat-label>Label</mat-label>
              <input matInput [(ngModel)]="editForm.label">
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Description</mat-label>
              <textarea matInput [(ngModel)]="editForm.description" rows="3"></textarea>
            </mat-form-field>
            <mat-form-field class="full-width">
              <mat-label>Color</mat-label>
              <input matInput [(ngModel)]="editForm.color" placeholder="#3b82f6">
            </mat-form-field>
            <mat-form-field class="full-width" *ngIf="parentOptions.length > 0">
              <mat-label>Parent Category</mat-label>
              <mat-select [(ngModel)]="editForm.parentCategoryId">
                <mat-option [value]="null">None (root level)</mat-option>
                <mat-option *ngFor="let p of parentOptions" [value]="p.categoryId">{{ p.label }}</mat-option>
              </mat-select>
            </mat-form-field>
          </mat-card-content>
          <mat-card-actions>
            <button mat-flat-button color="primary" (click)="saveCategory()" [disabled]="!editForm.label">
              {{ isNew ? 'Create' : 'Save' }}
            </button>
            <button mat-stroked-button (click)="cancelEdit()">Cancel</button>
          </mat-card-actions>
        </mat-card>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
    .spacer { flex: 1; }
    .cat-count { font-size: 13px; color: var(--mat-sys-on-surface-variant); }
    .manager-layout { display: flex; gap: 16px; }
    .tree-panel { width: 400px; min-width: 320px; overflow-y: auto; }
    .edit-panel { flex: 1; }
    .tree-node {
      display: flex; align-items: center; gap: 4px; padding: 4px 8px;
      cursor: pointer; border-radius: 4px;
    }
    .tree-node:hover { background: var(--mat-sys-surface-container-high); }
    .tree-node.selected { background: var(--mat-sys-primary-container); }
    .tree-spacer { width: 40px; }
    .color-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
    .cat-label { flex: 1; }
    .source-badge {
      font-size: 10px; padding: 1px 6px; border-radius: 8px;
      background: var(--mat-sys-surface-container-high);
    }
    .source-badge.user { background: #e3f2fd; color: #1565c0; }
    .source-badge.auto { background: #f3e5f5; color: #7b1fa2; }
    .entity-count-badge { font-size: 12px; color: var(--mat-sys-on-surface-variant); }
    .full-width { width: 100%; }
    .empty-state { text-align: center; padding: 24px; color: var(--mat-sys-on-surface-variant); }
    ul { list-style: none; padding-left: 16px; margin: 0; }
    li { list-style: none; }
    .category-tree { background: transparent; }
    .auto-label-status { padding: 4px 16px; font-size: 13px; color: var(--mat-sys-on-surface-variant); }
    .agent-desc { font-size: 12px; color: var(--mat-sys-on-surface-variant); margin-left: 4px; }
  `]
})
export class CategoryManagerComponent implements OnChanges, OnInit {
  @Input() factSheetId: number | null = null;

  treeControl = new NestedTreeControl<CategoryTreeNode>(node => node.children);
  dataSource = new MatTreeNestedDataSource<CategoryTreeNode>();

  categories: EntityCategory[] = [];
  selectedCategory: EntityCategory | null = null;
  parentOptions: EntityCategory[] = [];

  editing = false;
  isNew = false;
  editForm = { label: '', description: '', color: '#3b82f6', parentCategoryId: undefined as string | undefined };
  editingCategoryId: string | null = null;

  // Auto-label
  autoLabeling = false;
  autoLabelMessage = '';

  // KClaw agents
  agents: { name: string; description: string }[] = [];

  loading = false;
  userDefinedCount = 0;

  constructor(
    private enrichmentService: EnrichmentService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadAgents();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && this.factSheetId) {
      this.loadCategories();
    }
  }

  loadAgents(): void {
    this.enrichmentService.getAvailableAgents().subscribe({
      next: agents => this.agents = agents,
      error: () => this.agents = []
    });
  }

  loadCategories(): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.enrichmentService.getCategories(this.factSheetId, true).subscribe({
      next: categories => {
        this.categories = this.flattenTree(categories);
        this.dataSource.data = categories as CategoryTreeNode[];
        this.parentOptions = this.categories;
        this.userDefinedCount = this.categories.filter(c => c.source === 'USER_DEFINED').length;
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  private flattenTree(categories: EntityCategory[]): EntityCategory[] {
    const flat: EntityCategory[] = [];
    const walk = (cats: EntityCategory[]) => {
      for (const c of cats) {
        flat.push(c);
        if (c.children) walk(c.children);
      }
    };
    walk(categories);
    return flat;
  }

  selectCategory(cat: EntityCategory): void {
    this.selectedCategory = cat;
  }

  startCreate(parentId: string | null): void {
    this.editing = true;
    this.isNew = true;
    this.editingCategoryId = null;
    this.editForm = { label: '', description: '', color: '#3b82f6', parentCategoryId: parentId || undefined };
  }

  startEdit(cat: EntityCategory): void {
    this.editing = true;
    this.isNew = false;
    this.editingCategoryId = cat.categoryId;
    this.editForm = {
      label: cat.label,
      description: cat.description || '',
      color: cat.color || '#3b82f6',
      parentCategoryId: cat.parentCategoryId || undefined
    };
  }

  cancelEdit(): void {
    this.editing = false;
    this.editingCategoryId = null;
  }

  saveCategory(): void {
    if (!this.factSheetId || !this.editForm.label) return;
    if (this.isNew) {
      this.enrichmentService.createCategory(this.factSheetId, this.editForm).subscribe({
        next: () => { this.snackBar.open('Category created', 'OK', { duration: 2000 }); this.cancelEdit(); this.loadCategories(); },
        error: err => this.snackBar.open('Error: ' + (err.error?.message || err.message), 'OK', { duration: 4000 })
      });
    } else if (this.editingCategoryId) {
      this.enrichmentService.updateCategory(this.factSheetId, this.editingCategoryId, this.editForm).subscribe({
        next: () => { this.snackBar.open('Category updated', 'OK', { duration: 2000 }); this.cancelEdit(); this.loadCategories(); },
        error: err => this.snackBar.open('Error: ' + (err.error?.message || err.message), 'OK', { duration: 4000 })
      });
    }
  }

  deleteCategory(cat: EntityCategory): void {
    if (!this.factSheetId || !confirm('Delete category "' + cat.label + '"? Entities will be uncategorized.')) return;
    this.enrichmentService.deleteCategory(this.factSheetId, cat.categoryId).subscribe({
      next: () => { this.snackBar.open('Category deleted', 'OK', { duration: 2000 }); this.loadCategories(); },
      error: err => this.snackBar.open('Error: ' + (err.error?.message || err.message), 'OK', { duration: 4000 })
    });
  }

  /** Direct auto-label: applies immediately via LLM, no dry-run review */
  runDirectAutoLabel(): void {
    if (!this.factSheetId) return;
    this.autoLabeling = true;
    this.autoLabelMessage = 'Scanning and labeling entities via LLM...';
    this.enrichmentService.autoLabel(this.factSheetId, undefined, false, 0.7).subscribe({
      next: result => {
        this.autoLabeling = false;
        this.autoLabelMessage = '';
        const count = result.entitiesAffected || 0;
        if (count > 0) {
          this.snackBar.open(`Labeled ${count} entities`, 'OK', { duration: 3000 });
        } else {
          this.snackBar.open('No uncategorized entities found or no suggestions above threshold', 'OK', { duration: 3000 });
        }
        this.loadCategories();
      },
      error: err => {
        this.autoLabeling = false;
        this.autoLabelMessage = '';
        this.snackBar.open('Auto-label failed: ' + (err.error?.message || err.message), 'OK', { duration: 4000 });
      }
    });
  }

  /** Agent-based auto-label: uses a KClaw agent to scan and label entities directly */
  runAgentAutoLabel(agentName: string): void {
    if (!this.factSheetId) return;
    this.autoLabeling = true;
    this.autoLabelMessage = `Agent "${agentName}" is scanning entities...`;
    this.enrichmentService.autoLabelViaAgent(this.factSheetId, agentName).subscribe({
      next: result => {
        this.autoLabeling = false;
        this.autoLabelMessage = '';
        const count = result.entitiesAffected || 0;
        const errors = result.errors || [];
        if (count > 0) {
          this.snackBar.open(`Agent labeled ${count} entities`, 'OK', { duration: 3000 });
        } else if (errors.length > 0) {
          this.snackBar.open(errors[0], 'OK', { duration: 4000 });
        } else {
          this.snackBar.open('No uncategorized entities found', 'OK', { duration: 3000 });
        }
        this.loadCategories();
      },
      error: err => {
        this.autoLabeling = false;
        this.autoLabelMessage = '';
        this.snackBar.open('Agent auto-label failed: ' + (err.error?.message || err.message), 'OK', { duration: 4000 });
      }
    });
  }
}
