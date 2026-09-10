import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { NoteSyncService } from './note-sync.service';

describe('NoteSyncService source maintenance', () => {
  let service: NoteSyncService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    sessionStorage.setItem('kompile.channel.csrf', 'source-csrf');
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [NoteSyncService]
    });
    service = TestBed.inject(NoteSyncService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    sessionStorage.removeItem('kompile.channel.csrf');
  });

  it('exchanges the CLI login code on the current persona and retains only CSRF in session storage', () => {
    sessionStorage.removeItem('kompile.channel.csrf');
    service.exchangeIntegrationBrowserSession('one-time-code').subscribe(session => {
      expect(session.csrfToken).toBe('new-source-csrf');
      expect(sessionStorage.getItem('kompile.channel.csrf')).toBe('new-source-csrf');
    });

    const req = httpMock.expectOne(request =>
      request.url.endsWith('/channel-integrations/browser-sessions/exchange'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ code: 'one-time-code' });
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ csrfToken: 'new-source-csrf', expiresAt: '2026-07-19T12:00:00Z' });
  });

  it('starts a pull-only source update', () => {
    service.pullUpdates(42).subscribe(result => expect(result.mode).toBe('PULL'));

    const req = httpMock.expectOne(request => request.url.endsWith('/sync/connections/42/pull'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    expect(req.request.headers.get('X-Kompile-Channel-CSRF')).toBe('source-csrf');
    expect(req.request.withCredentials).toBeTrue();
    req.flush({
      sessionId: 'pull-42',
      connectionId: 42,
      factSheetId: 7,
      provider: 'NOTION',
      status: 'QUEUED',
      stage: 'QUEUED',
      mode: 'PULL',
      pushed: 0,
      pulled: 0,
      deleted: 0,
      conflicts: 0,
      skipped: 0,
      errors: 0,
      queuedAt: '2026-07-19T00:00:00Z',
      updatedAt: '2026-07-19T00:00:00Z'
    });
  });

  it('loads durable runs for the active fact sheet', () => {
    service.listRuns(undefined, 7).subscribe(runs => expect(runs).toEqual([]));

    const req = httpMock.expectOne(request =>
      request.url.endsWith('/sync/runs') && request.params.get('factSheetId') === '7');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('connectionId')).toBeFalse();
    req.flush([]);
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

  it('sends a replacement webhook secret but only receives its configured state', () => {
    service.updateConfig({ notionWebhookSecret: 'replacement' }).subscribe(config => {
      expect(config.notionWebhookSecretConfigured).toBeTrue();
      expect((config as unknown as Record<string, unknown>)['notionWebhookSecret']).toBeUndefined();
    });

    const req = httpMock.expectOne(request => request.url.endsWith('/sync/config'));
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ notionWebhookSecret: 'replacement' });
    req.flush({
      notionEnabled: true,
      notionWebhookSecretConfigured: true,
      notionCallbackBaseUrl: 'http://localhost:8082',
      obsidianEnabled: false,
      obsidianFileWatchEnabled: false,
      schedulerEnabled: false,
      schedulerCheckIntervalMs: 60000
    });
  });
});
