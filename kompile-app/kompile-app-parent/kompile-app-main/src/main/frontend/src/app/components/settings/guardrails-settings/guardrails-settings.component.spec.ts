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

import { GuardrailsSettingsComponent } from './guardrails-settings.component';
import { GuardrailsService } from '../../../services/guardrails.service';
import { GuardrailsConfig, AvailableGuardrails } from '../../../models/rag-management.models';

function makeDefaultConfig(): GuardrailsConfig {
  return {
    available: true,
    enabled: false,
    maxRetries: 2,
    input: {
      promptInjection: { enabled: false, threshold: 0.7 },
      toxicity: { enabled: false, threshold: 0.7, categories: [] },
      pii: {
        enabled: false,
        detectEmail: true,
        detectPhone: true,
        detectSsn: true,
        detectCreditCard: true,
        blockOnDetection: true
      },
      topic: { enabled: false, allowedTopics: [], blockedTopics: [] }
    },
    output: {
      hallucination: { enabled: false, threshold: 0.7, supportsRetry: true },
      format: { enabled: false, expectedFormat: null, maxLength: 0, minLength: 0 },
      relevancy: { enabled: false, threshold: 0.5, supportsRetry: true }
    }
  };
}

function makeAvailableGuardrails(): AvailableGuardrails {
  return {
    available: true,
    inputGuardrails: [
      { name: 'PII', categories: [], priority: 1, requiresLlm: false }
    ],
    outputGuardrails: [
      { name: 'Hallucination', categories: [], priority: 1, requiresLlm: true }
    ]
  };
}

describe('GuardrailsSettingsComponent', () => {
  let component: GuardrailsSettingsComponent;
  let fixture: ComponentFixture<GuardrailsSettingsComponent>;
  let guardrailsSpy: jasmine.SpyObj<GuardrailsService>;

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('GuardrailsService', [
      'getConfig',
      'updateConfig',
      'getAvailableGuardrails',
      'toggle',
      'createDefaultConfig'
    ]);

    spy.createDefaultConfig.and.returnValue(makeDefaultConfig());
    spy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: true }));
    spy.getAvailableGuardrails.and.returnValue(of(makeAvailableGuardrails()));

    await TestBed.configureTestingModule({
      imports: [
        GuardrailsSettingsComponent,
        CommonModule,
        FormsModule,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideProvider(GuardrailsService, { useValue: spy })
      .overrideComponent(GuardrailsSettingsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .compileComponents();

    guardrailsSpy = TestBed.inject(GuardrailsService) as jasmine.SpyObj<GuardrailsService>;
    fixture = TestBed.createComponent(GuardrailsSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call createDefaultConfig in constructor', () => {
    expect(guardrailsSpy.createDefaultConfig).toHaveBeenCalled();
  });

  describe('ngOnInit', () => {
    it('should call loadConfiguration', () => {
      expect(guardrailsSpy.getConfig).toHaveBeenCalled();
    });
  });

  describe('loadConfiguration', () => {
    it('should update config when response.available is true', fakeAsync(() => {
      const config = { ...makeDefaultConfig(), enabled: true };
      guardrailsSpy.getConfig.and.returnValue(of({ ...config, available: true }));
      component.loadConfiguration();
      tick();
      expect(component.config.enabled).toBeTrue();
      expect(component.loading).toBeFalse();
    }));

    it('should call loadAvailableGuardrails after load', fakeAsync(() => {
      guardrailsSpy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: true }));
      component.loadConfiguration();
      tick();
      expect(guardrailsSpy.getAvailableGuardrails).toHaveBeenCalled();
    }));

    it('should set error on failure', fakeAsync(() => {
      guardrailsSpy.getConfig.and.returnValue(
        throwError(() => ({ message: 'load error' }))
      );
      component.loadConfiguration();
      tick();
      expect(component.error).toContain('Failed to load guardrails configuration');
      expect(component.loading).toBeFalse();
    }));
  });

  describe('loadAvailableGuardrails', () => {
    it('should populate availableGuardrails', fakeAsync(() => {
      const response = makeAvailableGuardrails();
      guardrailsSpy.getAvailableGuardrails.and.returnValue(of(response));
      component.loadAvailableGuardrails();
      tick();
      expect(component.availableGuardrails).toEqual(response);
    }));

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      guardrailsSpy.getAvailableGuardrails.and.returnValue(
        throwError(() => ({ message: 'error' }))
      );
      component.loadAvailableGuardrails();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('saveConfiguration', () => {
    it('should call updateConfig and set successMessage', fakeAsync(() => {
      const saved = makeDefaultConfig();
      guardrailsSpy.updateConfig.and.returnValue(of(saved));
      component.saveConfiguration();
      tick();
      expect(guardrailsSpy.updateConfig).toHaveBeenCalled();
      expect(component.successMessage).toBe('Configuration saved successfully');
      expect(component.saving).toBeFalse();
    }));

    it('should clear successMessage after 3 seconds', fakeAsync(() => {
      guardrailsSpy.updateConfig.and.returnValue(of(makeDefaultConfig()));
      component.saveConfiguration();
      tick();
      expect(component.successMessage).toBeTruthy();
      tick(3000);
      expect(component.successMessage).toBeNull();
    }));

    it('should set error on failure', fakeAsync(() => {
      guardrailsSpy.updateConfig.and.returnValue(
        throwError(() => ({ message: 'save failed' }))
      );
      component.saveConfiguration();
      tick();
      expect(component.error).toContain('Failed to save configuration');
      expect(component.saving).toBeFalse();
    }));
  });

  describe('toggleEnabled', () => {
    it('should update config.enabled and show success', fakeAsync(() => {
      component.config.enabled = true;
      guardrailsSpy.toggle.and.returnValue(of({ success: true, enabled: true, message: 'Guardrails enabled' }));
      component.toggleEnabled();
      tick();
      expect(guardrailsSpy.toggle).toHaveBeenCalledWith(true);
      expect(component.config.enabled).toBeTrue();
      expect(component.successMessage).toContain('enabled');
    }));

    it('should revert config.enabled on error', fakeAsync(() => {
      component.config.enabled = true;
      guardrailsSpy.toggle.and.returnValue(
        throwError(() => ({ message: 'toggle error' }))
      );
      component.toggleEnabled();
      tick();
      expect(component.config.enabled).toBeFalse(); // reverted
      expect(component.error).toContain('Failed to toggle guardrails');
    }));
  });

  describe('topic management', () => {
    beforeEach(() => {
      component.config.input.topic.allowedTopics = [];
      component.config.input.topic.blockedTopics = [];
    });

    it('addAllowedTopic should add unique topic', () => {
      component.newAllowedTopic = 'AI';
      component.addAllowedTopic();
      expect(component.config.input.topic.allowedTopics).toContain('AI');
      expect(component.newAllowedTopic).toBe('');
    });

    it('addAllowedTopic should not add duplicate', () => {
      component.config.input.topic.allowedTopics = ['AI'];
      component.newAllowedTopic = 'AI';
      component.addAllowedTopic();
      expect(component.config.input.topic.allowedTopics.length).toBe(1);
    });

    it('addAllowedTopic should ignore blank input', () => {
      component.newAllowedTopic = '   ';
      component.addAllowedTopic();
      expect(component.config.input.topic.allowedTopics.length).toBe(0);
    });

    it('removeAllowedTopic should remove existing topic', () => {
      component.config.input.topic.allowedTopics = ['AI', 'ML'];
      component.removeAllowedTopic('AI');
      expect(component.config.input.topic.allowedTopics).not.toContain('AI');
    });

    it('addBlockedTopic should add unique topic', () => {
      component.newBlockedTopic = 'Spam';
      component.addBlockedTopic();
      expect(component.config.input.topic.blockedTopics).toContain('Spam');
      expect(component.newBlockedTopic).toBe('');
    });

    it('addBlockedTopic should not add duplicate', () => {
      component.config.input.topic.blockedTopics = ['Spam'];
      component.newBlockedTopic = 'Spam';
      component.addBlockedTopic();
      expect(component.config.input.topic.blockedTopics.length).toBe(1);
    });

    it('removeBlockedTopic should remove existing topic', () => {
      component.config.input.topic.blockedTopics = ['Spam', 'Ads'];
      component.removeBlockedTopic('Spam');
      expect(component.config.input.topic.blockedTopics).not.toContain('Spam');
    });
  });

  describe('formatThreshold', () => {
    it('should format 0.7 as "70%"', () => {
      expect(component.formatThreshold(0.7)).toBe('70%');
    });
    it('should format 0 as "0%"', () => {
      expect(component.formatThreshold(0)).toBe('0%');
    });
    it('should format 1 as "100%"', () => {
      expect(component.formatThreshold(1)).toBe('100%');
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
});
