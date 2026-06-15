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
import { SkillService, SkillDefinition, SkillsSummary } from '../../services/skill.service';

@Component({
  standalone: false,
  selector: 'app-skills-manager',
  templateUrl: './skills-manager.component.html',
  styleUrls: ['./skills-manager.component.css']
})
export class SkillsManagerComponent implements OnInit {

  skills: SkillDefinition[] = [];
  skillsByCategory: { [key: string]: SkillDefinition[] } = {};
  summary: SkillsSummary | null = null;

  selectedSkill: SkillDefinition | null = null;
  isCreating = false;
  isEditMode = false;
  editForm: Partial<SkillDefinition> = {};

  searchQuery = '';
  selectedCategory: string | null = null;

  // Preview
  previewArgs = '';
  previewResult = '';
  isPreviewLoading = false;

  // Markdown view
  skillsMarkdown = '';
  showMarkdownView = false;

  isLoading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  constructor(private skillService: SkillService) {}

  ngOnInit(): void {
    this.loadSkills();
  }

  // ── Data Loading ────────────────────────────────────────────────────────

  loadSkills(): void {
    this.isLoading = true;
    this.skillService.listAll(
      this.selectedCategory || undefined,
      this.searchQuery || undefined
    ).subscribe({
      next: skills => {
        this.skills = skills;
        this.groupByCategory(skills);
        this.isLoading = false;
      },
      error: err => {
        this.errorMessage = 'Failed to load skills: ' + (err.error?.error || err.message);
        this.isLoading = false;
      }
    });

    this.skillService.getSummary().subscribe({
      next: summary => this.summary = summary,
      error: () => {} // Non-critical
    });
  }

  private groupByCategory(skills: SkillDefinition[]): void {
    this.skillsByCategory = {};
    for (const skill of skills) {
      const cat = skill.category || 'general';
      if (!this.skillsByCategory[cat]) {
        this.skillsByCategory[cat] = [];
      }
      this.skillsByCategory[cat].push(skill);
    }
  }

  get categoryKeys(): string[] {
    return Object.keys(this.skillsByCategory).sort();
  }

  // ── Search & Filter ────────────────────────────────────────────────────

  searchSkills(): void {
    this.loadSkills();
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.loadSkills();
  }

  filterByCategory(category: string | null): void {
    this.selectedCategory = category;
    this.loadSkills();
  }

  // ── Selection & Detail View ────────────────────────────────────────────

  selectSkill(skill: SkillDefinition): void {
    this.selectedSkill = skill;
    this.isCreating = false;
    this.isEditMode = false;
    this.previewResult = '';
    this.previewArgs = '';
  }

  closeDetail(): void {
    this.selectedSkill = null;
    this.isCreating = false;
    this.isEditMode = false;
  }

  // ── Create ─────────────────────────────────────────────────────────────

  startCreate(): void {
    this.editForm = {
      name: '',
      displayName: '',
      description: '',
      category: 'custom',
      promptTemplate: 'Perform the task described below. {{args}}\n\nFollow these steps:\n1. Analyze the request\n2. Implement the solution\n3. Verify the result\n',
      allowedTools: ['*'],
      maxSteps: 0,
      modelHint: ''
    };
    this.isCreating = true;
    this.isEditMode = true;
    this.selectedSkill = null;
  }

  saveNewSkill(): void {
    if (!this.editForm.name || !this.editForm.name.match(/^[a-zA-Z][a-zA-Z0-9_-]*$/)) {
      this.errorMessage = 'Invalid name. Must start with a letter and contain only letters, digits, hyphens, or underscores.';
      return;
    }

    const skill: SkillDefinition = {
      name: this.editForm.name!,
      displayName: this.editForm.displayName || this.editForm.name!,
      description: this.editForm.description || '',
      category: this.editForm.category || 'custom',
      promptTemplate: this.editForm.promptTemplate || '',
      allowedTools: this.parseToolsList(this.editForm.allowedTools),
      maxSteps: this.editForm.maxSteps || 0,
      modelHint: this.editForm.modelHint || undefined
    };

    this.skillService.create(skill).subscribe({
      next: created => {
        this.showSuccess('Skill created: /' + created.name);
        this.isCreating = false;
        this.isEditMode = false;
        this.selectedSkill = created;
        this.loadSkills();
      },
      error: err => {
        this.errorMessage = err.error?.error || 'Failed to create skill';
      }
    });
  }

  // ── Edit ───────────────────────────────────────────────────────────────

  startEdit(): void {
    if (!this.selectedSkill) return;
    this.editForm = { ...this.selectedSkill };
    this.isEditMode = true;
  }

  cancelEdit(): void {
    this.isEditMode = false;
    if (this.isCreating) {
      this.isCreating = false;
    }
  }

  saveEdit(): void {
    if (!this.selectedSkill) return;

    const updates: Partial<SkillDefinition> = {
      displayName: this.editForm.displayName,
      description: this.editForm.description,
      category: this.editForm.category,
      promptTemplate: this.editForm.promptTemplate,
      allowedTools: this.parseToolsList(this.editForm.allowedTools),
      maxSteps: this.editForm.maxSteps || 0,
      modelHint: this.editForm.modelHint || undefined
    };

    this.skillService.update(this.selectedSkill.name, updates).subscribe({
      next: updated => {
        this.showSuccess('Skill updated: /' + updated.name);
        this.selectedSkill = updated;
        this.isEditMode = false;
        this.loadSkills();
      },
      error: err => {
        this.errorMessage = err.error?.error || 'Failed to update skill';
      }
    });
  }

  // ── Delete ─────────────────────────────────────────────────────────────

  deleteSkill(): void {
    if (!this.selectedSkill) return;
    if (!confirm(`Delete skill /${this.selectedSkill.name}? This cannot be undone.`)) return;

    this.skillService.delete(this.selectedSkill.name).subscribe({
      next: () => {
        this.showSuccess('Skill deleted: /' + this.selectedSkill!.name);
        this.selectedSkill = null;
        this.loadSkills();
      },
      error: err => {
        this.errorMessage = err.error?.error || 'Failed to delete skill';
      }
    });
  }

  // ── Preview ────────────────────────────────────────────────────────────

  expandPreview(): void {
    if (!this.selectedSkill) return;
    this.isPreviewLoading = true;
    this.skillService.expandTemplate(this.selectedSkill.name, this.previewArgs).subscribe({
      next: result => {
        this.previewResult = result.expanded;
        this.isPreviewLoading = false;
      },
      error: err => {
        this.previewResult = 'Error: ' + (err.error?.error || err.message);
        this.isPreviewLoading = false;
      }
    });
  }

  // ── Markdown View ──────────────────────────────────────────────────────

  toggleMarkdownView(): void {
    this.showMarkdownView = !this.showMarkdownView;
    if (this.showMarkdownView && !this.skillsMarkdown) {
      this.loadSkillsMarkdown();
    }
  }

  loadSkillsMarkdown(): void {
    this.skillService.getSkillsMarkdown(false).subscribe({
      next: result => this.skillsMarkdown = result.content,
      error: err => this.skillsMarkdown = 'Error loading: ' + err.message
    });
  }

  // ── Refresh ────────────────────────────────────────────────────────────

  refreshSkills(): void {
    this.skillService.refresh().subscribe({
      next: result => {
        this.showSuccess(result.message + ' (' + result.skillCount + ' skills)');
        this.loadSkills();
      },
      error: err => {
        this.errorMessage = 'Failed to refresh: ' + err.message;
      }
    });
  }

  // ── Helpers ────────────────────────────────────────────────────────────

  private parseToolsList(tools: any): string[] {
    if (!tools) return [];
    if (Array.isArray(tools)) return tools;
    if (typeof tools === 'string') {
      return tools.split(',').map((t: string) => t.trim()).filter((t: string) => t.length > 0);
    }
    return [];
  }

  getToolsDisplay(skill: SkillDefinition): string {
    if (!skill.allowedTools || skill.allowedTools.length === 0) return 'inherit';
    if (skill.allowedTools.includes('*')) return 'all';
    return skill.allowedTools.join(', ');
  }

  private showSuccess(msg: string): void {
    this.successMessage = msg;
    this.errorMessage = null;
    setTimeout(() => this.successMessage = null, 3000);
  }

  trackByName(index: number, skill: SkillDefinition): string {
    return skill.name;
  }

  trackByCategory(index: number, key: string): string {
    return key;
  }

  get editToolsString(): string {
    return this.editForm.allowedTools ? this.editForm.allowedTools.join(', ') : '';
  }

  onToolsStringChange(value: string): void {
    this.editForm.allowedTools = value.split(',')
      .map(t => t.trim())
      .filter(t => t.length > 0);
  }
}
