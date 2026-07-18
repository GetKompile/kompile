import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { NoteSyncService } from './note-sync.service';

describe('NoteSyncService source maintenance', () => {
  let service: NoteSyncService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [NoteSyncService]
    });
    service = TestBed.inject(NoteSyncService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('starts a pull-only source update', () => {
    service.pullUpdates(42).subscribe(result => expect(result.mode).toBe('PULL'));

    const req = httpMock.expectOne(request => request.url.endsWith('/sync/connections/42/pull'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush({ sessionId: 'pull-42', status: 'STARTED', mode: 'PULL' });
  });

  it('enables auto sync with the selected cron schedule', () => {
    service.updateAutoSync(42, true, '0 */15 * * * *').subscribe();

    const req = httpMock.expectOne(request => request.url.endsWith('/sync/connections/42/auto-sync'));
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ enabled: true, pollCron: '0 */15 * * * *' });
    req.flush({});
  });

  it('clears the schedule when auto sync is disabled', () => {
    service.updateAutoSync(42, false, '0 */15 * * * *').subscribe();

    const req = httpMock.expectOne(request => request.url.endsWith('/sync/connections/42/auto-sync'));
    expect(req.request.body).toEqual({ enabled: false, pollCron: null });
    req.flush({});
  });
});
