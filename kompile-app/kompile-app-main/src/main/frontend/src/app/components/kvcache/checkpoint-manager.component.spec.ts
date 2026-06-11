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

import { CheckpointManagerComponent } from './checkpoint-manager.component';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheSummary, CheckpointInfo } from '../../models/kvcache-models';

describe('CheckpointManagerComponent', () => {
  let component: CheckpointManagerComponent;
  let fixture: ComponentFixture<CheckpointManagerComponent>;
  let kvCacheServiceSpy: jasmine.SpyObj<KVCacheService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  const mockCaches: KVCacheSummary[] = [
    { name: 'main-cache', type: 'paged', createdAt: Date.now(), memoryUsageBytes: 1024, activeSequences: 2, freeBlocks: 80, totalBlocks: 100 },
    { name: 'aux-cache', type: 'evictable', createdAt: Date.now(), memoryUsageBytes: 512, activeSequences: 1, freeBlocks: 50, totalBlocks: 100 }
  ];

  const mockCheckpoints: CheckpointInfo[] = [
    { id: 'cp-001', label: 'before-finetune', createdAt: Date.now() - 10000, tokenCount: 512, sizeBytes: 4096, onDisk: false },
    { id: 'cp-002', label: 'after-warmup', createdAt: Date.now() - 5000, tokenCount: 256, sizeBytes: 2048, onDisk: true, diskPath: '/tmp/cp-002' }
  ];

  const mockCreatedCheckpoint: CheckpointInfo = {
    id: 'cp-003', label: 'new-checkpoint', createdAt: Date.now(), tokenCount: 100, sizeBytes: 1024, onDisk: false
  };

  beforeEach(async () => {
    kvCacheServiceSpy = jasmine.createSpyObj('KVCacheService', [
      'listCaches', 'listCheckpoints', 'createCheckpoint',
      'restoreCheckpoint', 'saveCheckpointToDisk', 'rollbackCheckpoint', 'deleteCheckpoint',
      'formatBytes'
    ]);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    kvCacheServiceSpy.listCaches.and.returnValue(of(mockCaches));
    kvCacheServiceSpy.listCheckpoints.and.returnValue(of(mockCheckpoints));
    kvCacheServiceSpy.createCheckpoint.and.returnValue(of(mockCreatedCheckpoint));
    kvCacheServiceSpy.restoreCheckpoint.and.returnValue(of({}));
    kvCacheServiceSpy.saveCheckpointToDisk.and.returnValue(of({}));
    kvCacheServiceSpy.rollbackCheckpoint.and.returnValue(of({}));
    kvCacheServiceSpy.deleteCheckpoint.and.returnValue(of({}));
    kvCacheServiceSpy.formatBytes.and.callFake((bytes: number) => bytes + ' B');

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(CheckpointManagerComponent, {
      set: {
        imports: [CommonModule, FormsModule],
        template: '<div></div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(KVCacheService, { useValue: kvCacheServiceSpy })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(CheckpointManagerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have correct displayedColumns', () => {
    expect(component.displayedColumns).toEqual(['label', 'created', 'tokens', 'size', 'disk', 'actions']);
  });

  it('should call listCaches on init', () => {
    expect(kvCacheServiceSpy.listCaches).toHaveBeenCalled();
  });

  it('should populate caches list on init', () => {
    expect(component.caches).toEqual(mockCaches);
  });

  it('should auto-select the first cache on init', () => {
    expect(component.selectedCache).toBe('main-cache');
  });

  it('should load checkpoints on init', () => {
    expect(kvCacheServiceSpy.listCheckpoints).toHaveBeenCalledWith('main-cache');
  });

  it('should populate checkpoints after init', () => {
    expect(component.checkpoints).toEqual(mockCheckpoints);
  });

  it('should not load checkpoints when selectedCache is empty', fakeAsync(() => {
    component.selectedCache = '';
    kvCacheServiceSpy.listCheckpoints.calls.reset();
    component.loadCheckpoints();
    tick();
    expect(kvCacheServiceSpy.listCheckpoints).not.toHaveBeenCalled();
  }));

  it('should load checkpoints for selectedCache', fakeAsync(() => {
    component.selectedCache = 'aux-cache';
    kvCacheServiceSpy.listCheckpoints.calls.reset();
    component.loadCheckpoints();
    tick();
    expect(kvCacheServiceSpy.listCheckpoints).toHaveBeenCalledWith('aux-cache');
  }));

  it('should set checkpoints to empty array on loadCheckpoints error', fakeAsync(() => {
    kvCacheServiceSpy.listCheckpoints.and.returnValue(throwError(() => new Error('fail')));
    component.loadCheckpoints();
    tick();
    expect(component.checkpoints).toEqual([]);
  }));

  it('should call createCheckpoint with selectedCache and label', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.newLabel = 'test-label';
    component.createCheckpoint();
    tick();
    expect(kvCacheServiceSpy.createCheckpoint).toHaveBeenCalledWith('main-cache', 'test-label');
  }));

  it('should pass undefined label when newLabel is empty', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.newLabel = '';
    component.createCheckpoint();
    tick();
    expect(kvCacheServiceSpy.createCheckpoint).toHaveBeenCalledWith('main-cache', undefined);
  }));

  it('should show success snack bar after createCheckpoint', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.newLabel = 'new-checkpoint';
    component.createCheckpoint();
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('new-checkpoint'),
      'Close',
      { duration: 2000 }
    );
  }));

  it('should clear newLabel after createCheckpoint success', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.newLabel = 'temp-label';
    component.createCheckpoint();
    tick();
    expect(component.newLabel).toBe('');
  }));

  it('should reload checkpoints after createCheckpoint success', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    kvCacheServiceSpy.listCheckpoints.calls.reset();
    component.createCheckpoint();
    tick();
    expect(kvCacheServiceSpy.listCheckpoints).toHaveBeenCalled();
  }));

  it('should show error snack bar when createCheckpoint fails', fakeAsync(() => {
    kvCacheServiceSpy.createCheckpoint.and.returnValue(throwError(() => ({ message: 'failed' })));
    component.selectedCache = 'main-cache';
    component.createCheckpoint();
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Failed'),
      'Close',
      { duration: 3000 }
    );
  }));

  it('should call restoreCheckpoint with correct args', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.restore('cp-001');
    tick();
    expect(kvCacheServiceSpy.restoreCheckpoint).toHaveBeenCalledWith('main-cache', 'cp-001');
  }));

  it('should show success snack bar after restore', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.restore('cp-001');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Checkpoint restored', 'Close', { duration: 2000 });
  }));

  it('should show error snack bar when restore fails', fakeAsync(() => {
    kvCacheServiceSpy.restoreCheckpoint.and.returnValue(throwError(() => ({ message: 'restore failed' })));
    component.selectedCache = 'main-cache';
    component.restore('cp-001');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Restore failed'),
      'Close',
      { duration: 3000 }
    );
  }));

  it('should call saveCheckpointToDisk with correct args', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.saveToDisk('cp-001');
    tick();
    expect(kvCacheServiceSpy.saveCheckpointToDisk).toHaveBeenCalledWith('main-cache', 'cp-001');
  }));

  it('should show success snack bar after saveToDisk', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.saveToDisk('cp-001');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Saved to disk', 'Close', { duration: 2000 });
  }));

  it('should reload checkpoints after saveToDisk success', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    kvCacheServiceSpy.listCheckpoints.calls.reset();
    component.saveToDisk('cp-001');
    tick();
    expect(kvCacheServiceSpy.listCheckpoints).toHaveBeenCalled();
  }));

  it('should show error snack bar when saveToDisk fails', fakeAsync(() => {
    kvCacheServiceSpy.saveCheckpointToDisk.and.returnValue(throwError(() => ({ message: 'disk full' })));
    component.selectedCache = 'main-cache';
    component.saveToDisk('cp-001');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Save failed'),
      'Close',
      { duration: 3000 }
    );
  }));

  it('should call rollbackCheckpoint with correct args', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.rollback('cp-002');
    tick();
    expect(kvCacheServiceSpy.rollbackCheckpoint).toHaveBeenCalledWith('main-cache', 'cp-002');
  }));

  it('should show success snack bar after rollback', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.rollback('cp-002');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Rolled back', 'Close', { duration: 2000 });
  }));

  it('should reload checkpoints after rollback success', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    kvCacheServiceSpy.listCheckpoints.calls.reset();
    component.rollback('cp-002');
    tick();
    expect(kvCacheServiceSpy.listCheckpoints).toHaveBeenCalled();
  }));

  it('should show error snack bar when rollback fails', fakeAsync(() => {
    kvCacheServiceSpy.rollbackCheckpoint.and.returnValue(throwError(() => ({ message: 'rollback error' })));
    component.selectedCache = 'main-cache';
    component.rollback('cp-002');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Rollback failed'),
      'Close',
      { duration: 3000 }
    );
  }));

  it('should call deleteCheckpoint with correct args', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.deleteCheckpoint('cp-001');
    tick();
    expect(kvCacheServiceSpy.deleteCheckpoint).toHaveBeenCalledWith('main-cache', 'cp-001');
  }));

  it('should show success snack bar after deleteCheckpoint', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    component.deleteCheckpoint('cp-001');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith('Checkpoint deleted', 'Close', { duration: 2000 });
  }));

  it('should reload checkpoints after deleteCheckpoint success', fakeAsync(() => {
    component.selectedCache = 'main-cache';
    kvCacheServiceSpy.listCheckpoints.calls.reset();
    component.deleteCheckpoint('cp-001');
    tick();
    expect(kvCacheServiceSpy.listCheckpoints).toHaveBeenCalled();
  }));

  it('should show error snack bar when deleteCheckpoint fails', fakeAsync(() => {
    kvCacheServiceSpy.deleteCheckpoint.and.returnValue(throwError(() => ({ message: 'not found' })));
    component.selectedCache = 'main-cache';
    component.deleteCheckpoint('cp-001');
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Delete failed'),
      'Close',
      { duration: 3000 }
    );
  }));

  it('should not auto-select when caches list is empty', fakeAsync(() => {
    kvCacheServiceSpy.listCaches.and.returnValue(of([]));
    component.selectedCache = '';
    component.ngOnInit();
    tick();
    expect(component.selectedCache).toBe('');
  }));
});
