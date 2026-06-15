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
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError, Subject } from 'rxjs';

import { ToolManagerComponent } from './tool-manager.component';
import { ToolDefinitionService } from '../../services/tool-definition.service';
import { EnhancedToolDefinition, ToolsSummary } from '../../models/api-models';

function makeTool(overrides: Partial<EnhancedToolDefinition> = {}): EnhancedToolDefinition {
  return {
    name: 'test_tool',
    description: 'A test tool',
    category: 'rag',
    enabled: true,
    tags: ['rag', 'search'],
    usageHints: ['Use for searching'],
    relatedTools: [],
    examples: [],
    ...overrides
  };
}

function makeSummary(): ToolsSummary {
  return {
    totalTools: 5,
    enabledTools: 4,
    builtInTools: 3,
    customTools: 2,
    categoryCount: 2,
    categories: ['rag', 'filesystem']
  };
}

describe('ToolManagerComponent', () => {
  let component: ToolManagerComponent;
  let fixture: ComponentFixture<ToolManagerComponent>;
  let toolServiceSpy: jasmine.SpyObj<ToolDefinitionService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  beforeEach(async () => {
    toolServiceSpy = jasmine.createSpyObj('ToolDefinitionService', [
      'getToolsSummary',
      'getToolsGroupedByCategory',
      'searchTools',
      'getToolsByCategory',
      'refreshTools',
      'updateTool',
      'createTool',
      'deleteTool',
      'setToolEnabled',
      'getAgentToolsPrompt'
    ]);
    toolServiceSpy.getToolsSummary.and.returnValue(of(makeSummary()));
    toolServiceSpy.getToolsGroupedByCategory.and.returnValue(of({
      rag: [makeTool({ name: 'rag_query', category: 'rag' })],
      filesystem: [makeTool({ name: 'file_read', category: 'filesystem', enabled: false })]
    }));
    toolServiceSpy.searchTools.and.returnValue(of([makeTool()]));
    toolServiceSpy.getToolsByCategory.and.returnValue(of([makeTool()]));
    toolServiceSpy.refreshTools.and.returnValue(of({ message: 'Refreshed', toolCount: 5 }));
    toolServiceSpy.updateTool.and.returnValue(of(makeTool({ name: 'test_tool', description: 'Updated' })));
    toolServiceSpy.createTool.and.returnValue(of(makeTool({ name: 'new_tool' })));
    toolServiceSpy.deleteTool.and.returnValue(of(undefined as any));
    toolServiceSpy.setToolEnabled.and.returnValue(of(makeTool({ enabled: false })));
    toolServiceSpy.getAgentToolsPrompt.and.returnValue(of('You have access to the following tools...'));

    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);

    await TestBed.configureTestingModule({
      declarations: [ToolManagerComponent],
      imports: [NoopAnimationsModule, FormsModule],
      providers: [
        { provide: ToolDefinitionService, useValue: toolServiceSpy },
        { provide: MatDialog, useValue: dialogSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(ToolManagerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ── Creation ──────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ── loadTools ─────────────────────────────────────────────────────────────

  it('should load summary and tools on init', () => {
    expect(toolServiceSpy.getToolsSummary).toHaveBeenCalled();
    expect(toolServiceSpy.getToolsGroupedByCategory).toHaveBeenCalled();
    expect(component.summary).toEqual(makeSummary());
    expect(component.tools.length).toBe(2);
    expect(component.isLoading).toBeFalse();
  });

  it('should group tools by category', () => {
    expect(component.toolsByCategory['rag'].length).toBe(1);
    expect(component.toolsByCategory['filesystem'].length).toBe(1);
  });

  it('should set error on load failure', () => {
    toolServiceSpy.getToolsGroupedByCategory.and.returnValue(throwError(() => new Error('Load failed')));
    component.loadTools();
    expect(component.errorMessage).toContain('Failed to load tools');
    expect(component.isLoading).toBeFalse();
  });

  it('should not crash if summary fails', () => {
    toolServiceSpy.getToolsSummary.and.returnValue(throwError(() => new Error('Summary fail')));
    component.loadTools();
    // Tools should still load
    expect(component.tools.length).toBe(2);
  });

  // ── categoryKeys getter ───────────────────────────────────────────────────

  it('should return keys from toolsByCategory', () => {
    expect(component.categoryKeys).toContain('rag');
    expect(component.categoryKeys).toContain('filesystem');
  });

  // ── refreshTools ──────────────────────────────────────────────────────────

  it('should call refreshTools service and reload', () => {
    component.refreshTools();
    expect(toolServiceSpy.refreshTools).toHaveBeenCalled();
    expect(component.successMessage).toContain('Refreshed');
  });

  it('should set error on refresh failure', () => {
    toolServiceSpy.refreshTools.and.returnValue(throwError(() => new Error('Refresh failed')));
    component.refreshTools();
    expect(component.errorMessage).toContain('Failed to refresh tools');
  });

  // ── searchTools ───────────────────────────────────────────────────────────

  it('should call loadTools when searchQuery is empty', () => {
    component.searchQuery = '';
    spyOn(component, 'loadTools');
    component.searchTools();
    expect(component.loadTools).toHaveBeenCalled();
  });

  it('should call service searchTools with query', () => {
    component.searchQuery = 'rag';
    component.searchTools();
    expect(toolServiceSpy.searchTools).toHaveBeenCalledWith('rag');
    expect(component.tools.length).toBe(1);
    expect(component.isLoading).toBeFalse();
  });

  it('should set error on search failure', () => {
    component.searchQuery = 'rag';
    toolServiceSpy.searchTools.and.returnValue(throwError(() => new Error('Search failed')));
    component.searchTools();
    expect(component.errorMessage).toContain('Search failed');
    expect(component.isLoading).toBeFalse();
  });

  // ── filterByCategory ──────────────────────────────────────────────────────

  it('should call loadTools when category is null', () => {
    spyOn(component, 'loadTools');
    component.filterByCategory(null);
    expect(component.loadTools).toHaveBeenCalled();
    expect(component.selectedCategory).toBeNull();
  });

  it('should call getToolsByCategory with category key', () => {
    component.filterByCategory('rag');
    expect(toolServiceSpy.getToolsByCategory).toHaveBeenCalledWith('rag');
    expect(component.selectedCategory).toBe('rag');
    expect(component.tools.length).toBe(1);
  });

  it('should set error on filter failure', () => {
    toolServiceSpy.getToolsByCategory.and.returnValue(throwError(() => new Error('Filter failed')));
    component.filterByCategory('rag');
    expect(component.errorMessage).toContain('Failed to filter tools');
  });

  // ── selectTool / closeTool ────────────────────────────────────────────────

  it('should select a tool', () => {
    const tool = makeTool();
    component.selectTool(tool);
    expect(component.selectedTool).toBe(tool);
    expect(component.isEditMode).toBeFalse();
    expect(component.editForm).toEqual({});
  });

  it('should close tool and reset state', () => {
    component.selectedTool = makeTool();
    component.isEditMode = true;
    component.isCreating = true;
    component.closeTool();
    expect(component.selectedTool).toBeNull();
    expect(component.isEditMode).toBeFalse();
    expect(component.isCreating).toBeFalse();
    expect(component.editForm).toEqual({});
  });

  // ── startEdit / cancelEdit / saveEdit ─────────────────────────────────────

  it('should start edit and copy tool to editForm', () => {
    const tool = makeTool();
    component.selectedTool = tool;
    component.startEdit();
    expect(component.isEditMode).toBeTrue();
    expect(component.editForm).toEqual(jasmine.objectContaining({ name: 'test_tool' }));
  });

  it('should not start edit when no selectedTool', () => {
    component.selectedTool = null;
    component.startEdit();
    expect(component.isEditMode).toBeFalse();
  });

  it('should cancel edit', () => {
    component.isEditMode = true;
    component.editForm = { name: 'something' };
    component.cancelEdit();
    expect(component.isEditMode).toBeFalse();
    expect(component.editForm).toEqual({});
  });

  it('should save edit successfully', () => {
    const tool = makeTool();
    component.selectedTool = tool;
    component.editForm = { name: 'test_tool', description: 'Updated' };

    const updated = makeTool({ description: 'Updated' });
    toolServiceSpy.updateTool.and.returnValue(of(updated));

    component.saveEdit();

    expect(toolServiceSpy.updateTool).toHaveBeenCalledWith('test_tool', { name: 'test_tool', description: 'Updated' });
    expect(component.selectedTool).toBe(updated);
    expect(component.isEditMode).toBeFalse();
  });

  it('should not save when editForm.name is missing', () => {
    component.selectedTool = makeTool();
    component.editForm = {};
    component.saveEdit();
    expect(toolServiceSpy.updateTool).not.toHaveBeenCalled();
  });

  it('should set error on save failure', () => {
    component.selectedTool = makeTool();
    component.editForm = { name: 'test_tool' };
    toolServiceSpy.updateTool.and.returnValue(throwError(() => new Error('Save failed')));
    component.saveEdit();
    expect(component.errorMessage).toContain('Failed to update tool');
  });

  // ── startCreate / createTool ──────────────────────────────────────────────

  it('should initialize creation form on startCreate', () => {
    component.startCreate();
    expect(component.isCreating).toBeTrue();
    expect(component.selectedTool).toBeNull();
    expect(component.editForm.name).toBe('');
    expect(component.editForm.category).toBe('custom');
    expect(component.editForm.enabled).toBeTrue();
  });

  it('should create tool successfully', () => {
    const created = makeTool({ name: 'new_tool' });
    toolServiceSpy.createTool.and.returnValue(of(created));

    component.isCreating = true;
    component.editForm = { name: 'new_tool', description: 'New desc' };
    component.createTool();

    expect(toolServiceSpy.createTool).toHaveBeenCalledWith(component.editForm as EnhancedToolDefinition);
    expect(component.selectedTool).toBe(created);
    expect(component.isCreating).toBeFalse();
  });

  it('should set error when name is missing', () => {
    component.editForm = { description: 'desc' };
    component.createTool();
    expect(component.errorMessage).toBe('Name and description are required');
    expect(toolServiceSpy.createTool).not.toHaveBeenCalled();
  });

  it('should set error when description is missing', () => {
    component.editForm = { name: 'tool' };
    component.createTool();
    expect(component.errorMessage).toBe('Name and description are required');
  });

  it('should set error on create failure', () => {
    toolServiceSpy.createTool.and.returnValue(throwError(() => new Error('Create failed')));
    component.editForm = { name: 'new_tool', description: 'desc' };
    component.createTool();
    expect(component.errorMessage).toContain('Failed to create tool');
  });

  // ── deleteTool ────────────────────────────────────────────────────────────

  it('should open confirm dialog on deleteTool', () => {
    const afterClosedSubject = new Subject<boolean>();
    const mockRef = { afterClosed: () => afterClosedSubject.asObservable() } as unknown as MatDialogRef<any>;
    dialogSpy.open.and.returnValue(mockRef);

    component.deleteTool(makeTool());
    expect(dialogSpy.open).toHaveBeenCalled();
  });

  it('should delete tool when dialog confirmed', fakeAsync(() => {
    const afterClosedSubject = new Subject<boolean>();
    const mockRef = { afterClosed: () => afterClosedSubject.asObservable() } as unknown as MatDialogRef<any>;
    dialogSpy.open.and.returnValue(mockRef);
    toolServiceSpy.deleteTool.and.returnValue(of(undefined as any));

    const tool = makeTool();
    component.selectedTool = tool;
    component.deleteTool(tool);

    afterClosedSubject.next(true);
    afterClosedSubject.complete();
    tick();

    expect(toolServiceSpy.deleteTool).toHaveBeenCalledWith('test_tool');
  }));

  it('should not delete when dialog is cancelled', fakeAsync(() => {
    const afterClosedSubject = new Subject<boolean>();
    const mockRef = { afterClosed: () => afterClosedSubject.asObservable() } as unknown as MatDialogRef<any>;
    dialogSpy.open.and.returnValue(mockRef);

    component.deleteTool(makeTool());
    afterClosedSubject.next(false);
    afterClosedSubject.complete();
    tick();

    expect(toolServiceSpy.deleteTool).not.toHaveBeenCalled();
  }));

  // ── toggleToolEnabled ─────────────────────────────────────────────────────

  it('should toggle tool enabled state', () => {
    const tool = makeTool({ enabled: true });
    const updatedTool = makeTool({ enabled: false });
    toolServiceSpy.setToolEnabled.and.returnValue(of(updatedTool));

    component.toggleToolEnabled(tool);

    expect(toolServiceSpy.setToolEnabled).toHaveBeenCalledWith('test_tool', false);
    expect(tool.enabled).toBeFalse();
  });

  it('should set error on toggle failure', () => {
    const tool = makeTool({ enabled: true });
    toolServiceSpy.setToolEnabled.and.returnValue(throwError(() => new Error('Toggle failed')));
    component.toggleToolEnabled(tool);
    expect(component.errorMessage).toContain('Failed to toggle tool');
  });

  // ── loadAgentPrompt ───────────────────────────────────────────────────────

  it('should load agent prompt', () => {
    component.loadAgentPrompt();
    expect(toolServiceSpy.getAgentToolsPrompt).toHaveBeenCalled();
    expect(component.agentPrompt).toBe('You have access to the following tools...');
    expect(component.showAgentPrompt).toBeTrue();
  });

  it('should set error on agent prompt load failure', () => {
    toolServiceSpy.getAgentToolsPrompt.and.returnValue(throwError(() => new Error('Prompt failed')));
    component.loadAgentPrompt();
    expect(component.errorMessage).toContain('Failed to load agent prompt');
  });

  it('should close agent prompt', () => {
    component.showAgentPrompt = true;
    component.closeAgentPrompt();
    expect(component.showAgentPrompt).toBeFalse();
  });

  it('should copy agent prompt to clipboard', async () => {
    component.agentPrompt = 'My prompt text';
    const mockClipboard = { writeText: jasmine.createSpy('writeText').and.returnValue(Promise.resolve()) };
    Object.defineProperty(navigator, 'clipboard', { value: mockClipboard, configurable: true });

    await component.copyAgentPrompt();
    expect(mockClipboard.writeText).toHaveBeenCalledWith('My prompt text');
  });

  it('should not copy when agentPrompt is null', () => {
    component.agentPrompt = null;
    const mockClipboard = { writeText: jasmine.createSpy('writeText').and.returnValue(Promise.resolve()) };
    Object.defineProperty(navigator, 'clipboard', { value: mockClipboard, configurable: true });

    component.copyAgentPrompt();
    expect(mockClipboard.writeText).not.toHaveBeenCalled();
  });

  // ── addExample / removeExample ────────────────────────────────────────────

  it('should add an example to editForm', () => {
    component.editForm = {};
    component.addExample();
    expect(component.editForm.examples!.length).toBe(1);
    expect(component.editForm.examples![0]).toEqual({ title: '', description: '', input: {}, scenario: '' });
  });

  it('should remove example by index', () => {
    component.editForm = { examples: [{ title: 'e1', scenario: '' }, { title: 'e2', scenario: '' }] };
    component.removeExample(0);
    expect(component.editForm.examples!.length).toBe(1);
    expect(component.editForm.examples![0].title).toBe('e2');
  });

  // ── addUsageHint / removeUsageHint ────────────────────────────────────────

  it('should add a usage hint', () => {
    component.editForm = {};
    component.addUsageHint();
    expect(component.editForm.usageHints!.length).toBe(1);
    expect(component.editForm.usageHints![0]).toBe('');
  });

  it('should remove usage hint by index', () => {
    component.editForm = { usageHints: ['hint1', 'hint2'] };
    component.removeUsageHint(0);
    expect(component.editForm.usageHints!).toEqual(['hint2']);
  });

  // ── addTag / removeTag ────────────────────────────────────────────────────

  it('should add a tag', () => {
    component.editForm = {};
    component.addTag();
    expect(component.editForm.tags!.length).toBe(1);
    expect(component.editForm.tags![0]).toBe('');
  });

  it('should remove tag by index', () => {
    component.editForm = { tags: ['a', 'b', 'c'] };
    component.removeTag(1);
    expect(component.editForm.tags!).toEqual(['a', 'c']);
  });

  // ── getCategoryDisplayName / getCategoryDescription ───────────────────────

  it('should return displayName for known category', () => {
    const name = component.getCategoryDisplayName('rag');
    expect(name).toBe('RAG & Document Search');
  });

  it('should return key for unknown category', () => {
    expect(component.getCategoryDisplayName('unknown_cat')).toBe('unknown_cat');
  });

  it('should return description for known category', () => {
    const desc = component.getCategoryDescription('rag');
    expect(desc.length).toBeGreaterThan(0);
  });

  it('should return empty string for unknown category description', () => {
    expect(component.getCategoryDescription('unknown_cat')).toBe('');
  });

  // ── getSourceBadgeClass ───────────────────────────────────────────────────

  it('should return badge-builtin for BUILT_IN source', () => {
    expect(component.getSourceBadgeClass('BUILT_IN')).toBe('badge-builtin');
  });

  it('should return badge-custom for CUSTOM source', () => {
    expect(component.getSourceBadgeClass('CUSTOM')).toBe('badge-custom');
  });

  it('should return badge-mcp for MCP_SERVER source', () => {
    expect(component.getSourceBadgeClass('MCP_SERVER')).toBe('badge-mcp');
  });

  it('should return badge-bridge for REST_BRIDGE source', () => {
    expect(component.getSourceBadgeClass('REST_BRIDGE')).toBe('badge-bridge');
  });

  it('should return badge-default for unknown source', () => {
    expect(component.getSourceBadgeClass(undefined)).toBe('badge-default');
  });

  // ── trackByToolName / trackByCategory ────────────────────────────────────

  it('should track by tool name', () => {
    expect(component.trackByToolName(0, makeTool({ name: 'my_tool' }))).toBe('my_tool');
  });

  it('should track by category string', () => {
    expect(component.trackByCategory(0, 'rag')).toBe('rag');
  });

  // ── clearSearch ───────────────────────────────────────────────────────────

  it('should clear search and reload all tools', () => {
    component.searchQuery = 'something';
    component.selectedCategory = 'rag';
    spyOn(component, 'loadTools');
    component.clearSearch();
    expect(component.searchQuery).toBe('');
    expect(component.selectedCategory).toBeNull();
    expect(component.loadTools).toHaveBeenCalled();
  });

  // ── showSuccess timer ─────────────────────────────────────────────────────

  it('should clear successMessage after 3 seconds', fakeAsync(() => {
    component.refreshTools();
    expect(component.successMessage).toBe('Refreshed');
    tick(3000);
    expect(component.successMessage).toBeNull();
  }));

  // ── ngOnDestroy ───────────────────────────────────────────────────────────

  it('should complete destroy$ on destroy', () => {
    spyOn((component as any).destroy$, 'next').and.callThrough();
    spyOn((component as any).destroy$, 'complete').and.callThrough();
    fixture.destroy();
    expect((component as any).destroy$.next).toHaveBeenCalled();
    expect((component as any).destroy$.complete).toHaveBeenCalled();
  });
});
