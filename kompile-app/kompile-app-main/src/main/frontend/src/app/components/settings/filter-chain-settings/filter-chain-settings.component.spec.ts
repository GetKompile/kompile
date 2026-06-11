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
import { MatDialog } from '@angular/material/dialog';
import { CdkDragDrop } from '@angular/cdk/drag-drop';

import { FilterChainSettingsComponent } from './filter-chain-settings.component';
import { FilterChainService } from '../../../services/filter-chain.service';
import {
  FilterChainConfig,
  FilterConfig,
  FilterInfo,
  ConfigUpdateResponse,
  ToggleFilterResponse
} from '../../../models/filter-chain.models';

function makeFilter(id: string, name: string, priority = 10): FilterConfig {
  return {
    id,
    name,
    type: 'LOCAL',
    enabled: true,
    priority,
    phases: ['PRE_RETRIEVAL'],
    settings: {}
  };
}

function makeFilterChainConfig(filters: FilterConfig[] = []): FilterChainConfig {
  return {
    available: true,
    enabled: true,
    globalTimeoutMs: 5000,
    continueOnError: true,
    tracingEnabled: false,
    filters
  };
}

function makeConfigUpdateResponse(
  config: FilterChainConfig,
  success = true,
  error?: string
): ConfigUpdateResponse {
  return { success, config, error };
}

describe('FilterChainSettingsComponent', () => {
  let component: FilterChainSettingsComponent;
  let fixture: ComponentFixture<FilterChainSettingsComponent>;
  let filterChainSpy: jasmine.SpyObj<FilterChainService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  beforeEach(async () => {
    const fSpy = jasmine.createSpyObj('FilterChainService', [
      'getConfig',
      'updateConfig',
      'getFilters',
      'addFilter',
      'updateFilter',
      'deleteFilter',
      'toggleFilter',
      'toggleEnabled',
      'resetConfig',
      'createDefaultFilter',
      'createHttpFilter',
      'createMcpFilter',
      'getPhaseDisplayName',
      'getTypeDisplayName'
    ]);

    const dSpy = jasmine.createSpyObj('MatDialog', ['open']);

    fSpy.getConfig.and.returnValue(of(makeFilterChainConfig()));
    fSpy.getFilters.and.returnValue(of({ configuredFilters: [], availableFilters: [] }));
    fSpy.createDefaultFilter.and.returnValue(makeFilter('', 'New Filter'));
    fSpy.createHttpFilter.and.returnValue({ ...makeFilter('', 'HTTP Filter'), type: 'HTTP' });
    fSpy.createMcpFilter.and.returnValue({ ...makeFilter('', 'MCP Filter'), type: 'MCP' });
    fSpy.getPhaseDisplayName.and.callFake((p: string) => p);
    fSpy.getTypeDisplayName.and.callFake((t: string) => t);

    await TestBed.configureTestingModule({
      imports: [
        FilterChainSettingsComponent,
        CommonModule,
        FormsModule,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideProvider(FilterChainService, { useValue: fSpy })
      .overrideProvider(MatDialog, { useValue: dSpy })
      .overrideComponent(FilterChainSettingsComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .compileComponents();

    filterChainSpy = TestBed.inject(FilterChainService) as jasmine.SpyObj<FilterChainService>;
    dialogSpy = TestBed.inject(MatDialog) as jasmine.SpyObj<MatDialog>;
    fixture = TestBed.createComponent(FilterChainSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('ngOnInit', () => {
    it('should call loadConfiguration on init', () => {
      expect(filterChainSpy.getConfig).toHaveBeenCalled();
    });
  });

  describe('loadConfiguration', () => {
    it('should set config and configuredFilters', fakeAsync(() => {
      const filters = [makeFilter('f1', 'Filter1')];
      filterChainSpy.getConfig.and.returnValue(of(makeFilterChainConfig(filters)));
      component.loadConfiguration();
      tick();
      expect(component.config).toBeDefined();
      expect(component.configuredFilters.length).toBe(1);
      expect(component.loading).toBeFalse();
    }));

    it('should call loadAvailableFilters after loading', fakeAsync(() => {
      filterChainSpy.getConfig.and.returnValue(of(makeFilterChainConfig()));
      component.loadConfiguration();
      tick();
      expect(filterChainSpy.getFilters).toHaveBeenCalled();
    }));

    it('should set error on failure', fakeAsync(() => {
      filterChainSpy.getConfig.and.returnValue(
        throwError(() => ({ message: 'load error' }))
      );
      component.loadConfiguration();
      tick();
      expect(component.error).toContain('Failed to load filter chain configuration');
      expect(component.loading).toBeFalse();
    }));
  });

  describe('loadAvailableFilters', () => {
    it('should populate availableFilters', fakeAsync(() => {
      const filterInfo: FilterInfo = {
        id: 'pii', name: 'PII', description: 'PII filter',
        type: 'LOCAL', enabled: true, priority: 100
      };
      filterChainSpy.getFilters.and.returnValue(
        of({ configuredFilters: [], availableFilters: [filterInfo] })
      );
      component.loadAvailableFilters();
      tick();
      expect(component.availableFilters.length).toBe(1);
    }));

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      filterChainSpy.getFilters.and.returnValue(throwError(() => ({ message: 'err' })));
      component.loadAvailableFilters();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('saveConfiguration', () => {
    it('should do nothing if config is null', fakeAsync(() => {
      component.config = null;
      component.saveConfiguration();
      tick();
      expect(filterChainSpy.updateConfig).not.toHaveBeenCalled();
    }));

    it('should call updateConfig and set successMessage on success', fakeAsync(() => {
      component.config = makeFilterChainConfig();
      component.configuredFilters = [];
      filterChainSpy.updateConfig.and.returnValue(of(makeConfigUpdateResponse(makeFilterChainConfig())));
      component.saveConfiguration();
      tick();
      expect(filterChainSpy.updateConfig).toHaveBeenCalled();
      expect(component.successMessage).toBe('Configuration saved successfully');
      expect(component.saving).toBeFalse();
    }));

    it('should set error when success=false', fakeAsync(() => {
      component.config = makeFilterChainConfig();
      filterChainSpy.updateConfig.and.returnValue(
        of(makeConfigUpdateResponse(makeFilterChainConfig(), false, 'validation error'))
      );
      component.saveConfiguration();
      tick();
      expect(component.error).toBe('validation error');
    }));

    it('should set error on HTTP failure', fakeAsync(() => {
      component.config = makeFilterChainConfig();
      filterChainSpy.updateConfig.and.returnValue(throwError(() => ({ message: 'http error' })));
      component.saveConfiguration();
      tick();
      expect(component.error).toContain('Failed to save configuration');
    }));
  });

  describe('toggleEnabled', () => {
    it('should do nothing if config is null', fakeAsync(() => {
      component.config = null;
      component.toggleEnabled();
      tick();
      expect(filterChainSpy.toggleEnabled).not.toHaveBeenCalled();
    }));

    it('should toggle enabled state and set successMessage', fakeAsync(() => {
      component.config = makeFilterChainConfig();
      component.config.enabled = true;
      const toggleResp: ToggleFilterResponse = { success: true, enabled: false };
      filterChainSpy.toggleEnabled.and.returnValue(of(toggleResp));
      component.toggleEnabled();
      tick();
      expect(component.config.enabled).toBeFalse();
    }));

    it('should set error on failure', fakeAsync(() => {
      component.config = makeFilterChainConfig();
      filterChainSpy.toggleEnabled.and.returnValue(throwError(() => ({ message: 'err' })));
      component.toggleEnabled();
      tick();
      expect(component.error).toContain('Failed to toggle filter chain');
    }));
  });

  describe('toggleFilter', () => {
    it('should toggle individual filter', fakeAsync(() => {
      const filter = makeFilter('f1', 'Filter1');
      filter.enabled = true;
      filterChainSpy.toggleFilter.and.returnValue(of({ success: true, filterId: 'f1', enabled: false }));
      component.toggleFilter(filter);
      tick();
      expect(filter.enabled).toBeFalse();
    }));

    it('should set error on failure', fakeAsync(() => {
      const filter = makeFilter('f1', 'Filter1');
      filterChainSpy.toggleFilter.and.returnValue(throwError(() => ({ message: 'err' })));
      component.toggleFilter(filter);
      tick();
      expect(component.error).toContain('Failed to toggle filter');
    }));
  });

  describe('addNewFilter', () => {
    it('should create LOCAL filter when type is LOCAL', () => {
      component.addNewFilter('LOCAL');
      expect(filterChainSpy.createDefaultFilter).toHaveBeenCalled();
      expect(component.editingFilter).toBeDefined();
      expect(component.isNewFilter).toBeTrue();
    });

    it('should create HTTP filter when type is HTTP', () => {
      component.addNewFilter('HTTP');
      expect(filterChainSpy.createHttpFilter).toHaveBeenCalled();
    });

    it('should create MCP filter when type is MCP', () => {
      component.addNewFilter('MCP');
      expect(filterChainSpy.createMcpFilter).toHaveBeenCalled();
    });
  });

  describe('editFilter', () => {
    it('should copy the filter for editing', () => {
      const filter = makeFilter('f1', 'Filter1');
      component.editFilter(filter);
      expect(component.editingFilter).not.toBe(filter); // shallow copy
      expect(component.editingFilter!.id).toBe('f1');
      expect(component.isNewFilter).toBeFalse();
    });

    it('should copy remoteConfig if present', () => {
      const filter: FilterConfig = {
        ...makeFilter('f1', 'Filter1'),
        type: 'HTTP',
        remoteConfig: { endpoint: 'http://example.com', httpMethod: 'POST' }
      };
      component.editFilter(filter);
      expect(component.editingFilter!.remoteConfig).not.toBe(filter.remoteConfig);
      expect(component.editingFilter!.remoteConfig!.endpoint).toBe('http://example.com');
    });
  });

  describe('cancelEdit', () => {
    it('should clear editingFilter and isNewFilter', () => {
      component.editingFilter = makeFilter('f1', 'F1');
      component.isNewFilter = true;
      component.cancelEdit();
      expect(component.editingFilter).toBeNull();
      expect(component.isNewFilter).toBeFalse();
    });
  });

  describe('saveFilter', () => {
    it('should do nothing if editingFilter is null', fakeAsync(() => {
      component.editingFilter = null;
      component.saveFilter();
      tick();
      expect(filterChainSpy.addFilter).not.toHaveBeenCalled();
      expect(filterChainSpy.updateFilter).not.toHaveBeenCalled();
    }));

    it('should call addFilter for new filter', fakeAsync(() => {
      component.editingFilter = makeFilter('new', 'New');
      component.isNewFilter = true;
      filterChainSpy.addFilter.and.returnValue(of(makeConfigUpdateResponse(makeFilterChainConfig())));
      component.saveFilter();
      tick();
      expect(filterChainSpy.addFilter).toHaveBeenCalled();
      expect(component.successMessage).toBe('Filter added');
      expect(component.editingFilter).toBeNull();
    }));

    it('should call updateFilter for existing filter', fakeAsync(() => {
      const filter = makeFilter('f1', 'F1');
      component.editingFilter = filter;
      component.isNewFilter = false;
      filterChainSpy.updateFilter.and.returnValue(of(makeConfigUpdateResponse(makeFilterChainConfig())));
      component.saveFilter();
      tick();
      expect(filterChainSpy.updateFilter).toHaveBeenCalledWith('f1', filter);
      expect(component.successMessage).toBe('Filter updated');
    }));

    it('should set error on failure', fakeAsync(() => {
      component.editingFilter = makeFilter('new', 'New');
      component.isNewFilter = true;
      filterChainSpy.addFilter.and.returnValue(throwError(() => ({ message: 'save err' })));
      component.saveFilter();
      tick();
      expect(component.error).toContain('Failed to save filter');
    }));
  });

  describe('deleteFilter', () => {
    it('should prompt and call deleteFilter on confirm', fakeAsync(() => {
      const filter = makeFilter('f1', 'Filter1');
      spyOn(window, 'confirm').and.returnValue(true);
      filterChainSpy.deleteFilter.and.returnValue(of(makeConfigUpdateResponse(makeFilterChainConfig())));
      component.deleteFilter(filter);
      tick();
      expect(filterChainSpy.deleteFilter).toHaveBeenCalledWith('f1');
      expect(component.successMessage).toBe('Filter deleted');
    }));

    it('should not delete if user cancels confirm', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(false);
      component.deleteFilter(makeFilter('f1', 'F1'));
      tick();
      expect(filterChainSpy.deleteFilter).not.toHaveBeenCalled();
    }));

    it('should set error on deletion failure', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(true);
      filterChainSpy.deleteFilter.and.returnValue(throwError(() => ({ message: 'err' })));
      component.deleteFilter(makeFilter('f1', 'F1'));
      tick();
      expect(component.error).toContain('Failed to delete filter');
    }));
  });

  describe('onDrop', () => {
    it('should reorder filters and update priorities', () => {
      component.configuredFilters = [
        makeFilter('f1', 'F1', 10),
        makeFilter('f2', 'F2', 20),
        makeFilter('f3', 'F3', 30)
      ];
      const event = {
        previousIndex: 0,
        currentIndex: 2,
        item: null as any,
        container: null as any,
        previousContainer: null as any,
        isPointerOverContainer: true,
        distance: { x: 0, y: 0 },
        dropPoint: { x: 0, y: 0 },
        event: new MouseEvent('drop')
      } as CdkDragDrop<FilterConfig[]>;
      component.onDrop(event);

      expect(component.configuredFilters[0].id).toBe('f2');
      expect(component.configuredFilters[2].id).toBe('f1');
      expect(component.configuredFilters[0].priority).toBe(10);
      expect(component.configuredFilters[1].priority).toBe(20);
      expect(component.configuredFilters[2].priority).toBe(30);
    });

    it('should not change order if previousIndex equals currentIndex', () => {
      component.configuredFilters = [makeFilter('f1', 'F1'), makeFilter('f2', 'F2')];
      const event = {
        previousIndex: 1,
        currentIndex: 1,
        item: null as any,
        container: null as any,
        previousContainer: null as any,
        isPointerOverContainer: true,
        distance: { x: 0, y: 0 },
        dropPoint: { x: 0, y: 0 },
        event: new MouseEvent('drop')
      } as CdkDragDrop<FilterConfig[]>;
      component.onDrop(event);
      expect(component.configuredFilters[0].id).toBe('f1');
    });
  });

  describe('resetToDefaults', () => {
    it('should prompt and call resetConfig on confirm', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(true);
      filterChainSpy.resetConfig.and.returnValue(of(makeConfigUpdateResponse(makeFilterChainConfig())));
      component.resetToDefaults();
      tick();
      expect(filterChainSpy.resetConfig).toHaveBeenCalled();
      expect(component.successMessage).toBe('Configuration reset to defaults');
    }));

    it('should not reset if user cancels', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(false);
      component.resetToDefaults();
      tick();
      expect(filterChainSpy.resetConfig).not.toHaveBeenCalled();
    }));
  });

  describe('togglePhase', () => {
    it('should add phase if not present', () => {
      component.editingFilter = { ...makeFilter('f1', 'F1'), phases: ['PRE_RETRIEVAL'] };
      component.togglePhase('POST_RETRIEVAL');
      expect(component.editingFilter.phases).toContain('POST_RETRIEVAL');
    });

    it('should remove phase if present', () => {
      component.editingFilter = { ...makeFilter('f1', 'F1'), phases: ['PRE_RETRIEVAL', 'POST_RETRIEVAL'] };
      component.togglePhase('PRE_RETRIEVAL');
      expect(component.editingFilter.phases).not.toContain('PRE_RETRIEVAL');
    });

    it('should do nothing if editingFilter is null', () => {
      component.editingFilter = null;
      expect(() => component.togglePhase('PRE_RETRIEVAL')).not.toThrow();
    });
  });

  describe('isPhaseSelected', () => {
    it('should return true if phase is in editingFilter.phases', () => {
      component.editingFilter = { ...makeFilter('f1', 'F1'), phases: ['PRE_RETRIEVAL'] };
      expect(component.isPhaseSelected('PRE_RETRIEVAL')).toBeTrue();
      expect(component.isPhaseSelected('POST_LLM')).toBeFalse();
    });

    it('should return false if editingFilter is null', () => {
      component.editingFilter = null;
      expect(component.isPhaseSelected('PRE_RETRIEVAL')).toBeFalse();
    });
  });

  describe('addLocalFilter', () => {
    it('should call addFilter with filterInfo details', fakeAsync(() => {
      const info: FilterInfo = {
        id: 'pii', name: 'PII Filter',
        description: 'Detects PII', type: 'LOCAL', enabled: true, priority: 100
      };
      filterChainSpy.addFilter.and.returnValue(of(makeConfigUpdateResponse(makeFilterChainConfig())));
      component.addLocalFilter(info);
      tick();
      expect(filterChainSpy.addFilter).toHaveBeenCalled();
      const calledWith = filterChainSpy.addFilter.calls.mostRecent().args[0];
      expect(calledWith.id).toBe('pii');
      expect(calledWith.name).toBe('PII Filter');
    }));

    it('should set error on failure', fakeAsync(() => {
      const info: FilterInfo = {
        id: 'pii', name: 'PII', description: '', type: 'LOCAL', enabled: true, priority: 100
      };
      filterChainSpy.addFilter.and.returnValue(throwError(() => ({ message: 'err' })));
      component.addLocalFilter(info);
      tick();
      expect(component.error).toContain('Failed to add filter');
    }));
  });

  describe('getPhaseDisplayName / getTypeDisplayName', () => {
    it('should delegate to service', () => {
      filterChainSpy.getPhaseDisplayName.and.returnValue('Pre-Retrieval');
      expect(component.getPhaseDisplayName('PRE_RETRIEVAL')).toBe('Pre-Retrieval');

      filterChainSpy.getTypeDisplayName.and.returnValue('Built-in');
      expect(component.getTypeDisplayName('LOCAL')).toBe('Built-in');
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
