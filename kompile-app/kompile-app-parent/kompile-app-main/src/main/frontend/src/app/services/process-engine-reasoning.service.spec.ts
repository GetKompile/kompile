import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { backendUrl } from './base.service';
import { ProcessEngineService } from './process-engine.service';

describe('ProcessEngineService reasoning endpoints', () => {
  let service: ProcessEngineService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(ProcessEngineService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('mines one fact-sheet graph', () => {
    const suggestion = { id: 'sug-1', name: 'Mined flow', confidence: 0.88 };
    service.mineProcesses(42).subscribe(result => expect(result).toEqual(suggestion));

    const request = http.expectOne(req =>
      req.url === `${backendUrl}/process/mining/discover`
      && req.params.get('factSheetId') === '42');
    expect(request.request.method).toBe('GET');
    request.flush(suggestion);
  });

  it('loads a persisted suggestion trace', () => {
    const trace = {
      size: 1,
      depth: 1,
      conclusion: { kind: 'CONCLUSION', conclusion: 'candidate', confidence: 0.9 }
    };
    service.getStoredSuggestionTrace('sug-1').subscribe(result => expect(result).toEqual(trace));

    const request = http.expectOne(
      `${backendUrl}/process/discovery/suggestions/sug-1/trace`);
    expect(request.request.method).toBe('GET');
    request.flush(trace);
  });
});
