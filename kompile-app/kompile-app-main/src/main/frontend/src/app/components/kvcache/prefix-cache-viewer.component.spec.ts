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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { PrefixCacheViewerComponent } from './prefix-cache-viewer.component';
import { KVCacheService } from '../../services/kvcache.service';
import { PrefixCacheStats, PrefixEntry } from '../../models/kvcache-models';

describe('PrefixCacheViewerComponent', () => {
  let component: PrefixCacheViewerComponent;
  let fixture: ComponentFixture<PrefixCacheViewerComponent>;
  let kvCacheServiceSpy: jasmine.SpyObj<KVCacheService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  const mockPrefixStats: PrefixCacheStats = {
    totalEntries: 10,
    maxEntries: 1024,
    totalLookups: 500,
    totalHits: 300,
    hitRate: 0.6
  };

  const mockEntries: PrefixEntry[] = [
    { prefixHash: 'abc123', tokenCount: 50, blockCount: 2, accessCount: 5, lastAccessed: Date.now() },
    { prefixHash: 'def456', tokenCount: 100, blockCount: 4, accessCount: 12, lastAccessed: Date.now() - 1000 }
  ];

  beforeEach(async () => {
    kvCacheServiceSpy = jasmine.createSpyObj('KVCacheService', [
      'getPrefixCacheStats', 'getPrefixCacheEntries', 'savePrefixCache', 'loadPrefixCache'
    ]);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    kvCacheServiceSpy.getPrefixCacheStats.and.returnValue(of(mockPrefixStats));
    kvCacheServiceSpy.getPrefixCacheEntries.and.returnValue(of(mockEntries));
    kvCacheServiceSpy.savePrefixCache.and.returnValue(of({}));
    kvCacheServiceSpy.loadPrefixCache.and.returnValue(of({}));

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(PrefixCacheViewerComponent, {
      set: {
        imports: [CommonModule, FormsModule],
        template: '<div></div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(KVCacheService, { useValue: kvCacheServiceSpy })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(PrefixCacheViewerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have correct displayedColumns', () => {
    expect(component.displayedColumns).toEqual([
      'prefixHash', 'tokenCount', 'blockCount', 'accessCount', 'lastAccessed'
    ]);
  });

  it('should call getPrefixCacheStats and getPrefixCacheEntries on init', () => {
    expect(kvCacheServiceSpy.getPrefixCacheStats).toHaveBeenCalled();
    expect(kvCacheServiceSpy.getPrefixCacheEntries).toHaveBeenCalled();
  });

  it('should set prefixStats after init', () => {
    expect(component.prefixStats).toEqual(mockPrefixStats);
  });

  it('should set entries after init', () => {
    expect(component.entries).toEqual(mockEntries);
  });

  it('should set prefixStats to null on error', fakeAsync(() => {
    kvCacheServiceSpy.getPrefixCacheStats.and.returnValue(throwError(() => new Error('fail')));
    kvCacheServiceSpy.getPrefixCacheEntries.and.returnValue(of([]));
    component.refresh();
    tick();
    expect(component.prefixStats).toBeNull();
  }));

  it('should set entries to empty array on error', fakeAsync(() => {
    kvCacheServiceSpy.getPrefixCacheStats.and.returnValue(of(mockPrefixStats));
    kvCacheServiceSpy.getPrefixCacheEntries.and.returnValue(throwError(() => new Error('fail')));
    component.refresh();
    tick();
    expect(component.entries).toEqual([]);
  }));

  it('should call savePrefixCache on save()', fakeAsync(() => {
    component.save();
    tick();
    expect(kvCacheServiceSpy.savePrefixCache).toHaveBeenCalled();
  }));

  it('should show success snack bar on save success', fakeAsync(() => {
    component.save();
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Prefix cache saved to disk', 'Close', { duration: 2000 });
  }));

  it('should show error snack bar on save failure', fakeAsync(() => {
    kvCacheServiceSpy.savePrefixCache.and.returnValue(throwError(() => ({ message: 'disk full' })));
    component.save();
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(jasmine.stringContaining('Save failed'), 'Close', { duration: 3000 });
  }));

  it('should call loadPrefixCache on load()', fakeAsync(() => {
    component.load();
    tick();
    expect(kvCacheServiceSpy.loadPrefixCache).toHaveBeenCalled();
  }));

  it('should show success snack bar on load success', fakeAsync(() => {
    component.load();
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Prefix cache loaded from disk', 'Close', { duration: 2000 });
  }));

  it('should call refresh after load success', fakeAsync(() => {
    const refreshSpy = spyOn(component, 'refresh').and.callThrough();
    component.load();
    tick();
    // refresh is called once by load (after success) + once internally after load returns
    expect(refreshSpy).toHaveBeenCalled();
  }));

  it('should show error snack bar on load failure', fakeAsync(() => {
    kvCacheServiceSpy.loadPrefixCache.and.returnValue(throwError(() => ({ message: 'file not found' })));
    component.load();
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(jasmine.stringContaining('Load failed'), 'Close', { duration: 3000 });
  }));

  it('should refresh stats and entries on refresh()', fakeAsync(() => {
    kvCacheServiceSpy.getPrefixCacheStats.calls.reset();
    kvCacheServiceSpy.getPrefixCacheEntries.calls.reset();
    component.refresh();
    tick();
    expect(kvCacheServiceSpy.getPrefixCacheStats).toHaveBeenCalledTimes(1);
    expect(kvCacheServiceSpy.getPrefixCacheEntries).toHaveBeenCalledTimes(1);
  }));

  it('should initialize entries as empty array', () => {
    // After fixture.detectChanges with mocked service returning mockEntries
    expect(Array.isArray(component.entries)).toBe(true);
  });
});
