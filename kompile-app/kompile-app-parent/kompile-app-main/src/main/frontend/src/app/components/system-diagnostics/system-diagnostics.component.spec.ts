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

import { ComponentFixture, TestBed, fakeAsync, tick, flush } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { of, throwError, Subject } from 'rxjs';

import { SystemDiagnosticsComponent } from './system-diagnostics.component';
import {
  DiagnosticService,
  DiagnosticReport,
  DiagnosticCheck,
  DiagnosticStatus
} from '../../services/diagnostic.service';
import { FactSheetService } from '../../services/fact-sheet.service';

function makeReport(overallStatus: DiagnosticStatus = 'pass'): DiagnosticReport {
  return {
    timestamp: new Date().toISOString(),
    overallStatus,
    categories: [
      {
        name: 'System',
        icon: 'memory',
        checks: [],
        overallStatus,
        passCount: overallStatus === 'pass' ? 1 : 0,
        warningCount: overallStatus === 'warning' ? 1 : 0,
        failCount: overallStatus === 'fail' ? 1 : 0
      }
    ],
    totalChecks: 1,
    passedChecks: overallStatus === 'pass' ? 1 : 0,
    warningChecks: overallStatus === 'warning' ? 1 : 0,
    failedChecks: overallStatus === 'fail' ? 1 : 0,
    prerequisites: {
      vectorSearch: { ready: true, missing: [] },
      ragQuery: { ready: true, missing: [] },
      documentIngestion: { ready: true, missing: [] },
      reranking: { ready: true, missing: [] }
    }
  };
}

function makeCheck(overrides: Partial<DiagnosticCheck> = {}): DiagnosticCheck {
  return {
    id: 'test-check',
    name: 'Test Check',
    category: 'System',
    status: 'pass',
    message: 'All good',
    ...overrides
  };
}

describe('SystemDiagnosticsComponent', () => {
  let component: SystemDiagnosticsComponent;
  let fixture: ComponentFixture<SystemDiagnosticsComponent>;
  let diagnosticServiceSpy: jasmine.SpyObj<DiagnosticService>;
  let factSheetServiceSpy: jasmine.SpyObj<FactSheetService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    diagnosticServiceSpy = jasmine.createSpyObj('DiagnosticService', ['runDiagnostics']);
    diagnosticServiceSpy.runDiagnostics.and.returnValue(of(makeReport()));

    factSheetServiceSpy = jasmine.createSpyObj('FactSheetService', [
      'loadActiveSheet',
      'getActiveSheet',
      'updateSheet'
    ]);
    factSheetServiceSpy.loadActiveSheet.and.returnValue(of({} as any));
    factSheetServiceSpy.getActiveSheet.and.returnValue(null);

    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
      .overrideComponent(SystemDiagnosticsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .overrideProvider(DiagnosticService, { useValue: diagnosticServiceSpy })
      .overrideProvider(FactSheetService, { useValue: factSheetServiceSpy })
      .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
      .overrideProvider(MatDialogRef, { useValue: null })
      .overrideProvider(MAT_DIALOG_DATA, { useValue: null })
      .compileComponents();

    fixture = TestBed.createComponent(SystemDiagnosticsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ── Creation ──────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should set isModal to false when no dialogRef', () => {
    expect(component.isModal).toBeFalse();
  });

  // ── ngOnInit ──────────────────────────────────────────────────────────────

  it('should call loadActiveSheet on init', () => {
    expect(factSheetServiceSpy.loadActiveSheet).toHaveBeenCalled();
  });

  it('should call runDiagnostics on init', () => {
    expect(diagnosticServiceSpy.runDiagnostics).toHaveBeenCalled();
  });

  it('should set report on successful diagnostics', () => {
    const report = makeReport('pass');
    diagnosticServiceSpy.runDiagnostics.and.returnValue(of(report));
    component.runDiagnostics();
    expect(component.report).toEqual(report);
    expect(component.loading).toBeFalse();
    expect(component.error).toBeNull();
  });

  it('should set error on diagnostics failure', () => {
    diagnosticServiceSpy.runDiagnostics.and.returnValue(
      throwError(() => new Error('Service unavailable'))
    );
    component.runDiagnostics();
    expect(component.error).toBe('Service unavailable');
    expect(component.loading).toBeFalse();
  });

  it('should set generic error when error has no message', () => {
    diagnosticServiceSpy.runDiagnostics.and.returnValue(throwError(() => ({})));
    component.runDiagnostics();
    expect(component.error).toBe('Failed to run diagnostics');
  });

  // ── getStatusIcon ─────────────────────────────────────────────────────────

  it('should return check_circle for pass status', () => {
    expect(component.getStatusIcon('pass')).toBe('check_circle');
  });

  it('should return warning for warning status', () => {
    expect(component.getStatusIcon('warning')).toBe('warning');
  });

  it('should return error for fail status', () => {
    expect(component.getStatusIcon('fail')).toBe('error');
  });

  it('should return help_outline for unknown status', () => {
    expect(component.getStatusIcon('unknown')).toBe('help_outline');
  });

  // ── getStatusClass ────────────────────────────────────────────────────────

  it('should return status-pass class', () => {
    expect(component.getStatusClass('pass')).toBe('status-pass');
  });

  it('should return status-fail class', () => {
    expect(component.getStatusClass('fail')).toBe('status-fail');
  });

  // ── getOverallStatusMessage ───────────────────────────────────────────────

  it('should return empty string when no report', () => {
    component.report = null;
    expect(component.getOverallStatusMessage()).toBe('');
  });

  it('should return "All systems operational" for pass', () => {
    component.report = makeReport('pass');
    expect(component.getOverallStatusMessage()).toBe('All systems operational');
  });

  it('should return warning message with count', () => {
    const report = makeReport('warning');
    report.warningChecks = 3;
    component.report = report;
    expect(component.getOverallStatusMessage()).toBe('3 warning(s) detected');
  });

  it('should return fail message with count', () => {
    const report = makeReport('fail');
    report.failedChecks = 2;
    component.report = report;
    expect(component.getOverallStatusMessage()).toBe('2 issue(s) require attention');
  });

  it('should return "Status unknown" for other status', () => {
    component.report = makeReport('unknown' as DiagnosticStatus);
    expect(component.getOverallStatusMessage()).toBe('Status unknown');
  });

  // ── exportReport ─────────────────────────────────────────────────────────

  it('should do nothing when report is null', () => {
    component.report = null;
    spyOn(document, 'createElement').and.callThrough();
    component.exportReport();
    // createElement should not be called for 'a' specifically when report is null
    expect(document.createElement).not.toHaveBeenCalledWith('a');
  });

  it('should create download link when report is set', () => {
    component.report = makeReport();
    const mockAnchor = { href: '', download: '', click: jasmine.createSpy('click') };
    spyOn(document, 'createElement').and.returnValue(mockAnchor as any);
    spyOn(URL, 'createObjectURL').and.returnValue('blob:test');
    spyOn(URL, 'revokeObjectURL');

    component.exportReport();

    expect(document.createElement).toHaveBeenCalledWith('a');
    expect(mockAnchor.click).toHaveBeenCalled();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test');
  });

  // ── getCategoryProgress ───────────────────────────────────────────────────

  it('should return 100 for empty checks', () => {
    const cat = { name: 'Test', icon: '', checks: [], overallStatus: 'pass' as DiagnosticStatus, passCount: 0, warningCount: 0, failCount: 0 };
    expect(component.getCategoryProgress(cat)).toBe(100);
  });

  it('should compute category progress correctly', () => {
    const cat = {
      name: 'Test',
      icon: '',
      checks: [makeCheck(), makeCheck({ status: 'fail' })],
      overallStatus: 'fail' as DiagnosticStatus,
      passCount: 1,
      warningCount: 0,
      failCount: 1
    };
    expect(component.getCategoryProgress(cat)).toBe(50);
  });

  // ── Inline editing: startEditing / cancelEditing / isEditing ─────────────

  it('should not start editing for non-editable check', () => {
    const check = makeCheck({ editable: undefined });
    component.startEditing(check);
    expect(component.editingCheckId).toBeNull();
  });

  it('should start editing for editable check', () => {
    const check = makeCheck({
      editable: { settingKey: 'vectorStorePath', inputType: 'path', label: 'Vector Store Path' },
      value: '/some/path'
    });
    component.startEditing(check);
    expect(component.editingCheckId).toBe('test-check');
    expect(component.editValues['test-check']).toBe('/some/path');
  });

  it('should default to empty string when check value is missing', () => {
    const check = makeCheck({
      editable: { settingKey: 'vectorStorePath', inputType: 'path', label: 'Vector Store Path' }
    });
    component.startEditing(check);
    expect(component.editValues['test-check']).toBe('');
  });

  it('should cancel editing', () => {
    component.editingCheckId = 'test-check';
    component.cancelEditing();
    expect(component.editingCheckId).toBeNull();
  });

  it('isEditing should return true for active check', () => {
    component.editingCheckId = 'test-check';
    expect(component.isEditing('test-check')).toBeTrue();
    expect(component.isEditing('other')).toBeFalse();
  });

  it('isSaving should return true for saving check', () => {
    component.savingCheckId = 'test-check';
    expect(component.isSaving('test-check')).toBeTrue();
    expect(component.isSaving('other')).toBeFalse();
  });

  // ── saveEdit ──────────────────────────────────────────────────────────────

  it('should not save non-editable check', () => {
    const check = makeCheck({ editable: undefined });
    component.saveEdit(check);
    expect(snackBarSpy.open).not.toHaveBeenCalled();
  });

  it('should show snackbar when value is empty', () => {
    const check = makeCheck({
      editable: { settingKey: 'vectorStorePath', inputType: 'path', label: 'Vector Store Path' }
    });
    component.editValues['test-check'] = '   ';
    component.saveEdit(check);
    expect(snackBarSpy.open).toHaveBeenCalledWith('Please enter a value', 'Close', { duration: 3000 });
  });

  it('should show snackbar when no active fact sheet', () => {
    factSheetServiceSpy.getActiveSheet.and.returnValue(null);
    const check = makeCheck({
      editable: { settingKey: 'vectorStorePath', inputType: 'path', label: 'Vector Store Path' }
    });
    component.editValues['test-check'] = '/valid/path';
    component.saveEdit(check);
    expect(snackBarSpy.open).toHaveBeenCalledWith('No active fact sheet to update', 'Close', { duration: 3000 });
  });

  it('should call updateSheet on successful save', () => {
    const fakeSheet = { id: 42 } as any;
    factSheetServiceSpy.getActiveSheet.and.returnValue(fakeSheet);
    factSheetServiceSpy.updateSheet.and.returnValue(of(fakeSheet));
    diagnosticServiceSpy.runDiagnostics.and.returnValue(of(makeReport()));

    const check = makeCheck({
      editable: { settingKey: 'vectorStorePath', inputType: 'path', label: 'Vector Store Path' }
    });
    component.editValues['test-check'] = '/new/path';
    component.saveEdit(check);

    expect(factSheetServiceSpy.updateSheet).toHaveBeenCalledWith(42, { vectorStorePath: '/new/path' });
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('updated successfully'),
      'Close',
      jasmine.objectContaining({ duration: 3000 })
    );
  });

  it('should show error snackbar on updateSheet failure', () => {
    const fakeSheet = { id: 42 } as any;
    factSheetServiceSpy.getActiveSheet.and.returnValue(fakeSheet);
    factSheetServiceSpy.updateSheet.and.returnValue(throwError(() => new Error('Network error')));

    const check = makeCheck({
      editable: { settingKey: 'vectorStorePath', inputType: 'path', label: 'Vector Store Path' }
    });
    component.editValues['test-check'] = '/new/path';
    component.saveEdit(check);

    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Failed to update'),
      'Close',
      jasmine.objectContaining({ duration: 5000 })
    );
    expect(component.savingCheckId).toBeNull();
  });

  // ── onEditKeydown ─────────────────────────────────────────────────────────

  it('should call saveEdit on Enter key', () => {
    spyOn(component, 'saveEdit');
    const check = makeCheck({
      editable: { settingKey: 'key', inputType: 'text', label: 'Key' }
    });
    const event = new KeyboardEvent('keydown', { key: 'Enter' });
    component.onEditKeydown(event, check);
    expect(component.saveEdit).toHaveBeenCalledWith(check);
  });

  it('should call cancelEditing on Escape key', () => {
    spyOn(component, 'cancelEditing');
    const check = makeCheck();
    const event = new KeyboardEvent('keydown', { key: 'Escape' });
    component.onEditKeydown(event, check);
    expect(component.cancelEditing).toHaveBeenCalled();
  });

  it('should do nothing for other keys', () => {
    spyOn(component, 'saveEdit');
    spyOn(component, 'cancelEditing');
    const check = makeCheck();
    const event = new KeyboardEvent('keydown', { key: 'A' });
    component.onEditKeydown(event, check);
    expect(component.saveEdit).not.toHaveBeenCalled();
    expect(component.cancelEditing).not.toHaveBeenCalled();
  });

  // ── close ─────────────────────────────────────────────────────────────────

  it('should not throw when close is called with no dialogRef', () => {
    expect(() => component.close()).not.toThrow();
  });

  // ── isModal with dialogRef ────────────────────────────────────────────────

  it('should set isModal to true when dialogRef is provided', async () => {
    const fakeDialogRef = { close: jasmine.createSpy('close') };

    await TestBed.resetTestingModule()
      .configureTestingModule({ imports: [NoopAnimationsModule] })
      .overrideComponent(SystemDiagnosticsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .overrideProvider(DiagnosticService, { useValue: diagnosticServiceSpy })
      .overrideProvider(FactSheetService, { useValue: factSheetServiceSpy })
      .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
      .overrideProvider(MatDialogRef, { useValue: fakeDialogRef })
      .overrideProvider(MAT_DIALOG_DATA, { useValue: { some: 'data' } })
      .compileComponents();

    const fix = TestBed.createComponent(SystemDiagnosticsComponent);
    const comp = fix.componentInstance;
    fix.detectChanges();

    expect(comp.isModal).toBeTrue();
    comp.close();
    expect(fakeDialogRef.close).toHaveBeenCalled();
  });

  // ── formatTimestamp ───────────────────────────────────────────────────────

  it('should format timestamp as locale string', () => {
    const ts = '2025-01-15T10:30:00.000Z';
    const result = component.formatTimestamp(ts);
    expect(result).toBeTruthy();
    expect(typeof result).toBe('string');
  });

  // ── getPrerequisiteIcon and getPrerequisiteClass ───────────────────────────

  it('should return check_circle for ready prerequisite', () => {
    expect(component.getPrerequisiteIcon(true)).toBe('check_circle');
  });

  it('should return cancel for missing prerequisite', () => {
    expect(component.getPrerequisiteIcon(false)).toBe('cancel');
  });

  it('should return prereq-ready class when ready', () => {
    expect(component.getPrerequisiteClass(true)).toBe('prereq-ready');
  });

  it('should return prereq-missing class when not ready', () => {
    expect(component.getPrerequisiteClass(false)).toBe('prereq-missing');
  });
});
