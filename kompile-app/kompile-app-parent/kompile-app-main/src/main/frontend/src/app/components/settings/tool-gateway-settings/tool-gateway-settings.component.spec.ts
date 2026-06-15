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

import { ToolGatewaySettingsComponent } from './tool-gateway-settings.component';
import { ToolGatewayService } from '../../../services/tool-gateway.service';
import { ToolGatewayConfig, ToolGatewayRule } from '../../../models/tool-gateway.models';

function makeDefaultConfig(): ToolGatewayConfig {
  return {
    available: true,
    enabled: false,
    failOpen: true,
    evaluationTimeoutMs: 10000,
    verboseLogging: false,
    hotReload: false,
    dryRun: false,
    rulesFilePath: '',
    defaultAction: 'ALLOW',
    systemPrompt: null,
    rulesCount: 0,
    enabledRulesCount: 0,
    model: {
      configured: false,
      baseUrl: null,
      apiKeySet: false,
      modelName: null,
      temperature: 0.0
    }
  };
}

function makeRule(id: string): ToolGatewayRule {
  return {
    id,
    description: 'Test rule',
    toolPatterns: ['*'],
    condition: 'always',
    action: 'BLOCK',
    blockMessage: null,
    rewriteInstructions: null,
    priority: 10,
    enabled: true
  };
}

describe('ToolGatewaySettingsComponent', () => {
  let component: ToolGatewaySettingsComponent;
  let fixture: ComponentFixture<ToolGatewaySettingsComponent>;
  let gatewaySpy: jasmine.SpyObj<ToolGatewayService>;

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('ToolGatewayService', [
      'getConfig',
      'updateConfig',
      'toggle',
      'getRules',
      'addRule',
      'deleteRule',
      'reloadRules',
      'createDefaultConfig'
    ]);

    spy.createDefaultConfig.and.returnValue(makeDefaultConfig());
    spy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: true }));
    spy.getRules.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [
        ToolGatewaySettingsComponent,
        CommonModule,
        FormsModule,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideProvider(ToolGatewayService, { useValue: spy })
      .overrideComponent(ToolGatewaySettingsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .compileComponents();

    gatewaySpy = TestBed.inject(ToolGatewayService) as jasmine.SpyObj<ToolGatewayService>;
    fixture = TestBed.createComponent(ToolGatewaySettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('ngOnInit', () => {
    it('should call loadConfiguration', () => {
      expect(gatewaySpy.getConfig).toHaveBeenCalled();
    });
  });

  describe('loadConfiguration', () => {
    it('should populate config and model fields when available', fakeAsync(() => {
      const config: ToolGatewayConfig = {
        ...makeDefaultConfig(),
        available: true,
        model: { configured: true, baseUrl: 'http://llm', apiKeySet: true, modelName: 'gpt-4', temperature: 0.5 }
      };
      gatewaySpy.getConfig.and.returnValue(of(config));
      component.loadConfiguration();
      tick();

      expect(component.config.available).toBeTrue();
      expect(component.modelBaseUrl).toBe('http://llm');
      expect(component.modelName).toBe('gpt-4');
      expect(component.modelTemperature).toBe(0.5);
      expect(component.modelApiKey).toBe(''); // never populated from server
    }));

    it('should use default config when response.available is false', fakeAsync(() => {
      gatewaySpy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: false }));
      gatewaySpy.createDefaultConfig.and.returnValue(makeDefaultConfig());
      component.loadConfiguration();
      tick();
      expect(component.config.available).toBeFalse();
    }));

    it('should not call loadRules when not available', fakeAsync(() => {
      gatewaySpy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: false }));
      const callCountBefore = gatewaySpy.getRules.calls.count();
      component.loadConfiguration();
      tick();
      expect(component.config.available).toBeFalse();
      // getRules should NOT have been called during this invocation
      expect(gatewaySpy.getRules.calls.count()).toBe(callCountBefore);
    }));

    it('should call loadRules when available', fakeAsync(() => {
      gatewaySpy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: true }));
      component.loadConfiguration();
      tick();
      expect(gatewaySpy.getRules).toHaveBeenCalled();
    }));

    it('should set error on failure', fakeAsync(() => {
      gatewaySpy.getConfig.and.returnValue(throwError(() => ({ message: 'load error' })));
      component.loadConfiguration();
      tick();
      expect(component.error).toContain('Failed to load tool gateway configuration');
      expect(component.loading).toBeFalse();
    }));
  });

  describe('loadRules', () => {
    it('should populate rules', fakeAsync(() => {
      const rules = [makeRule('r1'), makeRule('r2')];
      gatewaySpy.getRules.and.returnValue(of(rules));
      component.loadRules();
      tick();
      expect(component.rules).toEqual(rules);
    }));

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      gatewaySpy.getRules.and.returnValue(throwError(() => ({ message: 'err' })));
      component.loadRules();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('saveConfiguration', () => {
    it('should build payload with model fields and call updateConfig', fakeAsync(() => {
      component.config = makeDefaultConfig();
      component.modelBaseUrl = 'http://llm';
      component.modelApiKey = 'sk-secret';
      component.modelName = 'gpt-4';
      component.modelTemperature = 0.3;

      const savedConfig: ToolGatewayConfig = {
        ...makeDefaultConfig(),
        model: { configured: true, baseUrl: 'http://llm', apiKeySet: true, modelName: 'gpt-4', temperature: 0.3 }
      };
      gatewaySpy.updateConfig.and.returnValue(of(savedConfig));
      component.saveConfiguration();
      tick();

      expect(gatewaySpy.updateConfig).toHaveBeenCalled();
      const payload = gatewaySpy.updateConfig.calls.mostRecent().args[0] as any;
      expect(payload.model.baseUrl).toBe('http://llm');
      expect(payload.model.apiKey).toBe('sk-secret');
      expect(payload.model.modelName).toBe('gpt-4');
      expect(payload.model.temperature).toBe(0.3);
      expect(component.successMessage).toBe('Configuration saved successfully');
      expect(component.modelApiKey).toBe(''); // cleared after save
    }));

    it('should clear successMessage after 3 seconds', fakeAsync(() => {
      gatewaySpy.updateConfig.and.returnValue(of(makeDefaultConfig()));
      component.saveConfiguration();
      tick();
      expect(component.successMessage).toBeTruthy();
      tick(3000);
      expect(component.successMessage).toBeNull();
    }));

    it('should set error on failure', fakeAsync(() => {
      gatewaySpy.updateConfig.and.returnValue(throwError(() => ({ message: 'save error' })));
      component.saveConfiguration();
      tick();
      expect(component.error).toContain('Failed to save configuration');
      expect(component.saving).toBeFalse();
    }));
  });

  describe('toggleEnabled', () => {
    it('should update config.enabled and show success', fakeAsync(() => {
      component.config.enabled = true;
      gatewaySpy.toggle.and.returnValue(of({ success: true, enabled: true, message: 'Tool gateway enabled' }));
      component.toggleEnabled();
      tick();
      expect(gatewaySpy.toggle).toHaveBeenCalledWith(true);
      expect(component.config.enabled).toBeTrue();
      expect(component.successMessage).toContain('enabled');
    }));

    it('should revert config.enabled on error', fakeAsync(() => {
      component.config.enabled = true;
      gatewaySpy.toggle.and.returnValue(throwError(() => ({ message: 'err' })));
      component.toggleEnabled();
      tick();
      expect(component.config.enabled).toBeFalse();
      expect(component.error).toContain('Failed to toggle tool gateway');
    }));
  });

  describe('addToolPattern / removeToolPattern', () => {
    it('should add a new unique pattern', () => {
      component.newRule.toolPatterns = [];
      component.newToolPattern = 'rag_*';
      component.addToolPattern();
      expect(component.newRule.toolPatterns).toContain('rag_*');
      expect(component.newToolPattern).toBe('');
    });

    it('should not add duplicate pattern', () => {
      component.newRule.toolPatterns = ['rag_*'];
      component.newToolPattern = 'rag_*';
      component.addToolPattern();
      expect(component.newRule.toolPatterns.length).toBe(1);
    });

    it('should not add blank pattern', () => {
      component.newToolPattern = '  ';
      component.addToolPattern();
      expect(component.newRule.toolPatterns.length).toBe(0);
    });

    it('should remove existing pattern', () => {
      component.newRule.toolPatterns = ['rag_*', 'fs_*'];
      component.removeToolPattern('rag_*');
      expect(component.newRule.toolPatterns).not.toContain('rag_*');
      expect(component.newRule.toolPatterns).toContain('fs_*');
    });
  });

  describe('submitNewRule', () => {
    it('should not submit if id or condition is missing', fakeAsync(() => {
      component.newRule.id = '';
      component.newRule.condition = '';
      component.submitNewRule();
      tick();
      expect(gatewaySpy.addRule).not.toHaveBeenCalled();
    }));

    it('should call addRule and reset form on success', fakeAsync(() => {
      component.newRule.id = 'r1';
      component.newRule.condition = 'always';
      gatewaySpy.addRule.and.returnValue(of({}));
      gatewaySpy.getRules.and.returnValue(of([makeRule('r1')]));
      component.submitNewRule();
      tick();
      expect(gatewaySpy.addRule).toHaveBeenCalled();
      expect(component.successMessage).toContain('r1');
      expect(component.showNewRuleForm).toBeFalse();
      expect(component.newRule.id).toBe('');
    }));

    it('should set error on failure', fakeAsync(() => {
      component.newRule.id = 'r1';
      component.newRule.condition = 'always';
      gatewaySpy.addRule.and.returnValue(throwError(() => ({ message: 'add err' })));
      component.submitNewRule();
      tick();
      expect(component.error).toContain('Failed to add rule');
    }));
  });

  describe('deleteRule', () => {
    it('should call deleteRule and reload', fakeAsync(() => {
      gatewaySpy.deleteRule.and.returnValue(of({}));
      gatewaySpy.getRules.and.returnValue(of([]));
      component.deleteRule('r1');
      tick();
      expect(gatewaySpy.deleteRule).toHaveBeenCalledWith('r1');
      expect(component.successMessage).toContain('r1');
    }));

    it('should set error on failure', fakeAsync(() => {
      gatewaySpy.deleteRule.and.returnValue(throwError(() => ({ message: 'delete err' })));
      component.deleteRule('r1');
      tick();
      expect(component.error).toContain('Failed to delete rule');
    }));
  });

  describe('reloadRules', () => {
    it('should reload rules and config', fakeAsync(() => {
      gatewaySpy.reloadRules.and.returnValue(of({}));
      gatewaySpy.getRules.and.returnValue(of([makeRule('r1')]));
      gatewaySpy.getConfig.and.returnValue(of(makeDefaultConfig()));
      component.reloadRules();
      tick();
      expect(gatewaySpy.reloadRules).toHaveBeenCalled();
      expect(component.successMessage).toContain('reloaded');
    }));

    it('should set error on failure', fakeAsync(() => {
      gatewaySpy.reloadRules.and.returnValue(throwError(() => ({ message: 'reload err' })));
      component.reloadRules();
      tick();
      expect(component.error).toContain('Failed to reload rules');
    }));
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
