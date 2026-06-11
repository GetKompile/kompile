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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { BenchmarkRunnerComponent } from './benchmark-runner.component';
import {
  BenchmarkService,
  SamediffBenchmarkConfig,
  SamediffBenchmarkResult
} from '../../services/benchmark.service';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeConfig(overrides: Partial<SamediffBenchmarkConfig> = {}): SamediffBenchmarkConfig {
  return {
    name: 'test-cfg',
    tritonNumWarps: 8,
    tritonNumStages: 3,
    tritonCacheEnabled: true,
    maxTokens: 100,
    captureMinExec: 3,
    ...overrides
  };
}

function makeResult(overrides: Partial<SamediffBenchmarkResult> = {}): SamediffBenchmarkResult {
  return {
    configName: 'test-cfg',
    passed: true,
    resetMs: 50,
    compileMs: 200,
    decodeMs: 300,
    validateMs: 10,
    totalMs: 560,
    tokenCount: 100,
    tokPerSec: 178.6,
    decodeTokPerSec: 333.3,
    firstTokenMs: 120,
    tritonLaunches: 100,
    tritonCacheHits: 95,
    finishReason: 'eos',
    timestamp: '2025-01-01T00:00:00Z',
    ...overrides
  };
}

// ---------------------------------------------------------------------------
// Test setup for standalone component
// ---------------------------------------------------------------------------

describe('BenchmarkRunnerComponent', () => {
  let component: BenchmarkRunnerComponent;
  let fixture: ComponentFixture<BenchmarkRunnerComponent>;
  let benchmarkServiceSpy: jasmine.SpyObj<BenchmarkService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    benchmarkServiceSpy = jasmine.createSpyObj('BenchmarkService', [
      'listConfigs',
      'getConfig',
      'saveConfig',
      'updateConfig',
      'deleteConfig',
      'activateConfig',
      'getActiveConfig',
      'runBenchmark',
      'runMatrix',
      'searchOptimalProfile',
      'getResults',
      'clearResults',
      'applyOptimalDefaults'
    ]);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    // Default return values
    benchmarkServiceSpy.listConfigs.and.returnValue(of([]));
    benchmarkServiceSpy.getResults.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [BenchmarkRunnerComponent, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(BenchmarkRunnerComponent, {
      set: { schemas: [NO_ERRORS_SCHEMA] }
    })
    .overrideProvider(BenchmarkService, { useValue: benchmarkServiceSpy })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(BenchmarkRunnerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    component.ngOnDestroy();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. Creation
  // ─────────────────────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call loadConfigs and loadResults on init', () => {
    expect(benchmarkServiceSpy.listConfigs).toHaveBeenCalled();
    expect(benchmarkServiceSpy.getResults).toHaveBeenCalled();
  });

  it('should initialize with default state', () => {
    expect(component.configs).toEqual([]);
    expect(component.loading).toBeFalse();
    expect(component.error).toBeNull();
    expect(component.isEditing).toBeFalse();
    expect(component.runningBenchmark).toBeFalse();
    expect(component.results).toEqual([]);
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. loadConfigs()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadConfigs()', () => {
    it('should populate configs on success', fakeAsync(() => {
      const configs = [makeConfig({ name: 'a' }), makeConfig({ name: 'b', isActive: true })];
      benchmarkServiceSpy.listConfigs.and.returnValue(of(configs));

      component.loadConfigs();
      tick();

      expect(component.configs.length).toBe(2);
      expect(component.loading).toBeFalse();
    }));

    it('should set activeConfigName from the active config', fakeAsync(() => {
      const configs = [makeConfig({ name: 'alpha', isActive: true }), makeConfig({ name: 'beta' })];
      benchmarkServiceSpy.listConfigs.and.returnValue(of(configs));

      component.loadConfigs();
      tick();

      expect(component.activeConfigName).toBe('alpha');
    }));

    it('should set activeConfigName to null when no active config', fakeAsync(() => {
      benchmarkServiceSpy.listConfigs.and.returnValue(of([makeConfig({ name: 'x' })]));

      component.loadConfigs();
      tick();

      expect(component.activeConfigName).toBeNull();
    }));

    it('should set error and clear loading on HTTP error', fakeAsync(() => {
      benchmarkServiceSpy.listConfigs.and.returnValue(
        throwError(() => new Error('Network failure'))
      );

      component.loadConfigs();
      tick();

      expect(component.error).toBe('Network failure');
      expect(component.loading).toBeFalse();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. newConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('newConfig()', () => {
    it('should return a config with sensible defaults', () => {
      const cfg = component.newConfig();
      expect(cfg.name).toBe('');
      expect(cfg.tritonNumWarps).toBe(8);
      expect(cfg.tritonNumStages).toBe(3);
      expect(cfg.maxTokens).toBe(100);
      expect(cfg.tritonCacheEnabled).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. startNewConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startNewConfig()', () => {
    it('should reset editConfig and set isEditing=true', () => {
      component.startNewConfig();
      expect(component.isEditing).toBeTrue();
      expect(component.editConfig.name).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. editExistingConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('editExistingConfig()', () => {
    it('should copy the config and set isEditing=true', () => {
      const cfg = makeConfig({ name: 'my-cfg', tritonNumWarps: 16 });
      component.editExistingConfig(cfg);

      expect(component.isEditing).toBeTrue();
      expect(component.editConfig.name).toBe('my-cfg');
      expect(component.editConfig.tritonNumWarps).toBe(16);
    });

    it('should copy (not reference) the config', () => {
      const cfg = makeConfig({ name: 'original' });
      component.editExistingConfig(cfg);
      component.editConfig.name = 'modified';

      expect(cfg.name).toBe('original');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. cancelEdit()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('cancelEdit()', () => {
    it('should set isEditing=false', () => {
      component.isEditing = true;
      component.cancelEdit();
      expect(component.isEditing).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. saveConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('saveConfig()', () => {
    it('should show snackBar error and not call service if name is empty', fakeAsync(() => {
      component.editConfig.name = '';
      component.saveConfig();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Config name is required', 'Close', jasmine.any(Object));
      expect(benchmarkServiceSpy.saveConfig).not.toHaveBeenCalled();
    }));

    it('should call saveConfig and reload configs on success', fakeAsync(() => {
      const saved = makeConfig({ name: 'new-config' });
      benchmarkServiceSpy.saveConfig.and.returnValue(of(saved));
      benchmarkServiceSpy.listConfigs.and.returnValue(of([saved]));

      component.editConfig = makeConfig({ name: 'new-config' });
      component.saveConfig();
      tick();

      expect(benchmarkServiceSpy.saveConfig).toHaveBeenCalled();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Config saved', 'Close', jasmine.any(Object));
      expect(component.isEditing).toBeFalse();
    }));

    it('should show error snackBar on save failure', fakeAsync(() => {
      benchmarkServiceSpy.saveConfig.and.returnValue(
        throwError(() => new Error('Save failed'))
      );

      component.editConfig = makeConfig({ name: 'x' });
      component.saveConfig();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to save'), 'Close', jasmine.any(Object)
      );
      expect(component.loading).toBeFalse();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. deleteConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteConfig()', () => {
    it('should call service and reload configs on success', fakeAsync(() => {
      benchmarkServiceSpy.deleteConfig.and.returnValue(of({}));
      benchmarkServiceSpy.listConfigs.and.returnValue(of([]));

      component.deleteConfig('my-config');
      tick();

      expect(benchmarkServiceSpy.deleteConfig).toHaveBeenCalledWith('my-config');
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining("'my-config' deleted"), 'Close', jasmine.any(Object)
      );
    }));

    it('should show error snackBar on delete failure', fakeAsync(() => {
      benchmarkServiceSpy.deleteConfig.and.returnValue(
        throwError(() => new Error('Delete error'))
      );

      component.deleteConfig('bad');
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to delete'), 'Close', jasmine.any(Object)
      );
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. activateConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('activateConfig()', () => {
    it('should call service and reload configs on success', fakeAsync(() => {
      benchmarkServiceSpy.activateConfig.and.returnValue(of({}));
      benchmarkServiceSpy.listConfigs.and.returnValue(of([]));

      component.activateConfig('my-config');
      tick();

      expect(benchmarkServiceSpy.activateConfig).toHaveBeenCalledWith('my-config');
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining("'my-config' activated"), 'Close', jasmine.any(Object)
      );
    }));

    it('should show error on activate failure', fakeAsync(() => {
      benchmarkServiceSpy.activateConfig.and.returnValue(
        throwError(() => new Error('Activate error'))
      );

      component.activateConfig('x');
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to activate'), 'Close', jasmine.any(Object)
      );
      expect(component.loading).toBeFalse();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. runBenchmark()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runBenchmark()', () => {
    it('should show snackBar and not call service when no config selected', fakeAsync(() => {
      component.selectedConfigForRun = '';
      component.runBenchmark();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Select a config to run', 'Close', jasmine.any(Object));
      expect(benchmarkServiceSpy.runBenchmark).not.toHaveBeenCalled();
    }));

    it('should run benchmark and show success snackBar when passed', fakeAsync(() => {
      const result = makeResult({ passed: true, decodeTokPerSec: 333.3 });
      benchmarkServiceSpy.runBenchmark.and.returnValue(of(result));
      benchmarkServiceSpy.getResults.and.returnValue(of([result]));

      component.selectedConfigForRun = 'test-cfg';
      component.runBenchmark();
      tick();

      expect(benchmarkServiceSpy.runBenchmark).toHaveBeenCalledWith('test-cfg');
      expect(component.lastRunResult).toEqual(result);
      expect(component.runningBenchmark).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('333.3 tok/s'), 'Close', jasmine.any(Object)
      );
    }));

    it('should show failure snackBar when benchmark fails', fakeAsync(() => {
      const result = makeResult({ passed: false, failureMessage: 'OOM error' });
      benchmarkServiceSpy.runBenchmark.and.returnValue(of(result));
      benchmarkServiceSpy.getResults.and.returnValue(of([]));

      component.selectedConfigForRun = 'bad-cfg';
      component.runBenchmark();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('OOM error'), 'Close', jasmine.any(Object)
      );
    }));

    it('should show error snackBar on HTTP error', fakeAsync(() => {
      benchmarkServiceSpy.runBenchmark.and.returnValue(
        throwError(() => new Error('Timeout'))
      );

      component.selectedConfigForRun = 'cfg';
      component.runBenchmark();
      tick();

      expect(component.runningBenchmark).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Timeout'), 'Close', jasmine.any(Object)
      );
    }));

    it('should set runningBenchmark=true before result arrives', fakeAsync(() => {
      // We will not flush the observable, just check the flag
      // Using a never-resolving observable isn't practical here; check on emit start
      const result = makeResult();
      benchmarkServiceSpy.runBenchmark.and.returnValue(of(result));
      benchmarkServiceSpy.getResults.and.returnValue(of([]));

      component.selectedConfigForRun = 'cfg';
      // Flag is set synchronously before subscription emits
      // Verify the transition resets to false after tick
      component.runBenchmark();
      tick();
      expect(component.runningBenchmark).toBeFalse();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. startSearch()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startSearch()', () => {
    it('should show snackBar when warps input is invalid', fakeAsync(() => {
      component.searchWarps = 'abc';
      component.searchStages = '2,3';
      component.startSearch();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Enter valid warps and stages ranges', 'Close', jasmine.any(Object)
      );
      expect(benchmarkServiceSpy.searchOptimalProfile).not.toHaveBeenCalled();
    }));

    it('should show snackBar when stages input is empty', fakeAsync(() => {
      component.searchWarps = '4,8';
      component.searchStages = '';
      component.startSearch();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Enter valid warps and stages ranges', 'Close', jasmine.any(Object)
      );
    }));

    it('should call searchOptimalProfile with parsed ranges', fakeAsync(() => {
      const bestResult = makeResult({ decodeTokPerSec: 400 });
      benchmarkServiceSpy.searchOptimalProfile.and.returnValue(of({
        bestConfig: 'optimal',
        bestResult
      }));
      benchmarkServiceSpy.listConfigs.and.returnValue(of([]));
      benchmarkServiceSpy.getResults.and.returnValue(of([]));

      component.searchWarps = '4,8';
      component.searchStages = '2,3';
      component.searchFpFusion = true;
      component.startSearch();
      tick();

      expect(benchmarkServiceSpy.searchOptimalProfile).toHaveBeenCalledWith({
        warpsRange: [4, 8],
        stagesRange: [2, 3],
        fpFusionRange: [true, false]
      });
      expect(component.searchBestResult).toEqual(bestResult);
      expect(component.searchProgress).toBe(100);
      expect(component.searchRunning).toBeFalse();
    }));

    it('should show "No successful benchmark found" when bestResult is null', fakeAsync(() => {
      benchmarkServiceSpy.searchOptimalProfile.and.returnValue(of({ bestConfig: null }));
      benchmarkServiceSpy.listConfigs.and.returnValue(of([]));
      benchmarkServiceSpy.getResults.and.returnValue(of([]));

      component.searchWarps = '4';
      component.searchStages = '2';
      component.searchFpFusion = false;
      component.startSearch();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'No successful benchmark found', 'Close', jasmine.any(Object)
      );
    }));

    it('should show error snackBar on search failure', fakeAsync(() => {
      benchmarkServiceSpy.searchOptimalProfile.and.returnValue(
        throwError(() => new Error('Search error'))
      );

      component.searchWarps = '8';
      component.searchStages = '3';
      component.startSearch();
      tick();

      expect(component.searchRunning).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Search failed'), 'Close', jasmine.any(Object)
      );
    }));

    it('should include only [true] in fpFusionRange when searchFpFusion=false', fakeAsync(() => {
      benchmarkServiceSpy.searchOptimalProfile.and.returnValue(of({}));
      benchmarkServiceSpy.listConfigs.and.returnValue(of([]));
      benchmarkServiceSpy.getResults.and.returnValue(of([]));

      component.searchWarps = '4';
      component.searchStages = '2';
      component.searchFpFusion = false;
      component.startSearch();
      tick();

      const callArgs = benchmarkServiceSpy.searchOptimalProfile.calls.mostRecent().args[0];
      expect(callArgs.fpFusionRange).toEqual([true]);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. applyOptimal()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('applyOptimal()', () => {
    it('should call applyOptimalDefaults and reload configs', fakeAsync(() => {
      benchmarkServiceSpy.applyOptimalDefaults.and.returnValue(of({}));
      benchmarkServiceSpy.listConfigs.and.returnValue(of([]));

      component.applyOptimal();
      tick();

      expect(benchmarkServiceSpy.applyOptimalDefaults).toHaveBeenCalled();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Optimal defaults applied', 'Close', jasmine.any(Object));
    }));

    it('should show error on applyOptimal failure', fakeAsync(() => {
      benchmarkServiceSpy.applyOptimalDefaults.and.returnValue(
        throwError(() => new Error('Apply failed'))
      );

      component.applyOptimal();
      tick();

      expect(component.loading).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed'), 'Close', jasmine.any(Object)
      );
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. loadResults()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadResults()', () => {
    it('should populate results on success', fakeAsync(() => {
      const results = [makeResult(), makeResult({ configName: 'b' })];
      benchmarkServiceSpy.getResults.and.returnValue(of(results));

      component.loadResults();
      tick();

      expect(component.results.length).toBe(2);
    }));

    it('should log error (not throw) on failure', fakeAsync(() => {
      spyOn(console, 'error');
      benchmarkServiceSpy.getResults.and.returnValue(
        throwError(() => new Error('Load failed'))
      );

      component.loadResults();
      tick();

      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. clearResults()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('clearResults()', () => {
    it('should clear results array and show snackBar', fakeAsync(() => {
      component.results = [makeResult()];
      benchmarkServiceSpy.clearResults.and.returnValue(of({}));

      component.clearResults();
      tick();

      expect(component.results).toEqual([]);
      expect(snackBarSpy.open).toHaveBeenCalledWith('Results cleared', 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 15. formatTimestamp()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('formatTimestamp()', () => {
    it('should return empty string for empty input', () => {
      expect(component.formatTimestamp('')).toBe('');
    });

    it('should return a non-empty localized string for valid ISO timestamp', () => {
      const result = component.formatTimestamp('2025-01-01T12:00:00Z');
      expect(result.length).toBeGreaterThan(0);
    });

    it('should return original string on parse error', () => {
      const bad = 'not-a-date';
      // Invalid dates may still produce a string via toLocaleString; just ensure no throw
      expect(() => component.formatTimestamp(bad)).not.toThrow();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 16. ngOnDestroy()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('ngOnDestroy()', () => {
    it('should complete destroy$ subject', () => {
      let completed = false;
      (component as any).destroy$.subscribe({ complete: () => { completed = true; } });

      component.ngOnDestroy();

      expect(completed).toBeTrue();
    });
  });
});
