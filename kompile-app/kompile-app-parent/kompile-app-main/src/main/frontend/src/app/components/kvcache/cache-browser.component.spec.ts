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
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { CacheBrowserComponent } from './cache-browser.component';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheSummary } from '../../models/kvcache-models';

describe('CacheBrowserComponent', () => {
  let component: CacheBrowserComponent;
  let fixture: ComponentFixture<CacheBrowserComponent>;
  let kvCacheServiceSpy: jasmine.SpyObj<KVCacheService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  const mockCaches: KVCacheSummary[] = [
    { name: 'cache-a', type: 'paged', createdAt: Date.now(), memoryUsageBytes: 1024, activeSequences: 2, freeBlocks: 50, totalBlocks: 100 },
    { name: 'cache-b', type: 'evictable', createdAt: Date.now(), memoryUsageBytes: 2048, activeSequences: 5, freeBlocks: 20, totalBlocks: 200 }
  ];

  beforeEach(async () => {
    kvCacheServiceSpy = jasmine.createSpyObj('KVCacheService', [
      'listCaches', 'destroyCache', 'formatBytes'
    ]);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    kvCacheServiceSpy.listCaches.and.returnValue(of(mockCaches));
    kvCacheServiceSpy.destroyCache.and.returnValue(of({}));
    kvCacheServiceSpy.formatBytes.and.callFake((bytes: number) => bytes + ' B');

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(CacheBrowserComponent, {
      set: {
        imports: [CommonModule, FormsModule],
        template: '<div></div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(KVCacheService, { useValue: kvCacheServiceSpy })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(CacheBrowserComponent);
    component = fixture.componentInstance;
    // Prevent auto-refresh from starting during detectChanges
    component.autoRefresh = false;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have correct displayedColumns', () => {
    expect(component.displayedColumns).toEqual(['name', 'type', 'memory', 'sequences', 'blocks', 'actions']);
  });

  it('should call listCaches on init', () => {
    expect(kvCacheServiceSpy.listCaches).toHaveBeenCalled();
  });

  it('should populate caches after init', () => {
    expect(component.caches).toEqual(mockCaches);
  });

  it('should show error snack bar when listCaches fails', fakeAsync(() => {
    kvCacheServiceSpy.listCaches.and.returnValue(throwError(() => ({ message: 'network error' })));
    component.refresh();
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Failed to load caches'),
      'Close',
      { duration: 3000 }
    );
  }));

  it('should call destroyCache with cache name on deleteCache()', fakeAsync(() => {
    component.deleteCache('cache-a');
    tick();
    expect(kvCacheServiceSpy.destroyCache).toHaveBeenCalledWith('cache-a');
  }));

  it('should show success snack bar on deleteCache success', fakeAsync(() => {
    component.deleteCache('cache-a');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith("Cache 'cache-a' deleted", 'Close', { duration: 2000 });
  }));

  it('should refresh after deleteCache success', fakeAsync(() => {
    kvCacheServiceSpy.listCaches.calls.reset();
    component.deleteCache('cache-a');
    tick();
    expect(kvCacheServiceSpy.listCaches).toHaveBeenCalled();
  }));

  it('should show error snack bar on deleteCache failure', fakeAsync(() => {
    kvCacheServiceSpy.destroyCache.and.returnValue(throwError(() => ({ message: 'cache not found' })));
    component.deleteCache('nonexistent');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Delete failed'),
      'Close',
      { duration: 3000 }
    );
  }));

  it('should start interval on toggleAutoRefresh when autoRefresh is true', fakeAsync(() => {
    kvCacheServiceSpy.listCaches.calls.reset();
    component.autoRefresh = true;
    component.toggleAutoRefresh();
    tick(5000);
    expect(kvCacheServiceSpy.listCaches).toHaveBeenCalled();
    discardPeriodicTasks();
  }));

  it('should stop interval on toggleAutoRefresh when autoRefresh is false', fakeAsync(() => {
    component.autoRefresh = true;
    component.toggleAutoRefresh();
    component.autoRefresh = false;
    component.toggleAutoRefresh();
    kvCacheServiceSpy.listCaches.calls.reset();
    tick(10000);
    expect(kvCacheServiceSpy.listCaches).not.toHaveBeenCalled();
  }));

  it('should cancel previous interval when toggleAutoRefresh is called again', fakeAsync(() => {
    component.autoRefresh = true;
    component.toggleAutoRefresh();
    // Turn off, no more ticks should trigger listCaches
    component.autoRefresh = false;
    component.toggleAutoRefresh();
    kvCacheServiceSpy.listCaches.calls.reset();
    tick(15000);
    expect(kvCacheServiceSpy.listCaches).not.toHaveBeenCalled();
  }));

  it('should unsubscribe on ngOnDestroy', () => {
    component.autoRefresh = true;
    component.toggleAutoRefresh();
    expect(() => component.ngOnDestroy()).not.toThrow();
  });

  describe('getMemoryPercent', () => {
    it('should return 0 when totalBlocks is 0', () => {
      const cache: KVCacheSummary = { ...mockCaches[0], totalBlocks: 0, freeBlocks: 0 };
      expect(component.getMemoryPercent(cache)).toBe(0);
    });

    it('should return correct percentage', () => {
      const cache: KVCacheSummary = { ...mockCaches[0], totalBlocks: 100, freeBlocks: 25 };
      expect(component.getMemoryPercent(cache)).toBeCloseTo(75, 1);
    });

    it('should return 100% when no free blocks', () => {
      const cache: KVCacheSummary = { ...mockCaches[0], totalBlocks: 100, freeBlocks: 0 };
      expect(component.getMemoryPercent(cache)).toBe(100);
    });

    it('should return 0% when all blocks are free', () => {
      const cache: KVCacheSummary = { ...mockCaches[0], totalBlocks: 100, freeBlocks: 100 };
      expect(component.getMemoryPercent(cache)).toBe(0);
    });
  });
});
