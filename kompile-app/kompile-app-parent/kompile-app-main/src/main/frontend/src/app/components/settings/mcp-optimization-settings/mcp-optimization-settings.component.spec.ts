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

import { McpOptimizationSettingsComponent } from './mcp-optimization-settings.component';
import {
  McpOptimizationService,
  McpOptimizationConfig
} from '../../../services/mcp-optimization.service';

function makeDefaults(): McpOptimizationConfig {
  return {
    enabled: true,
    ragMaxContentChars: 2000,
    ragMaxDocs: 3,
    filesystemStorePreviousContentInCache: true,
    filesystemUndoTtlSeconds: 3600,
    knowledgeGraphTruncateChars: 200,
    compressionThresholdChars: 4000,
    resultCacheMaxEntries: 1000,
    resultCacheTtlSeconds: 900,
    metaToolMode: 'HYBRID',
    alwaysExposedTools: [],
    toolOverrides: {}
  };
}

describe('McpOptimizationSettingsComponent', () => {
  let component: McpOptimizationSettingsComponent;
  let fixture: ComponentFixture<McpOptimizationSettingsComponent>;
  let serviceSpy: jasmine.SpyObj<McpOptimizationService>;

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('McpOptimizationService', [
      'getConfig',
      'updateConfig',
      'resetConfig',
      'defaults'
    ]);

    spy.defaults.and.returnValue(makeDefaults());
    spy.getConfig.and.returnValue(of({ ...makeDefaults(), configFilePath: '/path/mcp.yaml' }));

    await TestBed.configureTestingModule({
      imports: [
        McpOptimizationSettingsComponent,
        CommonModule,
        FormsModule,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideProvider(McpOptimizationService, { useValue: spy })
      .overrideComponent(McpOptimizationSettingsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .compileComponents();

    serviceSpy = TestBed.inject(McpOptimizationService) as jasmine.SpyObj<McpOptimizationService>;
    fixture = TestBed.createComponent(McpOptimizationSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call defaults() in constructor', () => {
    expect(serviceSpy.defaults).toHaveBeenCalled();
  });

  describe('ngOnInit', () => {
    it('should call loadConfiguration', () => {
      expect(serviceSpy.getConfig).toHaveBeenCalled();
    });
  });

  describe('loadConfiguration', () => {
    it('should apply response and set configFilePath', fakeAsync(() => {
      serviceSpy.getConfig.and.returnValue(
        of({ ...makeDefaults(), metaToolMode: 'DIRECT', configFilePath: '/path/mcp.yaml' })
      );
      component.loadConfiguration();
      tick();
      expect(component.config.metaToolMode).toBe('DIRECT');
      expect(component.configFilePath).toBe('/path/mcp.yaml');
      expect(component.loading).toBeFalse();
    }));

    it('should fill missing fields with defaults', fakeAsync(() => {
      const partial: any = { metaToolMode: 'DYNAMIC' };
      serviceSpy.getConfig.and.returnValue(of(partial));
      serviceSpy.defaults.and.returnValue(makeDefaults());
      component.loadConfiguration();
      tick();
      expect(component.config.ragMaxContentChars).toBe(2000); // from defaults
      expect(component.config.metaToolMode).toBe('DYNAMIC'); // from response
    }));

    it('should set error on failure', fakeAsync(() => {
      serviceSpy.getConfig.and.returnValue(throwError(() => ({ message: 'load error' })));
      component.loadConfiguration();
      tick();
      expect(component.error).toContain('Failed to load MCP optimization configuration');
      expect(component.loading).toBeFalse();
    }));
  });

  describe('saveConfiguration', () => {
    it('should call updateConfig and set successMessage', fakeAsync(() => {
      const saved = { ...makeDefaults(), message: 'Configuration saved', configFilePath: '/mcp.yaml' };
      serviceSpy.updateConfig.and.returnValue(of(saved));
      component.saveConfiguration();
      tick();
      expect(serviceSpy.updateConfig).toHaveBeenCalled();
      expect(component.successMessage).toBe('Configuration saved');
      expect(component.saving).toBeFalse();
    }));

    it('should use fallback message if response.message is absent', fakeAsync(() => {
      const responseWithoutMessage: any = { ...makeDefaults() };
      delete responseWithoutMessage.message;
      serviceSpy.updateConfig.and.returnValue(of(responseWithoutMessage));
      component.saveConfiguration();
      tick();
      expect(component.successMessage).toBe('Configuration saved');
    }));

    it('should clear successMessage after 3 seconds', fakeAsync(() => {
      serviceSpy.updateConfig.and.returnValue(of({ ...makeDefaults(), message: 'Saved' }));
      component.saveConfiguration();
      tick();
      expect(component.successMessage).toBeTruthy();
      tick(3000);
      expect(component.successMessage).toBeNull();
    }));

    it('should set error on failure', fakeAsync(() => {
      serviceSpy.updateConfig.and.returnValue(throwError(() => ({ message: 'save error' })));
      component.saveConfiguration();
      tick();
      expect(component.error).toContain('Failed to save configuration');
      expect(component.saving).toBeFalse();
    }));
  });

  describe('resetConfiguration', () => {
    it('should prompt and call resetConfig on confirm', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(true);
      serviceSpy.resetConfig.and.returnValue(
        of({ ...makeDefaults(), message: 'Configuration reset to defaults' })
      );
      component.resetConfiguration();
      tick();
      expect(serviceSpy.resetConfig).toHaveBeenCalled();
      expect(component.successMessage).toBe('Configuration reset to defaults');
    }));

    it('should not reset if user cancels confirm', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(false);
      component.resetConfiguration();
      tick();
      expect(serviceSpy.resetConfig).not.toHaveBeenCalled();
    }));

    it('should set error on failure', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(true);
      serviceSpy.resetConfig.and.returnValue(throwError(() => ({ message: 'reset error' })));
      component.resetConfiguration();
      tick();
      expect(component.error).toContain('Failed to reset configuration');
    }));
  });

  describe('addAlwaysExposedTool', () => {
    it('should add a unique tool to the list', () => {
      component.config.alwaysExposedTools = [];
      component.newAlwaysExposedTool = 'rag_query';
      component.addAlwaysExposedTool();
      expect(component.config.alwaysExposedTools).toContain('rag_query');
      expect(component.newAlwaysExposedTool).toBe('');
    });

    it('should not add blank input', () => {
      component.config.alwaysExposedTools = [];
      component.newAlwaysExposedTool = '   ';
      component.addAlwaysExposedTool();
      expect(component.config.alwaysExposedTools!.length).toBe(0);
    });

    it('should not add duplicate', () => {
      component.config.alwaysExposedTools = ['rag_query'];
      component.newAlwaysExposedTool = 'rag_query';
      component.addAlwaysExposedTool();
      expect(component.config.alwaysExposedTools!.length).toBe(1);
    });

    it('should handle null alwaysExposedTools', () => {
      component.config.alwaysExposedTools = null;
      component.newAlwaysExposedTool = 'rag_query';
      component.addAlwaysExposedTool();
      expect(component.config.alwaysExposedTools).toContain('rag_query');
    });
  });

  describe('removeAlwaysExposedTool', () => {
    it('should remove tool from the list', () => {
      component.config.alwaysExposedTools = ['rag_query', 'read_file'];
      component.removeAlwaysExposedTool('rag_query');
      expect(component.config.alwaysExposedTools).not.toContain('rag_query');
      expect(component.config.alwaysExposedTools).toContain('read_file');
    });

    it('should handle null alwaysExposedTools gracefully', () => {
      component.config.alwaysExposedTools = null;
      expect(() => component.removeAlwaysExposedTool('rag_query')).not.toThrow();
    });
  });

  describe('metaToolModes', () => {
    it('should have 3 modes', () => {
      expect(component.metaToolModes.length).toBe(3);
    });

    it('should include DIRECT, DYNAMIC, HYBRID', () => {
      const values = component.metaToolModes.map(m => m.value);
      expect(values).toContain('DIRECT');
      expect(values).toContain('DYNAMIC');
      expect(values).toContain('HYBRID');
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
