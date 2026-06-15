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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { FormsModule } from '@angular/forms';

import { ContextualRagDebugComponent } from './contextual-rag-debug.component';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatInputModule } from '@angular/material/input';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

function makeConfig(overrides: Partial<any> = {}): any {
  return {
    enabled: true,
    llmProvider: 'openai',
    llmModel: 'gpt-4o',
    temperature: 0.3,
    maxContextTokens: 4096,
    includeDocumentSummary: true,
    documentSummaryMaxTokens: 512,
    includeSurroundingChunks: false,
    surroundingChunksWindow: 1,
    sourceAttributionEnabled: true,
    citationFormat: 'INLINE',
    customCitationTemplate: '',
    includePageNumbers: false,
    batchSize: 10,
    maxConcurrentRequests: 3,
    requestTimeoutSeconds: 30,
    maxRetries: 2,
    cachingEnabled: true,
    cachePath: '/tmp/cache',
    cacheTtlDays: 7,
    fallbackOnError: true,
    webSearchFallbackThreshold: 0.5,
    contextPromptTemplate: '',
    ...overrides
  };
}

function makeStatus(overrides: Partial<any> = {}): any {
  return {
    enricherAvailable: true,
    enabled: true,
    provider: 'openai',
    ...overrides
  };
}

function makePresets(): any[] {
  return [
    { name: 'balanced', displayName: 'Balanced', description: 'Balanced preset' },
    { name: 'aggressive', displayName: 'Aggressive', description: 'Aggressive preset' }
  ];
}

function makeProviders(): any[] {
  return [
    {
      id: 'openai',
      displayName: 'OpenAI',
      models: [
        { id: 'gpt-4o', displayName: 'GPT-4o', description: 'Most capable' },
        { id: 'gpt-3.5-turbo', displayName: 'GPT-3.5 Turbo', description: 'Fast' }
      ]
    },
    {
      id: 'anthropic',
      displayName: 'Anthropic',
      models: [
        { id: 'claude-3-5-sonnet', displayName: 'Claude 3.5 Sonnet', description: 'Latest' }
      ]
    }
  ];
}

function makeCacheStats(): any {
  return {
    documentSummaries: 5,
    chunkContexts: 42,
    hitRate: 0.75,
    totalEntries: 47
  };
}

/** Flushes all ngOnInit GET requests with default responses. */
function flushInitRequests(httpMock: HttpTestingController): void {
  httpMock.match(r => r.url.includes('/contextual-rag/config')).forEach(r => r.flush(makeConfig()));
  httpMock.match(r => r.url.includes('/contextual-rag/status')).forEach(r => r.flush(makeStatus()));
  httpMock.match(r => r.url.includes('/contextual-rag/presets')).forEach(r => r.flush(makePresets()));
  httpMock.match(r => r.url.includes('/contextual-rag/providers')).forEach(r => r.flush(makeProviders()));
  httpMock.match(r => r.url.includes('/contextual-rag/cache/stats')).forEach(r => r.flush(makeCacheStats()));
  httpMock.match(r => r.url.includes('/contextual-rag/prompt-template')).forEach(r => r.flush({ template: 'Default template', isCustom: false }));
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('ContextualRagDebugComponent', () => {
  let component: ContextualRagDebugComponent;
  let fixture: ComponentFixture<ContextualRagDebugComponent>;
  let httpMock: HttpTestingController;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [HttpClientTestingModule, NoopAnimationsModule, FormsModule, MatSelectModule, MatSlideToggleModule, MatInputModule],
      declarations: [ContextualRagDebugComponent],
      providers: [
        { provide: MatSnackBar, useValue: snackBarSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ContextualRagDebugComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    // Discard any outstanding ngOnInit HTTP requests
    httpMock.match(() => true);
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    it('should create the component', () => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
      expect(component).toBeTruthy();
    });

    it('should initialize with default state', () => {
      expect(component.config).toBeNull();
      expect(component.presets).toEqual([]);
      expect(component.providers).toEqual([]);
      expect(component.loading).toBeFalse();
      expect(component.enricherAvailable).toBeFalse();
      expect(component.testResult).toBeNull();
      expect(component.compareResult).toBeNull();
      expect(component.promptPreview).toBeNull();
      expect(component.batchResults).toBeNull();
      expect(component.cacheStats).toBeNull();
    });

    it('should initialize test inputs with defaults', () => {
      expect(component.testChunkText).toContain('Revenue increased');
      expect(component.testDocumentTitle).toBe('Q3 2024 Financial Report');
      expect(component.testChunkIndex).toBe(0);
      expect(component.testTotalChunks).toBe(1);
    });

    it('should call loadAll on ngOnInit', () => {
      fixture.detectChanges();
      // All 6 init requests should be pending
      const pending = httpMock.match(() => true);
      expect(pending.length).toBe(6);
      pending.forEach(r => r.flush({}));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. loadConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadConfig()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should GET /api/contextual-rag/config', () => {
      component.loadConfig();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/config') && r.method === 'GET');
      req.flush(makeConfig());
      expect(component.config).toBeTruthy();
    });

    it('should populate config on success', () => {
      const cfg = makeConfig({ llmProvider: 'anthropic', temperature: 0.7 });
      component.loadConfig();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/config') && r.method === 'GET');
      req.flush(cfg);
      expect(component.config?.llmProvider).toBe('anthropic');
      expect(component.config?.temperature).toBe(0.7);
    });

    it('should show error snackbar on load failure', () => {
      component.loadConfig();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/config') && r.method === 'GET');
      req.flush({ message: 'Not found' }, { status: 404, statusText: 'Not Found' });
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Failed to load configuration/),
        'Close',
        jasmine.any(Object)
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. loadStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadStatus()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should GET /api/contextual-rag/status', () => {
      component.loadStatus();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/status') && r.method === 'GET');
      req.flush(makeStatus());
      expect(component.status).toBeTruthy();
    });

    it('should set enricherAvailable from status response', () => {
      component.loadStatus();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/status') && r.method === 'GET');
      req.flush(makeStatus({ enricherAvailable: true }));
      expect(component.enricherAvailable).toBeTrue();
    });

    it('should set enricherAvailable to false when response says false', () => {
      component.loadStatus();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/status') && r.method === 'GET');
      req.flush(makeStatus({ enricherAvailable: false }));
      expect(component.enricherAvailable).toBeFalse();
    });

    it('should handle status load error gracefully (no snackbar)', () => {
      component.loadStatus();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/status') && r.method === 'GET');
      req.flush({}, { status: 503, statusText: 'Service Unavailable' });
      // status errors are logged but do not show a snackbar
      expect(snackBarSpy.open).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. loadPresets()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadPresets()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should GET /api/contextual-rag/presets', () => {
      component.loadPresets();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/presets') && r.method === 'GET');
      req.flush(makePresets());
      expect(component.presets.length).toBe(2);
    });

    it('should populate presets array on success', () => {
      component.loadPresets();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/presets') && r.method === 'GET');
      req.flush(makePresets());
      expect(component.presets[0].name).toBe('balanced');
      expect(component.presets[1].name).toBe('aggressive');
    });

    it('should handle presets load error gracefully', () => {
      component.loadPresets();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/presets') && r.method === 'GET');
      req.flush({}, { status: 500, statusText: 'Server Error' });
      expect(snackBarSpy.open).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. loadProviders()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadProviders()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should GET /api/contextual-rag/providers', () => {
      component.loadProviders();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/providers') && r.method === 'GET');
      req.flush(makeProviders());
      expect(component.providers.length).toBe(2);
    });

    it('should populate providers with their models', () => {
      component.loadProviders();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/providers') && r.method === 'GET');
      req.flush(makeProviders());
      const openai = component.providers.find(p => p.id === 'openai');
      expect(openai).toBeTruthy();
      expect(openai?.models.length).toBe(2);
    });

    it('should handle providers load error gracefully', () => {
      component.loadProviders();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/providers') && r.method === 'GET');
      req.flush({}, { status: 500, statusText: 'Server Error' });
      expect(snackBarSpy.open).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. loadCacheStats()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadCacheStats()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should GET /api/contextual-rag/cache/stats', () => {
      component.loadCacheStats();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/cache/stats') && r.method === 'GET');
      req.flush(makeCacheStats());
      expect(component.cacheStats).toBeTruthy();
    });

    it('should populate cacheStats on success', () => {
      component.loadCacheStats();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/cache/stats') && r.method === 'GET');
      req.flush(makeCacheStats());
      expect(component.cacheStats.chunkContexts).toBe(42);
      expect(component.cacheStats.hitRate).toBe(0.75);
    });

    it('should handle cache stats load error gracefully', () => {
      component.loadCacheStats();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/cache/stats') && r.method === 'GET');
      req.flush({}, { status: 500, statusText: 'Server Error' });
      expect(snackBarSpy.open).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. loadPromptTemplate()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadPromptTemplate()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should GET /api/contextual-rag/prompt-template', () => {
      component.loadPromptTemplate();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/prompt-template') && r.method === 'GET');
      req.flush({ template: 'My template', isCustom: true });
      expect(component.promptTemplate).toBe('My template');
      expect(component.useCustomPrompt).toBeTrue();
    });

    it('should set useCustomPrompt false for default template', () => {
      component.loadPromptTemplate();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/prompt-template') && r.method === 'GET');
      req.flush({ template: 'Default', isCustom: false });
      expect(component.useCustomPrompt).toBeFalse();
    });

    it('should handle prompt-template load error gracefully', () => {
      component.loadPromptTemplate();
      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/prompt-template') && r.method === 'GET');
      req.flush({}, { status: 500, statusText: 'Server Error' });
      expect(snackBarSpy.open).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. toggleEnabled()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('toggleEnabled()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
      component.config = makeConfig({ enabled: true });
    });

    it('should POST to /api/contextual-rag/toggle with enabled false when currently true', () => {
      component.toggleEnabled();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/toggle') && r.method === 'POST');
      expect(req.request.body).toEqual({ enabled: false });
      req.flush({ enabled: false, message: 'Enrichment disabled' });
    });

    it('should POST with enabled true when currently false', () => {
      component.config = makeConfig({ enabled: false });
      component.toggleEnabled();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/toggle') && r.method === 'POST');
      expect(req.request.body).toEqual({ enabled: true });
      req.flush({ enabled: true, message: 'Enrichment enabled' });
    });

    it('should update config.enabled from response', () => {
      component.toggleEnabled();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/toggle') && r.method === 'POST');
      req.flush({ enabled: false, message: 'Disabled' });

      expect(component.config?.enabled).toBeFalse();
    });

    it('should show success snackbar with response message', () => {
      component.toggleEnabled();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/toggle') && r.method === 'POST');
      req.flush({ enabled: false, message: 'Enrichment disabled' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Enrichment disabled',
        'Close',
        jasmine.any(Object)
      );
    });

    it('should show error snackbar on toggle failure', () => {
      component.toggleEnabled();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/toggle') && r.method === 'POST');
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Failed to toggle enrichment/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('should do nothing when config is null', () => {
      component.config = null;
      component.toggleEnabled();
      httpMock.expectNone(r => r.url.includes('/contextual-rag/toggle'));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. applyPreset()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('applyPreset()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should POST to /api/contextual-rag/presets/{presetName}', () => {
      component.applyPreset('balanced');

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/presets/balanced') && r.method === 'POST'
      );
      req.flush(makeConfig());

      expect(component.loading).toBeFalse();
    });

    it('should update config from preset response', () => {
      const newConfig = makeConfig({ temperature: 0.8, llmProvider: 'anthropic' });
      component.applyPreset('aggressive');

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/presets/aggressive'));
      req.flush(newConfig);

      expect(component.config?.temperature).toBe(0.8);
      expect(component.config?.llmProvider).toBe('anthropic');
    });

    it('should show success snackbar on preset applied', () => {
      component.applyPreset('balanced');

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/presets/balanced'));
      req.flush(makeConfig());

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Applied preset: balanced/),
        'Close',
        jasmine.any(Object)
      );
    });

    it('should show error snackbar on preset failure', () => {
      component.applyPreset('nonexistent');

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/presets/nonexistent'));
      req.flush({}, { status: 404, statusText: 'Not Found' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Failed to apply preset/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
      expect(component.loading).toBeFalse();
    });

    it('should set loading true while request is in flight', () => {
      component.applyPreset('balanced');
      expect(component.loading).toBeTrue();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/presets/balanced'));
      req.flush(makeConfig());

      expect(component.loading).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. saveConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('saveConfig()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
      component.config = makeConfig();
    });

    it('should POST current config to /api/contextual-rag/config', () => {
      const cfg = makeConfig({ temperature: 0.9 });
      component.config = cfg;
      component.saveConfig();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/config') && r.method === 'POST'
      );
      expect(req.request.body).toEqual(jasmine.objectContaining({ temperature: 0.9 }));
      req.flush(cfg);
    });

    it('should update config from server response', () => {
      const savedConfig = makeConfig({ cacheTtlDays: 14 });
      component.saveConfig();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/config') && r.method === 'POST'
      );
      req.flush(savedConfig);

      expect(component.config?.cacheTtlDays).toBe(14);
      expect(component.loading).toBeFalse();
    });

    it('should show success snackbar on save', () => {
      component.saveConfig();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/config') && r.method === 'POST'
      );
      req.flush(makeConfig());

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Configuration saved',
        'Close',
        jasmine.any(Object)
      );
    });

    it('should show error snackbar on save failure', () => {
      component.saveConfig();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/config') && r.method === 'POST'
      );
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Failed to save configuration/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('should do nothing when config is null', () => {
      component.config = null;
      component.saveConfig();
      httpMock.expectNone(r => r.url.includes('/contextual-rag/config') && r.method === 'POST');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. resetConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('resetConfig()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should POST to /api/contextual-rag/config/reset', () => {
      component.resetConfig();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/config/reset') && r.method === 'POST'
      );
      req.flush(makeConfig());
      expect(component.config).toBeTruthy();
    });

    it('should restore config to server defaults', () => {
      const defaults = makeConfig({ temperature: 0.1, batchSize: 5 });
      component.resetConfig();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/config/reset'));
      req.flush(defaults);

      expect(component.config?.temperature).toBe(0.1);
      expect(component.config?.batchSize).toBe(5);
      expect(component.loading).toBeFalse();
    });

    it('should show success snackbar on reset', () => {
      component.resetConfig();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/config/reset'));
      req.flush(makeConfig());

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Configuration reset to defaults',
        'Close',
        jasmine.any(Object)
      );
    });

    it('should show error snackbar on reset failure', () => {
      component.resetConfig();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/config/reset'));
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Failed to reset configuration/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
      expect(component.loading).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. testContextualization()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('testContextualization()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should POST to /api/contextual-rag/debug/test-contextualization', () => {
      component.testContextualization();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-contextualization') && r.method === 'POST'
      );
      expect(req.request.body).toBeDefined();
      req.flush({ success: true, processingTimeMs: 250 });
    });

    it('should include all test input fields in request body', () => {
      component.testChunkText = 'Custom chunk text';
      component.testDocumentTitle = 'Custom Doc Title';
      component.testDocumentSummary = 'Custom summary';
      component.testChunkIndex = 3;
      component.testTotalChunks = 10;

      component.testContextualization();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-contextualization')
      );
      expect(req.request.body).toEqual(jasmine.objectContaining({
        chunkText: 'Custom chunk text',
        documentTitle: 'Custom Doc Title',
        documentSummary: 'Custom summary',
        chunkIndex: 3,
        totalChunks: 10
      }));
      req.flush({ success: true, processingTimeMs: 150 });
    });

    it('should send null documentSummary when empty', () => {
      component.testDocumentSummary = '';
      component.testContextualization();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-contextualization')
      );
      expect(req.request.body.documentSummary).toBeNull();
      req.flush({ success: true, processingTimeMs: 100 });
    });

    it('should set testResult on success', () => {
      const result = { success: true, originalText: 'Original', contextualizedText: 'Contextualized', processingTimeMs: 200 };
      component.testContextualization();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-contextualization')
      );
      req.flush(result);

      expect(component.testResult).toEqual(jasmine.objectContaining({ success: true }));
      expect(component.loading).toBeFalse();
    });

    it('should show success snackbar with processing time', () => {
      component.testContextualization();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-contextualization')
      );
      req.flush({ success: true, processingTimeMs: 350 });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/350ms/),
        'Close',
        jasmine.any(Object)
      );
    });

    it('should show error snackbar when result.success is false', () => {
      component.testContextualization();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-contextualization')
      );
      req.flush({ success: false, error: 'LLM timeout' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'LLM timeout',
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('should show generic error snackbar on HTTP failure', () => {
      component.testContextualization();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-contextualization')
      );
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Test failed/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
      expect(component.loading).toBeFalse();
    });

    it('should clear previous testResult before new request', () => {
      component.testResult = { success: true };
      component.testContextualization();
      expect(component.testResult).toBeNull();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-contextualization')
      );
      req.flush({ success: true, processingTimeMs: 100 });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. compareTexts()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('compareTexts()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should POST to /api/contextual-rag/debug/compare', () => {
      component.compareTexts();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/compare') && r.method === 'POST'
      );
      req.flush({ success: true });
    });

    it('should set compareResult on success', () => {
      const result = {
        success: true,
        original: { text: 'Original', length: 8, wordCount: 1 },
        contextualized: { text: 'Contextualized Original', length: 23, wordCount: 2, contextPrefix: 'Context:', addedLength: 15, addedWords: 1 }
      };
      component.compareTexts();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/compare'));
      req.flush(result);

      expect(component.compareResult).toEqual(jasmine.objectContaining({ success: true }));
      expect(component.loading).toBeFalse();
    });

    it('should include test inputs in request body', () => {
      component.testChunkText = 'Compare chunk';
      component.testDocumentTitle = 'Compare Doc';
      component.testChunkIndex = 1;
      component.testTotalChunks = 5;
      component.testDocumentSummary = '';

      component.compareTexts();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/compare'));
      expect(req.request.body).toEqual(jasmine.objectContaining({
        chunkText: 'Compare chunk',
        documentTitle: 'Compare Doc',
        chunkIndex: 1,
        totalChunks: 5,
        documentSummary: null
      }));
      req.flush({ success: true });
    });

    it('should show error snackbar on comparison failure', () => {
      component.compareTexts();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/compare'));
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Comparison failed/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
      expect(component.loading).toBeFalse();
    });

    it('should clear previous compareResult before new request', () => {
      component.compareResult = { success: true };
      component.compareTexts();
      expect(component.compareResult).toBeNull();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/compare'));
      req.flush({ success: true });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. previewPrompt()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('previewPrompt()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should POST to /api/contextual-rag/debug/preview-prompt', () => {
      component.previewPrompt();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/preview-prompt') && r.method === 'POST'
      );
      req.flush({ prompt: 'Generated prompt text' });
    });

    it('should set promptPreview on success', () => {
      component.previewPrompt();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/preview-prompt')
      );
      req.flush({ prompt: 'Generated prompt text', tokens: 42 });

      expect(component.promptPreview).toEqual(jasmine.objectContaining({ prompt: 'Generated prompt text' }));
    });

    it('should supply fallback document summary when empty', () => {
      component.testDocumentSummary = '';
      component.previewPrompt();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/preview-prompt')
      );
      expect(req.request.body.documentSummary).toBeTruthy(); // fallback, not null
      req.flush({ prompt: 'Preview' });
    });

    it('should show error snackbar on preview failure', () => {
      component.previewPrompt();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/preview-prompt')
      );
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Failed to preview prompt/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 15. testBatch()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('testBatch()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should POST to /api/contextual-rag/debug/test-batch', () => {
      component.batchChunksText = 'Chunk one\n---\nChunk two';
      component.testBatch();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-batch') && r.method === 'POST'
      );
      req.flush({ success: true, processedChunks: 2, processingTimeMs: 500 });
    });

    it('should parse chunks by --- delimiter', () => {
      component.batchChunksText = 'First chunk\n---\nSecond chunk\n---\nThird chunk';
      component.testBatch();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/test-batch'));
      expect(req.request.body.chunks.length).toBe(3);
      expect(req.request.body.chunks[0]).toBe('First chunk');
      expect(req.request.body.chunks[2]).toBe('Third chunk');
      req.flush({ success: true, processedChunks: 3, processingTimeMs: 750 });
    });

    it('should filter empty lines after splitting', () => {
      component.batchChunksText = '   \n---\nValid chunk\n---\n   ';
      component.testBatch();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/test-batch'));
      expect(req.request.body.chunks.length).toBe(1);
      expect(req.request.body.chunks[0]).toBe('Valid chunk');
      req.flush({ success: true, processedChunks: 1, processingTimeMs: 100 });
    });

    it('should show warning snackbar when batchChunksText is empty', () => {
      component.batchChunksText = '';
      component.testBatch();

      httpMock.expectNone(r => r.url.includes('/contextual-rag/debug/test-batch'));
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Enter chunks separated by ---/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('should include documentTitle in batch request', () => {
      component.batchChunksText = 'Chunk A';
      component.testDocumentTitle = 'Batch Report';
      component.testBatch();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/test-batch'));
      expect(req.request.body.documentTitle).toBe('Batch Report');
      req.flush({ success: true, processedChunks: 1, processingTimeMs: 50 });
    });

    it('should show success snackbar with batch stats', () => {
      component.batchChunksText = 'Chunk one\n---\nChunk two';
      component.testBatch();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/test-batch'));
      req.flush({ success: true, processedChunks: 2, processingTimeMs: 400 });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/2 chunks.*400ms/),
        'Close',
        jasmine.any(Object)
      );
    });

    it('should set batchResults on success', () => {
      component.batchChunksText = 'A chunk';
      component.testBatch();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/test-batch'));
      const result = { success: true, processedChunks: 1, processingTimeMs: 80, results: [] };
      req.flush(result);

      expect(component.batchResults).toBeTruthy();
      expect(component.loading).toBeFalse();
    });

    it('should show error snackbar on batch test failure', () => {
      component.batchChunksText = 'A chunk';
      component.testBatch();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/debug/test-batch'));
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Batch test failed/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
      expect(component.loading).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 16. clearCaches()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('clearCaches()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should POST to /api/contextual-rag/cache/clear', () => {
      component.clearCaches();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/cache/clear') && r.method === 'POST'
      );
      req.flush({ success: true, clearedDocumentSummaries: 3, clearedChunkContexts: 20 });
    });

    it('should show success snackbar with cleared counts', () => {
      component.clearCaches();

      const clearReq = httpMock.expectOne(r => r.url.includes('/contextual-rag/cache/clear'));
      clearReq.flush({ success: true, clearedDocumentSummaries: 3, clearedChunkContexts: 20 });

      // clearCaches also triggers loadCacheStats
      const statsReq = httpMock.expectOne(r => r.url.includes('/contextual-rag/cache/stats'));
      statsReq.flush(makeCacheStats());

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/3 summaries.*20 chunk/),
        'Close',
        jasmine.any(Object)
      );
    });

    it('should reload cache stats after successful clear', () => {
      component.clearCaches();

      const clearReq = httpMock.expectOne(r => r.url.includes('/contextual-rag/cache/clear'));
      clearReq.flush({ success: true, clearedDocumentSummaries: 0, clearedChunkContexts: 0 });

      // Verify loadCacheStats is called again
      const statsReq = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/cache/stats') && r.method === 'GET'
      );
      expect(statsReq).toBeTruthy();
      statsReq.flush(makeCacheStats());
    });

    it('should show error snackbar on clear failure', () => {
      component.clearCaches();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/cache/clear'));
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Failed to clear caches/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 17. getModelsForProvider()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getModelsForProvider()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
      component.providers = makeProviders();
    });

    it('should return models for known provider', () => {
      const models = component.getModelsForProvider('openai');
      expect(models.length).toBe(2);
      expect(models[0].id).toBe('gpt-4o');
    });

    it('should return single model for anthropic', () => {
      const models = component.getModelsForProvider('anthropic');
      expect(models.length).toBe(1);
      expect(models[0].id).toBe('claude-3-5-sonnet');
    });

    it('should return empty array for unknown provider', () => {
      const models = component.getModelsForProvider('unknown-provider');
      expect(models).toEqual([]);
    });

    it('should return empty array when providers list is empty', () => {
      component.providers = [];
      const models = component.getModelsForProvider('openai');
      expect(models).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 18. copyToClipboard()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('copyToClipboard()', () => {
    let clipboardSpy: jasmine.SpyObj<Clipboard>;
    let originalClipboard: Clipboard;

    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
      originalClipboard = navigator.clipboard;
      clipboardSpy = jasmine.createSpyObj('Clipboard', ['writeText']);
      clipboardSpy.writeText.and.returnValue(Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', {
        value: clipboardSpy,
        configurable: true
      });
    });

    afterEach(() => {
      Object.defineProperty(navigator, 'clipboard', {
        value: originalClipboard,
        configurable: true
      });
    });

    it('should write text to navigator.clipboard', (done) => {
      component.copyToClipboard('Text to copy');
      expect(clipboardSpy.writeText).toHaveBeenCalledWith('Text to copy');
      clipboardSpy.writeText('').then(() => {
        done();
      });
    });

    it('should show Copied to clipboard snackbar', (done) => {
      clipboardSpy.writeText.and.callFake(() => {
        snackBarSpy.open('Copied to clipboard', 'Close', jasmine.any(Object) as any);
        return Promise.resolve();
      });

      component.copyToClipboard('Some text');

      clipboardSpy.writeText.calls.mostRecent().returnValue.then(() => {
        expect(snackBarSpy.open).toHaveBeenCalledWith(
          jasmine.stringMatching(/Copied to clipboard/),
          'Close',
          jasmine.any(Object)
        );
        done();
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 19. loadSampleChunk() / loadSampleBatch()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadSampleChunk()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should populate testChunkText with financial sample text', () => {
      component.testChunkText = '';
      component.loadSampleChunk();
      expect(component.testChunkText).toContain('revenue');
    });

    it('should set testDocumentTitle to Q3 2024 Financial Report', () => {
      component.loadSampleChunk();
      expect(component.testDocumentTitle).toBe('Q3 2024 Financial Report');
    });

    it('should populate testDocumentSummary with sample text', () => {
      component.testDocumentSummary = '';
      component.loadSampleChunk();
      expect(component.testDocumentSummary.length).toBeGreaterThan(0);
    });
  });

  describe('loadSampleBatch()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('should populate batchChunksText with multiple chunks', () => {
      component.batchChunksText = '';
      component.loadSampleBatch();
      expect(component.batchChunksText).toContain('---');
    });

    it('should produce parseable chunks separated by ---', () => {
      component.loadSampleBatch();
      const chunks = component.batchChunksText
        .split('\n---\n')
        .map((c: string) => c.trim())
        .filter((c: string) => c.length > 0);
      expect(chunks.length).toBeGreaterThanOrEqual(3);
    });

    it('should set testDocumentTitle', () => {
      component.loadSampleBatch();
      expect(component.testDocumentTitle).toBe('Q3 2024 Financial Report');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 20. ERROR HANDLING EDGE CASES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Error handling edge cases', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushInitRequests(httpMock);
    });

    it('testContextualization should show fallback error message when result.error is undefined', () => {
      component.testContextualization();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/debug/test-contextualization')
      );
      req.flush({ success: false }); // no error field

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Contextualization failed/),
        'Close',
        jasmine.objectContaining({ panelClass: 'snackbar-error' })
      );
    });

    it('applyPreset should reset loading flag on error', () => {
      component.applyPreset('bad-preset');
      expect(component.loading).toBeTrue();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/presets/bad-preset'));
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(component.loading).toBeFalse();
    });

    it('saveConfig should reset loading flag on error', () => {
      component.config = makeConfig();
      component.saveConfig();
      expect(component.loading).toBeTrue();

      const req = httpMock.expectOne(r =>
        r.url.includes('/contextual-rag/config') && r.method === 'POST'
      );
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(component.loading).toBeFalse();
    });

    it('resetConfig should reset loading flag on error', () => {
      component.resetConfig();
      expect(component.loading).toBeTrue();

      const req = httpMock.expectOne(r => r.url.includes('/contextual-rag/config/reset'));
      req.flush({}, { status: 500, statusText: 'Server Error' });

      expect(component.loading).toBeFalse();
    });

    it('testBatch should not POST when all chunks trim to empty', () => {
      component.batchChunksText = '   \n---\n   \n---\n   ';
      component.testBatch();

      httpMock.expectNone(r => r.url.includes('/contextual-rag/debug/test-batch'));
    });
  });
});
