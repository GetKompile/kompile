/*
 *   Copyright 2025 Kompile Inc.
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

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick
} from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError, Subject } from 'rxjs';

import { GraphEvalDebuggerComponent } from './graph-eval-debugger.component';
import { GraphEvalService } from '../../services/graph-eval.service';
import {
  GraphEvalStatus,
  GraphEvalResponse,
  GraphEntity,
  GraphRelationship
} from '../../models/graph-eval.models';

// ─────────────────────────────────────────────────────────────────────────────
// Test Data Helpers
// ─────────────────────────────────────────────────────────────────────────────

const makeStatus = (overrides: Partial<GraphEvalStatus> = {}): GraphEvalStatus => ({
  extractionAvailable: true,
  evaluatorCount: 2,
  evaluators: [
    { name: 'EntityPresence', type: 'ENTITY_PRESENCE', requiresLlm: false, requiresGroundTruth: true },
    { name: 'RelationshipPresence', type: 'RELATIONSHIP_PRESENCE', requiresLlm: false, requiresGroundTruth: true }
  ],
  ...overrides
});

const makeEntity = (overrides: Partial<GraphEntity> = {}): GraphEntity => ({
  id: 'e1',
  title: 'Alice',
  type: 'PERSON',
  ...overrides
});

const makeRelationship = (overrides: Partial<GraphRelationship> = {}): GraphRelationship => ({
  source: 'Alice',
  target: 'Acme Corp',
  type: 'WORKS_AT',
  ...overrides
});

const makeResponse = (overrides: Partial<GraphEvalResponse> = {}): GraphEvalResponse => ({
  success: true,
  extractedGraph: {
    entities: [makeEntity(), makeEntity({ id: 'e2', title: 'Acme Corp', type: 'ORGANIZATION' })],
    relationships: [makeRelationship()]
  },
  evaluationResults: [
    {
      evaluatorName: 'EntityPresence',
      evaluationType: 'ENTITY_PRESENCE',
      passed: true,
      score: 0.9,
      precision: 1.0,
      recall: 0.8,
      f1: 0.89,
      truePositives: 2,
      falsePositives: 0,
      falseNegatives: 1,
      threshold: 0.5,
      explanation: 'Good entity extraction',
      evaluationTimeMs: 45,
      metrics: {},
      entityMatches: [
        { extractedTitle: 'Alice', expectedTitle: 'Alice', extractedType: 'PERSON', expectedType: 'PERSON', matchType: 'TRUE_POSITIVE', similarity: 1.0 },
        { extractedTitle: 'Acme Corp', expectedTitle: 'Acme Corp', extractedType: 'ORGANIZATION', expectedType: 'ORGANIZATION', matchType: 'TRUE_POSITIVE', similarity: 1.0 },
        { expectedTitle: 'Bob', expectedType: 'PERSON', matchType: 'FALSE_NEGATIVE', similarity: 0 }
      ]
    }
  ],
  evaluationTimeMs: 120,
  ...overrides
});

// ─────────────────────────────────────────────────────────────────────────────
// Spec
// ─────────────────────────────────────────────────────────────────────────────

describe('GraphEvalDebuggerComponent', () => {
  let component: GraphEvalDebuggerComponent;
  let fixture: ComponentFixture<GraphEvalDebuggerComponent>;
  let serviceSpy: jasmine.SpyObj<GraphEvalService>;

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('GraphEvalService', [
      'getStatus',
      'runEvaluation',
      'evaluateOnly'
    ]);

    serviceSpy.getStatus.and.returnValue(of(makeStatus()));
    serviceSpy.runEvaluation.and.returnValue(of(makeResponse()));

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
      .overrideComponent(GraphEvalDebuggerComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .overrideProvider(GraphEvalService, { useValue: serviceSpy })
      .compileComponents();

    fixture = TestBed.createComponent(GraphEvalDebuggerComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    component.ngOnDestroy();
  });

  // ── Creation ────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ── loadStatus ──────────────────────────────────────────────────────────

  describe('loadStatus()', () => {
    it('should load status on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(serviceSpy.getStatus).toHaveBeenCalled();
      expect(component.status).toEqual(makeStatus());
      expect(component.loading).toBeFalse();
    }));

    it('should set error on status failure', fakeAsync(() => {
      serviceSpy.getStatus.and.returnValue(throwError(() => ({ error: { message: 'Server down' } })));
      fixture.detectChanges();
      tick();
      expect(component.error).toContain('Server down');
      expect(component.loading).toBeFalse();
    }));

    it('should set error with fallback message', fakeAsync(() => {
      serviceSpy.getStatus.and.returnValue(throwError(() => ({ message: 'Network error' })));
      fixture.detectChanges();
      tick();
      expect(component.error).toContain('Network error');
    }));
  });

  // ── Entity management ──────────────────────────────────────────────────

  describe('addEntity()', () => {
    it('should add entity with valid title', () => {
      component.newEntity = { title: 'Alice', type: 'PERSON' };
      component.addEntity();
      expect(component.groundTruthEntities.length).toBe(1);
      expect(component.groundTruthEntities[0].title).toBe('Alice');
      expect(component.groundTruthEntities[0].type).toBe('PERSON');
    });

    it('should not add entity with empty title', () => {
      component.newEntity = { title: '', type: 'PERSON' };
      component.addEntity();
      expect(component.groundTruthEntities.length).toBe(0);
    });

    it('should not add entity with undefined title', () => {
      component.newEntity = { type: 'PERSON' };
      component.addEntity();
      expect(component.groundTruthEntities.length).toBe(0);
    });

    it('should not add entity with whitespace-only title', () => {
      component.newEntity = { title: '   ', type: 'PERSON' };
      component.addEntity();
      expect(component.groundTruthEntities.length).toBe(0);
    });

    it('should trim title whitespace', () => {
      component.newEntity = { title: '  Bob  ', type: 'PERSON' };
      component.addEntity();
      expect(component.groundTruthEntities[0].title).toBe('Bob');
    });

    it('should preserve selected type for next entry', () => {
      component.newEntity = { title: 'MIT', type: 'ORGANIZATION' };
      component.addEntity();
      expect(component.newEntity.type).toBe('ORGANIZATION');
      expect(component.newEntity.title).toBeUndefined();
    });

    it('should default type to CONCEPT if not set', () => {
      component.newEntity = { title: 'Something' };
      component.addEntity();
      expect(component.groundTruthEntities[0].type).toBe('CONCEPT');
    });

    it('should generate sequential IDs', () => {
      component.newEntity = { title: 'A', type: 'PERSON' };
      component.addEntity();
      component.newEntity = { title: 'B', type: 'PERSON' };
      component.addEntity();
      expect(component.groundTruthEntities[0].id).toBe('gt-1');
      expect(component.groundTruthEntities[1].id).toBe('gt-2');
    });
  });

  describe('removeEntity()', () => {
    it('should remove entity at index', () => {
      component.groundTruthEntities = [
        makeEntity({ id: 'gt-1', title: 'A' }),
        makeEntity({ id: 'gt-2', title: 'B' }),
        makeEntity({ id: 'gt-3', title: 'C' })
      ];
      component.removeEntity(1);
      expect(component.groundTruthEntities.length).toBe(2);
      expect(component.groundTruthEntities.map(e => e.title)).toEqual(['A', 'C']);
    });
  });

  // ── Relationship management ────────────────────────────────────────────

  describe('addRelationship()', () => {
    it('should add relationship with valid source and target', () => {
      component.newRelationship = { source: 'Alice', target: 'Acme', type: 'WORKS_AT' };
      component.addRelationship();
      expect(component.groundTruthRelationships.length).toBe(1);
      expect(component.groundTruthRelationships[0].source).toBe('Alice');
      expect(component.groundTruthRelationships[0].target).toBe('Acme');
    });

    it('should not add relationship without source', () => {
      component.newRelationship = { source: '', target: 'Acme', type: 'WORKS_AT' };
      component.addRelationship();
      expect(component.groundTruthRelationships.length).toBe(0);
    });

    it('should not add relationship without target', () => {
      component.newRelationship = { source: 'Alice', target: '', type: 'WORKS_AT' };
      component.addRelationship();
      expect(component.groundTruthRelationships.length).toBe(0);
    });

    it('should trim whitespace from source and target', () => {
      component.newRelationship = { source: '  Alice  ', target: '  Acme  ', type: 'WORKS_AT' };
      component.addRelationship();
      expect(component.groundTruthRelationships[0].source).toBe('Alice');
      expect(component.groundTruthRelationships[0].target).toBe('Acme');
    });

    it('should default type to RELATED_TO if not set', () => {
      component.newRelationship = { source: 'A', target: 'B' };
      component.addRelationship();
      expect(component.groundTruthRelationships[0].type).toBe('RELATED_TO');
    });

    it('should preserve selected type for next entry', () => {
      component.newRelationship = { source: 'A', target: 'B', type: 'LOCATED_IN' };
      component.addRelationship();
      expect(component.newRelationship.type).toBe('LOCATED_IN');
      expect(component.newRelationship.source).toBeUndefined();
      expect(component.newRelationship.target).toBeUndefined();
    });
  });

  describe('removeRelationship()', () => {
    it('should remove relationship at index', () => {
      component.groundTruthRelationships = [
        makeRelationship({ source: 'A', target: 'B' }),
        makeRelationship({ source: 'C', target: 'D' })
      ];
      component.removeRelationship(0);
      expect(component.groundTruthRelationships.length).toBe(1);
      expect(component.groundTruthRelationships[0].source).toBe('C');
    });
  });

  // ── runEvaluation ──────────────────────────────────────────────────────

  describe('runEvaluation()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should not run with empty source text', () => {
      component.sourceText = '';
      component.runEvaluation();
      expect(serviceSpy.runEvaluation).not.toHaveBeenCalled();
    });

    it('should not run with whitespace-only source text', () => {
      component.sourceText = '   ';
      component.runEvaluation();
      expect(serviceSpy.runEvaluation).not.toHaveBeenCalled();
    });

    it('should call service with source text and no ground truth', fakeAsync(() => {
      component.sourceText = 'Alice works at Acme Corp.';
      component.runEvaluation();
      tick();
      expect(serviceSpy.runEvaluation).toHaveBeenCalledWith(jasmine.objectContaining({
        sourceText: 'Alice works at Acme Corp.',
        groundTruth: undefined,
        fuzzyMatch: false,
        similarityThreshold: 0.85
      }));
    }));

    it('should include ground truth when entities exist', fakeAsync(() => {
      component.sourceText = 'Alice works at Acme.';
      component.groundTruthEntities = [makeEntity()];
      component.runEvaluation();
      tick();
      expect(serviceSpy.runEvaluation).toHaveBeenCalledWith(jasmine.objectContaining({
        groundTruth: {
          entities: [makeEntity()],
          relationships: []
        }
      }));
    }));

    it('should include ground truth when relationships exist', fakeAsync(() => {
      component.sourceText = 'Alice works at Acme.';
      component.groundTruthRelationships = [makeRelationship()];
      component.runEvaluation();
      tick();
      expect(serviceSpy.runEvaluation).toHaveBeenCalledWith(jasmine.objectContaining({
        groundTruth: {
          entities: [],
          relationships: [makeRelationship()]
        }
      }));
    }));

    it('should pass fuzzy match settings', fakeAsync(() => {
      component.sourceText = 'Test text';
      component.fuzzyMatch = true;
      component.similarityThreshold = 0.7;
      component.runEvaluation();
      tick();
      expect(serviceSpy.runEvaluation).toHaveBeenCalledWith(jasmine.objectContaining({
        fuzzyMatch: true,
        similarityThreshold: 0.7
      }));
    }));

    it('should set running flag during evaluation', fakeAsync(() => {
      // Use a Subject so the observable doesn't complete synchronously
      const resultSubject = new Subject<ReturnType<typeof makeResponse>>();
      serviceSpy.runEvaluation.and.returnValue(resultSubject.asObservable());
      component.sourceText = 'Test text';
      component.runEvaluation();
      expect(component.running).toBeTrue();
      resultSubject.next(makeResponse());
      resultSubject.complete();
      tick();
      expect(component.running).toBeFalse();
    }));

    it('should store result on success', fakeAsync(() => {
      component.sourceText = 'Test text';
      component.runEvaluation();
      tick();
      expect(component.result).toEqual(makeResponse());
    }));

    it('should clear previous result before running', fakeAsync(() => {
      component.result = makeResponse();
      component.sourceText = 'New text';
      component.runEvaluation();
      // result is cleared before tick resolves the observable
      expect(component.error).toBeNull();
      tick();
    }));

    it('should set error on failure', fakeAsync(() => {
      serviceSpy.runEvaluation.and.returnValue(throwError(() => ({ error: { message: 'Extraction failed' } })));
      component.sourceText = 'Test text';
      component.runEvaluation();
      tick();
      expect(component.error).toContain('Extraction failed');
      expect(component.running).toBeFalse();
    }));
  });

  // ── File upload ────────────────────────────────────────────────────────

  describe('handleFileUpload()', () => {
    let originalFileReader: typeof FileReader;

    beforeEach(() => {
      originalFileReader = window.FileReader;
    });

    afterEach(() => {
      (window as any).FileReader = originalFileReader;
    });

    it('should read file content into sourceText', () => {
      const content = 'File content here';
      const mockReader: any = { result: content, readAsText: jasmine.createSpy('readAsText') };
      mockReader.readAsText.and.callFake(() => {
        if (mockReader.onload) {
          mockReader.onload({ target: mockReader } as any);
        }
      });
      (window as any).FileReader = function() { return mockReader; };

      const file = new File([content], 'test.txt', { type: 'text/plain' });
      const input = { files: [file], value: '' } as unknown as HTMLInputElement;
      const event = { target: input } as unknown as Event;

      component.handleFileUpload(event);

      expect(component.sourceText).toBe(content);
    });

    it('should do nothing if no files selected', () => {
      const input = { files: [] } as unknown as HTMLInputElement;
      const event = { target: input } as unknown as Event;
      component.handleFileUpload(event);
      expect(component.sourceText).toBe('');
    });
  });

  describe('handleGroundTruthUpload()', () => {
    let originalFileReader: typeof FileReader;

    beforeEach(() => {
      originalFileReader = window.FileReader;
    });

    afterEach(() => {
      (window as any).FileReader = originalFileReader;
    });

    it('should parse entities and relationships from JSON', () => {
      const data = {
        entities: [{ id: 'e1', title: 'Alice', type: 'PERSON' }],
        relationships: [{ source: 'Alice', target: 'Acme', type: 'WORKS_AT' }]
      };
      const mockReader: any = { result: JSON.stringify(data), readAsText: jasmine.createSpy('readAsText') };
      mockReader.readAsText.and.callFake(() => {
        if (mockReader.onload) {
          mockReader.onload({ target: mockReader } as any);
        }
      });
      (window as any).FileReader = function() { return mockReader; };

      const file = new File([JSON.stringify(data)], 'gt.json', { type: 'application/json' });
      const input = { files: [file], value: '' } as unknown as HTMLInputElement;
      const event = { target: input } as unknown as Event;

      component.handleGroundTruthUpload(event);

      expect(component.groundTruthEntities.length).toBe(1);
      expect(component.groundTruthRelationships.length).toBe(1);
    });

    it('should set error on invalid JSON', () => {
      const mockReader: any = { result: 'not json', readAsText: jasmine.createSpy('readAsText') };
      mockReader.readAsText.and.callFake(() => {
        if (mockReader.onload) {
          mockReader.onload({ target: mockReader } as any);
        }
      });
      (window as any).FileReader = function() { return mockReader; };

      const file = new File(['not json'], 'bad.json', { type: 'application/json' });
      const input = { files: [file], value: '' } as unknown as HTMLInputElement;
      const event = { target: input } as unknown as Event;

      component.handleGroundTruthUpload(event);

      expect(component.error).toBe('Invalid JSON file');
    });
  });

  // ── Score/match helpers ────────────────────────────────────────────────

  describe('getScoreColor()', () => {
    it('should return green for scores >= 0.8', () => {
      expect(component.getScoreColor(0.8)).toBe('#4caf50');
      expect(component.getScoreColor(1.0)).toBe('#4caf50');
    });

    it('should return orange for scores >= 0.5 and < 0.8', () => {
      expect(component.getScoreColor(0.5)).toBe('#ff9800');
      expect(component.getScoreColor(0.79)).toBe('#ff9800');
    });

    it('should return red for scores < 0.5', () => {
      expect(component.getScoreColor(0.49)).toBe('#f44336');
      expect(component.getScoreColor(0)).toBe('#f44336');
    });
  });

  describe('getMatchTypeColor()', () => {
    it('should return green for TRUE_POSITIVE', () => {
      expect(component.getMatchTypeColor('TRUE_POSITIVE')).toBe('#4caf50');
    });

    it('should return red for FALSE_POSITIVE', () => {
      expect(component.getMatchTypeColor('FALSE_POSITIVE')).toBe('#f44336');
    });

    it('should return orange for FALSE_NEGATIVE', () => {
      expect(component.getMatchTypeColor('FALSE_NEGATIVE')).toBe('#ff9800');
    });

    it('should return purple for TYPE_MISMATCH', () => {
      expect(component.getMatchTypeColor('TYPE_MISMATCH')).toBe('#9c27b0');
    });

    it('should return grey for unknown type', () => {
      expect(component.getMatchTypeColor('UNKNOWN')).toBe('#666');
    });
  });

  describe('getMatchTypeLabel()', () => {
    it('should return TP for TRUE_POSITIVE', () => {
      expect(component.getMatchTypeLabel('TRUE_POSITIVE')).toBe('TP');
    });

    it('should return FP for FALSE_POSITIVE', () => {
      expect(component.getMatchTypeLabel('FALSE_POSITIVE')).toBe('FP');
    });

    it('should return FN for FALSE_NEGATIVE', () => {
      expect(component.getMatchTypeLabel('FALSE_NEGATIVE')).toBe('FN');
    });

    it('should return label for TYPE_MISMATCH', () => {
      expect(component.getMatchTypeLabel('TYPE_MISMATCH')).toBe('Type Mismatch');
    });

    it('should return raw value for unknown type', () => {
      expect(component.getMatchTypeLabel('SOMETHING')).toBe('SOMETHING');
    });
  });

  describe('formatPercent()', () => {
    it('should format 0.85 as 85.0%', () => {
      expect(component.formatPercent(0.85)).toBe('85.0%');
    });

    it('should format 1 as 100.0%', () => {
      expect(component.formatPercent(1)).toBe('100.0%');
    });

    it('should format 0 as 0.0%', () => {
      expect(component.formatPercent(0)).toBe('0.0%');
    });

    it('should handle precision correctly', () => {
      expect(component.formatPercent(0.333)).toBe('33.3%');
    });
  });

  // ── exportGroundTruth ──────────────────────────────────────────────────

  describe('exportGroundTruth()', () => {
    it('should create downloadable JSON', () => {
      const createObjectURLSpy = spyOn(URL, 'createObjectURL').and.returnValue('blob:mock');
      const revokeObjectURLSpy = spyOn(URL, 'revokeObjectURL');
      const createElementSpy = spyOn(document, 'createElement').and.callThrough();

      component.groundTruthEntities = [makeEntity()];
      component.groundTruthRelationships = [makeRelationship()];
      component.exportGroundTruth();

      expect(createObjectURLSpy).toHaveBeenCalled();
      expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock');
    });
  });

  // ── Cleanup ────────────────────────────────────────────────────────────

  describe('ngOnDestroy()', () => {
    it('should complete destroy subject', () => {
      const destroySpy = spyOn(component['destroy$'], 'next');
      const completeSpy = spyOn(component['destroy$'], 'complete');
      component.ngOnDestroy();
      expect(destroySpy).toHaveBeenCalled();
      expect(completeSpy).toHaveBeenCalled();
    });
  });
});
