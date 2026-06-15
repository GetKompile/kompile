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
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA } from '@angular/core';

import { ProcessBuilderComponent } from './process-builder.component';
import { ProcessPhase } from '../../services/process-engine.service';

describe('ProcessBuilderComponent', () => {
  let component: ProcessBuilderComponent;
  let fixture: ComponentFixture<ProcessBuilderComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProcessBuilderComponent, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(ProcessBuilderComponent);
    component = fixture.componentInstance;
  });

  // ─── Phase Management ─────────────────────────────────────────────────

  describe('addPhase()', () => {
    it('should add a phase with incremented order', () => {
      component.addPhase();
      expect(component.phases.length).toBe(1);
      expect(component.phases[0].id).toBe('phase-1');
      expect(component.phases[0].order).toBe(1);
      expect(component.phases[0].steps).toEqual([]);

      component.addPhase();
      expect(component.phases.length).toBe(2);
      expect(component.phases[1].id).toBe('phase-2');
      expect(component.phases[1].order).toBe(2);
    });
  });

  describe('removePhase()', () => {
    it('should remove the phase at the given index', () => {
      component.addPhase();
      component.addPhase();
      component.addPhase();
      expect(component.phases.length).toBe(3);

      component.removePhase(1);

      expect(component.phases.length).toBe(2);
      expect(component.phases[0].id).toBe('phase-1');
      expect(component.phases[1].id).toBe('phase-3');
    });

    it('should reorder remaining phases after removal', () => {
      component.addPhase();
      component.addPhase();
      component.addPhase();

      component.removePhase(0);

      expect(component.phases[0].order).toBe(1);
      expect(component.phases[1].order).toBe(2);
    });
  });

  describe('dropPhase()', () => {
    it('should reorder phases after drag-drop', () => {
      component.addPhase();
      component.phases[0].name = 'First';
      component.addPhase();
      component.phases[1].name = 'Second';

      component.dropPhase({
        previousIndex: 0,
        currentIndex: 1,
        container: { data: component.phases } as any,
        previousContainer: { data: component.phases } as any,
        item: {} as any,
        isPointerOverContainer: true,
        distance: { x: 0, y: 0 },
        dropPoint: { x: 0, y: 0 },
        event: new MouseEvent('drop')
      } as any);

      expect(component.phases[0].name).toBe('Second');
      expect(component.phases[1].name).toBe('First');
      expect(component.phases[0].order).toBe(1);
      expect(component.phases[1].order).toBe(2);
    });
  });

  // ─── Step Management ──────────────────────────────────────────────────

  describe('addStep()', () => {
    it('should add a step to the phase with correct ID', () => {
      component.addPhase();
      const phase = component.phases[0];

      component.addStep(phase);

      expect(phase.steps!.length).toBe(1);
      expect(phase.steps![0].id).toBe('phase-1-step-1');
      expect(phase.steps![0].stepType).toBe('AUTO');
      expect(phase.steps![0].name).toBe('');
    });

    it('should increment step numbers within the same phase', () => {
      component.addPhase();
      const phase = component.phases[0];

      component.addStep(phase);
      component.addStep(phase);

      expect(phase.steps!.length).toBe(2);
      expect(phase.steps![0].id).toBe('phase-1-step-1');
      expect(phase.steps![1].id).toBe('phase-1-step-2');
    });

    it('should initialize steps array if null', () => {
      const phase: ProcessPhase = { id: 'test', name: 'Test', order: 1 };
      component.addStep(phase);

      expect(phase.steps).toBeDefined();
      expect(phase.steps!.length).toBe(1);
    });
  });

  describe('removeStep()', () => {
    it('should remove the step at the given index', () => {
      component.addPhase();
      const phase = component.phases[0];
      component.addStep(phase);
      component.addStep(phase);

      component.removeStep(phase, 0);

      expect(phase.steps!.length).toBe(1);
      expect(phase.steps![0].id).toBe('phase-1-step-2');
    });
  });

  // ─── Utility Methods ──────────────────────────────────────────────────

  describe('splitKeys()', () => {
    it('should split comma-separated values into trimmed array', () => {
      expect(component.splitKeys('a, b, c')).toEqual(['a', 'b', 'c']);
    });

    it('should filter out empty strings', () => {
      expect(component.splitKeys('a,, b, , c')).toEqual(['a', 'b', 'c']);
    });

    it('should return empty array for empty/whitespace input', () => {
      expect(component.splitKeys('')).toEqual([]);
      expect(component.splitKeys('   ')).toEqual([]);
    });

    it('should return empty array for null/undefined input', () => {
      expect(component.splitKeys(null as any)).toEqual([]);
      expect(component.splitKeys(undefined as any)).toEqual([]);
    });
  });

  describe('ensureApprovalPolicy()', () => {
    it('should initialize approvalPolicy when missing', () => {
      const step = { id: 's1', name: 'Approve', stepType: 'APPROVE' };
      component.ensureApprovalPolicy(step as any);

      expect(step).toEqual(jasmine.objectContaining({
        approvalPolicy: { approverPool: [] }
      }));
    });

    it('should not overwrite existing approvalPolicy', () => {
      const step: any = {
        id: 's1', name: 'Approve', stepType: 'APPROVE',
        approvalPolicy: { approverPool: ['admin@test.com'] }
      };
      component.ensureApprovalPolicy(step);

      expect(step.approvalPolicy.approverPool).toEqual(['admin@test.com']);
    });
  });

  // ─── Emit Save ────────────────────────────────────────────────────────

  describe('emitSave()', () => {
    it('should emit saved event with correct structure', () => {
      spyOn(component.saved, 'emit');
      component.definition = { name: 'My Process', ontologySchemaId: 'ont-1' };
      component.addPhase();
      component.phases[0].name = 'Phase One';
      component.addStep(component.phases[0]);
      component.phases[0].steps![0].name = 'Step One';

      component.emitSave();

      expect(component.saved.emit).toHaveBeenCalledWith(jasmine.objectContaining({
        name: 'My Process',
        ontologySchemaId: 'ont-1',
        phases: [jasmine.objectContaining({
          name: 'Phase One',
          order: 1,
          steps: [jasmine.objectContaining({ name: 'Step One' })]
        })]
      }));
    });

    it('should recompute phase order starting from 1', () => {
      spyOn(component.saved, 'emit');
      component.definition = { name: 'Test' };
      component.addPhase();
      component.addPhase();
      // Manually scramble orders
      component.phases[0].order = 99;
      component.phases[1].order = 42;

      component.emitSave();

      const emitted = (component.saved.emit as jasmine.Spy).calls.mostRecent().args[0];
      expect(emitted.phases[0].order).toBe(1);
      expect(emitted.phases[1].order).toBe(2);
    });
  });

  // ─── Preview JSON ─────────────────────────────────────────────────────

  describe('getPreviewJson()', () => {
    it('should return valid JSON matching the definition structure', () => {
      component.definition = { name: 'Preview Test' };
      component.addPhase();
      component.phases[0].name = 'P1';

      const json = component.getPreviewJson();
      const parsed = JSON.parse(json);

      expect(parsed.name).toBe('Preview Test');
      expect(parsed.phases.length).toBe(1);
      expect(parsed.phases[0].name).toBe('P1');
      expect(parsed.phases[0].order).toBe(1);
    });
  });
});
