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
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { of, throwError } from 'rxjs';

import { EvaluationSettingsComponent } from './evaluation-settings.component';
import { EvaluationService } from '../../../services/evaluation.service';
import {
  EvaluationConfig,
  AvailableEvaluators,
  EvaluationType
} from '../../../models/rag-management.models';

function makeDefaultConfig(): EvaluationConfig {
  return {
    available: true,
    enabled: false,
    async: true,
    defaultThreshold: 0.5,
    evaluators: {
      relevancy: { enabled: false, threshold: 0.5 },
      faithfulness: { enabled: false, threshold: 0.5 },
      answerCorrectness: {
        enabled: false,
        threshold: 0.5,
        semanticWeight: 0.5,
        factualWeight: 0.5
      },
      contextRelevancy: { enabled: false, threshold: 0.5 },
      hallucination: { enabled: false, threshold: 0.5 },
      entityPresence: { enabled: false, threshold: 0.5, fuzzyMatch: false, similarityThreshold: 0.8 },
      relationshipPresence: { enabled: false, threshold: 0.5, fuzzyMatch: false, similarityThreshold: 0.8 },
      entityTypeAccuracy: { enabled: false, threshold: 0.5 },
      graphCompleteness: { enabled: false, threshold: 0.5 }
    }
  };
}

function makeAvailableEvaluators(): AvailableEvaluators {
  return {
    available: true,
    serviceEnabled: true,
    evaluators: [
      { name: 'Relevancy', type: 'relevancy' },
      { name: 'Faithfulness', type: 'faithfulness' }
    ]
  };
}

function makeEvaluationTypes(): EvaluationType[] {
  return [
    { type: 'relevancy', description: 'Measures relevancy' },
    { type: 'faithfulness', description: 'Measures faithfulness' }
  ];
}

describe('EvaluationSettingsComponent', () => {
  let component: EvaluationSettingsComponent;
  let fixture: ComponentFixture<EvaluationSettingsComponent>;
  let evaluationSpy: jasmine.SpyObj<EvaluationService>;

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('EvaluationService', [
      'getConfig',
      'updateConfig',
      'getAvailableEvaluators',
      'getEvaluationTypes',
      'toggle',
      'createDefaultConfig'
    ]);

    spy.createDefaultConfig.and.returnValue(makeDefaultConfig());
    spy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: true }));
    spy.getAvailableEvaluators.and.returnValue(of(makeAvailableEvaluators()));
    spy.getEvaluationTypes.and.returnValue(of(makeEvaluationTypes()));

    await TestBed.configureTestingModule({
      imports: [
        EvaluationSettingsComponent,
        CommonModule,
        FormsModule,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideProvider(EvaluationService, { useValue: spy })
      .overrideComponent(EvaluationSettingsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .compileComponents();

    evaluationSpy = TestBed.inject(EvaluationService) as jasmine.SpyObj<EvaluationService>;
    fixture = TestBed.createComponent(EvaluationSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('ngOnInit', () => {
    it('should call loadConfiguration and loadEvaluationTypes', () => {
      expect(evaluationSpy.getConfig).toHaveBeenCalled();
      expect(evaluationSpy.getEvaluationTypes).toHaveBeenCalled();
    });
  });

  describe('loadConfiguration', () => {
    it('should update config when response.available is true', fakeAsync(() => {
      const config = { ...makeDefaultConfig(), enabled: true };
      evaluationSpy.getConfig.and.returnValue(of({ ...config, available: true }));
      component.loadConfiguration();
      tick();
      expect(component.config.enabled).toBeTrue();
      expect(component.loading).toBeFalse();
    }));

    it('should not update config when response.available is false', fakeAsync(() => {
      evaluationSpy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: false }));
      component.config.enabled = false;
      component.loadConfiguration();
      tick();
      expect(component.config.enabled).toBeFalse();
    }));

    it('should call loadAvailableEvaluators after loading', fakeAsync(() => {
      evaluationSpy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: true }));
      component.loadConfiguration();
      tick();
      expect(evaluationSpy.getAvailableEvaluators).toHaveBeenCalled();
    }));

    it('should set error on failure', fakeAsync(() => {
      evaluationSpy.getConfig.and.returnValue(
        throwError(() => ({ message: 'load error' }))
      );
      component.loadConfiguration();
      tick();
      expect(component.error).toContain('Failed to load evaluation configuration');
      expect(component.loading).toBeFalse();
    }));
  });

  describe('loadAvailableEvaluators', () => {
    it('should set availableEvaluators', fakeAsync(() => {
      const response = makeAvailableEvaluators();
      evaluationSpy.getAvailableEvaluators.and.returnValue(of(response));
      component.loadAvailableEvaluators();
      tick();
      expect(component.availableEvaluators).toEqual(response);
    }));

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      evaluationSpy.getAvailableEvaluators.and.returnValue(
        throwError(() => ({ message: 'error' }))
      );
      component.loadAvailableEvaluators();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('loadEvaluationTypes', () => {
    it('should set evaluationTypes', fakeAsync(() => {
      const types = makeEvaluationTypes();
      evaluationSpy.getEvaluationTypes.and.returnValue(of(types));
      component.loadEvaluationTypes();
      tick();
      expect(component.evaluationTypes).toEqual(types);
    }));

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      evaluationSpy.getEvaluationTypes.and.returnValue(
        throwError(() => ({ message: 'error' }))
      );
      component.loadEvaluationTypes();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('saveConfiguration', () => {
    it('should call updateConfig and set successMessage', fakeAsync(() => {
      const saved = makeDefaultConfig();
      evaluationSpy.updateConfig.and.returnValue(of(saved));
      component.saveConfiguration();
      tick();
      expect(evaluationSpy.updateConfig).toHaveBeenCalled();
      expect(component.successMessage).toBe('Configuration saved successfully');
      expect(component.saving).toBeFalse();
    }));

    it('should clear successMessage after 3 seconds', fakeAsync(() => {
      evaluationSpy.updateConfig.and.returnValue(of(makeDefaultConfig()));
      component.saveConfiguration();
      tick();
      expect(component.successMessage).toBeTruthy();
      tick(3000);
      expect(component.successMessage).toBeNull();
    }));

    it('should set error on failure', fakeAsync(() => {
      evaluationSpy.updateConfig.and.returnValue(
        throwError(() => ({ message: 'save error' }))
      );
      component.saveConfiguration();
      tick();
      expect(component.error).toContain('Failed to save configuration');
      expect(component.saving).toBeFalse();
    }));
  });

  describe('toggleEnabled', () => {
    it('should update config.enabled to true and show success', fakeAsync(() => {
      component.config.enabled = true;
      evaluationSpy.toggle.and.returnValue(of({ success: true, enabled: true, message: 'enabled' }));
      component.toggleEnabled();
      tick();
      expect(evaluationSpy.toggle).toHaveBeenCalledWith(true);
      expect(component.config.enabled).toBeTrue();
      expect(component.successMessage).toContain('Evaluation');
    }));

    it('should revert config.enabled on error', fakeAsync(() => {
      component.config.enabled = true;
      evaluationSpy.toggle.and.returnValue(
        throwError(() => ({ message: 'toggle failed' }))
      );
      component.toggleEnabled();
      tick();
      expect(component.config.enabled).toBeFalse(); // reverted
      expect(component.error).toContain('Failed to toggle evaluation');
    }));
  });

  describe('formatThreshold', () => {
    it('should format 0.5 as "50%"', () => {
      expect(component.formatThreshold(0.5)).toBe('50%');
    });
    it('should format 0 as "0%"', () => {
      expect(component.formatThreshold(0)).toBe('0%');
    });
    it('should format 1 as "100%"', () => {
      expect(component.formatThreshold(1)).toBe('100%');
    });
  });

  describe('getEvaluatorTypeDescription', () => {
    beforeEach(fakeAsync(() => {
      component.evaluationTypes = makeEvaluationTypes();
      tick();
    }));

    it('should return description for known type', () => {
      expect(component.getEvaluatorTypeDescription('relevancy')).toBe('Measures relevancy');
    });

    it('should return empty string for unknown type', () => {
      expect(component.getEvaluatorTypeDescription('unknown')).toBe('');
    });
  });

  describe('ngOnDestroy', () => {
    it('should complete the destroy subject', () => {
      spyOn((component as any).destroy$, 'next').and.callThrough();
      spyOn((component as any).destroy$, 'complete').and.callThrough();
      component.ngOnDestroy();
      expect((component as any).destroy$.next).toHaveBeenCalled();
      expect((component as any).destroy$.complete).toHaveBeenCalled();
    });
  });

  // ── Graph evaluator config ──────────────────────────────────────────────

  describe('graph evaluator config', () => {
    it('should initialize with graph evaluators in default config', () => {
      expect(component.config.evaluators.entityPresence).toBeDefined();
      expect(component.config.evaluators.relationshipPresence).toBeDefined();
      expect(component.config.evaluators.entityTypeAccuracy).toBeDefined();
      expect(component.config.evaluators.graphCompleteness).toBeDefined();
    });

    it('should have graph evaluators disabled by default', () => {
      expect(component.config.evaluators.entityPresence.enabled).toBeFalse();
      expect(component.config.evaluators.relationshipPresence.enabled).toBeFalse();
      expect(component.config.evaluators.entityTypeAccuracy.enabled).toBeFalse();
      expect(component.config.evaluators.graphCompleteness.enabled).toBeFalse();
    });

    it('should have fuzzy match defaults on entityPresence', () => {
      expect(component.config.evaluators.entityPresence.fuzzyMatch).toBeFalse();
      expect(component.config.evaluators.entityPresence.similarityThreshold).toBe(0.8);
    });

    it('should have fuzzy match defaults on relationshipPresence', () => {
      expect(component.config.evaluators.relationshipPresence.fuzzyMatch).toBeFalse();
      expect(component.config.evaluators.relationshipPresence.similarityThreshold).toBe(0.8);
    });

    it('should load graph evaluator config from server', fakeAsync(() => {
      const serverConfig = {
        ...makeDefaultConfig(),
        available: true,
        evaluators: {
          ...makeDefaultConfig().evaluators,
          entityPresence: { enabled: true, threshold: 0.6, fuzzyMatch: true, similarityThreshold: 0.75 },
          entityTypeAccuracy: { enabled: true, threshold: 0.8 }
        }
      };
      evaluationSpy.getConfig.and.returnValue(of(serverConfig));
      component.loadConfiguration();
      tick();
      expect(component.config.evaluators.entityPresence.enabled).toBeTrue();
      expect(component.config.evaluators.entityPresence.fuzzyMatch).toBeTrue();
      expect(component.config.evaluators.entityPresence.similarityThreshold).toBe(0.75);
      expect(component.config.evaluators.entityTypeAccuracy.enabled).toBeTrue();
      expect(component.config.evaluators.entityTypeAccuracy.threshold).toBe(0.8);
    }));

    it('should save graph evaluator config to server', fakeAsync(() => {
      component.config.evaluators.entityPresence.enabled = true;
      component.config.evaluators.entityPresence.fuzzyMatch = true;
      component.config.evaluators.entityPresence.similarityThreshold = 0.7;
      component.config.evaluators.graphCompleteness.enabled = true;

      evaluationSpy.updateConfig.and.returnValue(of(component.config));
      component.saveConfiguration();
      tick();

      expect(evaluationSpy.updateConfig).toHaveBeenCalled();
      const savedConfig = evaluationSpy.updateConfig.calls.mostRecent().args[0] as any;
      expect(savedConfig.evaluators.entityPresence.enabled).toBeTrue();
      expect(savedConfig.evaluators.entityPresence.fuzzyMatch).toBeTrue();
      expect(savedConfig.evaluators.entityPresence.similarityThreshold).toBe(0.7);
      expect(savedConfig.evaluators.graphCompleteness.enabled).toBeTrue();
    }));

    it('should format graph evaluator threshold correctly', () => {
      expect(component.formatThreshold(0.85)).toBe('85%');
      expect(component.formatThreshold(0.7)).toBe('70%');
    });
  });
});
