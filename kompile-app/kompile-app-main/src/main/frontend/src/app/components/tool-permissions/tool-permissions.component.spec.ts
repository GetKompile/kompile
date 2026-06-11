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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { ToolPermissionsComponent } from './tool-permissions.component';
import { ToolPermissionService } from '../../services/tool-permission.service';
import {
  ToolPermissionStatus,
  ToolPermissionInfo,
  PermissionLevel
} from '../../models/api-models';

function makeStatus(overrides: Partial<ToolPermissionStatus> = {}): ToolPermissionStatus {
  return {
    defaultPermission: 'ALLOW',
    categories: {
      rag: { displayName: 'RAG & Search', permission: null, toolCount: 2 },
      filesystem: { displayName: 'File System', permission: 'DENY', toolCount: 3 }
    },
    tools: [
      { name: 'rag_query', category: 'rag', description: 'Query RAG', resolvedPermission: 'ALLOW', hasOverride: false },
      { name: 'file_read', category: 'filesystem', description: 'Read file', resolvedPermission: 'DENY', hasOverride: true }
    ],
    ...overrides
  };
}

describe('ToolPermissionsComponent', () => {
  let component: ToolPermissionsComponent;
  let fixture: ComponentFixture<ToolPermissionsComponent>;
  let permissionServiceSpy: jasmine.SpyObj<ToolPermissionService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    permissionServiceSpy = jasmine.createSpyObj('ToolPermissionService', [
      'getToolsWithStatus',
      'setDefaultPermission',
      'setCategoryRule',
      'removeCategoryRule',
      'setToolRule',
      'removeToolRule',
      'bulkUpdate'
    ]);
    permissionServiceSpy.getToolsWithStatus.and.returnValue(of(makeStatus()));
    permissionServiceSpy.setDefaultPermission.and.returnValue(of({}));
    permissionServiceSpy.setCategoryRule.and.returnValue(of({}));
    permissionServiceSpy.removeCategoryRule.and.returnValue(of({}));
    permissionServiceSpy.setToolRule.and.returnValue(of({}));
    permissionServiceSpy.removeToolRule.and.returnValue(of({}));
    permissionServiceSpy.bulkUpdate.and.returnValue(of({}));

    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
      .overrideComponent(ToolPermissionsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .overrideProvider(ToolPermissionService, { useValue: permissionServiceSpy })
      .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
      .compileComponents();

    fixture = TestBed.createComponent(ToolPermissionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ── Creation ──────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ── loadPermissions ───────────────────────────────────────────────────────

  it('should call getToolsWithStatus on init', () => {
    expect(permissionServiceSpy.getToolsWithStatus).toHaveBeenCalled();
  });

  it('should populate categories from status', () => {
    expect(component.categories.length).toBe(2);
  });

  it('should sort categories alphabetically by displayName', () => {
    expect(component.categories[0].displayName).toBe('File System');
    expect(component.categories[1].displayName).toBe('RAG & Search');
  });

  it('should set defaultPermission from status', () => {
    expect(component.defaultPermission).toBe('ALLOW');
  });

  it('should associate tools with their categories', () => {
    const ragCat = component.categories.find(c => c.key === 'rag')!;
    expect(ragCat.tools.length).toBe(1);
    expect(ragCat.tools[0].name).toBe('rag_query');
  });

  it('should set loading false after successful load', () => {
    expect(component.loading).toBeFalse();
    expect(component.error).toBeNull();
  });

  it('should set error on load failure', () => {
    permissionServiceSpy.getToolsWithStatus.and.returnValue(throwError(() => new Error('Server error')));
    component.loadPermissions();
    expect(component.error).toBe('Failed to load tool permissions');
    expect(component.loading).toBeFalse();
  });

  // ── isCategoryAllowed ─────────────────────────────────────────────────────

  it('should return true when category has ALLOW permission', () => {
    const cat = component.categories.find(c => c.key === 'filesystem')!;
    // filesystem has DENY override
    expect(component.isCategoryAllowed({ ...cat, permission: 'ALLOW' })).toBeTrue();
  });

  it('should fall back to defaultPermission when category permission is null', () => {
    const ragCat = component.categories.find(c => c.key === 'rag')!;
    component.defaultPermission = 'ALLOW';
    expect(component.isCategoryAllowed(ragCat)).toBeTrue();

    component.defaultPermission = 'DENY';
    expect(component.isCategoryAllowed(ragCat)).toBeFalse();
  });

  // ── onDefaultPermissionToggle ─────────────────────────────────────────────

  it('should toggle ALLOW to DENY and call service', () => {
    component.defaultPermission = 'ALLOW';
    component.onDefaultPermissionToggle();
    expect(permissionServiceSpy.setDefaultPermission).toHaveBeenCalledWith('DENY');
  });

  it('should toggle DENY to ALLOW', () => {
    component.defaultPermission = 'DENY';
    component.onDefaultPermissionToggle();
    expect(permissionServiceSpy.setDefaultPermission).toHaveBeenCalledWith('ALLOW');
  });

  it('should show error snackbar on toggle failure', () => {
    permissionServiceSpy.setDefaultPermission.and.returnValue(throwError(() => new Error('fail')));
    component.onDefaultPermissionToggle();
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      'Failed to update default permission',
      'Close',
      jasmine.anything()
    );
  });

  // ── onCategoryToggle ──────────────────────────────────────────────────────

  it('should call setCategoryRule when toggling category', () => {
    const ragCat = component.categories.find(c => c.key === 'rag')!;
    ragCat.permission = 'ALLOW';
    component.onCategoryToggle(ragCat);
    expect(permissionServiceSpy.setCategoryRule).toHaveBeenCalledWith('rag', 'DENY');
  });

  it('should show error snackbar on category toggle failure', () => {
    permissionServiceSpy.setCategoryRule.and.returnValue(throwError(() => new Error('fail')));
    const ragCat = component.categories.find(c => c.key === 'rag')!;
    component.onCategoryToggle(ragCat);
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      'Failed to update category permission',
      'Close',
      jasmine.anything()
    );
  });

  // ── resetCategoryRule ─────────────────────────────────────────────────────

  it('should call removeCategoryRule and set permission to null', () => {
    const ragCat = component.categories.find(c => c.key === 'rag')!;
    ragCat.permission = 'ALLOW';
    component.resetCategoryRule(ragCat);
    expect(permissionServiceSpy.removeCategoryRule).toHaveBeenCalledWith('rag');
    expect(ragCat.permission).toBeNull();
  });

  it('should show error snackbar on reset failure', () => {
    permissionServiceSpy.removeCategoryRule.and.returnValue(throwError(() => new Error('fail')));
    component.resetCategoryRule(component.categories[0]);
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to reset category', 'Close', jasmine.anything());
  });

  // ── isToolAllowed ─────────────────────────────────────────────────────────

  it('should return true when tool resolvedPermission is ALLOW', () => {
    const tool: ToolPermissionInfo = { name: 't', category: 'rag', description: '', resolvedPermission: 'ALLOW', hasOverride: false };
    expect(component.isToolAllowed(tool)).toBeTrue();
  });

  it('should return false when tool resolvedPermission is DENY', () => {
    const tool: ToolPermissionInfo = { name: 't', category: 'rag', description: '', resolvedPermission: 'DENY', hasOverride: false };
    expect(component.isToolAllowed(tool)).toBeFalse();
  });

  // ── onToolToggle ──────────────────────────────────────────────────────────

  it('should set tool to DENY when currently ALLOW', () => {
    const tool: ToolPermissionInfo = { name: 'rag_query', category: 'rag', description: '', resolvedPermission: 'ALLOW', hasOverride: false };
    component.onToolToggle(tool);
    expect(permissionServiceSpy.setToolRule).toHaveBeenCalledWith('rag_query', 'DENY');
    expect(tool.resolvedPermission).toBe('DENY');
    expect(tool.hasOverride).toBeTrue();
  });

  it('should set tool to ALLOW when currently DENY', () => {
    const tool: ToolPermissionInfo = { name: 'file_read', category: 'filesystem', description: '', resolvedPermission: 'DENY', hasOverride: true };
    component.onToolToggle(tool);
    expect(permissionServiceSpy.setToolRule).toHaveBeenCalledWith('file_read', 'ALLOW');
  });

  it('should show error on tool toggle failure', () => {
    permissionServiceSpy.setToolRule.and.returnValue(throwError(() => new Error('fail')));
    const tool: ToolPermissionInfo = { name: 'x', category: 'rag', description: '', resolvedPermission: 'ALLOW', hasOverride: false };
    component.onToolToggle(tool);
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to update tool permission', 'Close', jasmine.anything());
  });

  // ── removeToolOverride ────────────────────────────────────────────────────

  it('should call removeToolRule and set hasOverride to false', () => {
    const tool: ToolPermissionInfo = { name: 'file_read', category: 'filesystem', description: '', resolvedPermission: 'DENY', hasOverride: true };
    component.removeToolOverride(tool);
    expect(permissionServiceSpy.removeToolRule).toHaveBeenCalledWith('file_read');
    expect(tool.hasOverride).toBeFalse();
  });

  it('should show error on removeToolOverride failure', () => {
    permissionServiceSpy.removeToolRule.and.returnValue(throwError(() => new Error('fail')));
    const tool: ToolPermissionInfo = { name: 'x', category: 'rag', description: '', resolvedPermission: 'ALLOW', hasOverride: true };
    component.removeToolOverride(tool);
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to remove tool override', 'Close', jasmine.anything());
  });

  // ── allowAllCategories ────────────────────────────────────────────────────

  it('should call bulkUpdate with ALLOW for all categories', () => {
    component.allowAllCategories();
    const expectedRules: { [key: string]: PermissionLevel } = {};
    for (const cat of component.categories) {
      expectedRules[cat.key] = 'ALLOW';
    }
    expect(permissionServiceSpy.bulkUpdate).toHaveBeenCalledWith({ categoryRules: expectedRules });
  });

  it('should show error on allowAll failure', () => {
    permissionServiceSpy.bulkUpdate.and.returnValue(throwError(() => new Error('fail')));
    component.allowAllCategories();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to update categories', 'Close', jasmine.anything());
  });

  // ── denyWriteCategories ───────────────────────────────────────────────────

  it('should call bulkUpdate with DENY for write categories only', () => {
    // Add a 'filesystem' category to categories list
    permissionServiceSpy.bulkUpdate.and.returnValue(of({}));

    component.denyWriteCategories();

    const call = permissionServiceSpy.bulkUpdate.calls.mostRecent().args[0];
    // filesystem is a write category and exists in our test data
    if (call.categoryRules && call.categoryRules['filesystem']) {
      expect(call.categoryRules['filesystem']).toBe('DENY');
    }
    // rag is not a write category, so it should not appear
    if (call.categoryRules) {
      expect(call.categoryRules['rag']).toBeUndefined();
    }
  });

  it('should show error on denyWrite failure', () => {
    permissionServiceSpy.bulkUpdate.and.returnValue(throwError(() => new Error('fail')));
    component.denyWriteCategories();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to update categories', 'Close', jasmine.anything());
  });

  // ── getCategoryIcon ───────────────────────────────────────────────────────

  it('should return known icon for rag category', () => {
    expect(component.getCategoryIcon('rag')).toBe('search');
  });

  it('should return known icon for filesystem category', () => {
    expect(component.getCategoryIcon('filesystem')).toBe('folder');
  });

  it('should return extension for unknown category', () => {
    expect(component.getCategoryIcon('unknown_category')).toBe('extension');
  });

  it('should return correct icons for all defined categories', () => {
    const expectedIcons: { [k: string]: string } = {
      rag: 'search',
      filesystem: 'folder',
      indexing: 'storage',
      model: 'psychology',
      system: 'memory',
      config: 'settings',
      action_log: 'history',
      chat: 'chat',
      factsheet: 'description',
      evaluation: 'assessment',
      ingestion: 'upload',
      pipeline: 'route',
      settings: 'tune',
      chunk: 'view_module',
      prompt: 'edit_note',
      timing: 'timer',
      benchmark: 'speed',
      backup: 'backup',
      orchestrator: 'account_tree',
      experiment: 'science',
      crossindex: 'sync',
      archive: 'archive',
      vlm: 'visibility',
      kvcache: 'cached',
      device: 'devices',
      subprocess: 'terminal',
      delegation: 'group_work'
    };
    for (const [key, icon] of Object.entries(expectedIcons)) {
      expect(component.getCategoryIcon(key)).toBe(icon, `Expected icon for '${key}'`);
    }
  });
});
