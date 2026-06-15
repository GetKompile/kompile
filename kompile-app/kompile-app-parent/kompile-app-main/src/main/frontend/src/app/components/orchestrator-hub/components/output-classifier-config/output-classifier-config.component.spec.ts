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
import { ReactiveFormsModule, FormBuilder } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { CdkDragDrop } from '@angular/cdk/drag-drop';

import { OutputClassifierConfigComponent } from './output-classifier-config.component';
import { OrchestratorService } from '../../../../services/orchestrator.service';
import {
  OutputClassifier,
  ClassificationRule,
  ClassificationType,
  ClassificationSeverity,
  ClassificationAction,
  PatternTestResult
} from '../../../../models/orchestrator-models';

// Angular Material Modules
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { DragDropModule } from '@angular/cdk/drag-drop';

describe('OutputClassifierConfigComponent', () => {
  let component: OutputClassifierConfigComponent;
  let fixture: ComponentFixture<OutputClassifierConfigComponent>;
  let orchestratorServiceSpy: jasmine.SpyObj<OrchestratorService>;

  // Test data
  const mockRules: ClassificationRule[] = [
    {
      id: 1,
      classifierId: 1,
      name: 'Compilation Error',
      pattern: '(?i)error:\\s*(.+?)(?:\\n|$)',
      classificationType: 'COMPILATION_ERROR',
      severity: 'ERROR',
      action: 'INVOKE_LLM_FOR_FIX',
      enabled: true,
      ruleOrder: 0
    },
    {
      id: 2,
      classifierId: 1,
      name: 'Build Success',
      pattern: 'BUILD SUCCESS',
      classificationType: 'BUILD_SUCCESS',
      severity: 'INFO',
      action: 'CONTINUE',
      enabled: true,
      ruleOrder: 1
    },
    {
      id: 3,
      classifierId: 1,
      name: 'Test Failure',
      pattern: '(?i)tests? failed',
      classificationType: 'TEST_FAILURE',
      severity: 'ERROR',
      action: 'INVOKE_LLM',
      enabled: true,
      ruleOrder: 2
    }
  ];

  const mockClassifiers: OutputClassifier[] = [
    {
      id: 1,
      orchestratorInstanceId: 'test-instance',
      name: 'Build Output Classifier',
      description: 'Classifies build output for Maven/Gradle builds',
      enabled: true,
      applyAllMatches: false,
      defaultAction: 'CONTINUE',
      rules: mockRules
    },
    {
      id: 2,
      orchestratorInstanceId: 'test-instance',
      name: 'Test Output Classifier',
      description: 'Classifies test output',
      enabled: true,
      applyAllMatches: true,
      defaultAction: 'LOG',
      rules: []
    }
  ];

  const mockTemplates: ClassificationRule[] = [
    {
      id: 0,
      name: 'Compilation Error',
      pattern: '(?i)error:\\s*(.+?)(?:\\n|$)',
      classificationType: 'COMPILATION_ERROR',
      severity: 'ERROR',
      action: 'INVOKE_LLM_FOR_FIX'
    },
    {
      id: 0,
      name: 'Build Success',
      pattern: 'BUILD SUCCESS|\\[INFO\\] BUILD SUCCESS',
      classificationType: 'BUILD_SUCCESS',
      severity: 'INFO',
      action: 'CONTINUE'
    }
  ];

  const mockPatternTestResult: PatternTestResult = {
    pattern: 'error:\\s*(.+)',
    input: 'error: undefined reference to main',
    valid: true,
    matchCount: 1,
    matches: [
      {
        text: 'error: undefined reference to main',
        start: 0,
        end: 35,
        groups: ['undefined reference to main']
      }
    ]
  };

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('OrchestratorService', [
      'getOutputClassifiers',
      'getOutputClassifier',
      'createOutputClassifier',
      'updateOutputClassifier',
      'deleteOutputClassifier',
      'toggleClassifierEnabled',
      'getClassificationTemplates',
      'addClassificationRule',
      'updateClassificationRule',
      'deleteClassificationRule',
      'reorderClassificationRules',
      'testPattern'
    ]);

    await TestBed.configureTestingModule({
      declarations: [OutputClassifierConfigComponent],
      imports: [
        ReactiveFormsModule,
        NoopAnimationsModule,
        MatButtonModule,
        MatIconModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        MatCheckboxModule,
        MatChipsModule,
        MatProgressSpinnerModule,
        DragDropModule
      ],
      providers: [
        FormBuilder,
        { provide: OrchestratorService, useValue: spy }
      ]
    }).compileComponents();

    orchestratorServiceSpy = TestBed.inject(OrchestratorService) as jasmine.SpyObj<OrchestratorService>;
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(OutputClassifierConfigComponent);
    component = fixture.componentInstance;
    component.instanceId = 'test-instance';

    // Setup default responses
    orchestratorServiceSpy.getOutputClassifiers.and.returnValue(of(mockClassifiers));
    orchestratorServiceSpy.getClassificationTemplates.and.returnValue(of(mockTemplates));
  });

  // ==================== Component Initialization ====================

  describe('Component Initialization', () => {
    it('should create the component', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should initialize with default values', () => {
      expect(component.classifiers).toEqual([]);
      expect(component.selectedClassifier).toBeNull();
      expect(component.loading).toBeFalse();
      expect(component.saving).toBeFalse();
      expect(component.editingClassifier).toBeFalse();
      expect(component.editingRule).toBeNull();
    });

    it('should load classifiers on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getOutputClassifiers).toHaveBeenCalledWith('test-instance');
      expect(component.classifiers.length).toBe(2);
    }));

    it('should load templates on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getClassificationTemplates).toHaveBeenCalledWith('test-instance');
    }));

    it('should use default templates if API fails', fakeAsync(() => {
      orchestratorServiceSpy.getClassificationTemplates.and.returnValue(
        throwError(() => new Error('API error'))
      );
      fixture.detectChanges();
      tick();
      expect(component.templates.length).toBeGreaterThan(0);
    }));

    it('should not load data if instanceId is empty', fakeAsync(() => {
      component.instanceId = '';
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getOutputClassifiers).not.toHaveBeenCalled();
    }));
  });

  // ==================== Form Initialization ====================

  describe('Form Initialization', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should initialize classifierForm with correct fields', () => {
      expect(component.classifierForm).toBeDefined();
      expect(component.classifierForm.contains('name')).toBeTrue();
      expect(component.classifierForm.contains('description')).toBeTrue();
      expect(component.classifierForm.contains('enabled')).toBeTrue();
      expect(component.classifierForm.contains('applyAllMatches')).toBeTrue();
      expect(component.classifierForm.contains('defaultAction')).toBeTrue();
      expect(component.classifierForm.contains('tags')).toBeTrue();
    });

    it('should initialize ruleForm with correct fields', () => {
      expect(component.ruleForm).toBeDefined();
      expect(component.ruleForm.contains('name')).toBeTrue();
      expect(component.ruleForm.contains('pattern')).toBeTrue();
      expect(component.ruleForm.contains('classificationType')).toBeTrue();
      expect(component.ruleForm.contains('severity')).toBeTrue();
      expect(component.ruleForm.contains('action')).toBeTrue();
      expect(component.ruleForm.contains('caseSensitive')).toBeTrue();
      expect(component.ruleForm.contains('multiline')).toBeTrue();
      expect(component.ruleForm.contains('enabled')).toBeTrue();
      expect(component.ruleForm.contains('stopOnMatch')).toBeTrue();
    });

    it('should have required validators on name and pattern', () => {
      const nameControl = component.ruleForm.get('name');
      const patternControl = component.ruleForm.get('pattern');

      nameControl?.setValue('');
      patternControl?.setValue('');

      expect(nameControl?.valid).toBeFalse();
      expect(patternControl?.valid).toBeFalse();

      nameControl?.setValue('Test Rule');
      patternControl?.setValue('test.*');

      expect(nameControl?.valid).toBeTrue();
      expect(patternControl?.valid).toBeTrue();
    });
  });

  // ==================== Classifier CRUD ====================

  describe('Classifier CRUD Operations', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    describe('Select Classifier', () => {
      it('should select a classifier', () => {
        component.selectClassifier(mockClassifiers[0]);
        expect(component.selectedClassifier).toBe(mockClassifiers[0]);
      });

      it('should clear editing rule when selecting classifier', () => {
        component.editingRule = mockRules[0];
        component.selectClassifier(mockClassifiers[0]);
        expect(component.editingRule).toBeNull();
      });
    });

    describe('Create Classifier', () => {
      it('should open form for new classifier', () => {
        component.newClassifier();

        expect(component.editingClassifier).toBeTrue();
        expect(component.selectedClassifier).toBeNull();
        expect(component.classifierForm.get('enabled')?.value).toBeTrue();
        expect(component.classifierForm.get('defaultAction')?.value).toBe('CONTINUE');
      });

      it('should save new classifier', fakeAsync(() => {
        const newClassifier: OutputClassifier = {
          id: 3,
          orchestratorInstanceId: 'test-instance',
          name: 'New Classifier',
          enabled: true,
          rules: []
        };
        orchestratorServiceSpy.createOutputClassifier.and.returnValue(of(newClassifier));

        component.newClassifier();
        component.classifierForm.patchValue({ name: 'New Classifier' });
        component.saveClassifier();
        tick();

        expect(orchestratorServiceSpy.createOutputClassifier).toHaveBeenCalledWith(
          'test-instance',
          jasmine.objectContaining({ name: 'New Classifier' })
        );
        expect(component.editingClassifier).toBeFalse();
      }));

      it('should not save if form is invalid', () => {
        component.newClassifier();
        component.classifierForm.patchValue({ name: '' });
        component.saveClassifier();

        expect(orchestratorServiceSpy.createOutputClassifier).not.toHaveBeenCalled();
      });
    });

    describe('Edit Classifier', () => {
      it('should populate form with classifier data', () => {
        const classifier = mockClassifiers[0];
        component.editClassifier(classifier);

        expect(component.editingClassifier).toBeTrue();
        expect(component.classifierForm.get('name')?.value).toBe('Build Output Classifier');
        expect(component.classifierForm.get('description')?.value).toBe(classifier.description);
        expect(component.classifierForm.get('enabled')?.value).toBeTrue();
      });

      it('should update existing classifier', fakeAsync(() => {
        const classifier = mockClassifiers[0];
        orchestratorServiceSpy.updateOutputClassifier.and.returnValue(of(classifier));

        component.editClassifier(classifier);
        component.classifierForm.patchValue({ name: 'Updated Name' });
        component.saveClassifier();
        tick();

        expect(orchestratorServiceSpy.updateOutputClassifier).toHaveBeenCalledWith(
          'test-instance',
          1,
          jasmine.objectContaining({ name: 'Updated Name' })
        );
      }));
    });

    describe('Delete Classifier', () => {
      it('should delete classifier after confirmation', fakeAsync(() => {
        spyOn(window, 'confirm').and.returnValue(true);
        orchestratorServiceSpy.deleteOutputClassifier.and.returnValue(of(void 0));

        component.deleteClassifier(mockClassifiers[0]);
        tick();

        expect(orchestratorServiceSpy.deleteOutputClassifier).toHaveBeenCalledWith(
          'test-instance',
          1
        );
      }));

      it('should not delete if user cancels', () => {
        spyOn(window, 'confirm').and.returnValue(false);

        component.deleteClassifier(mockClassifiers[0]);

        expect(orchestratorServiceSpy.deleteOutputClassifier).not.toHaveBeenCalled();
      });

      it('should clear selected classifier if deleted', fakeAsync(() => {
        spyOn(window, 'confirm').and.returnValue(true);
        orchestratorServiceSpy.deleteOutputClassifier.and.returnValue(of(void 0));

        component.selectedClassifier = mockClassifiers[0];
        component.deleteClassifier(mockClassifiers[0]);
        tick();

        expect(component.selectedClassifier).toBeNull();
      }));
    });

    describe('Toggle Classifier Enabled', () => {
      it('should toggle classifier enabled status', fakeAsync(() => {
        orchestratorServiceSpy.toggleClassifierEnabled.and.returnValue(of(void 0));

        const classifier = { ...mockClassifiers[0], enabled: true };
        component.toggleClassifierEnabled(classifier);
        tick();

        expect(orchestratorServiceSpy.toggleClassifierEnabled).toHaveBeenCalledWith(
          'test-instance',
          1,
          false
        );
        expect(classifier.enabled).toBeFalse();
      }));
    });
  });

  // ==================== Rule CRUD ====================

  describe('Rule CRUD Operations', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      component.selectedClassifier = mockClassifiers[0];
    }));

    describe('Create Rule', () => {
      it('should open form for new rule', () => {
        component.newRule();

        expect(component.editingRule).toBeDefined();
        expect(component.ruleForm.get('classificationType')?.value).toBe('CUSTOM');
        expect(component.ruleForm.get('severity')?.value).toBe('INFO');
        expect(component.ruleForm.get('action')?.value).toBe('LOG');
      });

      it('should not allow new rule without selected classifier', () => {
        component.selectedClassifier = null;
        component.newRule();

        expect(component.editingRule).toBeNull();
      });

      it('should save new rule', fakeAsync(() => {
        const newRule: ClassificationRule = {
          id: 4,
          name: 'New Rule',
          pattern: 'test',
          classificationType: 'CUSTOM',
          severity: 'INFO',
          action: 'LOG'
        };
        orchestratorServiceSpy.addClassificationRule.and.returnValue(of(newRule));
        orchestratorServiceSpy.getOutputClassifier.and.returnValue(of(mockClassifiers[0]));

        component.newRule();
        component.ruleForm.patchValue({
          name: 'New Rule',
          pattern: 'test'
        });
        component.saveRule();
        tick();

        expect(orchestratorServiceSpy.addClassificationRule).toHaveBeenCalledWith(
          'test-instance',
          1,
          jasmine.objectContaining({ name: 'New Rule', pattern: 'test' })
        );
      }));
    });

    describe('Edit Rule', () => {
      it('should populate form with rule data', () => {
        const rule = mockRules[0];
        component.editRule(rule);

        expect(component.editingRule).toBe(rule);
        expect(component.ruleForm.get('name')?.value).toBe('Compilation Error');
        expect(component.ruleForm.get('pattern')?.value).toBe(rule.pattern);
        expect(component.ruleForm.get('classificationType')?.value).toBe('COMPILATION_ERROR');
        expect(component.ruleForm.get('severity')?.value).toBe('ERROR');
        expect(component.ruleForm.get('action')?.value).toBe('INVOKE_LLM_FOR_FIX');
      });

      it('should update existing rule', fakeAsync(() => {
        const rule = mockRules[0];
        orchestratorServiceSpy.updateClassificationRule.and.returnValue(of(rule));
        orchestratorServiceSpy.getOutputClassifier.and.returnValue(of(mockClassifiers[0]));

        component.editRule(rule);
        component.ruleForm.patchValue({ name: 'Updated Rule' });
        component.saveRule();
        tick();

        expect(orchestratorServiceSpy.updateClassificationRule).toHaveBeenCalledWith(
          'test-instance',
          1,
          1,
          jasmine.objectContaining({ name: 'Updated Rule' })
        );
      }));
    });

    describe('Delete Rule', () => {
      it('should delete rule after confirmation', fakeAsync(() => {
        spyOn(window, 'confirm').and.returnValue(true);
        orchestratorServiceSpy.deleteClassificationRule.and.returnValue(of(void 0));
        orchestratorServiceSpy.getOutputClassifier.and.returnValue(of(mockClassifiers[0]));

        component.deleteRule(mockRules[0]);
        tick();

        expect(orchestratorServiceSpy.deleteClassificationRule).toHaveBeenCalledWith(
          'test-instance',
          1,
          1
        );
      }));

      it('should not delete if user cancels', () => {
        spyOn(window, 'confirm').and.returnValue(false);

        component.deleteRule(mockRules[0]);

        expect(orchestratorServiceSpy.deleteClassificationRule).not.toHaveBeenCalled();
      });
    });

    describe('Reorder Rules', () => {
      it('should reorder rules on drag drop', fakeAsync(() => {
        orchestratorServiceSpy.reorderClassificationRules.and.returnValue(of(void 0));
        component.selectedClassifier = { ...mockClassifiers[0], rules: [...mockRules] };

        const event = {
          previousIndex: 0,
          currentIndex: 2
        } as CdkDragDrop<ClassificationRule[]>;

        component.dropRule(event);
        tick();

        expect(orchestratorServiceSpy.reorderClassificationRules).toHaveBeenCalledWith(
          'test-instance',
          1,
          jasmine.any(Array)
        );
      }));

      it('should not reorder if no classifier selected', fakeAsync(() => {
        component.selectedClassifier = null;

        const event = {
          previousIndex: 0,
          currentIndex: 2
        } as CdkDragDrop<ClassificationRule[]>;

        component.dropRule(event);
        tick();

        expect(orchestratorServiceSpy.reorderClassificationRules).not.toHaveBeenCalled();
      }));
    });
  });

  // ==================== Template Usage ====================

  describe('Template Usage', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should apply template to form', () => {
      component.newRule();
      component.applyTemplate(mockTemplates[0]);

      expect(component.ruleForm.get('name')?.value).toBe('Compilation Error');
      expect(component.ruleForm.get('pattern')?.value).toBe(mockTemplates[0].pattern);
      expect(component.ruleForm.get('classificationType')?.value).toBe('COMPILATION_ERROR');
      expect(component.ruleForm.get('severity')?.value).toBe('ERROR');
      expect(component.ruleForm.get('action')?.value).toBe('INVOKE_LLM_FOR_FIX');
    });
  });

  // ==================== Pattern Testing ====================

  describe('Pattern Testing', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should test pattern against input', fakeAsync(() => {
      orchestratorServiceSpy.testPattern.and.returnValue(of(mockPatternTestResult));

      component.ruleForm.patchValue({ pattern: 'error:\\s*(.+)' });
      component.testPatternInput = 'error: undefined reference to main';

      component.testPattern();
      tick();

      expect(orchestratorServiceSpy.testPattern).toHaveBeenCalledWith(
        'test-instance',
        'error:\\s*(.+)',
        'error: undefined reference to main'
      );
      expect(component.testPatternResult).toEqual(mockPatternTestResult);
    }));

    it('should not test if pattern is empty', () => {
      component.ruleForm.patchValue({ pattern: '' });
      component.testPatternInput = 'test input';

      component.testPattern();

      expect(orchestratorServiceSpy.testPattern).not.toHaveBeenCalled();
    });

    it('should not test if input is empty', () => {
      component.ruleForm.patchValue({ pattern: 'test' });
      component.testPatternInput = '';

      component.testPattern();

      expect(orchestratorServiceSpy.testPattern).not.toHaveBeenCalled();
    });

    it('should handle invalid pattern', fakeAsync(() => {
      orchestratorServiceSpy.testPattern.and.returnValue(
        throwError(() => ({ error: { message: 'Invalid regex' } }))
      );

      component.ruleForm.patchValue({ pattern: '(invalid' });
      component.testPatternInput = 'test';

      component.testPattern();
      tick();

      expect(component.testPatternResult?.valid).toBeFalse();
      expect(component.testPatternResult?.error).toContain('Invalid regex');
    }));
  });

  // ==================== Helper Methods ====================

  describe('Helper Methods', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    describe('getSeverityClass', () => {
      it('should return correct class for each severity', () => {
        expect(component.getSeverityClass('CRITICAL')).toBe('severity-critical');
        expect(component.getSeverityClass('ERROR')).toBe('severity-error');
        expect(component.getSeverityClass('WARNING')).toBe('severity-warning');
        expect(component.getSeverityClass('INFO')).toBe('severity-info');
      });

      it('should return empty string for unknown severity', () => {
        expect(component.getSeverityClass('UNKNOWN' as ClassificationSeverity)).toBe('');
      });
    });

    describe('getActionClass', () => {
      it('should return correct class for severe actions', () => {
        expect(component.getActionClass('ABORT')).toBe('action-severe');
        expect(component.getActionClass('ESCALATE')).toBe('action-severe');
      });

      it('should return correct class for retry actions', () => {
        expect(component.getActionClass('RETRY')).toBe('action-retry');
        expect(component.getActionClass('RETRY_WITH_BACKOFF')).toBe('action-retry');
      });

      it('should return correct class for LLM actions', () => {
        expect(component.getActionClass('INVOKE_LLM')).toBe('action-llm');
        expect(component.getActionClass('INVOKE_LLM_FOR_FIX')).toBe('action-llm');
      });

      it('should return correct class for passive actions', () => {
        expect(component.getActionClass('CONTINUE')).toBe('action-passive');
        expect(component.getActionClass('LOG')).toBe('action-passive');
      });

      it('should return empty string for unknown action', () => {
        expect(component.getActionClass('UNKNOWN' as ClassificationAction)).toBe('');
      });
    });
  });

  // ==================== Error Handling ====================

  describe('Error Handling', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should display error on load failure', fakeAsync(() => {
      orchestratorServiceSpy.getOutputClassifiers.and.returnValue(
        throwError(() => ({ error: { message: 'Load failed' } }))
      );

      component.loadClassifiers();
      tick();

      expect(component.error).toContain('Failed to load classifiers');
      expect(component.loading).toBeFalse();
    }));

    it('should clear error when clearError is called', () => {
      component.error = 'Test error';
      component.clearError();
      expect(component.error).toBeNull();
    });

    it('should show error on save failure', fakeAsync(() => {
      orchestratorServiceSpy.createOutputClassifier.and.returnValue(
        throwError(() => ({ error: { message: 'Save failed' } }))
      );

      component.newClassifier();
      component.classifierForm.patchValue({ name: 'Test' });
      component.saveClassifier();
      tick();

      expect(component.error).toContain('Failed to save classifier');
      expect(component.saving).toBeFalse();
    }));
  });

  // ==================== Available Options ====================

  describe('Available Options', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should have all classification types', () => {
      expect(component.classificationTypes).toContain('COMPILATION_ERROR');
      expect(component.classificationTypes).toContain('BUILD_SUCCESS');
      expect(component.classificationTypes).toContain('TEST_FAILURE');
      expect(component.classificationTypes).toContain('TIMEOUT');
      expect(component.classificationTypes).toContain('CUSTOM');
    });

    it('should have all severity levels', () => {
      expect(component.severityLevels).toEqual(['CRITICAL', 'ERROR', 'WARNING', 'INFO']);
    });

    it('should have all actions', () => {
      expect(component.actions).toContain('RETRY');
      expect(component.actions).toContain('INVOKE_LLM');
      expect(component.actions).toContain('ABORT');
      expect(component.actions).toContain('CONTINUE');
    });
  });

  // ==================== Compliance: Rule Pattern Validation ====================

  describe('Classification Rule Compliance', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      component.selectedClassifier = mockClassifiers[0];
    }));

    it('should have valid pattern for all rules', () => {
      mockRules.forEach(rule => {
        expect(rule.pattern).toBeTruthy();
        expect(() => new RegExp(rule.pattern)).not.toThrow();
      });
    });

    it('should have valid classification type for all rules', () => {
      mockRules.forEach(rule => {
        expect(component.classificationTypes).toContain(rule.classificationType);
      });
    });

    it('should have valid severity for all rules', () => {
      mockRules.forEach(rule => {
        expect(component.severityLevels).toContain(rule.severity);
      });
    });

    it('should have valid action for all rules', () => {
      mockRules.forEach(rule => {
        expect(component.actions).toContain(rule.action);
      });
    });

    it('should have unique rule IDs within a classifier', () => {
      const classifier = mockClassifiers[0];
      if (classifier.rules) {
        const ids = classifier.rules.map(r => r.id).filter(id => id);
        const uniqueIds = new Set(ids);
        expect(uniqueIds.size).toBe(ids.length);
      }
    });

    it('should have CRITICAL severity only for severe errors', () => {
      const criticalRules = mockRules.filter(r => r.severity === 'CRITICAL');
      criticalRules.forEach(rule => {
        // Critical severity should typically be for errors that require immediate attention
        expect(['ABORT', 'ESCALATE', 'AWAIT_APPROVAL', 'NOTIFY']).toContain(rule.action);
      });
    });

    it('should have appropriate action for error types', () => {
      const errorRules = mockRules.filter(r =>
        r.classificationType.includes('ERROR') || r.classificationType.includes('FAILURE')
      );

      errorRules.forEach(rule => {
        // Error types should not have CONTINUE as action
        if (rule.severity === 'ERROR' || rule.severity === 'CRITICAL') {
          expect(rule.action).not.toBe('CONTINUE');
        }
      });
    });

    it('should have BUILD_SUCCESS type action be non-destructive', () => {
      const successRules = mockRules.filter(r =>
        r.classificationType === 'BUILD_SUCCESS' || r.classificationType === 'TEST_SUCCESS'
      );

      successRules.forEach(rule => {
        // Success patterns should not abort or escalate
        expect(['ABORT', 'ESCALATE']).not.toContain(rule.action);
      });
    });
  });

  // ==================== Compliance: Classifier Configuration ====================

  describe('Classifier Configuration Compliance', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should have unique names for classifiers', () => {
      const names = component.classifiers.map(c => c.name);
      const uniqueNames = new Set(names);
      expect(uniqueNames.size).toBe(names.length);
    });

    it('should have orchestratorInstanceId matching current instance', () => {
      component.classifiers.forEach(classifier => {
        expect(classifier.orchestratorInstanceId).toBe('test-instance');
      });
    });

    it('should have enabled status defined', () => {
      component.classifiers.forEach(classifier => {
        expect(classifier.enabled).toBeDefined();
      });
    });

    it('should have valid defaultAction', () => {
      component.classifiers.forEach(classifier => {
        if (classifier.defaultAction) {
          expect(component.actions).toContain(classifier.defaultAction);
        }
      });
    });

    it('should have rules array (even if empty)', () => {
      component.classifiers.forEach(classifier => {
        expect(classifier.rules).toBeDefined();
        expect(Array.isArray(classifier.rules)).toBeTrue();
      });
    });
  });

  // ==================== Template Compliance ====================

  describe('Template Compliance', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should have templates with valid patterns', () => {
      component.templates.forEach(template => {
        expect(template.pattern).toBeTruthy();
        expect(() => new RegExp(template.pattern)).not.toThrow();
      });
    });

    it('should have templates covering common error types', () => {
      const templateTypes = component.templates.map(t => t.classificationType);

      // Should have at least compilation error and success templates
      const hasCommonTypes = templateTypes.some(t =>
        t === 'COMPILATION_ERROR' || t === 'BUILD_SUCCESS'
      );
      expect(hasCommonTypes).toBeTrue();
    });

    it('should have templates with proper severity mapping', () => {
      component.templates.forEach(template => {
        if (template.classificationType.includes('ERROR') ||
            template.classificationType.includes('FAILURE')) {
          expect(['CRITICAL', 'ERROR']).toContain(template.severity);
        }
        if (template.classificationType.includes('SUCCESS') ||
            template.classificationType === 'INFO') {
          expect(['INFO', 'WARNING']).toContain(template.severity);
        }
      });
    });
  });
});
