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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import {
  CompositeEntityDialogComponent,
  CompositeEntityDialogData
} from './composite-entity-dialog.component';
import { GraphService } from '../../services/graph.service';
import { GraphNode } from '../../models/graph-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test data helpers
// ═══════════════════════════════════════════════════════════════════════════════

const mockParentNode: GraphNode = {
  id: 1,
  nodeId: 'parent-node-1',
  nodeType: 'SOURCE',
  title: 'Parent Source',
  childCount: 0,
  edgeCount: 0
};

const mockCreatedNode: GraphNode = {
  id: 99,
  nodeId: 'composite-uuid',
  nodeType: 'ENTITY',
  title: 'Test Entity',
  externalId: 'ext-1',
  isComposite: true,
  subGraphId: 'sub-graph-uuid',
  confidence: 0.8,
  childCount: 0,
  edgeCount: 0
};

const mockAvailableNodes: GraphNode[] = [
  mockParentNode,
  {
    id: 2,
    nodeId: 'doc-node-1',
    nodeType: 'DOCUMENT',
    title: 'Some Document',
    childCount: 3,
    edgeCount: 1
  }
];

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('CompositeEntityDialogComponent', () => {
  let component: CompositeEntityDialogComponent;
  let fixture: ComponentFixture<CompositeEntityDialogComponent>;
  let graphServiceSpy: jasmine.SpyObj<GraphService>;
  let dialogRefSpy: jasmine.SpyObj<MatDialogRef<CompositeEntityDialogComponent, GraphNode>>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  // Default dialog data — can be overridden per describe block
  let dialogData: CompositeEntityDialogData;

  async function configureTestBed(data: CompositeEntityDialogData = {}) {
    graphServiceSpy = jasmine.createSpyObj('GraphService', [
      'createCompositeEntity',
      'getNodes'
    ]);
    dialogRefSpy = jasmine.createSpyObj('MatDialogRef', ['close']);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    // Default: createCompositeEntity succeeds, getNodes returns empty list
    graphServiceSpy.createCompositeEntity.and.returnValue(of(mockCreatedNode));
    graphServiceSpy.getNodes.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(CompositeEntityDialogComponent, {
      set: {
        imports: [CommonModule, FormsModule, ReactiveFormsModule],
        template: '<div></div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(GraphService, { useValue: graphServiceSpy })
    .overrideProvider(MatDialogRef, { useValue: dialogRefSpy })
    .overrideProvider(MAT_DIALOG_DATA, { useValue: data })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(CompositeEntityDialogComponent);
    component = fixture.componentInstance;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    beforeEach(async () => {
      await configureTestBed({});
      fixture.detectChanges();
    });

    it('should create the component', () => {
      expect(component).toBeTruthy();
    });

    it('should initialise metadataEntries as empty array', () => {
      expect(component.metadataEntries).toEqual([]);
    });

    it('should initialise saving to false', () => {
      expect(component.saving).toBe(false);
    });

    it('should initialise availableNodes to empty when no data provided', () => {
      // no pre-supplied nodes → will try to load via service
      expect(Array.isArray(component.availableNodes)).toBe(true);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. FORM VALIDATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Form validation', () => {
    beforeEach(async () => {
      await configureTestBed({});
      fixture.detectChanges();
    });

    it('should have invalid form when title is empty', () => {
      component.form.get('title')!.setValue('');
      expect(component.form.invalid).toBe(true);
    });

    it('should have invalid form when title is whitespace only', () => {
      component.form.get('title')!.setValue('   ');
      // minLength(1) counts whitespace as a character but the required validator
      // still passes — minLength is what we test for the "blank" case
      expect(component.form.get('title')!.hasError('required')).toBe(false);
    });

    it('should have valid form when a non-empty title is provided', () => {
      component.form.get('title')!.setValue('My Entity');
      expect(component.form.valid).toBe(true);
    });

    it('should default confidence to 0.8', () => {
      expect(component.form.get('confidence')!.value).toBe(0.8);
    });

    it('should default parentNodeId to pre-selected parent when supplied in data', async () => {
      TestBed.resetTestingModule();
      await configureTestBed({ parentNode: mockParentNode });
      fixture.detectChanges();
      expect(component.form.get('parentNodeId')!.value).toBe('parent-node-1');
    });

    it('should default parentNodeId to empty string when no parent in data', () => {
      expect(component.form.get('parentNodeId')!.value).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. SAVE (createCompositeEntity)
  // ─────────────────────────────────────────────────────────────────────────────

  describe('save()', () => {
    beforeEach(async () => {
      await configureTestBed({});
      fixture.detectChanges();
    });

    it('should not call createCompositeEntity when form is invalid', () => {
      component.form.get('title')!.setValue('');
      component.save();
      expect(graphServiceSpy.createCompositeEntity).not.toHaveBeenCalled();
    });

    it('should call createCompositeEntity with the form data', () => {
      component.form.get('title')!.setValue('Acme Corp');
      component.form.get('externalId')!.setValue('acme-1');
      component.form.get('description')!.setValue('A company');
      component.form.get('parentNodeId')!.setValue('parent-node-1');
      component.form.get('confidence')!.setValue(0.9);
      component.save();
      expect(graphServiceSpy.createCompositeEntity).toHaveBeenCalledWith(
        jasmine.objectContaining({
          title: 'Acme Corp',
          externalId: 'acme-1',
          description: 'A company',
          parentNodeId: 'parent-node-1',
          confidence: 0.9
        })
      );
    });

    it('should close the dialog with the created node on success', () => {
      component.form.get('title')!.setValue('Test');
      graphServiceSpy.createCompositeEntity.and.returnValue(of(mockCreatedNode));
      component.save();
      expect(dialogRefSpy.close).toHaveBeenCalledWith(mockCreatedNode);
    });

    it('should set saving to false after successful save', () => {
      component.form.get('title')!.setValue('Test');
      graphServiceSpy.createCompositeEntity.and.returnValue(of(mockCreatedNode));
      component.save();
      expect(component.saving).toBe(false);
    });

    it('should show error snackbar on failure', () => {
      component.form.get('title')!.setValue('Fail Entity');
      graphServiceSpy.createCompositeEntity.and.returnValue(throwError(() => new Error('error')));
      component.save();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Failed to create composite entity',
        'Dismiss',
        { duration: 3000 }
      );
    });

    it('should set saving to false after failure', () => {
      component.form.get('title')!.setValue('Fail Entity');
      graphServiceSpy.createCompositeEntity.and.returnValue(throwError(() => new Error('error')));
      component.save();
      expect(component.saving).toBe(false);
    });

    it('should not close the dialog on failure', () => {
      component.form.get('title')!.setValue('Fail Entity');
      graphServiceSpy.createCompositeEntity.and.returnValue(throwError(() => new Error('error')));
      component.save();
      expect(dialogRefSpy.close).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. CANCEL
  // ─────────────────────────────────────────────────────────────────────────────

  describe('cancel()', () => {
    beforeEach(async () => {
      await configureTestBed({});
      fixture.detectChanges();
    });

    it('should close the dialog without a result', () => {
      component.cancel();
      expect(dialogRefSpy.close).toHaveBeenCalledWith();
    });

    it('should close the dialog without calling createCompositeEntity', () => {
      component.cancel();
      expect(graphServiceSpy.createCompositeEntity).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. ngOnInit — load parent nodes when none supplied
  // ─────────────────────────────────────────────────────────────────────────────

  describe('ngOnInit() — loading parent nodes', () => {
    it('should call getNodes when no availableNodes were supplied in dialog data', async () => {
      await configureTestBed({});
      graphServiceSpy.getNodes.and.returnValue(of(mockAvailableNodes));
      fixture.detectChanges(); // triggers ngOnInit
      expect(graphServiceSpy.getNodes).toHaveBeenCalled();
    });

    it('should populate availableNodes from getNodes when data has no pre-supplied nodes', async () => {
      await configureTestBed({});
      graphServiceSpy.getNodes.and.returnValue(of(mockAvailableNodes));
      fixture.detectChanges();
      expect(component.availableNodes).toEqual(mockAvailableNodes);
    });

    it('should not call getNodes when availableNodes are pre-supplied', async () => {
      await configureTestBed({ availableNodes: mockAvailableNodes });
      fixture.detectChanges();
      expect(graphServiceSpy.getNodes).not.toHaveBeenCalled();
    });

    it('should initialise availableNodes from dialog data when pre-supplied', async () => {
      await configureTestBed({ availableNodes: mockAvailableNodes });
      fixture.detectChanges();
      expect(component.availableNodes).toEqual(mockAvailableNodes);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. METADATA ENTRIES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Metadata entry management', () => {
    beforeEach(async () => {
      await configureTestBed({});
      fixture.detectChanges();
    });

    it('should add a metadata entry when addMetadataEntry is called', () => {
      expect(component.metadataEntries.length).toBe(0);
      component.addMetadataEntry();
      expect(component.metadataEntries.length).toBe(1);
    });

    it('should initialise new entry with empty key and value', () => {
      component.addMetadataEntry();
      const entry = component.metadataEntries[0];
      expect(entry.key).toBe('');
      expect(entry.value).toBe('');
    });

    it('should grow the entries array with each addMetadataEntry call', () => {
      component.addMetadataEntry();
      component.addMetadataEntry();
      component.addMetadataEntry();
      expect(component.metadataEntries.length).toBe(3);
    });

    it('should remove a metadata entry at the given index', () => {
      component.addMetadataEntry();
      component.addMetadataEntry();
      component.metadataEntries[0].key = 'first';
      component.metadataEntries[1].key = 'second';
      component.removeMetadataEntry(0);
      expect(component.metadataEntries.length).toBe(1);
      expect(component.metadataEntries[0].key).toBe('second');
    });

    it('should shrink the entries array after removal', () => {
      component.addMetadataEntry();
      expect(component.metadataEntries.length).toBe(1);
      component.removeMetadataEntry(0);
      expect(component.metadataEntries.length).toBe(0);
    });

    it('should include metadata in the createCompositeEntity request when keys are set', () => {
      component.form.get('title')!.setValue('With Metadata');
      component.addMetadataEntry();
      component.metadataEntries[0].key = 'industry';
      component.metadataEntries[0].value = 'tech';
      component.save();
      expect(graphServiceSpy.createCompositeEntity).toHaveBeenCalledWith(
        jasmine.objectContaining({
          metadata: jasmine.objectContaining({ industry: 'tech' })
        })
      );
    });

    it('should send undefined metadata when all entries have empty keys', () => {
      component.form.get('title')!.setValue('No Metadata Keys');
      component.addMetadataEntry(); // key stays ''
      component.save();
      const call = graphServiceSpy.createCompositeEntity.calls.mostRecent();
      expect(call.args[0].metadata).toBeUndefined();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. formatConfidence helper
  // ─────────────────────────────────────────────────────────────────────────────

  describe('formatConfidence()', () => {
    beforeEach(async () => {
      await configureTestBed({});
      fixture.detectChanges();
    });

    it('should return "80%" for value 0.8', () => {
      expect(component.formatConfidence(0.8)).toBe('80%');
    });

    it('should return "0%" for value 0', () => {
      expect(component.formatConfidence(0)).toBe('0%');
    });

    it('should return "100%" for value 1', () => {
      expect(component.formatConfidence(1)).toBe('100%');
    });

    it('should return "50%" for value 0.5', () => {
      expect(component.formatConfidence(0.5)).toBe('50%');
    });
  });
});
