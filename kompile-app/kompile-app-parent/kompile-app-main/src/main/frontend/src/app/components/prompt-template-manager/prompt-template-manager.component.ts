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

import { Component, OnInit } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { filter } from 'rxjs/operators';
import { PromptTemplateService } from '../../services/prompt-template.service';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import {
  PromptTemplate,
  TemplateCategoryInfo,
  TemplatesSummary,
  TemplateVariable,
  TemplateExample,
  TEMPLATE_CATEGORIES
} from '../../models/api-models';

@Component({
  standalone: false,
  selector: 'app-prompt-template-manager',
  templateUrl: './prompt-template-manager.component.html',
  styleUrls: ['./prompt-template-manager.component.css']
})
export class PromptTemplateManagerComponent implements OnInit {
  // Data
  templates: PromptTemplate[] = [];
  templatesByCategory: { [key: string]: PromptTemplate[] } = {};
  categories: { [key: string]: TemplateCategoryInfo } = TEMPLATE_CATEGORIES;
  summary: TemplatesSummary | null = null;

  // UI State
  isLoading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  searchQuery = '';
  selectedCategory: string | null = null;
  viewMode: 'grid' | 'list' | 'categories' = 'categories';

  // Template Detail/Edit
  selectedTemplate: PromptTemplate | null = null;
  isEditMode = false;
  isCreating = false;
  editForm: Partial<PromptTemplate> = {};

  // Preview
  showPreview = false;
  previewVariables: { [key: string]: string } = {};
  previewResult: string | null = null;

  constructor(
    private templateService: PromptTemplateService,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadTemplates();
  }

  loadTemplates(): void {
    this.isLoading = true;
    this.errorMessage = null;

    // Load summary
    this.templateService.getTemplatesSummary().subscribe({
      next: (summary) => {
        this.summary = summary;
      },
      error: (err) => {
        console.error('Failed to load templates summary:', err);
      }
    });

    // Load templates grouped by category
    this.templateService.getTemplatesGroupedByCategory().subscribe({
      next: (grouped) => {
        this.templatesByCategory = grouped;
        this.templates = Object.values(grouped).flat();
        this.isLoading = false;
      },
      error: (err) => {
        this.errorMessage = `Failed to load templates: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  refreshTemplates(): void {
    this.isLoading = true;
    this.templateService.refreshTemplates().subscribe({
      next: (result) => {
        this.showSuccess(result.message);
        this.loadTemplates();
      },
      error: (err) => {
        this.errorMessage = `Failed to refresh templates: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  searchTemplates(): void {
    if (!this.searchQuery.trim()) {
      this.loadTemplates();
      return;
    }

    this.isLoading = true;
    this.templateService.searchTemplates(this.searchQuery).subscribe({
      next: (templates) => {
        this.templates = templates;
        this.templatesByCategory = this.groupByCategory(templates);
        this.isLoading = false;
      },
      error: (err) => {
        this.errorMessage = `Search failed: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  filterByCategory(category: string | null): void {
    this.selectedCategory = category;

    if (!category) {
      this.loadTemplates();
      return;
    }

    this.isLoading = true;
    this.templateService.getTemplatesByCategory(category).subscribe({
      next: (templates) => {
        this.templates = templates;
        this.templatesByCategory = { [category]: templates };
        this.isLoading = false;
      },
      error: (err) => {
        this.errorMessage = `Failed to filter templates: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  selectTemplate(template: PromptTemplate): void {
    this.selectedTemplate = template;
    this.isEditMode = false;
    this.editForm = {};
    this.resetPreview();
  }

  closeTemplate(): void {
    this.selectedTemplate = null;
    this.isEditMode = false;
    this.isCreating = false;
    this.editForm = {};
    this.resetPreview();
  }

  startEdit(): void {
    if (this.selectedTemplate) {
      this.isEditMode = true;
      this.editForm = { ...this.selectedTemplate };
      if (!this.editForm.variables) {
        this.editForm.variables = [];
      }
      if (!this.editForm.examples) {
        this.editForm.examples = [];
      }
      if (!this.editForm.tags) {
        this.editForm.tags = [];
      }
    }
  }

  cancelEdit(): void {
    this.isEditMode = false;
    this.isCreating = false;
    this.editForm = {};
  }

  saveEdit(): void {
    if (!this.selectedTemplate || !this.editForm.name) return;

    this.isLoading = true;
    this.templateService.updateTemplate(this.selectedTemplate.name, this.editForm).subscribe({
      next: (updated) => {
        this.selectedTemplate = updated;
        this.isEditMode = false;
        this.showSuccess('Template updated successfully');
        this.loadTemplates();
      },
      error: (err) => {
        this.errorMessage = `Failed to update template: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  startCreate(): void {
    this.isCreating = true;
    this.selectedTemplate = null;
    this.editForm = {
      name: '',
      displayName: '',
      description: '',
      content: '',
      category: 'custom',
      enabled: true,
      tags: [],
      variables: [],
      examples: []
    };
  }

  createTemplate(): void {
    if (!this.editForm.name || !this.editForm.content) {
      this.errorMessage = 'Name and content are required';
      return;
    }

    this.isLoading = true;
    this.templateService.createTemplate(this.editForm as PromptTemplate).subscribe({
      next: (created) => {
        this.selectedTemplate = created;
        this.isCreating = false;
        this.showSuccess('Template created successfully');
        this.loadTemplates();
      },
      error: (err) => {
        this.errorMessage = `Failed to create template: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  deleteTemplate(template: PromptTemplate): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete Template',
      message: `Are you sure you want to delete "${template.name}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.isLoading = true;
        this.templateService.deleteTemplate(template.name).subscribe({
          next: () => {
            this.closeTemplate();
            this.showSuccess('Template deleted successfully');
            this.loadTemplates();
          },
          error: (err) => {
            this.errorMessage = `Failed to delete template: ${err.message}`;
            this.isLoading = false;
          }
        });
      });
  }

  duplicateTemplate(template: PromptTemplate): void {
    const newName = prompt('Enter name for the copy:', template.name + '_copy');
    if (!newName) return;

    this.isLoading = true;
    this.templateService.duplicateTemplate(template.name, newName).subscribe({
      next: (copy) => {
        this.selectedTemplate = copy;
        this.showSuccess('Template duplicated successfully');
        this.loadTemplates();
      },
      error: (err) => {
        this.errorMessage = `Failed to duplicate template: ${err.message}`;
        this.isLoading = false;
      }
    });
  }

  toggleTemplateEnabled(template: PromptTemplate): void {
    const newState = !template.enabled;
    this.templateService.setTemplateEnabled(template.name, newState).subscribe({
      next: (updated) => {
        template.enabled = updated.enabled;
        this.showSuccess(`Template ${newState ? 'enabled' : 'disabled'}`);
      },
      error: (err) => {
        this.errorMessage = `Failed to toggle template: ${err.message}`;
      }
    });
  }

  // Preview functionality
  initPreview(): void {
    if (!this.selectedTemplate) return;
    this.showPreview = true;
    this.previewVariables = {};

    // Initialize variables with defaults
    if (this.selectedTemplate.variables) {
      for (const v of this.selectedTemplate.variables) {
        this.previewVariables[v.name] = v.defaultValue || v.exampleValue || '';
      }
    }
    this.previewResult = null;
  }

  runPreview(): void {
    if (!this.selectedTemplate) return;

    this.templateService.renderTemplate(this.selectedTemplate.name, this.previewVariables).subscribe({
      next: (result) => {
        this.previewResult = result.rendered;
      },
      error: (err) => {
        this.errorMessage = `Failed to render template: ${err.message}`;
      }
    });
  }

  resetPreview(): void {
    this.showPreview = false;
    this.previewVariables = {};
    this.previewResult = null;
  }

  copyTemplateContent(): void {
    if (this.selectedTemplate?.content) {
      navigator.clipboard.writeText(this.selectedTemplate.content).then(() => {
        this.showSuccess('Template content copied to clipboard');
      });
    }
  }

  copyPreviewResult(): void {
    if (this.previewResult) {
      navigator.clipboard.writeText(this.previewResult).then(() => {
        this.showSuccess('Preview result copied to clipboard');
      });
    }
  }

  // Variable management
  addVariable(): void {
    if (!this.editForm.variables) {
      this.editForm.variables = [];
    }
    this.editForm.variables.push({
      name: '',
      displayName: '',
      description: '',
      type: 'string',
      required: false,
      defaultValue: ''
    });
  }

  removeVariable(index: number): void {
    if (this.editForm.variables) {
      this.editForm.variables.splice(index, 1);
    }
  }

  // Example management
  addExample(): void {
    if (!this.editForm.examples) {
      this.editForm.examples = [];
    }
    this.editForm.examples.push({
      title: '',
      description: '',
      inputs: {},
      renderedOutput: ''
    });
  }

  removeExample(index: number): void {
    if (this.editForm.examples) {
      this.editForm.examples.splice(index, 1);
    }
  }

  // Tag management
  addTag(): void {
    if (!this.editForm.tags) {
      this.editForm.tags = [];
    }
    this.editForm.tags.push('');
  }

  removeTag(index: number): void {
    if (this.editForm.tags) {
      this.editForm.tags.splice(index, 1);
    }
  }

  // Helpers
  getCategoryDisplayName(categoryKey: string): string {
    return this.categories[categoryKey]?.displayName || categoryKey;
  }

  getCategoryDescription(categoryKey: string): string {
    return this.categories[categoryKey]?.description || '';
  }

  getSourceBadgeClass(builtIn: boolean | undefined): string {
    return builtIn ? 'badge-builtin' : 'badge-custom';
  }

  trackByTemplateName(index: number, template: PromptTemplate): string {
    return template.name;
  }

  trackByCategory(index: number, category: string): string {
    return category;
  }

  trackByIndex(index: number): number {
    return index;
  }

  private groupByCategory(templates: PromptTemplate[]): { [key: string]: PromptTemplate[] } {
    const grouped: { [key: string]: PromptTemplate[] } = {};
    for (const template of templates) {
      const category = template.category || 'custom';
      if (!grouped[category]) {
        grouped[category] = [];
      }
      grouped[category].push(template);
    }
    return grouped;
  }

  private showSuccess(message: string): void {
    this.successMessage = message;
    setTimeout(() => {
      this.successMessage = null;
    }, 3000);
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.selectedCategory = null;
    this.loadTemplates();
  }

  get categoryKeys(): string[] {
    return Object.keys(this.templatesByCategory);
  }
}
