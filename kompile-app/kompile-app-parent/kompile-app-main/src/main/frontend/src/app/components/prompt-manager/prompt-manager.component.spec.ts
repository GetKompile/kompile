/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { PromptManagerComponent } from './prompt-manager.component';
import { SystemPromptService } from '../../services/system-prompt.service';
import {
  SystemPrompt,
  SystemPromptTestResult,
  PromptTestStats,
  EvalSuiteSummary
} from '../../models/system-prompt.models';

function makePrompt(overrides: Partial<SystemPrompt> = {}): SystemPrompt {
  return {
    id: 'prompt-1',
    name: 'Test Prompt',
    description: 'A test prompt',
    content: 'You are a helpful assistant.',
    factSheetId: 1,
    version: 1,
    isActive: true,
    tagsJson: '["ai","test"]',
    createdAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-01-01T00:00:00Z',
    ...overrides
  };
}

function makeTestResult(): SystemPromptTestResult {
  return {
    id: 'result-1',
    promptId: 'prompt-1',
    evalSuiteId: 'suite-1',
    passed: true,
    score: 0.85,
    passedCount: 8,
    failedCount: 2,
    totalCount: 10,
    startedAt: '2025-01-01T10:00:00Z'
  };
}

function makeStats(): PromptTestStats {
  return {
    totalTests: 5,
    passedTests: 4,
    failedTests: 1,
    passRate: 0.8,
    averageScore: 0.75
  };
}

function makeSuite(): EvalSuiteSummary {
  return {
    id: 'suite-1',
    name: 'Test Suite',
    factSheetId: 1,
    enabled: true,
    testCaseCount: 10
  };
}

describe('PromptManagerComponent', () => {
  let component: PromptManagerComponent;
  let fixture: ComponentFixture<PromptManagerComponent>;
  let promptServiceSpy: jasmine.SpyObj<SystemPromptService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    promptServiceSpy = jasmine.createSpyObj('SystemPromptService', [
      'listPrompts',
      'searchPrompts',
      'createPrompt',
      'updatePrompt',
      'deletePrompt',
      'getVersionHistory',
      'getTestResults',
      'getTestStats',
      'getAvailableEvalSuites',
      'createVersion',
      'activatePrompt',
      'parseTags',
      'stringifyTags'
    ]);
    promptServiceSpy.listPrompts.and.returnValue(of([makePrompt(), makePrompt({ id: 'prompt-2', name: 'Second Prompt' })]));
    promptServiceSpy.searchPrompts.and.returnValue(of([makePrompt()]));
    promptServiceSpy.createPrompt.and.returnValue(of(makePrompt({ id: 'new-prompt' })));
    promptServiceSpy.updatePrompt.and.returnValue(of(makePrompt({ name: 'Updated Prompt' })));
    promptServiceSpy.deletePrompt.and.returnValue(of(undefined as any));
    promptServiceSpy.getVersionHistory.and.returnValue(of([makePrompt(), makePrompt({ id: 'v2', version: 2 })]));
    promptServiceSpy.getTestResults.and.returnValue(of([makeTestResult()]));
    promptServiceSpy.getTestStats.and.returnValue(of(makeStats()));
    promptServiceSpy.getAvailableEvalSuites.and.returnValue(of([makeSuite()]));
    promptServiceSpy.createVersion.and.returnValue(of(makePrompt({ id: 'v3', version: 3 })));
    promptServiceSpy.activatePrompt.and.returnValue(of(makePrompt({ isActive: true })));
    promptServiceSpy.parseTags.and.callFake((json: string | null | undefined) => {
      if (!json) return [];
      try { return JSON.parse(json); } catch { return []; }
    });
    promptServiceSpy.stringifyTags.and.callFake((tags: string[]) => JSON.stringify(tags));

    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      declarations: [PromptManagerComponent],
      imports: [NoopAnimationsModule, FormsModule],
      providers: [
        { provide: SystemPromptService, useValue: promptServiceSpy },
        { provide: MatSnackBar, useValue: snackBarSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(PromptManagerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ── Creation ──────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ── loadPrompts ───────────────────────────────────────────────────────────

  it('should call listPrompts on init', () => {
    expect(promptServiceSpy.listPrompts).toHaveBeenCalled();
  });

  it('should populate prompts list', () => {
    expect(component.prompts.length).toBe(2);
    expect(component.loading).toBeFalse();
  });

  it('should show snackbar on load failure', () => {
    promptServiceSpy.listPrompts.and.returnValue(throwError(() => new Error('fail')));
    component.loadPrompts();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load prompts', 'Close', jasmine.anything());
    expect(component.loading).toBeFalse();
  });

  // ── searchPrompts ─────────────────────────────────────────────────────────

  it('should search prompts by query', () => {
    component.searchPrompts('assistant');
    expect(promptServiceSpy.searchPrompts).toHaveBeenCalledWith('assistant');
    expect(component.prompts.length).toBe(1);
  });

  it('should show snackbar on search failure', () => {
    promptServiceSpy.searchPrompts.and.returnValue(throwError(() => new Error('fail')));
    component.searchPrompts('query');
    expect(snackBarSpy.open).toHaveBeenCalledWith('Search failed', 'Close', jasmine.anything());
    expect(component.loading).toBeFalse();
  });

  // ── onSearchChange with debounce ──────────────────────────────────────────

  it('should call searchPrompts after debounce with non-empty query', fakeAsync(() => {
    component.onSearchChange('test query');
    tick(300);
    expect(promptServiceSpy.searchPrompts).toHaveBeenCalledWith('test query');
  }));

  it('should call loadPrompts after debounce with empty query', fakeAsync(() => {
    spyOn(component, 'loadPrompts').and.callThrough();
    component.onSearchChange('');
    tick(300);
    expect(component.loadPrompts).toHaveBeenCalled();
  }));

  it('should debounce multiple rapid changes', fakeAsync(() => {
    component.onSearchChange('a');
    component.onSearchChange('ab');
    component.onSearchChange('abc');
    tick(300);
    // Only one call with the last value
    expect(promptServiceSpy.searchPrompts).toHaveBeenCalledTimes(1);
    expect(promptServiceSpy.searchPrompts).toHaveBeenCalledWith('abc');
  }));

  // ── selectPrompt ──────────────────────────────────────────────────────────

  it('should select a prompt and load related data', () => {
    const prompt = makePrompt();
    component.selectPrompt(prompt);

    expect(component.selectedPrompt).toBe(prompt);
    expect(component.isEditing).toBeFalse();
    expect(component.isCreating).toBeFalse();
    expect(component.activeTab).toBe('editor');
    expect(promptServiceSpy.getVersionHistory).toHaveBeenCalledWith('prompt-1');
    expect(promptServiceSpy.getTestResults).toHaveBeenCalledWith('prompt-1');
    expect(promptServiceSpy.getTestStats).toHaveBeenCalledWith('prompt-1');
    expect(promptServiceSpy.getAvailableEvalSuites).toHaveBeenCalledWith('prompt-1');
  });

  it('should populate version history', () => {
    component.selectPrompt(makePrompt());
    expect(component.versionHistory.length).toBe(2);
  });

  it('should populate test results', () => {
    component.selectPrompt(makePrompt());
    expect(component.testResults.length).toBe(1);
  });

  it('should populate test stats', () => {
    component.selectPrompt(makePrompt());
    expect(component.testStats).toEqual(makeStats());
  });

  it('should populate available suites', () => {
    component.selectPrompt(makePrompt());
    expect(component.availableSuites.length).toBe(1);
  });

  it('should show error on version history load failure', () => {
    promptServiceSpy.getVersionHistory.and.returnValue(throwError(() => new Error('fail')));
    component.selectPrompt(makePrompt());
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load version history', 'Close', jasmine.anything());
  });

  it('should show error on test results load failure', () => {
    promptServiceSpy.getTestResults.and.returnValue(throwError(() => new Error('fail')));
    component.selectPrompt(makePrompt());
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load test results', 'Close', jasmine.anything());
  });

  // ── resetEditor ───────────────────────────────────────────────────────────

  it('should populate editor fields from selectedPrompt', () => {
    component.selectedPrompt = makePrompt({ name: 'My Prompt', content: 'Content here', tagsJson: '["a","b"]' });
    component.resetEditor();

    expect(component.editedName).toBe('My Prompt');
    expect(component.editedContent).toBe('Content here');
    expect(component.editedTags).toEqual(['a', 'b']);
    expect(component.changeNotes).toBe('');
    expect(component.newTag).toBe('');
  });

  it('should clear editor when no selectedPrompt', () => {
    component.selectedPrompt = null;
    component.resetEditor();

    expect(component.editedName).toBe('');
    expect(component.editedContent).toBe('');
    expect(component.editedTags).toEqual([]);
  });

  // ── startCreating / startEditing / cancelEditing ──────────────────────────

  it('should set up creation state on startCreating', () => {
    component.startCreating();
    expect(component.selectedPrompt).toBeNull();
    expect(component.isCreating).toBeTrue();
    expect(component.isEditing).toBeTrue();
    expect(component.editedName).toBe('New Prompt');
    expect(component.editedContent).toContain('You are a helpful assistant');
    expect(component.versionHistory).toEqual([]);
    expect(component.testResults).toEqual([]);
    expect(component.testStats).toBeNull();
  });

  it('should set isEditing on startEditing', () => {
    component.isEditing = false;
    component.startEditing();
    expect(component.isEditing).toBeTrue();
  });

  it('should cancel creating and select first prompt', () => {
    component.prompts = [makePrompt()];
    component.isCreating = true;
    component.isEditing = true;
    spyOn(component, 'selectPrompt').and.callThrough();

    component.cancelEditing();

    expect(component.isEditing).toBeFalse();
    expect(component.isCreating).toBeFalse();
    expect(component.selectPrompt).toHaveBeenCalledWith(component.prompts[0]);
  });

  it('should cancel editing and reset editor', () => {
    component.isCreating = false;
    component.isEditing = true;
    component.selectedPrompt = makePrompt();
    spyOn(component, 'resetEditor').and.callThrough();

    component.cancelEditing();

    expect(component.isEditing).toBeFalse();
    expect(component.resetEditor).toHaveBeenCalled();
  });

  // ── savePrompt ────────────────────────────────────────────────────────────

  it('should show error when name is empty', () => {
    component.editedName = '';
    component.editedContent = 'content';
    component.savePrompt();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Name is required', 'Close', jasmine.anything());
    expect(promptServiceSpy.createPrompt).not.toHaveBeenCalled();
  });

  it('should show error when content is empty', () => {
    component.editedName = 'My Prompt';
    component.editedContent = '   ';
    component.savePrompt();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Content is required', 'Close', jasmine.anything());
  });

  it('should create prompt when isCreating is true', () => {
    const created = makePrompt({ id: 'new-prompt', name: 'New' });
    promptServiceSpy.createPrompt.and.returnValue(of(created));

    component.isCreating = true;
    component.isEditing = true;
    component.editedName = 'New';
    component.editedContent = 'content';
    component.editedDescription = 'desc';
    component.editedTags = ['tag1'];

    component.savePrompt();

    expect(promptServiceSpy.createPrompt).toHaveBeenCalledWith(jasmine.objectContaining({
      name: 'New',
      content: 'content'
    }));
    expect(component.prompts[0]).toBe(created);
    expect(component.isCreating).toBeFalse();
    expect(component.isEditing).toBeFalse();
    expect(component.saving).toBeFalse();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Prompt created', 'Close', jasmine.anything());
  });

  it('should show error on create failure', () => {
    promptServiceSpy.createPrompt.and.returnValue(throwError(() => new Error('fail')));
    component.isCreating = true;
    component.editedName = 'name';
    component.editedContent = 'content';
    component.savePrompt();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to create prompt', 'Close', jasmine.anything());
    expect(component.saving).toBeFalse();
  });

  it('should update prompt when isCreating is false', () => {
    const updated = makePrompt({ name: 'Updated' });
    promptServiceSpy.updatePrompt.and.returnValue(of(updated));

    component.isCreating = false;
    component.selectedPrompt = makePrompt({ id: 'prompt-1' });
    component.prompts = [makePrompt({ id: 'prompt-1' })];
    component.editedName = 'Updated';
    component.editedContent = 'new content';
    component.editedDescription = 'desc';
    component.editedTags = [];
    component.changeNotes = 'Minor update';

    component.savePrompt();

    expect(promptServiceSpy.updatePrompt).toHaveBeenCalledWith('prompt-1', jasmine.objectContaining({
      name: 'Updated',
      content: 'new content',
      changeNotes: 'Minor update'
    }));
    expect(component.selectedPrompt).toBe(updated);
    expect(component.isEditing).toBeFalse();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Prompt updated', 'Close', jasmine.anything());
  });

  it('should show error on update failure', () => {
    promptServiceSpy.updatePrompt.and.returnValue(throwError(() => new Error('fail')));
    component.isCreating = false;
    component.selectedPrompt = makePrompt();
    component.editedName = 'name';
    component.editedContent = 'content';
    component.savePrompt();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to update prompt', 'Close', jasmine.anything());
    expect(component.saving).toBeFalse();
  });

  // ── deletePrompt ──────────────────────────────────────────────────────────

  it('should delete prompt when confirmed', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    component.prompts = [makePrompt({ id: 'p1' }), makePrompt({ id: 'p2' })];
    component.selectedPrompt = makePrompt({ id: 'p1' });

    component.deletePrompt(component.selectedPrompt);

    expect(promptServiceSpy.deletePrompt).toHaveBeenCalledWith('p1');
    expect(component.prompts.some(p => p.id === 'p1')).toBeFalse();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Prompt deleted', 'Close', jasmine.anything());
  });

  it('should not delete when not confirmed', () => {
    spyOn(window, 'confirm').and.returnValue(false);
    component.deletePrompt(makePrompt());
    expect(promptServiceSpy.deletePrompt).not.toHaveBeenCalled();
  });

  it('should show error on delete failure', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    promptServiceSpy.deletePrompt.and.returnValue(throwError(() => new Error('fail')));
    component.deletePrompt(makePrompt());
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to delete prompt', 'Close', jasmine.anything());
  });

  it('should select next prompt after deleting selected', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    const p1 = makePrompt({ id: 'p1' });
    const p2 = makePrompt({ id: 'p2', name: 'Second' });
    component.prompts = [p1, p2];
    component.selectedPrompt = p1;
    spyOn(component, 'selectPrompt').and.callThrough();

    component.deletePrompt(p1);

    expect(component.selectPrompt).toHaveBeenCalled();
  });

  // ── createNewVersion ──────────────────────────────────────────────────────

  it('should not create version when no selectedPrompt', () => {
    component.selectedPrompt = null;
    component.createNewVersion();
    expect(promptServiceSpy.createVersion).not.toHaveBeenCalled();
  });

  it('should create new version', () => {
    spyOn(window, 'prompt').and.returnValue('version notes');
    component.selectedPrompt = makePrompt();
    component.editedContent = 'updated content';

    const newVersion = makePrompt({ id: 'v3', version: 3 });
    promptServiceSpy.createVersion.and.returnValue(of(newVersion));

    component.createNewVersion();

    expect(promptServiceSpy.createVersion).toHaveBeenCalledWith('prompt-1', {
      content: 'updated content',
      changeNotes: 'version notes'
    });
    expect(snackBarSpy.open).toHaveBeenCalledWith('New version created', 'Close', jasmine.anything());
    expect(component.saving).toBeFalse();
  });

  it('should not create version when prompt dialog is cancelled', () => {
    spyOn(window, 'prompt').and.returnValue(null);
    component.selectedPrompt = makePrompt();
    component.createNewVersion();
    expect(promptServiceSpy.createVersion).not.toHaveBeenCalled();
  });

  it('should show error on createVersion failure', () => {
    spyOn(window, 'prompt').and.returnValue('notes');
    promptServiceSpy.createVersion.and.returnValue(throwError(() => new Error('fail')));
    component.selectedPrompt = makePrompt();
    component.createNewVersion();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to create new version', 'Close', jasmine.anything());
    expect(component.saving).toBeFalse();
  });

  // ── activateVersion ───────────────────────────────────────────────────────

  it('should activate version and update isActive flags', () => {
    const v1 = makePrompt({ id: 'v1', isActive: true });
    const v2 = makePrompt({ id: 'v2', isActive: false, version: 2 });
    const activated = makePrompt({ id: 'v2', isActive: true, version: 2 });
    promptServiceSpy.activatePrompt.and.returnValue(of(activated));

    component.prompts = [v1, v2];
    component.versionHistory = [v1, v2];
    component.activateVersion(v2);

    expect(promptServiceSpy.activatePrompt).toHaveBeenCalledWith('v2');
    expect(component.selectedPrompt).toBe(activated);
    expect(snackBarSpy.open).toHaveBeenCalledWith('Version activated', 'Close', jasmine.anything());
  });

  it('should show error on activate failure', () => {
    promptServiceSpy.activatePrompt.and.returnValue(throwError(() => new Error('fail')));
    component.activateVersion(makePrompt());
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to activate version', 'Close', jasmine.anything());
  });

  // ── addTag / removeTag ────────────────────────────────────────────────────

  it('should add a tag to editedTags', () => {
    component.editedTags = ['existing'];
    component.newTag = 'newTag';
    component.addTag();
    expect(component.editedTags).toContain('newTag');
    expect(component.newTag).toBe('');
  });

  it('should not add duplicate tag', () => {
    component.editedTags = ['tag1'];
    component.newTag = 'tag1';
    component.addTag();
    expect(component.editedTags.length).toBe(1);
  });

  it('should not add empty tag', () => {
    component.editedTags = [];
    component.newTag = '   ';
    component.addTag();
    expect(component.editedTags.length).toBe(0);
  });

  it('should remove a tag', () => {
    component.editedTags = ['a', 'b', 'c'];
    component.removeTag('b');
    expect(component.editedTags).toEqual(['a', 'c']);
  });

  // ── formatDate ────────────────────────────────────────────────────────────

  it('should return empty string for empty date', () => {
    expect(component.formatDate('')).toBe('');
  });

  it('should format date string as locale string', () => {
    const result = component.formatDate('2025-01-15T10:00:00Z');
    expect(typeof result).toBe('string');
    expect(result.length).toBeGreaterThan(0);
  });

  // ── formatScore ───────────────────────────────────────────────────────────

  it('should return N/A for undefined score', () => {
    expect(component.formatScore(undefined)).toBe('N/A');
  });

  it('should return N/A for null score', () => {
    expect(component.formatScore(null)).toBe('N/A');
  });

  it('should format score as percentage with 1 decimal', () => {
    expect(component.formatScore(0.856)).toBe('85.6%');
  });

  it('should format zero score', () => {
    expect(component.formatScore(0)).toBe('0.0%');
  });

  it('should format perfect score', () => {
    expect(component.formatScore(1)).toBe('100.0%');
  });

  // ── ngOnDestroy ───────────────────────────────────────────────────────────

  it('should complete destroy$ on destroy', () => {
    spyOn((component as any).destroy$, 'next').and.callThrough();
    spyOn((component as any).destroy$, 'complete').and.callThrough();
    fixture.destroy();
    expect((component as any).destroy$.next).toHaveBeenCalled();
    expect((component as any).destroy$.complete).toHaveBeenCalled();
  });
});
