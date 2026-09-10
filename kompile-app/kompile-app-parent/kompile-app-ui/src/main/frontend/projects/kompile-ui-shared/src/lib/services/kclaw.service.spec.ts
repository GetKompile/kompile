/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { KClawService } from './kclaw.service';
import { backendUrl } from './base.service';

describe('KClawService channel control plane', () => {
  let service: KClawService;
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.removeItem('kompile.channel.csrf');
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(KClawService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.removeItem('kompile.channel.csrf');
  });

  it('loads named connections with the server-issued CSRF token', () => {
    authorize();
    service.getChannels().subscribe(connections => expect(connections).toEqual([]));

    const request = http.expectOne(`${backendUrl}/channel-integrations/connections`);
    expect(request.request.method).toBe('GET');
    expect(request.request.headers.get('X-Kompile-Channel-Request')).toBe('1');
    expect(request.request.headers.get('X-Kompile-Channel-CSRF')).toBe('csrf-token');
    request.flush([]);
  });

  it('starts Telegram pairing through the authenticated lifecycle API', () => {
    authorize();
    service.startTelegramPairing('ops').subscribe(pairing =>
      expect(pairing.command).toBe('/pair once'));

    const request = http.expectOne(
      `${backendUrl}/channel-integrations/connections/ops/telegram/pairings`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-Kompile-Channel-Request')).toBe('1');
    expect(request.request.headers.get('X-Kompile-Channel-CSRF')).toBe('csrf-token');
    request.flush({
      pairingId: '6b4bc3a0-8d76-4a38-96dd-f4df63d3c3ff',
      code: 'once',
      command: '/pair once',
      expiresAt: '2026-01-01T00:10:00Z'
    });
  });

  function authorize(): void {
    service.exchangeChannelBrowserSession('one-time-code').subscribe();
    const exchange = http.expectOne(`${backendUrl}/channel-integrations/browser-sessions/exchange`);
    expect(exchange.request.headers.has('X-Kompile-Channel-CSRF')).toBeFalse();
    exchange.flush({ csrfToken: 'csrf-token', expiresAt: '2026-01-01T12:00:00Z' });
  }
});
