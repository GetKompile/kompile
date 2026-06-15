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

import { QueryTransformerSettingsComponent } from './query-transformer-settings.component';
import { QueryTransformerService } from '../../../services/query-transformer.service';
import {
  QueryTransformerConfig,
  TransformerType,
  TransformerPreset
} from '../../../models/rag-management.models';

function makeDefaultConfig(): QueryTransformerConfig {
  return {
    available: true,
    enabled: true,
    type: 'passthrough',
    maxQueries: 3,
    includeOriginal: true,
    systemPrompt: null,
    temperature: 0.7,
    maxTokens: 256
  };
}

function makeTransformerTypes(): TransformerType[] {
  return [
    { type: 'passthrough', name: 'Passthrough', description: 'No transformation', requiresLlm: false },
    { type: 'hyde', name: 'HyDE', description: 'Hypothetical Document Embeddings', requiresLlm: true }
  ];
}

function makePresets(): TransformerPreset[] {
  return [
    { preset: 'simple', name: 'Simple', description: 'Simple rewriting', type: 'rewrite' },
    { preset: 'advanced', name: 'Advanced', description: 'Advanced expansion', type: 'expand' }
  ];
}

describe('QueryTransformerSettingsComponent', () => {
  let component: QueryTransformerSettingsComponent;
  let fixture: ComponentFixture<QueryTransformerSettingsComponent>;
  let qTransformerSpy: jasmine.SpyObj<QueryTransformerService>;

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('QueryTransformerService', [
      'getConfig',
      'updateConfig',
      'toggle',
      'getTransformerTypes',
      'getPresets',
      'applyPreset',
      'createDefaultConfig'
    ]);

    spy.createDefaultConfig.and.returnValue(makeDefaultConfig());
    spy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: true }));
    spy.getTransformerTypes.and.returnValue(of(makeTransformerTypes()));
    spy.getPresets.and.returnValue(of(makePresets()));

    await TestBed.configureTestingModule({
      imports: [
        QueryTransformerSettingsComponent,
        CommonModule,
        FormsModule,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideProvider(QueryTransformerService, { useValue: spy })
      .overrideComponent(QueryTransformerSettingsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .compileComponents();

    qTransformerSpy = TestBed.inject(QueryTransformerService) as jasmine.SpyObj<QueryTransformerService>;
    fixture = TestBed.createComponent(QueryTransformerSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('ngOnInit', () => {
    it('should call loadConfiguration, loadTransformerTypes, loadPresets', () => {
      expect(qTransformerSpy.getConfig).toHaveBeenCalled();
      expect(qTransformerSpy.getTransformerTypes).toHaveBeenCalled();
      expect(qTransformerSpy.getPresets).toHaveBeenCalled();
    });
  });

  describe('loadConfiguration', () => {
    it('should update config when response.available is true', fakeAsync(() => {
      const config = { ...makeDefaultConfig(), type: 'rewrite' };
      qTransformerSpy.getConfig.and.returnValue(of({ ...config, available: true }));
      component.loadConfiguration();
      tick();
      expect(component.config.type).toBe('rewrite');
      expect(component.loading).toBeFalse();
    }));

    it('should not update config when response.available is false', fakeAsync(() => {
      qTransformerSpy.getConfig.and.returnValue(of({ ...makeDefaultConfig(), available: false }));
      component.config.type = 'passthrough';
      component.loadConfiguration();
      tick();
      expect(component.config.type).toBe('passthrough');
    }));

    it('should set error on failure', fakeAsync(() => {
      qTransformerSpy.getConfig.and.returnValue(
        throwError(() => ({ message: 'load error' }))
      );
      component.loadConfiguration();
      tick();
      expect(component.error).toContain('Failed to load query transformer configuration');
      expect(component.loading).toBeFalse();
    }));
  });

  describe('loadTransformerTypes', () => {
    it('should populate transformerTypes', fakeAsync(() => {
      const types = makeTransformerTypes();
      qTransformerSpy.getTransformerTypes.and.returnValue(of(types));
      component.loadTransformerTypes();
      tick();
      expect(component.transformerTypes).toEqual(types);
    }));

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      qTransformerSpy.getTransformerTypes.and.returnValue(
        throwError(() => ({ message: 'error' }))
      );
      component.loadTransformerTypes();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('loadPresets', () => {
    it('should populate presets', fakeAsync(() => {
      const presets = makePresets();
      qTransformerSpy.getPresets.and.returnValue(of(presets));
      component.loadPresets();
      tick();
      expect(component.presets).toEqual(presets);
    }));

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      qTransformerSpy.getPresets.and.returnValue(
        throwError(() => ({ message: 'error' }))
      );
      component.loadPresets();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('saveConfiguration', () => {
    it('should call updateConfig and set successMessage', fakeAsync(() => {
      const saved = makeDefaultConfig();
      qTransformerSpy.updateConfig.and.returnValue(of(saved));
      component.saveConfiguration();
      tick();
      expect(qTransformerSpy.updateConfig).toHaveBeenCalled();
      expect(component.successMessage).toBe('Configuration saved successfully');
      expect(component.saving).toBeFalse();
    }));

    it('should clear successMessage after 3 seconds', fakeAsync(() => {
      qTransformerSpy.updateConfig.and.returnValue(of(makeDefaultConfig()));
      component.saveConfiguration();
      tick();
      expect(component.successMessage).toBeTruthy();
      tick(3000);
      expect(component.successMessage).toBeNull();
    }));

    it('should set error on failure', fakeAsync(() => {
      qTransformerSpy.updateConfig.and.returnValue(
        throwError(() => ({ message: 'save error' }))
      );
      component.saveConfiguration();
      tick();
      expect(component.error).toContain('Failed to save configuration');
      expect(component.saving).toBeFalse();
    }));
  });

  describe('toggleEnabled', () => {
    it('should update config.enabled on success', fakeAsync(() => {
      component.config.enabled = true;
      qTransformerSpy.toggle.and.returnValue(of({ success: true, enabled: true, message: 'Query transformer enabled' }));
      component.toggleEnabled();
      tick();
      expect(qTransformerSpy.toggle).toHaveBeenCalledWith(true);
      expect(component.config.enabled).toBeTrue();
      expect(component.successMessage).toContain('enabled');
    }));

    it('should revert config.enabled on error', fakeAsync(() => {
      component.config.enabled = true;
      qTransformerSpy.toggle.and.returnValue(
        throwError(() => ({ message: 'toggle error' }))
      );
      component.toggleEnabled();
      tick();
      expect(component.config.enabled).toBeFalse(); // reverted
      expect(component.error).toContain('Failed to toggle query transformer');
    }));
  });

  describe('applyPreset', () => {
    it('should apply preset and set successMessage', fakeAsync(() => {
      const result = { ...makeDefaultConfig(), type: 'rewrite' };
      qTransformerSpy.applyPreset.and.returnValue(of(result));
      component.applyPreset('simple');
      tick();
      expect(qTransformerSpy.applyPreset).toHaveBeenCalledWith('simple');
      expect(component.config.type).toBe('rewrite');
      expect(component.successMessage).toContain('simple');
      expect(component.saving).toBeFalse();
    }));

    it('should set error on preset failure', fakeAsync(() => {
      qTransformerSpy.applyPreset.and.returnValue(
        throwError(() => ({ message: 'preset error' }))
      );
      component.applyPreset('bad-preset');
      tick();
      expect(component.error).toContain('Failed to apply preset');
      expect(component.saving).toBeFalse();
    }));
  });

  describe('getSelectedTypeInfo', () => {
    beforeEach(fakeAsync(() => {
      component.transformerTypes = makeTransformerTypes();
      tick();
    }));

    it('should return info for current type', () => {
      component.config.type = 'passthrough';
      const info = component.getSelectedTypeInfo();
      expect(info).toBeDefined();
      expect(info!.type).toBe('passthrough');
    });

    it('should return undefined for unknown type', () => {
      component.config.type = 'unknown';
      const info = component.getSelectedTypeInfo();
      expect(info).toBeUndefined();
    });
  });

  describe('requiresLlm', () => {
    beforeEach(fakeAsync(() => {
      component.transformerTypes = makeTransformerTypes();
      tick();
    }));

    it('should return false for passthrough', () => {
      component.config.type = 'passthrough';
      expect(component.requiresLlm()).toBeFalse();
    });

    it('should return true for hyde', () => {
      component.config.type = 'hyde';
      expect(component.requiresLlm()).toBeTrue();
    });

    it('should return false for unknown type', () => {
      component.config.type = 'unknown';
      expect(component.requiresLlm()).toBeFalse();
    });
  });

  describe('showAdvancedOptions', () => {
    it('should return false for passthrough', () => {
      component.config.type = 'passthrough';
      expect(component.showAdvancedOptions()).toBeFalse();
    });

    it('should return true for non-passthrough types', () => {
      component.config.type = 'hyde';
      expect(component.showAdvancedOptions()).toBeTrue();
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
