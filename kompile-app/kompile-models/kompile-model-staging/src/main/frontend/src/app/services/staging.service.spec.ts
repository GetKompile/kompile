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
});
