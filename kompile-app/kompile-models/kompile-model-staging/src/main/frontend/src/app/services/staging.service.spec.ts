import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { ImportDiagnosticEvent } from '../models/api-models';
import { StagingService } from './staging.service';

describe('StagingService import diagnostics', () => {
  let service: StagingService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        StagingService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(StagingService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('caps recent diagnostic history requests at the server journal bound', () => {
    const response: ImportDiagnosticEvent[] = [{
      attemptId: 'attempt-1',
      timestamp: '2026-07-19T00:00:00Z',
      phase: 'download',
      code: 'download.failed',
      severity: 'error',
      summary: 'Connection failed',
      remediation: 'Retry',
      modelId: 'chat',
      source: 'https-components:component-bundle',
      details: {}
    }];

    service.getImportDiagnostics(999).subscribe(events => {
      expect(events).toEqual(response);
    });

    const request = http.expectOne(candidate =>
      candidate.url.endsWith('/api/staging/import-diagnostics')
        && candidate.params.get('limit') === '200');
    expect(request.request.method).toBe('GET');
    request.flush(response);
  });

  it('encodes attempt identifiers when browsing one retained timeline', () => {
    service.getImportAttemptDiagnostics('attempt/with space').subscribe(events => {
      expect(events).toEqual([]);
    });

    const request = http.expectOne(candidate =>
      candidate.url.endsWith(
        '/api/staging/import-diagnostics/attempt%2Fwith%20space'
      ));
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('loads and updates the UI-managed staging settings', () => {
    const settings = {
      callback_url: 'http://localhost:8080',
      auto_reload_enabled: true,
      callback_timeout_ms: 30000,
      optimizer_fp16_enabled: true,
      optimizer_enabled: true,
      optimizer_max_iterations: 3,
      optimizer_log_applied: false,
      default_optimization_profile: 'default',
      default_performance_profile: 'BALANCED'
    };

    service.getSettings().subscribe(result => expect(result).toEqual(settings));
    const getRequest = http.expectOne(candidate => candidate.url.endsWith('/api/staging/settings'));
    expect(getRequest.request.method).toBe('GET');
    getRequest.flush(settings);

    service.updateSettings(settings).subscribe(result => expect(result).toEqual(settings));
    const putRequest = http.expectOne(candidate => candidate.url.endsWith('/api/staging/settings'));
    expect(putRequest.request.method).toBe('PUT');
    expect(putRequest.request.body).toEqual(settings);
    putRequest.flush(settings);
  });

  it('tests the managed Admin callback', () => {
    service.testCallback().subscribe(result => expect(result.success).toBeTrue());

    const request = http.expectOne(candidate =>
      candidate.url.endsWith('/api/staging/settings/test-callback'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({});
    request.flush({ success: true, message: 'Connection successful' });
  });
});
