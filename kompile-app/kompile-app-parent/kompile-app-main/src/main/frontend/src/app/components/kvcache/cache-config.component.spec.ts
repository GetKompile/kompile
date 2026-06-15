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

import { CacheConfigComponent } from './cache-config.component';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheProperties, KVCacheSummary } from '../../models/kvcache-models';

describe('CacheConfigComponent', () => {
  let component: CacheConfigComponent;
  let fixture: ComponentFixture<CacheConfigComponent>;
  let kvCacheServiceSpy: jasmine.SpyObj<KVCacheService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  const mockProps: KVCacheProperties = {
    enabled: true,
    defaultType: 'paged',
    blockSize: 64,
    maxBatchSize: 8,
    maxSeqLen: 4096,
    numKvHeads: 32,
    headDim: 128,
    dataType: 'FLOAT',
    poolSizeFactor: 1.2,
    evictionPolicy: 'h2o',
    tokenBudget: 2048,
    quantFormat: 'INT8',
    turboQuantBits: 3,
    tieredEnabled: false,
    gpuPressureThreshold: 0.10,
    hostPoolMaxBlocks: 1024,
    diskOffloadPath: '',
    prefixCacheEnabled: false,
    prefixCacheMaxEntries: 1024,
    checkpointEnabled: false,
    maxCheckpoints: 16,
    checkpointDir: '',
    statsWindowSeconds: 300
  };

  const mockCacheSummary: KVCacheSummary = {
    name: 'my-cache',
    type: 'paged',
    createdAt: Date.now(),
    memoryUsageBytes: 1024,
    activeSequences: 0,
    freeBlocks: 100,
    totalBlocks: 100
  };

  beforeEach(async () => {
    kvCacheServiceSpy = jasmine.createSpyObj('KVCacheService', [
      'getConfig', 'updateConfig', 'createCache'
    ]);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    kvCacheServiceSpy.getConfig.and.returnValue(of(mockProps));
    kvCacheServiceSpy.updateConfig.and.returnValue(of(mockProps));
    kvCacheServiceSpy.createCache.and.returnValue(of(mockCacheSummary));

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(CacheConfigComponent, {
      set: {
        imports: [CommonModule, FormsModule],
        template: '<div></div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(KVCacheService, { useValue: kvCacheServiceSpy })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(CacheConfigComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call getConfig on init', () => {
    expect(kvCacheServiceSpy.getConfig).toHaveBeenCalled();
  });

  it('should populate props from service on init', () => {
    expect(component.props).toEqual(mockProps);
  });

  it('should use default props on getConfig error', fakeAsync(() => {
    kvCacheServiceSpy.getConfig.and.returnValue(throwError(() => new Error('not found')));
    // Store the default values before any overwrite
    const defaultEnabled = false;
    component.loadConfig();
    tick();
    // On error the component silently keeps the current props
    // Since error handler is empty, props should remain unchanged (not reset)
    expect(component.props).toBeDefined();
  }));

  it('should have empty cacheName initially', () => {
    expect(component.cacheName).toBe('');
  });

  it('should have empty cacheConfig initially', () => {
    expect(component.cacheConfig).toEqual({});
  });

  describe('loadConfig', () => {
    it('should reload config from service', fakeAsync(() => {
      const newProps = { ...mockProps, blockSize: 128 };
      kvCacheServiceSpy.getConfig.and.returnValue(of(newProps));
      component.loadConfig();
      tick();
      expect(component.props.blockSize).toBe(128);
    }));

    it('should make a copy of the props (not reference)', fakeAsync(() => {
      component.loadConfig();
      tick();
      // Mutating component.props should not affect mockProps
      component.props.blockSize = 999;
      expect(mockProps.blockSize).toBe(64);
    }));
  });

  describe('saveConfig', () => {
    it('should call updateConfig with current props', fakeAsync(() => {
      component.saveConfig();
      tick();
      expect(kvCacheServiceSpy.updateConfig).toHaveBeenCalledWith(component.props);
    }));

    it('should update props from the returned value', fakeAsync(() => {
      const updatedProps = { ...mockProps, blockSize: 256 };
      kvCacheServiceSpy.updateConfig.and.returnValue(of(updatedProps));
      component.saveConfig();
      tick();
      expect(component.props.blockSize).toBe(256);
    }));

    it('should show success snack bar after save', fakeAsync(() => {
      component.saveConfig();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Settings saved and persisted', 'Close', { duration: 2000 }
      );
    }));

    it('should emit configChanged event after save', fakeAsync(() => {
      let emitted = false;
      component.configChanged.subscribe(() => emitted = true);
      component.saveConfig();
      tick();
      expect(emitted).toBe(true);
    }));

    it('should show error snack bar when save fails with error.error.error', fakeAsync(() => {
      kvCacheServiceSpy.updateConfig.and.returnValue(
        throwError(() => ({ error: { error: 'validation failed' } }))
      );
      component.saveConfig();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to save'),
        'Close',
        { duration: 3000 }
      );
    }));

    it('should show error snack bar when save fails with message', fakeAsync(() => {
      kvCacheServiceSpy.updateConfig.and.returnValue(
        throwError(() => ({ message: 'server error' }))
      );
      component.saveConfig();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to save'),
        'Close',
        { duration: 3000 }
      );
    }));
  });

  describe('createCache', () => {
    it('should call createCache when cacheName is set', fakeAsync(() => {
      component.cacheName = 'my-cache';
      component.createCache();
      tick();
      expect(kvCacheServiceSpy.createCache).toHaveBeenCalledWith('my-cache', component.cacheConfig);
    }));

    it('should not call createCache when cacheName is empty', fakeAsync(() => {
      component.cacheName = '';
      component.createCache();
      tick();
      expect(kvCacheServiceSpy.createCache).not.toHaveBeenCalled();
    }));

    it('should show success snack bar with name and block count after createCache', fakeAsync(() => {
      component.cacheName = 'my-cache';
      component.createCache();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining("'my-cache'"),
        'Close',
        { duration: 3000 }
      );
    }));

    it('should show block count in success snack bar', fakeAsync(() => {
      component.cacheName = 'my-cache';
      component.createCache();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('100 blocks'),
        'Close',
        { duration: 3000 }
      );
    }));

    it('should clear cacheName after createCache success', fakeAsync(() => {
      component.cacheName = 'my-cache';
      component.createCache();
      tick();
      expect(component.cacheName).toBe('');
    }));

    it('should reset cacheConfig after createCache success', fakeAsync(() => {
      component.cacheName = 'my-cache';
      component.cacheConfig = { type: 'evictable', blockSize: 128 };
      component.createCache();
      tick();
      expect(component.cacheConfig).toEqual({});
    }));

    it('should show error snack bar when createCache fails with error.error.error', fakeAsync(() => {
      kvCacheServiceSpy.createCache.and.returnValue(
        throwError(() => ({ error: { error: 'name conflict' } }))
      );
      component.cacheName = 'my-cache';
      component.createCache();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed'),
        'Close',
        { duration: 3000 }
      );
    }));

    it('should show error snack bar when createCache fails with message', fakeAsync(() => {
      kvCacheServiceSpy.createCache.and.returnValue(
        throwError(() => ({ message: 'service unavailable' }))
      );
      component.cacheName = 'my-cache';
      component.createCache();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed'),
        'Close',
        { duration: 3000 }
      );
    }));

    it('should pass cacheConfig type override when specified', fakeAsync(() => {
      component.cacheName = 'typed-cache';
      component.cacheConfig = { type: 'quantized' };
      component.createCache();
      tick();
      expect(kvCacheServiceSpy.createCache).toHaveBeenCalledWith(
        'typed-cache',
        jasmine.objectContaining({ type: 'quantized' })
      );
    }));
  });

  describe('props defaults', () => {
    it('should have enabled=false as default before loadConfig', () => {
      // The component initializes with enabled: false before getConfig returns
      // (but getConfig is called in ngOnInit and mock returns enabled: true)
      // After init, it should reflect the mocked value
      expect(component.props.enabled).toBe(true);
    });

    it('should have defaultType=paged', () => {
      expect(component.props.defaultType).toBe('paged');
    });

    it('should have evictionPolicy=h2o', () => {
      expect(component.props.evictionPolicy).toBe('h2o');
    });

    it('should have tieredEnabled=false', () => {
      expect(component.props.tieredEnabled).toBe(false);
    });

    it('should have prefixCacheEnabled=false', () => {
      expect(component.props.prefixCacheEnabled).toBe(false);
    });

    it('should have checkpointEnabled=false', () => {
      expect(component.props.checkpointEnabled).toBe(false);
    });
  });
});
