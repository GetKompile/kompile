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

import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { CacheStatsComponent } from './cache-stats.component';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheStats, KVCacheSummary, StatsSample } from '../../models/kvcache-models';

describe('CacheStatsComponent', () => {
  let component: CacheStatsComponent;
  let fixture: ComponentFixture<CacheStatsComponent>;
  let kvCacheServiceSpy: jasmine.SpyObj<KVCacheService>;

  const mockCaches: KVCacheSummary[] = [
    { name: 'primary', type: 'paged', createdAt: Date.now(), memoryUsageBytes: 1024, activeSequences: 2, freeBlocks: 80, totalBlocks: 100 },
    { name: 'secondary', type: 'evictable', createdAt: Date.now(), memoryUsageBytes: 512, activeSequences: 1, freeBlocks: 50, totalBlocks: 100 }
  ];

  const mockSamples: StatsSample[] = [
    { timestamp: Date.now() - 4000, memoryUsedBytes: 500, activeSequences: 1, appendsPerSecond: 10, evictionsPerSecond: 0 },
    { timestamp: Date.now() - 2000, memoryUsedBytes: 750, activeSequences: 2, appendsPerSecond: 15, evictionsPerSecond: 1 },
    { timestamp: Date.now(), memoryUsedBytes: 1000, activeSequences: 3, appendsPerSecond: 20, evictionsPerSecond: 2 }
  ];

  const mockStats: KVCacheStats = {
    cacheName: 'primary',
    cacheType: 'paged',
    totalAppends: 1000,
    totalEvictions: 50,
    totalFrees: 200,
    hitCount: 800,
    missCount: 200,
    hitRate: 0.8,
    memoryUsedBytes: 1024,
    memoryCapacityBytes: 4096,
    memoryUtilization: 0.25,
    activeSequences: 2,
    freeBlocks: 80,
    totalBlocks: 100,
    recentSamples: mockSamples,
    collectedAt: Date.now()
  };

  const mockAggregateStats: KVCacheStats = {
    ...mockStats,
    cacheName: 'aggregate',
    totalAppends: 2000,
    totalEvictions: 100,
    memoryUsedBytes: 2048
  };

  beforeEach(async () => {
    kvCacheServiceSpy = jasmine.createSpyObj('KVCacheService', [
      'listCaches', 'getCacheStats', 'getAggregateStats', 'formatBytes'
    ]);

    kvCacheServiceSpy.listCaches.and.returnValue(of(mockCaches));
    kvCacheServiceSpy.getCacheStats.and.returnValue(of(mockStats));
    kvCacheServiceSpy.getAggregateStats.and.returnValue(of(mockAggregateStats));
    kvCacheServiceSpy.formatBytes.and.callFake((bytes: number) => bytes + ' B');

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(CacheStatsComponent, {
      set: {
        imports: [CommonModule, FormsModule],
        template: '<div></div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(KVCacheService, { useValue: kvCacheServiceSpy })
    .compileComponents();

    fixture = TestBed.createComponent(CacheStatsComponent);
    component = fixture.componentInstance;
    // Prevent auto-refresh from leaking during detectChanges
    component.autoRefresh = false;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call listCaches on init', () => {
    expect(kvCacheServiceSpy.listCaches).toHaveBeenCalled();
  });

  it('should populate caches list on init', () => {
    expect(component.caches).toEqual(mockCaches);
  });

  it('should auto-select the first cache on init', () => {
    expect(component.selectedCache).toBe('primary');
  });

  it('should call getCacheStats on init when caches are available', () => {
    expect(kvCacheServiceSpy.getCacheStats).toHaveBeenCalledWith('primary');
  });

  it('should call getAggregateStats on init', () => {
    expect(kvCacheServiceSpy.getAggregateStats).toHaveBeenCalled();
  });

  it('should set aggregateStats after init', () => {
    expect(component.aggregateStats).toEqual(mockAggregateStats);
  });

  it('should set stats after loadStats()', () => {
    expect(component.stats).toEqual(mockStats);
  });

  it('should not load stats when selectedCache is empty', fakeAsync(() => {
    component.selectedCache = '';
    kvCacheServiceSpy.getCacheStats.calls.reset();
    component.loadStats();
    tick();
    expect(kvCacheServiceSpy.getCacheStats).not.toHaveBeenCalled();
  }));

  it('should load stats for selectedCache in loadStats()', fakeAsync(() => {
    component.selectedCache = 'secondary';
    kvCacheServiceSpy.getCacheStats.calls.reset();
    component.loadStats();
    tick();
    expect(kvCacheServiceSpy.getCacheStats).toHaveBeenCalledWith('secondary');
  }));

  it('should refresh aggregate stats on loadStats()', fakeAsync(() => {
    kvCacheServiceSpy.getAggregateStats.calls.reset();
    component.loadStats();
    tick();
    expect(kvCacheServiceSpy.getAggregateStats).toHaveBeenCalled();
  }));

  it('should start auto-refresh interval when autoRefresh is true', fakeAsync(() => {
    kvCacheServiceSpy.getCacheStats.calls.reset();
    component.selectedCache = 'primary';
    component.autoRefresh = true;
    component.toggleAutoRefresh();
    tick(2000);
    expect(kvCacheServiceSpy.getCacheStats).toHaveBeenCalled();
    discardPeriodicTasks();
  }));

  it('should stop auto-refresh when autoRefresh is false', fakeAsync(() => {
    component.autoRefresh = true;
    component.toggleAutoRefresh();
    component.autoRefresh = false;
    component.toggleAutoRefresh();
    kvCacheServiceSpy.getCacheStats.calls.reset();
    tick(10000);
    expect(kvCacheServiceSpy.getCacheStats).not.toHaveBeenCalled();
  }));

  it('should unsubscribe on destroy', () => {
    component.autoRefresh = true;
    component.toggleAutoRefresh();
    expect(() => component.ngOnDestroy()).not.toThrow();
  });

  it('should not auto-select cache when caches list is empty', fakeAsync(() => {
    kvCacheServiceSpy.listCaches.and.returnValue(of([]));
    component.selectedCache = '';
    component.ngOnInit();
    tick();
    expect(component.selectedCache).toBe('');
  }));

  it('should handle stats with no recentSamples', fakeAsync(() => {
    const statsWithoutSamples = { ...mockStats, recentSamples: [] };
    kvCacheServiceSpy.getCacheStats.and.returnValue(of(statsWithoutSamples));
    component.loadStats();
    tick();
    expect(component.stats).toEqual(statsWithoutSamples);
  }));
});
