/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';

import { OAuthSettingsService } from './oauth-settings.service';
import { ServiceEndpointRouter } from './service-endpoint-routing';

describe('OAuthSettingsService', () => {
  it('uses the crawl persona URL for provider callback registration', () => {
    const router = jasmine.createSpyObj<ServiceEndpointRouter>('ServiceEndpointRouter', ['resolve']);
    router.resolve.and.returnValue('https://crawl.example/api/oauth/reddit/callback');
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        OAuthSettingsService,
        { provide: ServiceEndpointRouter, useValue: router }
      ]
    });

    const service = TestBed.inject(OAuthSettingsService);

    expect(service.getCallbackUrl('reddit'))
      .toBe('https://crawl.example/api/oauth/reddit/callback');
    expect(router.resolve).toHaveBeenCalledWith('/api/oauth/reddit/callback');
  });
});
