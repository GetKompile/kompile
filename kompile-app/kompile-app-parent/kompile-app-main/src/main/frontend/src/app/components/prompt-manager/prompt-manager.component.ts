/*
 *   Copyright 2025 Kompile Inc.
 *  Licensed under the Apache License, Version 2.0
 */

import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subject } from 'rxjs';
import { takeUntil, debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { MatSnackBar } from '@angular/material/snack-bar';
import { SystemPromptService } from '../../services/system-prompt.service';
import {
  SystemPrompt,
  SystemPromptTestResult,
  CreatePromptRequest,
  UpdatePromptRequest,
  CreateVersionRequest,
  PromptTestStats,
  EvalSuiteSummary
} from '../../models/system-prompt.models';

@Component({
  standalone: false,
  selector: 'app-prompt-manager',
  templateUrl: './prompt-manager.component.html',
  styleUrls: ['./prompt-manager.component.css']
})
export class PromptManagerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // Data
  prompts: SystemPrompt[] = [];
  selectedPrompt: SystemPrompt | null = null;
  versionHistory: SystemPrompt[] = [];
  testResults: SystemPromptTestResult[] = [];
  testStats: PromptTestStats | null = null;
  availableSuites: EvalSuiteSummary[] = [];

  // UI state
  loading = false;
  saving = false;
  searchQuery = '';
  activeTab: 'editor' | 'versions' | 'testing' = 'editor';
  isEditing = false;
  isCreating = false;

  // Editor state
  editedName = '';
  editedDescription = '';
  editedContent = '';
  editedTags: string[] = [];
  newTag = '';
  changeNotes = '';

  // Search subject for debouncing
  private searchSubject = new Subject<string>();

  constructor(
    private promptService: SystemPromptService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadPrompts();

    // Setup search debounce
    this.searchSubject.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      takeUntil(this.destroy$)
    ).subscribe(query => {
      if (query.trim()) {
        this.searchPrompts(query);
      } else {
        this.loadPrompts();
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ==================== Data Loading ====================

  loadPrompts(): void {
    this.loading = true;
    this.promptService.listPrompts().pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (prompts) => {
        this.prompts = prompts;
        this.loading = false;
      },
      error: (error) => {
        this.showError('Failed to load prompts');
        this.loading = false;
      }
    });
  }

  searchPrompts(query: string): void {
    this.loading = true;
    this.promptService.searchPrompts(query).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (prompts) => {
        this.prompts = prompts;
        this.loading = false;
      },
      error: (error) => {
        this.showError('Search failed');
        this.loading = false;
      }
    });
  }

  onSearchChange(query: string): void {
    this.searchQuery = query;
    this.searchSubject.next(query);
  }

  loadVersionHistory(promptId: string): void {
    this.promptService.getVersionHistory(promptId).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (versions) => {
        this.versionHistory = versions;
      },
      error: (error) => {
        this.showError('Failed to load version history');
      }
    });
  }

  loadTestResults(promptId: string): void {
    this.promptService.getTestResults(promptId).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (results) => {
        this.testResults = results;
      },
      error: (error) => {
        this.showError('Failed to load test results');
      }
    });
  }

  loadTestStats(promptId: string): void {
    this.promptService.getTestStats(promptId).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (stats) => {
        this.testStats = stats;
      },
      error: (error) => {
        console.error('Failed to load test stats:', error);
      }
    });
  }

  loadAvailableSuites(promptId: string): void {
    this.promptService.getAvailableEvalSuites(promptId).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (suites) => {
        this.availableSuites = suites;
      },
      error: (error) => {
        console.error('Failed to load eval suites:', error);
      }
    });
  }

  // ==================== Prompt Selection ====================

  selectPrompt(prompt: SystemPrompt): void {
    this.selectedPrompt = prompt;
    this.isEditing = false;
    this.isCreating = false;
    this.activeTab = 'editor';
    this.resetEditor();

    // Load related data
    this.loadVersionHistory(prompt.id);
    this.loadTestResults(prompt.id);
    this.loadTestStats(prompt.id);
    this.loadAvailableSuites(prompt.id);
  }

  resetEditor(): void {
    if (this.selectedPrompt) {
      this.editedName = this.selectedPrompt.name;
      this.editedDescription = this.selectedPrompt.description || '';
      this.editedContent = this.selectedPrompt.content;
      this.editedTags = this.promptService.parseTags(this.selectedPrompt.tagsJson);
    } else {
      this.editedName = '';
      this.editedDescription = '';
      this.editedContent = '';
      this.editedTags = [];
    }
    this.changeNotes = '';
    this.newTag = '';
  }

  // ==================== CRUD Operations ====================

  startCreating(): void {
    this.selectedPrompt = null;
    this.isCreating = true;
    this.isEditing = true;
    this.activeTab = 'editor';
    this.editedName = 'New Prompt';
    this.editedDescription = '';
    this.editedContent = 'You are a helpful assistant.\n\n';
    this.editedTags = [];
    this.changeNotes = '';
    this.versionHistory = [];
    this.testResults = [];
    this.testStats = null;
  }

  startEditing(): void {
    this.isEditing = true;
  }

  cancelEditing(): void {
    this.isEditing = false;
    if (this.isCreating) {
      this.isCreating = false;
      if (this.prompts.length > 0) {
        this.selectPrompt(this.prompts[0]);
      }
    } else {
      this.resetEditor();
    }
  }

  savePrompt(): void {
    if (!this.editedName.trim()) {
      this.showError('Name is required');
      return;
    }
    if (!this.editedContent.trim()) {
      this.showError('Content is required');
      return;
    }

    this.saving = true;

    if (this.isCreating) {
      const request: CreatePromptRequest = {
        name: this.editedName,
        description: this.editedDescription,
        content: this.editedContent,
        tagsJson: this.promptService.stringifyTags(this.editedTags)
      };

      this.promptService.createPrompt(request).pipe(
        takeUntil(this.destroy$)
      ).subscribe({
        next: (created) => {
          this.prompts.unshift(created);
          this.selectPrompt(created);
          this.isCreating = false;
          this.isEditing = false;
          this.saving = false;
          this.showSuccess('Prompt created');
        },
        error: (error) => {
          this.showError('Failed to create prompt');
          this.saving = false;
        }
      });
    } else if (this.selectedPrompt) {
      const request: UpdatePromptRequest = {
        name: this.editedName,
        description: this.editedDescription,
        content: this.editedContent,
        tagsJson: this.promptService.stringifyTags(this.editedTags),
        changeNotes: this.changeNotes
      };

      this.promptService.updatePrompt(this.selectedPrompt.id, request).pipe(
        takeUntil(this.destroy$)
      ).subscribe({
        next: (updated) => {
          const index = this.prompts.findIndex(p => p.id === updated.id);
          if (index >= 0) {
            this.prompts[index] = updated;
          }
          this.selectedPrompt = updated;
          this.isEditing = false;
          this.saving = false;
          this.showSuccess('Prompt updated');
        },
        error: (error) => {
          this.showError('Failed to update prompt');
          this.saving = false;
        }
      });
    }
  }

  deletePrompt(prompt: SystemPrompt): void {
    if (!confirm(`Delete "${prompt.name}"? This cannot be undone.`)) {
      return;
    }

    this.promptService.deletePrompt(prompt.id).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: () => {
        this.prompts = this.prompts.filter(p => p.id !== prompt.id);
        if (this.selectedPrompt?.id === prompt.id) {
          this.selectedPrompt = null;
          if (this.prompts.length > 0) {
            this.selectPrompt(this.prompts[0]);
          }
        }
        this.showSuccess('Prompt deleted');
      },
      error: (error) => {
        this.showError('Failed to delete prompt');
      }
    });
  }

  // ==================== Versioning ====================

  createNewVersion(): void {
    if (!this.selectedPrompt) return;

    const changeNotes = prompt('Enter change notes for this version:', '');
    if (changeNotes === null) return;

    const request: CreateVersionRequest = {
      content: this.editedContent,
      changeNotes: changeNotes
    };

    this.saving = true;
    this.promptService.createVersion(this.selectedPrompt.id, request).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (newVersion) => {
        this.prompts.unshift(newVersion);
        this.selectPrompt(newVersion);
        this.saving = false;
        this.showSuccess('New version created');
      },
      error: (error) => {
        this.showError('Failed to create new version');
        this.saving = false;
      }
    });
  }

  activateVersion(prompt: SystemPrompt): void {
    this.promptService.activatePrompt(prompt.id).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (activated) => {
        // Update all prompts to reflect the new active state
        this.prompts.forEach(p => {
          p.isActive = p.id === activated.id;
        });
        this.versionHistory.forEach(p => {
          p.isActive = p.id === activated.id;
        });
        this.selectedPrompt = activated;
        this.showSuccess('Version activated');
      },
      error: (error) => {
        this.showError('Failed to activate version');
      }
    });
  }

  // ==================== Tags ====================

  addTag(): void {
    const tag = this.newTag.trim();
    if (tag && !this.editedTags.includes(tag)) {
      this.editedTags.push(tag);
      this.newTag = '';
    }
  }

  removeTag(tag: string): void {
    this.editedTags = this.editedTags.filter(t => t !== tag);
  }

  // ==================== Utilities ====================

  formatDate(dateString: string): string {
    if (!dateString) return '';
    return new Date(dateString).toLocaleString();
  }

  formatScore(score: number | undefined | null): string {
    if (score === undefined || score === null) return 'N/A';
    return (score * 100).toFixed(1) + '%';
  }

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 3000,
      panelClass: ['success-snackbar']
    });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 5000,
      panelClass: ['error-snackbar']
    });
  }
}
