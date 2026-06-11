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

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
  discardPeriodicTasks
} from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, ChangeDetectorRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { PipelineSettingsPanelComponent } from './pipeline-settings-panel.component';
import { ProcessingSettingsService } from '../../../services/processing-settings.service';
import {
  GraphExtractionService,
  GraphExtractionConfig,
  SchemaMode,
  ModelProvider,
  ModelInfo
} from '../../../services/graph-extraction.service';
import {
  PipelineConfig,
  PipelinePresets,
  PipelinePreset,
  StageMetrics,
  StageMetricsResponse,
  LoaderInfo
} from '../../../models/api-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

function mockStageMetrics(overrides: Partial<StageMetrics> = {}): StageMetrics {
  return {
    itemsProcessed: 0,
    itemsFailed: 0,
    throughput: 0,
    avgProcessingTimeMs: 0,
    queueSize: 0,
    ...overrides
  };
}

function mockStageMetricsResponse(overrides: Partial<StageMetricsResponse> = {}): StageMetricsResponse {
  return {
    status: 'idle',
    extraction: mockStageMetrics(),
    tokenization: mockStageMetrics(),
    chunking: mockStageMetrics(),
    embedding: mockStageMetrics(),
    indexing: mockStageMetrics(),
    ...overrides
  };
}

function mockPipelineConfig(overrides: Partial<PipelineConfig> = {}): PipelineConfig {
  return {
    extraction: {} as any,
    tokenization: {} as any,
    chunking: {} as any,
    embedding: {} as any,
    indexing: {} as any,
    graphBuilding: {} as any,
    queues: {} as any,
    system: {
      availableCores: 8,
      maxMemoryMB: 16384,
      usedMemoryMB: 4096
    },
    ...overrides
  } as PipelineConfig;
}

function mockGraphConfig(overrides: Partial<GraphExtractionConfig> = {}): GraphExtractionConfig {
  return {
    enabled: false,
    batchSize: 10,
    schemaEnforcement: 'none',
    extractionModelProvider: '',
    extractionModelName: undefined,
    ...overrides
  };
}

function mockModelProvider(overrides: Partial<ModelProvider> = {}): ModelProvider {
  return {
    id: 'openai',
    name: 'OpenAI',
    available: true,
    models: [
      { id: 'gpt-4o', name: 'GPT-4o' },
      { id: 'gpt-4o-mini', name: 'GPT-4o Mini' }
    ],
    ...overrides
  };
}

function mockPipelinePresets(): PipelinePresets {
  return {
    adaptive: {
      name: 'Adaptive',
      description: 'Adapts to system resources',
      extractionThreads: 2,
      tokenizationThreads: 2,
      chunkingThreads: 2,
      embeddingThreads: 2,
      embeddingBatchSize: 32,
      indexBatchSize: 100
    },
    memoryOptimized: {
      name: 'Memory Optimized',
      description: 'Minimizes memory usage',
      extractionThreads: 1,
      tokenizationThreads: 1,
      chunkingThreads: 1,
      embeddingThreads: 1,
      embeddingBatchSize: 8,
      indexBatchSize: 50
    },
    highThroughput: {
      name: 'High Throughput',
      description: 'Maximizes throughput',
      extractionThreads: 4,
      tokenizationThreads: 4,
      chunkingThreads: 4,
      embeddingThreads: 4,
      embeddingBatchSize: 64,
      indexBatchSize: 200
    }
  };
}

function createTestBed() {
  const processingSettingsServiceSpy = jasmine.createSpyObj('ProcessingSettingsService', [
    'getPipelineConfig',
    'getPipelinePresets',
    'getStageMetrics'
  ]);

  const graphExtractionServiceSpy = jasmine.createSpyObj('GraphExtractionService', [
    'getConfig',
    'getSchemaModes',
    'getSuggestedEntityTypes',
    'getSuggestedRelationshipTypes',
    'getModelProviders',
    'patchConfig',
    'toggleEnabled',
    'resetConfig'
  ]);

  // Safe defaults
  processingSettingsServiceSpy.getPipelineConfig.and.returnValue(of(mockPipelineConfig()));
  processingSettingsServiceSpy.getPipelinePresets.and.returnValue(of(mockPipelinePresets()));
  processingSettingsServiceSpy.getStageMetrics.and.returnValue(of(mockStageMetricsResponse()));

  graphExtractionServiceSpy.getConfig.and.returnValue(of(mockGraphConfig()));
  graphExtractionServiceSpy.getSchemaModes.and.returnValue(of([]));
  graphExtractionServiceSpy.getSuggestedEntityTypes.and.returnValue(of([]));
  graphExtractionServiceSpy.getSuggestedRelationshipTypes.and.returnValue(of([]));
  graphExtractionServiceSpy.getModelProviders.and.returnValue(of([]));

  return {
    processingSettingsServiceSpy,
    graphExtractionServiceSpy,
    providers: [
      { provide: ProcessingSettingsService, useValue: processingSettingsServiceSpy },
      { provide: GraphExtractionService, useValue: graphExtractionServiceSpy }
    ]
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('PipelineSettingsPanelComponent', () => {
  let component: PipelineSettingsPanelComponent;
  let fixture: ComponentFixture<PipelineSettingsPanelComponent>;
  let spies: ReturnType<typeof createTestBed>;

  beforeEach(async () => {
    spies = createTestBed();

    await TestBed.configureTestingModule({
      imports: [
        PipelineSettingsPanelComponent, // standalone — import instead of declare
        FormsModule,
        NoopAnimationsModule
      ],
      providers: spies.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineSettingsPanelComponent);
    component = fixture.componentInstance;
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    it('should create', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should call getPipelineConfig, getPipelinePresets, and all graph extraction services on init', () => {
      fixture.detectChanges();
      expect(spies.processingSettingsServiceSpy.getPipelineConfig).toHaveBeenCalled();
      expect(spies.processingSettingsServiceSpy.getPipelinePresets).toHaveBeenCalled();
      expect(spies.graphExtractionServiceSpy.getConfig).toHaveBeenCalled();
      expect(spies.graphExtractionServiceSpy.getSchemaModes).toHaveBeenCalled();
      expect(spies.graphExtractionServiceSpy.getSuggestedEntityTypes).toHaveBeenCalled();
      expect(spies.graphExtractionServiceSpy.getSuggestedRelationshipTypes).toHaveBeenCalled();
      expect(spies.graphExtractionServiceSpy.getModelProviders).toHaveBeenCalled();
    });

    it('should populate config from service', () => {
      fixture.detectChanges();
      expect(component.config).not.toBeNull();
      expect(component.config!.system.maxMemoryMB).toBe(16384);
    });

    it('should set isLoading=false after config loads', () => {
      fixture.detectChanges();
      expect(component.isLoading).toBeFalse();
    });

    it('should set isLoading=false on config load error', () => {
      spies.processingSettingsServiceSpy.getPipelineConfig.and.returnValue(throwError(() => new Error('500')));
      fixture.detectChanges();
      expect(component.isLoading).toBeFalse();
      expect(component.errorMessage).not.toBeNull();
    });

    it('should populate presets from service', () => {
      fixture.detectChanges();
      expect(component.presets).not.toBeNull();
      expect(component.presets!.adaptive).toBeDefined();
    });

    it('should default selectedPreset to adaptive', () => {
      fixture.detectChanges();
      expect(component.selectedPreset).toBe('adaptive');
    });

    it('should start with isExpanded=true', () => {
      // Check the field default before ngOnInit side-effects
      expect(component.isExpanded).toBeTrue();
    });

    it('should populate graphConfig from service', () => {
      spies.graphExtractionServiceSpy.getConfig.and.returnValue(of(mockGraphConfig({ enabled: true, batchSize: 20 })));
      fixture.detectChanges();
      expect(component.graphConfig).not.toBeNull();
      expect(component.graphConfig!.batchSize).toBe(20);
    });

    it('should populate modelProviders from service', () => {
      const providers = [mockModelProvider(), mockModelProvider({ id: 'anthropic', name: 'Anthropic' })];
      spies.graphExtractionServiceSpy.getModelProviders.and.returnValue(of(providers));
      fixture.detectChanges();
      expect(component.modelProviders.length).toBe(2);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. @Input HANDLING
  // ─────────────────────────────────────────────────────────────────────────────

  describe('@Input handling', () => {
    it('should default showAdvanced to false', () => {
      fixture.detectChanges();
      expect(component.showAdvanced).toBeFalse();
    });

    it('should reflect showAdvanced=true when set', () => {
      component.showAdvanced = true;
      fixture.detectChanges();
      expect(component.showAdvanced).toBeTrue();
    });

    it('should default availableLoaders to empty array', () => {
      fixture.detectChanges();
      expect(component.availableLoaders).toEqual([]);
    });

    it('should reflect availableLoaders when set', () => {
      const loaders: LoaderInfo[] = [
        { name: 'PDF Loader', className: 'ai.kompile.PdfLoader' },
        { name: 'Office Loader', className: 'ai.kompile.OfficeLoader' }
      ];
      component.availableLoaders = loaders;
      fixture.detectChanges();
      expect(component.availableLoaders.length).toBe(2);
      expect(component.availableLoaders[0].name).toBe('PDF Loader');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. @Output — settingsChanged
  // ─────────────────────────────────────────────────────────────────────────────

  describe('@Output settingsChanged', () => {
    beforeEach(() => fixture.detectChanges());

    it('should emit settingsChanged when onSettingChange is called with a config', () => {
      const emitted: PipelineConfig[] = [];
      component.settingsChanged.subscribe((c: PipelineConfig) => emitted.push(c));

      component.onSettingChange();

      expect(emitted.length).toBe(1);
      expect(emitted[0]).toBe(component.config!);
    });

    it('should NOT emit when config is null', () => {
      const emitted: PipelineConfig[] = [];
      component.settingsChanged.subscribe((c: PipelineConfig) => emitted.push(c));
      component.config = null;

      component.onSettingChange();

      expect(emitted.length).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. MODEL PROVIDER SELECTION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Model provider selection', () => {
    beforeEach(() => {
      const providers: ModelProvider[] = [
        mockModelProvider({ id: 'openai', name: 'OpenAI', models: [{ id: 'gpt-4o', name: 'GPT-4o' }] }),
        mockModelProvider({ id: 'anthropic', name: 'Anthropic', models: [{ id: 'claude-3-5-sonnet', name: 'Claude 3.5 Sonnet' }] }),
        mockModelProvider({ id: 'ollama', name: 'Ollama', models: [], available: false })
      ];
      spies.graphExtractionServiceSpy.getModelProviders.and.returnValue(of(providers));
      spies.graphExtractionServiceSpy.patchConfig.and.returnValue(of(mockGraphConfig()));
      fixture.detectChanges();
    });

    describe('onModelProviderChange()', () => {
      it('should update graphConfig.extractionModelProvider', () => {
        component.graphConfig = mockGraphConfig();
        component.onModelProviderChange('openai');
        expect(component.graphConfig!.extractionModelProvider).toBe('openai');
      });

      it('should clear extractionModelName when provider changes', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: 'openai', extractionModelName: 'gpt-4o' });
        component.onModelProviderChange('anthropic');
        expect(component.graphConfig!.extractionModelName).toBeUndefined();
      });

      it('should call patchConfig on the graph extraction service', () => {
        component.graphConfig = mockGraphConfig();
        component.onModelProviderChange('openai');
        expect(spies.graphExtractionServiceSpy.patchConfig).toHaveBeenCalled();
      });

      it('should not throw when graphConfig is null', () => {
        component.graphConfig = null;
        expect(() => component.onModelProviderChange('openai')).not.toThrow();
      });
    });

    describe('getSelectedProvider()', () => {
      it('should return the provider matching graphConfig.extractionModelProvider', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: 'openai' });
        const provider = component.getSelectedProvider();
        expect(provider).not.toBeNull();
        expect(provider!.id).toBe('openai');
      });

      it('should return null when graphConfig is null', () => {
        component.graphConfig = null;
        expect(component.getSelectedProvider()).toBeNull();
      });

      it('should return null when no provider ID is set', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: '' });
        expect(component.getSelectedProvider()).toBeNull();
      });

      it('should return null for unknown provider ID', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: 'unknown-provider' });
        expect(component.getSelectedProvider()).toBeNull();
      });
    });

    describe('getAvailableModels()', () => {
      it('should return models for selected provider', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: 'openai' });
        const models = component.getAvailableModels();
        expect(models.length).toBeGreaterThan(0);
        expect(models[0].id).toBe('gpt-4o');
      });

      it('should return empty array when no provider is selected', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: '' });
        expect(component.getAvailableModels()).toEqual([]);
      });

      it('should return empty array for provider with no models', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: 'ollama' });
        expect(component.getAvailableModels()).toEqual([]);
      });
    });

    describe('hasModelsAvailable()', () => {
      it('should return true when selected provider has models', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: 'openai' });
        expect(component.hasModelsAvailable()).toBeTrue();
      });

      it('should return false when no provider is selected', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: '' });
        expect(component.hasModelsAvailable()).toBeFalse();
      });

      it('should return false for provider with no models', () => {
        component.graphConfig = mockGraphConfig({ extractionModelProvider: 'ollama' });
        expect(component.hasModelsAvailable()).toBeFalse();
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. GRAPH EXTRACTION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Graph extraction', () => {
    beforeEach(() => {
      spies.graphExtractionServiceSpy.patchConfig.and.returnValue(of(mockGraphConfig()));
      spies.graphExtractionServiceSpy.toggleEnabled.and.returnValue(of(mockGraphConfig({ enabled: true })));
      spies.graphExtractionServiceSpy.resetConfig.and.returnValue(of(mockGraphConfig()));
      fixture.detectChanges();
    });

    describe('onGraphConfigChange()', () => {
      it('should call patchConfig with the field update', () => {
        component.graphConfig = mockGraphConfig();
        component.onGraphConfigChange('batchSize', 25);
        expect(spies.graphExtractionServiceSpy.patchConfig).toHaveBeenCalledWith({ batchSize: 25 });
      });

      it('should update graphConfig from the response', () => {
        const updatedConfig = mockGraphConfig({ batchSize: 30 });
        spies.graphExtractionServiceSpy.patchConfig.and.returnValue(of(updatedConfig));
        component.graphConfig = mockGraphConfig();
        component.onGraphConfigChange('batchSize', 30);
        expect(component.graphConfig!.batchSize).toBe(30);
      });

      it('should set graphConfigSaving=true then false on success', () => {
        component.graphConfig = mockGraphConfig();
        component.onGraphConfigChange('enabled', true);
        expect(component.graphConfigSaving).toBeFalse();
      });

      it('should set graphConfigSaving=false on error', () => {
        spies.graphExtractionServiceSpy.patchConfig.and.returnValue(throwError(() => new Error('Patch failed')));
        component.graphConfig = mockGraphConfig();
        component.onGraphConfigChange('batchSize', 5);
        expect(component.graphConfigSaving).toBeFalse();
      });

      it('should do nothing when graphConfig is null', () => {
        component.graphConfig = null;
        component.onGraphConfigChange('batchSize', 5);
        expect(spies.graphExtractionServiceSpy.patchConfig).not.toHaveBeenCalled();
      });
    });

    describe('toggleGraphExtraction()', () => {
      it('should call toggleEnabled on the service', () => {
        component.toggleGraphExtraction();
        expect(spies.graphExtractionServiceSpy.toggleEnabled).toHaveBeenCalled();
      });

      it('should update graphConfig from the toggle response', () => {
        spies.graphExtractionServiceSpy.toggleEnabled.and.returnValue(of(mockGraphConfig({ enabled: true })));
        component.toggleGraphExtraction();
        expect(component.graphConfig!.enabled).toBeTrue();
      });

      it('should set graphConfigSaving=false after toggle success', () => {
        component.toggleGraphExtraction();
        expect(component.graphConfigSaving).toBeFalse();
      });

      it('should set graphConfigSaving=false on toggle error', () => {
        spies.graphExtractionServiceSpy.toggleEnabled.and.returnValue(throwError(() => new Error('Toggle failed')));
        component.toggleGraphExtraction();
        expect(component.graphConfigSaving).toBeFalse();
      });
    });

    describe('resetGraphConfig()', () => {
      it('should call resetConfig on the service', () => {
        component.resetGraphConfig();
        expect(spies.graphExtractionServiceSpy.resetConfig).toHaveBeenCalled();
      });

      it('should update graphConfig from the reset response', () => {
        const resetConfig = mockGraphConfig({ batchSize: 10, schemaEnforcement: 'none' });
        spies.graphExtractionServiceSpy.resetConfig.and.returnValue(of(resetConfig));
        component.resetGraphConfig();
        expect(component.graphConfig!.batchSize).toBe(10);
        expect(component.graphConfig!.schemaEnforcement).toBe('none');
      });

      it('should set graphConfigSaving=false after reset success', () => {
        component.resetGraphConfig();
        expect(component.graphConfigSaving).toBeFalse();
      });

      it('should set graphConfigSaving=false on reset error', () => {
        spies.graphExtractionServiceSpy.resetConfig.and.returnValue(throwError(() => new Error('Reset failed')));
        component.resetGraphConfig();
        expect(component.graphConfigSaving).toBeFalse();
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. METRICS POLLING
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startMetricsPolling()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should immediately fetch stage metrics on start', fakeAsync(() => {
      const metrics = mockStageMetricsResponse({ status: 'running' });
      spies.processingSettingsServiceSpy.getStageMetrics.and.returnValue(of(metrics));
      spies.processingSettingsServiceSpy.getStageMetrics.calls.reset();

      component.startMetricsPolling();
      tick(0); // flush startWith(0)

      expect(spies.processingSettingsServiceSpy.getStageMetrics).toHaveBeenCalled();
      expect(component.stageMetrics).not.toBeNull();
      expect(component.stageMetrics!.status).toBe('running');

      discardPeriodicTasks();
    }));

    it('should update stageMetrics on each poll', fakeAsync(() => {
      spies.processingSettingsServiceSpy.getStageMetrics.and.returnValue(
        of(mockStageMetricsResponse({ status: 'idle' }))
      );
      component.startMetricsPolling();
      tick(5000);

      expect(spies.processingSettingsServiceSpy.getStageMetrics.calls.count()).toBeGreaterThan(1);
      discardPeriodicTasks();
    }));

    it('should not throw when getStageMetrics errors during polling', fakeAsync(() => {
      spies.processingSettingsServiceSpy.getStageMetrics.and.returnValue(throwError(() => new Error('Metrics failed')));
      expect(() => {
        component.startMetricsPolling();
        tick(0);
      }).not.toThrow();
      discardPeriodicTasks();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. PRESET MANAGEMENT
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Preset management', () => {
    beforeEach(() => fixture.detectChanges());

    describe('onPresetChange()', () => {
      it('should call applyPreset with the selected preset value', () => {
        spyOn(component, 'applyPreset').and.callThrough();
        component.onPresetChange({ value: 'highThroughput' });
        expect(component.applyPreset).toHaveBeenCalledWith('highThroughput');
      });
    });

    describe('applyPreset()', () => {
      it('should update selectedPreset', () => {
        component.applyPreset('memoryOptimized');
        expect(component.selectedPreset).toBe('memoryOptimized');
      });

      it('should update selectedPreset for highThroughput', () => {
        component.applyPreset('highThroughput');
        expect(component.selectedPreset).toBe('highThroughput');
      });

      it('should update selectedPreset for custom', () => {
        component.applyPreset('custom');
        expect(component.selectedPreset).toBe('custom');
      });
    });

    describe('getPresetDescription()', () => {
      it('should return description for adaptive preset', () => {
        const desc = component.getPresetDescription('adaptive');
        expect(desc).toBe('Adapts to system resources');
      });

      it('should return description for memoryOptimized preset', () => {
        const desc = component.getPresetDescription('memoryOptimized');
        expect(desc).toBe('Minimizes memory usage');
      });

      it('should return description for highThroughput preset', () => {
        const desc = component.getPresetDescription('highThroughput');
        expect(desc).toBe('Maximizes throughput');
      });

      it('should return empty string for custom preset', () => {
        const desc = component.getPresetDescription('custom');
        expect(desc).toBe('');
      });

      it('should return empty string when presets are null', () => {
        component.presets = null;
        expect(component.getPresetDescription('adaptive')).toBe('');
      });
    });

    describe('getPresetInfo()', () => {
      it('should return preset info for adaptive', () => {
        const info = component.getPresetInfo('adaptive');
        expect(info).not.toBeNull();
        expect(info!.name).toBe('Adaptive');
        expect(info!.embeddingBatchSize).toBe(32);
      });

      it('should return null for custom preset', () => {
        const info = component.getPresetInfo('custom');
        expect(info).toBeNull();
      });

      it('should return null when presets are not loaded', () => {
        component.presets = null;
        expect(component.getPresetInfo('adaptive')).toBeNull();
      });

      it('should return preset info for highThroughput', () => {
        const info = component.getPresetInfo('highThroughput');
        expect(info).not.toBeNull();
        expect(info!.extractionThreads).toBe(4);
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. UI TOGGLE — toggleExpanded()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('toggleExpanded()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should collapse when expanded', () => {
      component.isExpanded = true;
      component.toggleExpanded();
      expect(component.isExpanded).toBeFalse();
    });

    it('should expand when collapsed', () => {
      component.isExpanded = false;
      component.toggleExpanded();
      expect(component.isExpanded).toBeTrue();
    });

    it('should start metrics polling when expanding', fakeAsync(() => {
      spies.processingSettingsServiceSpy.getStageMetrics.calls.reset();
      component.isExpanded = false;
      component.toggleExpanded();
      tick(0);
      expect(spies.processingSettingsServiceSpy.getStageMetrics).toHaveBeenCalled();
      discardPeriodicTasks();
    }));

    it('should NOT start metrics polling when collapsing', fakeAsync(() => {
      component.isExpanded = true;
      spies.processingSettingsServiceSpy.getStageMetrics.calls.reset();
      component.toggleExpanded();
      tick(0);
      expect(spies.processingSettingsServiceSpy.getStageMetrics).not.toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. DISPLAY HELPERS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Display helpers', () => {
    beforeEach(() => fixture.detectChanges());

    describe('formatMemory()', () => {
      it('should format values < 1024 MB as MB', () => {
        expect(component.formatMemory(512)).toBe('512 MB');
      });

      it('should format values >= 1024 MB as GB with one decimal', () => {
        expect(component.formatMemory(2048)).toBe('2.0 GB');
      });

      it('should format 4096 MB as 4.0 GB', () => {
        expect(component.formatMemory(4096)).toBe('4.0 GB');
      });

      it('should format 1536 MB as 1.5 GB', () => {
        expect(component.formatMemory(1536)).toBe('1.5 GB');
      });
    });

    describe('getMemoryUsagePercent()', () => {
      it('should return 0 when config is null', () => {
        component.config = null;
        expect(component.getMemoryUsagePercent()).toBe(0);
      });

      it('should return 0 when system is undefined', () => {
        component.config = { ...mockPipelineConfig(), system: undefined as any };
        expect(component.getMemoryUsagePercent()).toBe(0);
      });

      it('should calculate percent correctly', () => {
        component.config = mockPipelineConfig();
        // usedMemoryMB=4096, maxMemoryMB=16384 → 25%
        expect(component.getMemoryUsagePercent()).toBe(25);
      });

      it('should round to nearest integer', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 4, maxMemoryMB: 3000, usedMemoryMB: 1000 }
        });
        // 1000/3000 = 33.33% → 33
        expect(component.getMemoryUsagePercent()).toBe(33);
      });
    });

    describe('getMemoryStatusClass()', () => {
      it('should return memory-ok when usage < 70%', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 4, maxMemoryMB: 16384, usedMemoryMB: 4096 }
        }); // 25%
        expect(component.getMemoryStatusClass()).toBe('memory-ok');
      });

      it('should return memory-warning when usage >= 70% and < 90%', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 4, maxMemoryMB: 1000, usedMemoryMB: 750 }
        }); // 75%
        expect(component.getMemoryStatusClass()).toBe('memory-warning');
      });

      it('should return memory-critical when usage >= 90%', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 4, maxMemoryMB: 1000, usedMemoryMB: 950 }
        }); // 95%
        expect(component.getMemoryStatusClass()).toBe('memory-critical');
      });
    });

    describe('getStageStatusClass()', () => {
      it('should return stage-error when itemsFailed > 0', () => {
        const stage = mockStageMetrics({ itemsFailed: 2 });
        expect(component.getStageStatusClass(stage)).toBe('stage-error');
      });

      it('should return stage-active when itemsProcessed > 0 and no failures', () => {
        const stage = mockStageMetrics({ itemsProcessed: 10, itemsFailed: 0 });
        expect(component.getStageStatusClass(stage)).toBe('stage-active');
      });

      it('should return stage-idle when nothing processed or failed', () => {
        const stage = mockStageMetrics({ itemsProcessed: 0, itemsFailed: 0 });
        expect(component.getStageStatusClass(stage)).toBe('stage-idle');
      });

      it('should prefer stage-error over stage-active when both conditions met', () => {
        const stage = mockStageMetrics({ itemsProcessed: 5, itemsFailed: 1 });
        expect(component.getStageStatusClass(stage)).toBe('stage-error');
      });
    });

    describe('getStageIcon()', () => {
      it('should return correct icons for known stage names', () => {
        expect(component.getStageIcon('extraction')).toBe('folder_open');
        expect(component.getStageIcon('tokenization')).toBe('text_fields');
        expect(component.getStageIcon('chunking')).toBe('content_cut');
        expect(component.getStageIcon('embedding')).toBe('memory');
        expect(component.getStageIcon('indexing')).toBe('storage');
        expect(component.getStageIcon('graph-building')).toBe('hub');
      });

      it('should return settings for unknown stage name', () => {
        expect(component.getStageIcon('unknown-stage')).toBe('settings');
      });
    });

    describe('formatThroughput()', () => {
      it('should format values < 1000 as /s with one decimal', () => {
        expect(component.formatThroughput(123.456)).toBe('123.5/s');
      });

      it('should format values >= 1000 as K/s with one decimal', () => {
        expect(component.formatThroughput(2500)).toBe('2.5K/s');
      });

      it('should format exactly 1000 as 1.0K/s', () => {
        expect(component.formatThroughput(1000)).toBe('1.0K/s');
      });

      it('should format 0 as 0.0/s', () => {
        expect(component.formatThroughput(0)).toBe('0.0/s');
      });
    });

    describe('getStageMetricsByName()', () => {
      beforeEach(() => {
        component.stageMetrics = {
          status: 'running',
          extraction: mockStageMetrics({ itemsProcessed: 10 }),
          tokenization: mockStageMetrics({ itemsProcessed: 9 }),
          chunking: mockStageMetrics({ itemsProcessed: 8 }),
          embedding: mockStageMetrics({ itemsProcessed: 7 }),
          indexing: mockStageMetrics({ itemsProcessed: 6 }),
          graphBuilding: mockStageMetrics({ itemsProcessed: 5 })
        };
      });

      it('should return extraction metrics for "extraction"', () => {
        const metrics = component.getStageMetricsByName('extraction');
        expect(metrics).not.toBeNull();
        expect(metrics!.itemsProcessed).toBe(10);
      });

      it('should return tokenization metrics for "tokenization"', () => {
        expect(component.getStageMetricsByName('tokenization')!.itemsProcessed).toBe(9);
      });

      it('should return chunking metrics for "chunking"', () => {
        expect(component.getStageMetricsByName('chunking')!.itemsProcessed).toBe(8);
      });

      it('should return embedding metrics for "embedding"', () => {
        expect(component.getStageMetricsByName('embedding')!.itemsProcessed).toBe(7);
      });

      it('should return indexing metrics for "indexing"', () => {
        expect(component.getStageMetricsByName('indexing')!.itemsProcessed).toBe(6);
      });

      it('should return graphBuilding metrics for "graph-building"', () => {
        expect(component.getStageMetricsByName('graph-building')!.itemsProcessed).toBe(5);
      });

      it('should return null for unknown stage name', () => {
        expect(component.getStageMetricsByName('unknown')).toBeNull();
      });

      it('should return null when stageMetrics is null', () => {
        component.stageMetrics = null;
        expect(component.getStageMetricsByName('extraction')).toBeNull();
      });

      it('should return null for graph-building when graphBuilding is undefined', () => {
        component.stageMetrics!.graphBuilding = undefined;
        expect(component.getStageMetricsByName('graph-building')).toBeNull();
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. RAM TIER
  // ─────────────────────────────────────────────────────────────────────────────

  describe('RAM tier methods', () => {
    beforeEach(() => fixture.detectChanges());

    describe('isCurrentRamTier()', () => {
      it('should return false when config is null', () => {
        component.config = null;
        expect(component.isCurrentRamTier('lt4')).toBeFalse();
      });

      it('should return false when system is undefined', () => {
        component.config = { ...mockPipelineConfig(), system: undefined as any };
        expect(component.isCurrentRamTier('lt4')).toBeFalse();
      });

      it('should identify lt4 tier (< 4 GB = < 4096 MB)', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 4, maxMemoryMB: 3072, usedMemoryMB: 1024 }
        });
        expect(component.isCurrentRamTier('lt4')).toBeTrue();
        expect(component.isCurrentRamTier('4to8')).toBeFalse();
        expect(component.isCurrentRamTier('8to16')).toBeFalse();
        expect(component.isCurrentRamTier('gt16')).toBeFalse();
      });

      it('should identify 4to8 tier (4–8 GB = 4096–8192 MB)', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 4, maxMemoryMB: 6144, usedMemoryMB: 2048 }
        });
        expect(component.isCurrentRamTier('4to8')).toBeTrue();
        expect(component.isCurrentRamTier('lt4')).toBeFalse();
        expect(component.isCurrentRamTier('8to16')).toBeFalse();
      });

      it('should identify 8to16 tier (8–16 GB)', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 8, maxMemoryMB: 12288, usedMemoryMB: 4096 }
        });
        expect(component.isCurrentRamTier('8to16')).toBeTrue();
        expect(component.isCurrentRamTier('4to8')).toBeFalse();
        expect(component.isCurrentRamTier('gt16')).toBeFalse();
      });

      it('should identify gt16 tier (> 16 GB)', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 16, maxMemoryMB: 32768, usedMemoryMB: 8192 }
        });
        expect(component.isCurrentRamTier('gt16')).toBeTrue();
        expect(component.isCurrentRamTier('8to16')).toBeFalse();
      });

      it('should identify gt16 at exactly 16 GB boundary', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 8, maxMemoryMB: 16384, usedMemoryMB: 4096 }
        });
        // 16384 MB = 16 GB → gt16 (>=16)
        expect(component.isCurrentRamTier('gt16')).toBeTrue();
        expect(component.isCurrentRamTier('8to16')).toBeFalse();
      });
    });

    describe('getSystemRamGb()', () => {
      it('should return "?" when config is null', () => {
        component.config = null;
        expect(component.getSystemRamGb()).toBe('?');
      });

      it('should return "?" when system is undefined', () => {
        component.config = { ...mockPipelineConfig(), system: undefined as any };
        expect(component.getSystemRamGb()).toBe('?');
      });

      it('should return RAM in GB as a string with one decimal', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 8, maxMemoryMB: 16384, usedMemoryMB: 4096 }
        });
        expect(component.getSystemRamGb()).toBe('16.0');
      });

      it('should handle non-integer GB values', () => {
        component.config = mockPipelineConfig({
          system: { availableCores: 4, maxMemoryMB: 6144, usedMemoryMB: 1024 }
        });
        expect(component.getSystemRamGb()).toBe('6.0');
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. STAGE NAMES CONSTANT
  // ─────────────────────────────────────────────────────────────────────────────

  describe('stageNames constant', () => {
    it('should contain all 6 expected stage names', () => {
      fixture.detectChanges();
      expect(component.stageNames).toContain('extraction');
      expect(component.stageNames).toContain('tokenization');
      expect(component.stageNames).toContain('chunking');
      expect(component.stageNames).toContain('embedding');
      expect(component.stageNames).toContain('indexing');
      expect(component.stageNames).toContain('graph-building');
      expect(component.stageNames.length).toBe(6);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. SETTINGS CHANGE
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onSettingChange()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should emit the current config to settingsChanged', () => {
      const received: PipelineConfig[] = [];
      component.settingsChanged.subscribe((c: PipelineConfig) => received.push(c));
      component.onSettingChange();
      expect(received.length).toBe(1);
      expect(received[0]).toBe(component.config!);
    });

    it('should emit each time it is called', () => {
      const received: PipelineConfig[] = [];
      component.settingsChanged.subscribe((c: PipelineConfig) => received.push(c));
      component.onSettingChange();
      component.onSettingChange();
      component.onSettingChange();
      expect(received.length).toBe(3);
    });
  });
});
