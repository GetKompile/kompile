/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { ChatSettingsComponent } from './chat-settings.component';

describe('ChatSettingsComponent', () => {
  let fixture: ComponentFixture<ChatSettingsComponent>;
  let component: ChatSettingsComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatSettingsComponent, HttpClientTestingModule, NoopAnimationsModule]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatSettingsComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('loads the managed Model Staging dependency', () => {
    const request = http.expectOne('/api/service-endpoints');
    expect(request.request.method).toBe('GET');
    request.flush({ stagingUrl: 'http://staging.example:18090' });

    expect(component.stagingUrl).toBe('http://staging.example:18090');
  });

  it('saves only the Chat dependency endpoint', () => {
    http.expectOne('/api/service-endpoints').flush({ stagingUrl: 'http://localhost:8090' });
    component.stagingUrl = 'http://staging.example:18090/';

    component.save();

    const request = http.expectOne('/api/service-endpoints');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ stagingUrl: 'http://staging.example:18090' });
    request.flush({ stagingUrl: 'http://staging.example:18090' });
  });

  it('rejects an invalid dependency URL without making a request', () => {
    http.expectOne('/api/service-endpoints').flush({ stagingUrl: 'http://localhost:8090' });
    component.stagingUrl = 'not-a-url';

    component.save();

    expect(component.error).toContain('HTTP(S)');
    http.expectNone(request => request.method === 'POST');
  });
});
