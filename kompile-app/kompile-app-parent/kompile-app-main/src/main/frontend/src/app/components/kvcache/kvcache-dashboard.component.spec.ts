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

import { KVCacheDashboardComponent } from './kvcache-dashboard.component';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheStatus } from '../../models/kvcache-models';

describe('KVCacheDashboardComponent', () => {
  let component: KVCacheDashboardComponent;
  let fixture: ComponentFixture<KVCacheDashboardComponent>;
  let kvCacheServiceSpy: jasmine.SpyObj<KVCacheService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  const mockStatus: KVCacheStatus = {
    enabled: true,
    cacheCount: 3,
    checkpointsEnabled: true,
    prefixCacheEnabled: true
  };

  beforeEach(async () => {
    kvCacheServiceSpy = jasmine.createSpyObj('KVCacheService', [
      'getStatus', 'enable', 'disable'
    ]);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    kvCacheServiceSpy.getStatus.and.returnValue(of(mockStatus));
    kvCacheServiceSpy.enable.and.returnValue(of({}));
    kvCacheServiceSpy.disable.and.returnValue(of({}));

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(KVCacheDashboardComponent, {
      set: {
        imports: [CommonModule, FormsModule],
        template: '<div></div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(KVCacheService, { useValue: kvCacheServiceSpy })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(KVCacheDashboardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call getStatus on init', () => {
    expect(kvCacheServiceSpy.getStatus).toHaveBeenCalled();
  });

  it('should set status from service on init', () => {
    expect(component.status).toEqual(mockStatus);
  });

  it('should set status to disabled defaults on getStatus error', fakeAsync(() => {
    kvCacheServiceSpy.getStatus.and.returnValue(throwError(() => new Error('network error')));
    component.refreshStatus();
    tick();
    expect(component.status).toEqual({
      enabled: false,
      cacheCount: 0,
      checkpointsEnabled: false,
      prefixCacheEnabled: false
    });
  }));

  it('should call enable when toggleEnabled is called with true', fakeAsync(() => {
    kvCacheServiceSpy.getStatus.and.returnValue(of(mockStatus));
    component.toggleEnabled(true);
    tick();
    expect(kvCacheServiceSpy.enable).toHaveBeenCalled();
  }));

  it('should show snack bar after enabling', fakeAsync(() => {
    kvCacheServiceSpy.getStatus.and.returnValue(of(mockStatus));
    component.toggleEnabled(true);
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith('KV Cache enabled', 'Close', { duration: 2000 });
  }));

  it('should call disable when toggleEnabled is called with false', fakeAsync(() => {
    kvCacheServiceSpy.getStatus.and.returnValue(of(mockStatus));
    component.toggleEnabled(false);
    tick();
    expect(kvCacheServiceSpy.disable).toHaveBeenCalled();
  }));

  it('should show snack bar after disabling', fakeAsync(() => {
    kvCacheServiceSpy.getStatus.and.returnValue(of(mockStatus));
    component.toggleEnabled(false);
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith('KV Cache disabled', 'Close', { duration: 2000 });
  }));

  it('should show error snack bar when enable fails', fakeAsync(() => {
    kvCacheServiceSpy.enable.and.returnValue(throwError(() => ({ message: 'fail' })));
    component.toggleEnabled(true);
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(jasmine.stringContaining('Failed'), 'Close', { duration: 3000 });
  }));

  it('should show error snack bar when disable fails', fakeAsync(() => {
    kvCacheServiceSpy.disable.and.returnValue(throwError(() => ({ message: 'fail' })));
    component.toggleEnabled(false);
    tick();
    expect(snackBarSpy.open).toHaveBeenCalledWith(jasmine.stringContaining('Failed'), 'Close', { duration: 3000 });
  }));

  it('should refresh status after successful toggle', fakeAsync(() => {
    kvCacheServiceSpy.getStatus.and.returnValue(of(mockStatus));
    const callsBefore = kvCacheServiceSpy.getStatus.calls.count();
    component.toggleEnabled(true);
    tick();
    expect(kvCacheServiceSpy.getStatus.calls.count()).toBeGreaterThan(callsBefore);
  }));

  it('should unsubscribe previous subscription on refreshStatus', fakeAsync(() => {
    kvCacheServiceSpy.getStatus.and.returnValue(of(mockStatus));
    component.refreshStatus();
    tick();
    component.refreshStatus();
    tick();
    // Should not throw and status should still be set
    expect(component.status).toEqual(mockStatus);
  }));

  it('should unsubscribe on destroy', () => {
    expect(() => component.ngOnDestroy()).not.toThrow();
  });
});
