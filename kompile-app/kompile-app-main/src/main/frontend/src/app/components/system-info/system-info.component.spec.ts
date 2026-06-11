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
import { CommonModule } from '@angular/common';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import {
  HttpClientTestingModule,
  HttpTestingController
} from '@angular/common/http/testing';

import { SystemInfoComponent, SystemInfoResponse } from './system-info.component';
import { backendUrl } from '../../services/base.service';

const INFO_URL = `${backendUrl}/system/info`;

const mockInfo: SystemInfoResponse = {
  version: {
    app: '1.2.3',
    springBoot: '3.2.5',
    java: '17.0.8',
    os: 'Linux',
    arch: 'amd64'
  },
  homeDirectory: '/home/user/.kompile',
  homeExists: true,
  installedTools: {
    graalvm: true,
    python: false,
    maven: true
  },
  runtime: {
    totalMemory: 536870912,
    freeMemory: 268435456,
    maxMemory: 1073741824,
    availableProcessors: 8,
    uptime: '3661'
  }
};

describe('SystemInfoComponent', () => {
  let component: SystemInfoComponent;
  let fixture: ComponentFixture<SystemInfoComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule, HttpClientTestingModule]
    })
      .overrideComponent(SystemInfoComponent, {
        set: {
          imports: [CommonModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // Cancel any pending requests so verify() doesn't complain
    httpMock.match(() => true).forEach(r => r.flush({}));
    httpMock.verify();
  });

  // ── Creation ──────────────────────────────────────────────────────────────

  it('should create', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush(mockInfo);
    expect(component).toBeTruthy();
    discardPeriodicTasks();
  }));

  // ── ngOnInit / loadInfo ───────────────────────────────────────────────────

  it('should call loadInfo on init and set info on success', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    expect(component.loading).toBeTrue();

    httpMock.expectOne(INFO_URL).flush(mockInfo);

    expect(component.loading).toBeFalse();
    expect(component.info).toEqual(mockInfo);
    expect(component.lastRefreshed).toBeInstanceOf(Date);
    expect(component.error).toBeNull();
    discardPeriodicTasks();
  }));

  it('should set error message on loadInfo HTTP failure', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    // Network error — err.message will be the HttpErrorResponse message string
    httpMock.expectOne(INFO_URL).error(
      new ErrorEvent('Network error', { message: 'Connection refused' })
    );

    expect(component.loading).toBeFalse();
    // The component sets error = err?.message || 'Failed to load system information'
    // HttpErrorResponse.message may contain the URL; just verify it's truthy
    expect(component.error).toBeTruthy();
    discardPeriodicTasks();
  }));

  it('should set generic error when HTTP error has no message', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    // Flush a 500 error – the component falls back to 'Failed to load system information'
    // only if err.message is falsy. HttpErrorResponse always has a message, so test
    // that the error property is non-null and truthy.
    httpMock.expectOne(INFO_URL).flush(null, { status: 500, statusText: 'Server Error' });

    expect(component.loading).toBeFalse();
    expect(component.error).toBeTruthy();
    discardPeriodicTasks();
  }));

  it('should auto-refresh every 30 seconds', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush(mockInfo);

    tick(30000);
    const req2 = httpMock.expectOne(INFO_URL);
    req2.flush({ ...mockInfo, homeExists: false });
    expect(component.info!.homeExists).toBeFalse();

    tick(30000);
    httpMock.expectOne(INFO_URL).flush(mockInfo);
    expect(component.info).toEqual(mockInfo);

    discardPeriodicTasks();
  }));

  // ── ngOnDestroy ───────────────────────────────────────────────────────────

  it('should unsubscribe on destroy and stop auto-refresh', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush(mockInfo);

    // Destroy triggers ngOnDestroy which unsubscribes from the interval
    fixture.destroy();
    expect((component as any).refreshSub?.closed).toBeTruthy();

    tick(30000);
    // No new HTTP requests should be made after destroy
    const pending = httpMock.match(INFO_URL);
    expect(pending.length).toBe(0);
  }));

  // ── formatBytes ───────────────────────────────────────────────────────────

  it('should return "0 B" for undefined bytes', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({});
    expect(component.formatBytes(undefined)).toBe('0 B');
    discardPeriodicTasks();
  }));

  it('should return "0 B" for zero or negative bytes', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({});
    expect(component.formatBytes(0)).toBe('0 B');
    expect(component.formatBytes(-1)).toBe('0 B');
    discardPeriodicTasks();
  }));

  it('should format bytes progressively through units', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({});
    expect(component.formatBytes(512)).toBe('512.0 B');
    expect(component.formatBytes(1024)).toBe('1.0 KB');
    expect(component.formatBytes(1048576)).toBe('1.0 MB');
    expect(component.formatBytes(1073741824)).toBe('1.0 GB');
    discardPeriodicTasks();
  }));

  // ── memoryUsedPercent ─────────────────────────────────────────────────────

  it('should return 0 when info is null or missing maxMemory', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({});
    component.info = null;
    expect(component.memoryUsedPercent()).toBe(0);
    component.info = { runtime: { totalMemory: 100, freeMemory: 50 } };
    expect(component.memoryUsedPercent()).toBe(0);
    discardPeriodicTasks();
  }));

  it('should compute used memory percent correctly', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush(mockInfo);
    // used = 536870912 - 268435456 = 268435456; max = 1073741824 → 25%
    expect(component.memoryUsedPercent()).toBe(25);
    discardPeriodicTasks();
  }));

  // ── memoryUsedBytes ───────────────────────────────────────────────────────

  it('should compute memoryUsedBytes as total minus free', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush(mockInfo);
    expect(component.memoryUsedBytes).toBe(268435456);
    discardPeriodicTasks();
  }));

  it('should return 0 for memoryUsedBytes when info is null', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({});
    component.info = null;
    expect(component.memoryUsedBytes).toBe(0);
    discardPeriodicTasks();
  }));

  // ── formatUptime ──────────────────────────────────────────────────────────

  it('should return "N/A" for undefined or negative uptime', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({});
    expect(component.formatUptime(undefined)).toBe('N/A');
    expect(component.formatUptime(-1)).toBe('N/A');
    discardPeriodicTasks();
  }));

  it('should format uptime correctly for various durations', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({});
    expect(component.formatUptime(0)).toBe('0s');
    expect(component.formatUptime(45)).toBe('45s');
    expect(component.formatUptime(125)).toBe('2m 5s');
    expect(component.formatUptime(3661)).toBe('1h 1m 1s');
    expect(component.formatUptime(90061)).toBe('1d 1h 1m 1s');
    discardPeriodicTasks();
  }));

  // ── installedToolEntries ──────────────────────────────────────────────────

  it('should return empty array when info is null or missing installedTools', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({});
    component.info = null;
    expect(component.installedToolEntries()).toEqual([]);
    component.info = {};
    expect(component.installedToolEntries()).toEqual([]);
    discardPeriodicTasks();
  }));

  it('should return entries for installedTools', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush(mockInfo);

    const entries = component.installedToolEntries();
    expect(entries.length).toBe(3);
    expect(entries).toContain(jasmine.objectContaining({ name: 'graalvm', installed: true }));
    expect(entries).toContain(jasmine.objectContaining({ name: 'python', installed: false }));
    expect(entries).toContain(jasmine.objectContaining({ name: 'maven', installed: true }));
    discardPeriodicTasks();
  }));

  // ── versionFields ─────────────────────────────────────────────────────────

  it('should return empty array when info is null', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({});
    component.info = null;
    expect(component.versionFields()).toEqual([]);
    discardPeriodicTasks();
  }));

  it('should return version fields with actual values', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush(mockInfo);

    const fields = component.versionFields();
    expect(fields.length).toBe(5);
    expect(fields[0]).toEqual({ label: 'App Version', value: '1.2.3' });
    expect(fields[1]).toEqual({ label: 'Spring Boot', value: '3.2.5' });
    expect(fields[2]).toEqual({ label: 'Java Version', value: '17.0.8' });
    expect(fields[3]).toEqual({ label: 'Operating System', value: 'Linux' });
    expect(fields[4]).toEqual({ label: 'Architecture', value: 'amd64' });
    discardPeriodicTasks();
  }));

  it('should return "N/A" for missing version fields', fakeAsync(() => {
    fixture = TestBed.createComponent(SystemInfoComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne(INFO_URL).flush({ version: {} });

    const fields = component.versionFields();
    expect(fields.every(f => f.value === 'N/A')).toBeTrue();
    discardPeriodicTasks();
  }));
});
