import {
  HttpClient,
  HTTP_INTERCEPTORS,
  provideHttpClient,
  withInterceptorsFromDi
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  STAGING_REQUEST_HEADER,
  STAGING_TOKEN_HEADER,
  StagingSecurityInterceptor
} from './staging-security.interceptor';
import { StagingPairingService } from './staging-pairing.service';

describe('StagingSecurityInterceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let pairing: StagingPairingService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        StagingPairingService,
        {
          provide: HTTP_INTERCEPTORS,
          useClass: StagingSecurityInterceptor,
          multi: true
        },
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting()
      ]
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    pairing = TestBed.inject(StagingPairingService);
  });

  afterEach(() => {
    pairing.clear();
    http.verify();
  });

  it('adds the mutation guard without inventing a token transport', () => {
    client.post('/api/staging/models', {}).subscribe();

    const request = http.expectOne('/api/staging/models');
    expect(request.request.headers.get(STAGING_REQUEST_HEADER)).toBe('1');
    expect(request.request.headers.has(STAGING_TOKEN_HEADER)).toBeFalse();
    request.flush({});
  });

  it('sends a paired token only as a same-origin API header', () => {
    const token = 'header-only-pairing-secret';
    expect(pairing.pair(token)).toBeTrue();

    client.get('/api/staging/models?view=summary').subscribe();

    const request = http.expectOne('/api/staging/models?view=summary');
    expect(request.request.headers.get(STAGING_TOKEN_HEADER)).toBe(token);
    expect(request.request.urlWithParams).not.toContain(token);
    expect(document.cookie).not.toContain(token);
    request.flush({});
  });

  it('never sends pairing headers to another origin', () => {
    expect(pairing.pair('must-not-leak')).toBeTrue();

    client.post('https://example.invalid/api/collect', {}).subscribe();

    const request = http.expectOne('https://example.invalid/api/collect');
    expect(request.request.headers.has(STAGING_TOKEN_HEADER)).toBeFalse();
    expect(request.request.headers.has(STAGING_REQUEST_HEADER)).toBeFalse();
    request.flush({});
  });

  it('surfaces API 401 responses as pairing guidance', () => {
    let required = false;
    const subscription = pairing.pairingRequired$.subscribe(value => {
      required = value;
    });
    client.get('/api/staging/models').subscribe({ error: () => undefined });

    const request = http.expectOne('/api/staging/models');
    request.flush(
      { error: 'A valid staging pairing token is required' },
      {
        status: 401,
        statusText: 'Unauthorized',
        headers: { 'X-Kompile-Staging-Pairing-Required': 'true' }
      }
    );

    expect(required).toBeTrue();
    subscription.unsubscribe();
  });
});
