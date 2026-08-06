import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { StagingService, StagingSettings } from '../../services/staging.service';
import { ServiceConnectionsComponent } from './service-connections.component';

const settings: StagingSettings = {
  callback_url: 'http://localhost:8080',
  auto_reload_enabled: true,
  callback_timeout_ms: 30000,
  optimizer_fp16_enabled: false,
  optimizer_enabled: true,
  optimizer_max_iterations: 100,
  optimizer_log_applied: true,
  default_optimization_profile: 'balanced',
  default_performance_profile: 'balanced'
};

describe('ServiceConnectionsComponent', () => {
  let fixture: ComponentFixture<ServiceConnectionsComponent>;
  let component: ServiceConnectionsComponent;
  let staging: jasmine.SpyObj<StagingService>;
  let http: HttpTestingController;

  beforeEach(async () => {
    staging = jasmine.createSpyObj<StagingService>(
      'StagingService',
      ['getSettings', 'updateSettings', 'testCallback']
    );
    staging.getSettings.and.returnValue(of(settings));
    staging.updateSettings.and.callFake(update => of(update));
    staging.testCallback.and.returnValue(of({ success: true, message: 'Connection successful' }));

    await TestBed.configureTestingModule({
      imports: [ServiceConnectionsComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [{ provide: StagingService, useValue: staging }]
    }).compileComponents();

    fixture = TestBed.createComponent(ServiceConnectionsComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/service-endpoints').flush({ servingUrl: 'http://127.0.0.1:8091' });
  });

  afterEach(() => http.verify());

  it('loads all independently deployable service dependencies', () => {
    expect(component.servingUrl).toBe('http://127.0.0.1:8091');
    expect(component.callbackUrl).toBe('http://localhost:8080');
    expect(component.autoReloadEnabled).toBeTrue();
    expect(component.callbackTimeoutMs).toBe(30000);
  });

  it('saves serving and callback configuration through managed APIs', () => {
    component.servingUrl = 'http://localhost:19091/';
    component.callbackUrl = 'https://admin.example:8443/';
    component.callbackTimeoutMs = 12500;

    component.save();

    const endpointRequest = http.expectOne('/api/service-endpoints');
    expect(endpointRequest.request.method).toBe('POST');
    expect(endpointRequest.request.body).toEqual({ servingUrl: 'http://localhost:19091' });
    endpointRequest.flush({ servingUrl: 'http://localhost:19091' });
    expect(staging.updateSettings).toHaveBeenCalledWith(jasmine.objectContaining({
      callback_url: 'https://admin.example:8443',
      callback_timeout_ms: 12500,
      optimizer_enabled: true,
      default_optimization_profile: 'balanced'
    }));
  });

  it('persists both configurations before testing the callback', () => {
    component.testConnection();

    http.expectOne('/api/service-endpoints').flush({ servingUrl: 'http://127.0.0.1:8091' });
    expect(staging.updateSettings).toHaveBeenCalled();
    expect(staging.testCallback).toHaveBeenCalled();
    expect(component.testMessage).toBe('Connection successful');
  });

  it('rejects a non-loopback serving child before saving', () => {
    component.servingUrl = 'http://serving.example:8091';

    component.save();

    http.expectNone('/api/service-endpoints');
    expect(staging.updateSettings).not.toHaveBeenCalled();
    expect(component.error).toContain('loopback');
  });
});
