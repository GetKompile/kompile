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
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { of } from 'rxjs';

import { ProcessOntologyComponent } from './process-ontology.component';
import {
  ProcessEngineService,
  OntologySchema,
  EntityTypeDefinition,
  ValidationRule
} from '../../services/process-engine.service';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeEntityType(overrides: Partial<EntityTypeDefinition> = {}): EntityTypeDefinition {
  return {
    name: 'Invoice',
    description: 'An invoice entity',
    classification: 'document',
    fields: [
      { name: 'amount', type: 'number', required: true, description: 'Invoice amount' },
      { name: 'vendor', type: 'string', required: true, description: 'Vendor name' }
    ],
    rules: [
      { name: 'amount-positive', type: 'constraint', severity: 'ERROR', expression: 'amount > 0' }
    ],
    ...overrides
  };
}

function makeOntologySchema(overrides: Partial<OntologySchema> = {}): OntologySchema {
  return {
    id: 'ontology-001',
    name: 'Financial Ontology',
    version: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    updatedBy: 'admin@example.com',
    entityTypes: [
      makeEntityType({ name: 'Invoice' }),
      makeEntityType({ name: 'Payment', description: 'A payment entity' })
    ],
    relationshipTypes: [
      {
        name: 'PAID_BY',
        sourceEntityType: 'Invoice',
        targetEntityType: 'Payment',
        cardinality: 'ONE_TO_ONE',
        description: 'Invoice paid by payment'
      }
    ],
    globalRules: [
      { name: 'cross-entity-check', type: 'cross-entity', severity: 'WARNING' }
    ],
    metadata: { domain: 'finance' },
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────

describe('ProcessOntologyComponent', () => {
  let component: ProcessOntologyComponent;
  let fixture: ComponentFixture<ProcessOntologyComponent>;
  let mockService: jasmine.SpyObj<ProcessEngineService>;

  const mockOntology = makeOntologySchema();
  const mockValidationRules: ValidationRule[] = [
    { name: 'amount-positive', type: 'constraint', severity: 'ERROR', expression: 'amount > 0' },
    { name: 'vendor-required', type: 'constraint', severity: 'WARNING', expression: 'vendor != null' }
  ];

  beforeEach(async () => {
    mockService = jasmine.createSpyObj('ProcessEngineService', [
      'listOntologies',
      'createOntology',
      'getOntology',
      'updateOntology',
      'validateData',
      'listActiveRuns',
      'getPendingApprovals'
    ]);
    mockService.listOntologies.and.returnValue(of([]));
    mockService.createOntology.and.returnValue(of(mockOntology));
    mockService.getOntology.and.returnValue(of(mockOntology));
    mockService.updateOntology.and.returnValue(of(mockOntology));
    mockService.validateData.and.returnValue(of(mockValidationRules));

    await TestBed.configureTestingModule({
      imports: [ProcessOntologyComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(ProcessOntologyComponent, {
      set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProcessOntologyComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. Component creation
  // ─────────────────────────────────────────────────────────────────────────────

  it('should create the component', () => {
    expect(component).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. Initial view mode
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Initial view mode', () => {
    it('should start in list view mode', () => {
      expect(component.viewMode).toBe('list');
    });

    it('should have no selected ontology on startup', () => {
      expect(component.selectedOntology).toBeNull();
    });

    it('should start with an empty ontologies array', () => {
      expect(Array.isArray(component.ontologies)).toBeTrue();
    });

    it('should start with saving = false', () => {
      expect(component.saving).toBeFalse();
    });

    it('should start with validating = false', () => {
      expect(component.validating).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. View transitions
  // ─────────────────────────────────────────────────────────────────────────────

  describe('View transitions', () => {
    it('should switch to create view on startCreate()', () => {
      component.startCreate();
      expect(component.viewMode).toBe('create');
    });

    it('should clear createJson when switching to create view', () => {
      component.createJson = 'some old json';
      component.startCreate();
      expect(component.createJson).toBe('');
    });

    it('should switch back to list view by setting viewMode', () => {
      component.startCreate();
      component.viewMode = 'list';
      expect(component.viewMode).toBe('list');
    });

    it('should switch to detail view when viewDetail() is called with an ontology that has no id', () => {
      const noIdOnt: OntologySchema = { name: 'Local Only' };
      component.viewDetail(noIdOnt);
      expect(component.viewMode).toBe('detail');
      expect(component.selectedOntology).toEqual(noIdOnt);
    });

    it('should fetch from service when viewDetail() is called with an ontology that has an id', () => {
      component.viewDetail(mockOntology);
      expect(mockService.getOntology).toHaveBeenCalledWith('ontology-001', 1);
    });

    it('should set selectedOntology and viewMode=detail after successful getOntology call', () => {
      component.viewDetail(mockOntology);
      expect(component.viewMode).toBe('detail');
      expect(component.selectedOntology).toEqual(mockOntology);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. Create ontology
  // ─────────────────────────────────────────────────────────────────────────────

  describe('createOntology()', () => {
    it('should call createOntology when createJson contains valid JSON', () => {
      component.createJson = JSON.stringify({ name: 'New Ontology', entityTypes: [] });
      component.createOntology();
      expect(mockService.createOntology).toHaveBeenCalled();
    });

    it('should NOT call createOntology when createJson is invalid JSON', () => {
      component.createJson = 'not-valid-json';
      component.createOntology();
      expect(mockService.createOntology).not.toHaveBeenCalled();
    });

    it('should switch to list view after successful creation', () => {
      component.createJson = JSON.stringify({ name: 'New Ontology', entityTypes: [] });
      component.createOntology();
      expect(component.viewMode).toBe('list');
    });

    it('should add the created ontology to the ontologies array', () => {
      component.createJson = JSON.stringify({ name: 'New Ontology', entityTypes: [] });
      component.createOntology();
      expect(component.ontologies.length).toBe(1);
      expect(component.ontologies[0].name).toBe('Financial Ontology');
    });

    it('should reset saving flag after successful creation', () => {
      component.createJson = JSON.stringify({ name: 'New Ontology', entityTypes: [] });
      component.createOntology();
      expect(component.saving).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. Display ontology details
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Ontology details', () => {
    beforeEach(() => {
      component.viewDetail(mockOntology);
      fixture.detectChanges();
    });

    it('should display ontology name', () => {
      expect(component.selectedOntology?.name).toBe('Financial Ontology');
    });

    it('should display ontology version', () => {
      expect(component.selectedOntology?.version).toBe(1);
    });

    it('should show entity types in detail view', () => {
      expect(component.selectedOntology?.entityTypes?.length).toBe(2);
      expect(component.selectedOntology?.entityTypes?.[0].name).toBe('Invoice');
    });

    it('should show relationship types in detail view', () => {
      expect(component.selectedOntology?.relationshipTypes?.length).toBe(1);
      expect(component.selectedOntology?.relationshipTypes?.[0].name).toBe('PAID_BY');
    });

    it('should show global rules in detail view', () => {
      expect(component.selectedOntology?.globalRules?.length).toBe(1);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. validateSample
  // ─────────────────────────────────────────────────────────────────────────────

  describe('validateSample()', () => {
    it('should call validateData when ontology is saved and has an id', () => {
      component.selectedOntology = mockOntology;
      const et = makeEntityType({ name: 'Invoice' });
      component.validateSample(et);
      expect(mockService.validateData).toHaveBeenCalledWith(
        'ontology-001',
        'Invoice',
        1,
        jasmine.any(Object)
      );
    });

    it('should not call validateData when no ontology is selected', () => {
      component.selectedOntology = null;
      const et = makeEntityType({ name: 'Invoice' });
      component.validateSample(et);
      expect(mockService.validateData).not.toHaveBeenCalled();
    });

    it('should not call validateData when selectedOntology has no id', () => {
      component.selectedOntology = { name: 'No Id Ontology' };
      const et = makeEntityType({ name: 'Invoice' });
      component.validateSample(et);
      expect(mockService.validateData).not.toHaveBeenCalled();
    });

    it('should populate validationResults[entityType.name] after validation', () => {
      component.selectedOntology = mockOntology;
      const et = makeEntityType({ name: 'Invoice' });
      component.validateSample(et);
      expect(component.validationResults['Invoice']).toBeDefined();
      expect(component.validationResults['Invoice'].length).toBe(2);
    });

    it('should reset validating flag after validation completes', () => {
      component.selectedOntology = mockOntology;
      const et = makeEntityType({ name: 'Invoice' });
      component.validateSample(et);
      expect(component.validating).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. countRules helper
  // ─────────────────────────────────────────────────────────────────────────────

  describe('countRules()', () => {
    it('should count global rules plus all entity-type rules', () => {
      // mockOntology: 1 global rule + (1 rule in Invoice + 1 rule in Payment) = 3
      const total = component.countRules(mockOntology);
      expect(total).toBe(3);
    });

    it('should return 0 for an ontology with no rules', () => {
      const empty: OntologySchema = {
        name: 'empty',
        entityTypes: [],
        globalRules: []
      };
      expect(component.countRules(empty)).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. loadOntologies
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadOntologies()', () => {
    it('should reset ontologies to empty array', () => {
      component.ontologies = [mockOntology];
      component.loadOntologies();
      expect(component.ontologies).toEqual([]);
    });
  });
});
