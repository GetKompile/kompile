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
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';

import { ConfirmDialogComponent, ConfirmDialogData } from './confirm-dialog.component';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

/** Creates a minimal valid ConfirmDialogData with optional overrides. */
function makeDialogData(overrides: Partial<ConfirmDialogData> = {}): ConfirmDialogData {
  return {
    title: 'Confirm Action',
    message: 'Are you sure you want to proceed?',
    ...overrides
  };
}

/** Creates the TestBed configuration for a given dialog data payload. */
function createTestBed(dialogData: ConfirmDialogData) {
  const dialogRefSpy = jasmine.createSpyObj<MatDialogRef<ConfirmDialogComponent>>('MatDialogRef', ['close']);

  TestBed.configureTestingModule({
    imports: [ConfirmDialogComponent, NoopAnimationsModule],
    providers: [
      { provide: MatDialogRef, useValue: dialogRefSpy },
      { provide: MAT_DIALOG_DATA, useValue: dialogData }
    ],
    schemas: [CUSTOM_ELEMENTS_SCHEMA]
  });

  return { dialogRefSpy };
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('ConfirmDialogComponent', () => {
  let component: ConfirmDialogComponent;
  let fixture: ComponentFixture<ConfirmDialogComponent>;
  let dialogRefSpy: jasmine.SpyObj<MatDialogRef<ConfirmDialogComponent>>;

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    beforeEach(async () => {
      const spies = createTestBed(makeDialogData());
      dialogRefSpy = spies.dialogRefSpy;

      await TestBed.compileComponents();
      fixture = TestBed.createComponent(ConfirmDialogComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should create successfully', () => {
      expect(component).toBeTruthy();
    });

    it('should expose injected dialog data via the data property', () => {
      expect(component.data.title).toBe('Confirm Action');
      expect(component.data.message).toBe('Are you sure you want to proceed?');
    });

    it('should expose the MatDialogRef as dialogRef', () => {
      expect(component.dialogRef).toBeDefined();
    });
  });

  describe('Component creation with all optional fields', () => {
    beforeEach(async () => {
      const fullData = makeDialogData({
        confirmText: 'Yes, Delete',
        cancelText: 'No, Keep',
        confirmColor: 'warn',
        icon: 'delete',
        iconColor: '#f44336'
      });
      const spies = createTestBed(fullData);
      dialogRefSpy = spies.dialogRefSpy;

      await TestBed.compileComponents();
      fixture = TestBed.createComponent(ConfirmDialogComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should hold all optional field values', () => {
      expect(component.data.confirmText).toBe('Yes, Delete');
      expect(component.data.cancelText).toBe('No, Keep');
      expect(component.data.confirmColor).toBe('warn');
      expect(component.data.icon).toBe('delete');
      expect(component.data.iconColor).toBe('#f44336');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. onCancel()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onCancel()', () => {
    beforeEach(async () => {
      const spies = createTestBed(makeDialogData());
      dialogRefSpy = spies.dialogRefSpy;

      await TestBed.compileComponents();
      fixture = TestBed.createComponent(ConfirmDialogComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should call dialogRef.close with false', () => {
      component.onCancel();
      expect(dialogRefSpy.close).toHaveBeenCalledOnceWith(false);
    });

    it('should close the dialog when the cancel button is clicked', () => {
      const cancelBtn: HTMLButtonElement = fixture.nativeElement.querySelector('button[mat-button]');
      cancelBtn?.click();
      expect(dialogRefSpy.close).toHaveBeenCalledWith(false);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. onConfirm()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onConfirm()', () => {
    beforeEach(async () => {
      const spies = createTestBed(makeDialogData());
      dialogRefSpy = spies.dialogRefSpy;

      await TestBed.compileComponents();
      fixture = TestBed.createComponent(ConfirmDialogComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should call dialogRef.close with true', () => {
      component.onConfirm();
      expect(dialogRefSpy.close).toHaveBeenCalledOnceWith(true);
    });

    it('should close the dialog when the confirm button is clicked', () => {
      const confirmBtn: HTMLButtonElement = fixture.nativeElement.querySelector('button[mat-raised-button]');
      confirmBtn?.click();
      expect(dialogRefSpy.close).toHaveBeenCalledWith(true);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. getIconColor()
  // ─────────────────────────────────────────────────────────────────────────────
  // getIconColor() is a pure function of this.data.confirmColor. We can test it
  // by directly mutating component.data in a single configured TestBed rather
  // than reconfiguring the module for each color variant.

  describe('getIconColor()', () => {
    beforeEach(async () => {
      const spies = createTestBed(makeDialogData());
      dialogRefSpy = spies.dialogRefSpy;

      await TestBed.compileComponents();
      fixture = TestBed.createComponent(ConfirmDialogComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should return red for confirmColor "warn"', () => {
      component.data = makeDialogData({ confirmColor: 'warn' });
      expect(component.getIconColor()).toBe('#f44336');
    });

    it('should return pink for confirmColor "accent"', () => {
      component.data = makeDialogData({ confirmColor: 'accent' });
      expect(component.getIconColor()).toBe('#ff4081');
    });

    it('should return blue for confirmColor "primary"', () => {
      component.data = makeDialogData({ confirmColor: 'primary' });
      expect(component.getIconColor()).toBe('#1976d2');
    });

    it('should return blue (primary default) when confirmColor is omitted', () => {
      component.data = makeDialogData(); // no confirmColor
      expect(component.getIconColor()).toBe('#1976d2');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. DEFAULT VALUES WHEN OPTIONAL FIELDS ARE OMITTED
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Default values when optional fields are omitted', () => {
    beforeEach(async () => {
      // Only required fields: title and message
      const spies = createTestBed(makeDialogData());
      dialogRefSpy = spies.dialogRefSpy;

      await TestBed.compileComponents();
      fixture = TestBed.createComponent(ConfirmDialogComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should not have confirmText set (template falls back to "Confirm")', () => {
      expect(component.data.confirmText).toBeUndefined();
    });

    it('should not have cancelText set (template falls back to "Cancel")', () => {
      expect(component.data.cancelText).toBeUndefined();
    });

    it('should not have confirmColor set (template falls back to "primary")', () => {
      expect(component.data.confirmColor).toBeUndefined();
    });

    it('should not have icon set', () => {
      expect(component.data.icon).toBeUndefined();
    });

    it('should not have iconColor set', () => {
      expect(component.data.iconColor).toBeUndefined();
    });

    it('should render "Cancel" as the cancel button label', () => {
      const cancelBtn: HTMLButtonElement = fixture.nativeElement.querySelector('button[mat-button]');
      expect(cancelBtn?.textContent?.trim()).toBe('Cancel');
    });

    it('should render "Confirm" as the confirm button label', () => {
      const confirmBtn: HTMLButtonElement = fixture.nativeElement.querySelector('button[mat-raised-button]');
      expect(confirmBtn?.textContent?.trim()).toBe('Confirm');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. TEMPLATE RENDERING
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Template rendering', () => {
    describe('with full dialog data', () => {
      beforeEach(async () => {
        const spies = createTestBed(makeDialogData({
          title: 'Delete File',
          message: 'This action cannot be undone.',
          confirmText: 'Delete',
          cancelText: 'Keep',
          confirmColor: 'warn',
          icon: 'warning'
        }));
        dialogRefSpy = spies.dialogRefSpy;

        await TestBed.compileComponents();
        fixture = TestBed.createComponent(ConfirmDialogComponent);
        component = fixture.componentInstance;
        fixture.detectChanges();
      });

      it('should render the dialog title', () => {
        const titleEl = fixture.nativeElement.querySelector('[mat-dialog-title]');
        expect(titleEl?.textContent?.trim()).toBe('Delete File');
      });

      it('should render the dialog message', () => {
        const compiled: HTMLElement = fixture.nativeElement;
        expect(compiled.textContent).toContain('This action cannot be undone.');
      });

      it('should render the custom confirm button text', () => {
        const confirmBtn: HTMLButtonElement = fixture.nativeElement.querySelector('button[mat-raised-button]');
        expect(confirmBtn?.textContent?.trim()).toBe('Delete');
      });

      it('should render the custom cancel button text', () => {
        const cancelBtn: HTMLButtonElement = fixture.nativeElement.querySelector('button[mat-button]');
        expect(cancelBtn?.textContent?.trim()).toBe('Keep');
      });
    });

    describe('with minimal dialog data', () => {
      beforeEach(async () => {
        const spies = createTestBed(makeDialogData({
          title: 'Confirm Action',
          message: 'Do you want to continue?'
        }));
        dialogRefSpy = spies.dialogRefSpy;

        await TestBed.compileComponents();
        fixture = TestBed.createComponent(ConfirmDialogComponent);
        component = fixture.componentInstance;
        fixture.detectChanges();
      });

      it('should render the title in the dialog header', () => {
        const titleEl = fixture.nativeElement.querySelector('[mat-dialog-title]');
        expect(titleEl?.textContent?.trim()).toBe('Confirm Action');
      });

      it('should render the message in dialog content', () => {
        const compiled: HTMLElement = fixture.nativeElement;
        expect(compiled.textContent).toContain('Do you want to continue?');
      });

      it('should fall back to "Cancel" and "Confirm" labels', () => {
        const compiled: HTMLElement = fixture.nativeElement;
        expect(compiled.textContent).toContain('Cancel');
        expect(compiled.textContent).toContain('Confirm');
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. INTERACTION INDEPENDENCE
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Interaction independence', () => {
    beforeEach(async () => {
      const spies = createTestBed(makeDialogData());
      dialogRefSpy = spies.dialogRefSpy;

      await TestBed.compileComponents();
      fixture = TestBed.createComponent(ConfirmDialogComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should not call close when neither onCancel nor onConfirm has been invoked', () => {
      expect(dialogRefSpy.close).not.toHaveBeenCalled();
    });

    it('should call close exactly once per onCancel call', () => {
      component.onCancel();
      component.onCancel();
      expect(dialogRefSpy.close).toHaveBeenCalledTimes(2);
    });

    it('should call close exactly once per onConfirm call', () => {
      component.onConfirm();
      expect(dialogRefSpy.close).toHaveBeenCalledTimes(1);
    });

    it('onCancel and onConfirm pass different values to close', () => {
      component.onCancel();
      component.onConfirm();
      expect(dialogRefSpy.close.calls.argsFor(0)).toEqual([false]);
      expect(dialogRefSpy.close.calls.argsFor(1)).toEqual([true]);
    });
  });
});
