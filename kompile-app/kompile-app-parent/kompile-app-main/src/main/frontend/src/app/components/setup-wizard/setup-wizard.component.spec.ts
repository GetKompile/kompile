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
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { SetupWizardComponent, SetupStatus, StepStatus } from './setup-wizard.component';
import { BaseService } from '../../services/base.service';

describe('SetupWizardComponent', () => {
  let component: SetupWizardComponent;
  let fixture: ComponentFixture<SetupWizardComponent>;
  let httpMock: HttpTestingController;

  const mockBaseService = {
    backendUrl: '/api'
  };

  function buildStepStatus(overrides: Partial<StepStatus> = {}): StepStatus {
    return {
      stepNumber: 1,
      name: 'Test Step',
      description: 'Test description',
      status: 'NOT_STARTED',
      complete: false,
      message: null,
      detail: null,
      action: null,
      ...overrides
    };
  }

  function buildSetupStatus(overrides: Partial<SetupStatus> = {}): SetupStatus {
    return {
      stagingServer: buildStepStatus({ stepNumber: 1, name: 'Staging Server' }),
      modelSource: buildStepStatus({ stepNumber: 2, name: 'Model Source' }),
      embeddingModel: buildStepStatus({ stepNumber: 3, name: 'Embedding Model' }),
      indexing: buildStepStatus({ stepNumber: 4, name: 'Document Index' }),
      searchReady: buildStepStatus({ stepNumber: 5, name: 'Search Ready' }),
      setupComplete: false,
      wizardDismissed: false,
      currentStep: 1,
      totalSteps: 5,
      ...overrides
    };
  }

  /**
   * Initialize the component and flush the ngOnInit fetch.
   * Must be called after TestBed setup.
   */
  function initAndFlush(status?: SetupStatus): void {
    fixture.detectChanges(); // triggers ngOnInit
    const req = httpMock.expectOne('/api/setup/status');
    req.flush(status || buildSetupStatus());
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SetupWizardComponent, HttpClientTestingModule],
      providers: [
        { provide: BaseService, useValue: mockBaseService }
      ]
    })
    .overrideComponent(SetupWizardComponent, {
      // Remove HttpClientModule from the standalone component's imports
      // so the test's HttpClientTestingModule is used instead
      set: {
        imports: [
          await import('@angular/common').then(m => m.CommonModule)
        ]
      }
    })
    .compileComponents();

    fixture = TestBed.createComponent(SetupWizardComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // Stop the interval timer by destroying the component
    component.ngOnDestroy();
    // Flush any outstanding requests
    httpMock.match(() => true).forEach(r => {
      if (!r.cancelled) r.flush(buildSetupStatus());
    });
    httpMock.verify();
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // INITIALIZATION
  // ═══════════════════════════════════════════════════════════════════════════

  it('should create', () => {
    initAndFlush();
    expect(component).toBeTruthy();
  });

  it('should fetch status on init', () => {
    initAndFlush();
    expect(component.status).toBeTruthy();
    expect(component.status!.totalSteps).toBe(5);
  });

  it('should be visible initially', () => {
    initAndFlush();
    expect(component.visible).toBeTrue();
  });

  it('should have stagingServer in status', () => {
    initAndFlush();
    expect(component.status!.stagingServer).toBeTruthy();
    expect(component.status!.stagingServer.name).toBe('Staging Server');
    expect(component.status!.stagingServer.stepNumber).toBe(1);
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // PROGRESS CALCULATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  it('should return 0 progress when no steps complete', () => {
    initAndFlush();
    expect(component.getCompletedCount()).toBe(0);
    expect(component.getProgressPercent()).toBe(0);
  });

  it('should count 3 completed steps correctly', () => {
    initAndFlush(buildSetupStatus({
      stagingServer: buildStepStatus({ complete: true }),
      modelSource: buildStepStatus({ complete: true }),
      embeddingModel: buildStepStatus({ complete: true }),
    }));
    expect(component.getCompletedCount()).toBe(3);
    expect(component.getProgressPercent()).toBe(60);
  });

  it('should return 100% when all 5 steps complete', () => {
    initAndFlush(buildSetupStatus({
      stagingServer: buildStepStatus({ complete: true }),
      modelSource: buildStepStatus({ complete: true }),
      embeddingModel: buildStepStatus({ complete: true }),
      indexing: buildStepStatus({ complete: true }),
      searchReady: buildStepStatus({ complete: true }),
      setupComplete: true,
    }));
    expect(component.getCompletedCount()).toBe(5);
    expect(component.getProgressPercent()).toBe(100);
  });

  it('should count only 1 completed step', () => {
    initAndFlush(buildSetupStatus({
      stagingServer: buildStepStatus({ complete: true }),
    }));
    expect(component.getCompletedCount()).toBe(1);
    expect(component.getProgressPercent()).toBe(20);
  });

  it('should return 0 progress when status is null', () => {
    // Before init, status is null
    expect(component.getCompletedCount()).toBe(0);
    expect(component.getProgressPercent()).toBe(0);
    // Now init
    initAndFlush();
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // FORMAT STATUS
  // ═══════════════════════════════════════════════════════════════════════════

  it('should format status labels correctly', () => {
    initAndFlush();
    expect(component.formatStatus('NOT_STARTED')).toBe('Pending');
    expect(component.formatStatus('IN_PROGRESS')).toBe('Loading');
    expect(component.formatStatus('COMPLETE')).toBe('Complete');
    expect(component.formatStatus('WARNING')).toBe('Warning');
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // STAGING SERVER ACTIONS
  // ═══════════════════════════════════════════════════════════════════════════

  it('should call start staging server endpoint', () => {
    initAndFlush();

    component.startStagingServer();
    expect(component.startingStagingServer).toBeTrue();

    const startReq = httpMock.expectOne('/api/setup/staging-server/start');
    expect(startReq.request.method).toBe('POST');
    startReq.flush({ success: true, message: 'Started' });

    expect(component.startingStagingServer).toBeFalse();

    // Should trigger a status refresh
    const refreshReq = httpMock.expectOne('/api/setup/status');
    refreshReq.flush(buildSetupStatus({
      stagingServer: buildStepStatus({ complete: true, status: 'COMPLETE' })
    }));
  });

  it('should handle start staging server error gracefully', () => {
    initAndFlush();

    component.startStagingServer();
    expect(component.startingStagingServer).toBeTrue();

    const startReq = httpMock.expectOne('/api/setup/staging-server/start');
    startReq.error(new ProgressEvent('error'), { status: 500, statusText: 'Server Error' });

    expect(component.startingStagingServer).toBeFalse();
  });

  it('should call stop staging server endpoint', () => {
    initAndFlush();

    component.stopStagingServer();

    const stopReq = httpMock.expectOne('/api/setup/staging-server/stop');
    expect(stopReq.request.method).toBe('POST');
    stopReq.flush({ success: true, message: 'Stopped' });

    // Should trigger a status refresh
    const refreshReq = httpMock.expectOne('/api/setup/status');
    refreshReq.flush(buildSetupStatus());
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // FORCE RELOAD
  // ═══════════════════════════════════════════════════════════════════════════

  it('should call force reload endpoint', () => {
    initAndFlush();

    component.forceReloadModels();

    const reloadReq = httpMock.expectOne('/api/models/registry/refresh-and-reload');
    expect(reloadReq.request.method).toBe('POST');
    reloadReq.flush({});

    // Should trigger a status refresh
    const refreshReq = httpMock.expectOne('/api/setup/status');
    refreshReq.flush(buildSetupStatus());
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // DISMISSAL
  // ═══════════════════════════════════════════════════════════════════════════

  it('should dismiss wizard and call server', () => {
    initAndFlush();

    const dismissed = jasmine.createSpy('dismissed');
    component.dismissed.subscribe(dismissed);

    component.dismiss();

    expect(component.visible).toBeFalse();
    expect(dismissed).toHaveBeenCalled();

    const dismissReq = httpMock.expectOne('/api/setup/dismiss');
    expect(dismissReq.request.method).toBe('POST');
    dismissReq.flush({});
  });

  it('should hide wizard if server reports dismissed', () => {
    initAndFlush(buildSetupStatus({ wizardDismissed: true }));
    expect(component.visible).toBeFalse();
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // NAVIGATION
  // ═══════════════════════════════════════════════════════════════════════════

  it('should emit navigation event and dismiss', () => {
    initAndFlush();

    const navigated = jasmine.createSpy('navigated');
    component.navigateToTab.subscribe(navigated);

    component.navigateTo('developer');

    expect(navigated).toHaveBeenCalledWith('developer');
    expect(component.visible).toBeFalse();

    // dismiss call
    httpMock.expectOne('/api/setup/dismiss').flush({});
  });

  it('should navigate to sources tab', () => {
    initAndFlush();

    const navigated = jasmine.createSpy('navigated');
    component.navigateToTab.subscribe(navigated);

    component.navigateTo('sources');

    expect(navigated).toHaveBeenCalledWith('sources');

    httpMock.expectOne('/api/setup/dismiss').flush({});
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // ERROR HANDLING
  // ═══════════════════════════════════════════════════════════════════════════

  it('should handle fetch status error gracefully', () => {
    fixture.detectChanges();
    const req = httpMock.expectOne('/api/setup/status');
    req.error(new ProgressEvent('error'), { status: 500, statusText: 'Server Error' });

    // Should not throw, status remains null
    expect(component.status).toBeNull();
    expect(component.visible).toBeTrue();
  });
});
