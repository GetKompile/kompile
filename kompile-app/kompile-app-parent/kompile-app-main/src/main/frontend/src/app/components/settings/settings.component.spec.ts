/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA } from '@angular/core';

import { SettingsComponent } from './settings.component';

describe('SettingsComponent', () => {
  let component: SettingsComponent;
  let fixture: ComponentFixture<SettingsComponent>;
  let httpMock: HttpTestingController;

  // Helper: initialize component and flush the ngOnInit GET request
  function initComponent() {
    fixture.detectChanges();
    const req = httpMock.expectOne('/api/config/k-app');
    req.flush({});
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [SettingsComponent],
      imports: [HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(SettingsComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create', () => {
    initComponent();
    expect(component).toBeTruthy();
  });

  describe('ngOnInit', () => {
    it('should call loadConfig on init', () => {
      fixture.detectChanges();
      const req = httpMock.expectOne('/api/config/k-app');
      expect(req.request.method).toBe('GET');
      req.flush({});
    });
  });

  describe('loadConfig', () => {
    it('should set isLoading to true during request and false on success', fakeAsync(() => {
      initComponent();
      tick();

      component.loadConfig();
      expect(component.isLoading).toBeTrue();

      const req = httpMock.expectOne('/api/config/k-app');
      req.flush({ vectorStoreType: 'ANSERINI', vectorStorePath: '/path' });
      tick();

      expect(component.isLoading).toBeFalse();
    }));

    it('should merge returned data into config', fakeAsync(() => {
      initComponent();
      tick();

      component.loadConfig();
      const req = httpMock.expectOne('/api/config/k-app');
      req.flush({ vectorStoreType: 'PGVECTOR', vectorStorePath: '/data/pg' });
      tick();

      expect(component.config.vectorStoreType).toBe('PGVECTOR');
      expect(component.config.vectorStorePath).toBe('/data/pg');
    }));

    it('should keep pgvectorPassword empty when pgvectorPasswordSet is true and password is empty', fakeAsync(() => {
      initComponent();
      tick();

      component.loadConfig();
      const req = httpMock.expectOne('/api/config/k-app');
      req.flush({ pgvectorPasswordSet: true, pgvectorPassword: '' });
      tick();

      expect(component.config.pgvectorPassword).toBe('');
    }));

    it('should set isLoading to false on error', fakeAsync(() => {
      initComponent();
      tick();

      spyOn(console, 'error');
      component.loadConfig();
      const req = httpMock.expectOne('/api/config/k-app');
      req.error(new ProgressEvent('error'));
      tick();

      expect(component.isLoading).toBeFalse();
    }));
  });

  describe('saveSettings', () => {
    beforeEach(fakeAsync(() => {
      initComponent();
      tick();
    }));

    it('should set isLoading during save and clear on success', fakeAsync(() => {
      component.saveSettings();
      expect(component.isLoading).toBeTrue();

      const req = httpMock.expectOne('/api/config/k-app');
      expect(req.request.method).toBe('PUT');
      req.flush({
        vectorStoreType: 'ANSERINI',
        vectorStorePath: '/path',
        keywordIndexPath: '/kw',
        switched: false,
        restartRequired: false,
        message: 'Saved'
      });
      tick();

      expect(component.isLoading).toBeFalse();
      expect(component.saveMessage).toBe('Saved');
    }));

    it('should set restartRequired from response', fakeAsync(() => {
      component.saveSettings();
      const req = httpMock.expectOne('/api/config/k-app');
      req.flush({
        vectorStoreType: 'VESPA',
        vectorStorePath: '',
        keywordIndexPath: '',
        switched: true,
        restartRequired: true,
        message: 'Restart needed'
      });
      tick();

      expect(component.restartRequired).toBeTrue();
      expect(component.saveMessage).toBe('Restart needed');
    }));

    it('should not send pgvectorPassword when it is empty', fakeAsync(() => {
      component.config.pgvectorPassword = '';
      component.saveSettings();
      const req = httpMock.expectOne('/api/config/k-app');
      expect(req.request.body.pgvectorPassword).toBeUndefined();
      req.flush({
        vectorStoreType: 'ANSERINI',
        vectorStorePath: '',
        keywordIndexPath: '',
        switched: false,
        restartRequired: false,
        message: 'OK'
      });
      tick();
    }));

    it('should send pgvectorPassword when set', fakeAsync(() => {
      component.config.pgvectorPassword = 'secret';
      component.saveSettings();
      const req = httpMock.expectOne('/api/config/k-app');
      expect(req.request.body.pgvectorPassword).toBe('secret');
      req.flush({
        vectorStoreType: 'PGVECTOR',
        vectorStorePath: '',
        keywordIndexPath: '',
        switched: false,
        restartRequired: false,
        message: 'OK'
      });
      tick();
    }));

    it('should clear saveMessage after 5 seconds', fakeAsync(() => {
      component.saveSettings();
      const req = httpMock.expectOne('/api/config/k-app');
      req.flush({
        vectorStoreType: 'ANSERINI',
        vectorStorePath: '',
        keywordIndexPath: '',
        switched: false,
        restartRequired: false,
        message: 'Saved'
      });
      tick();
      expect(component.saveMessage).toBe('Saved');
      tick(5000);
      expect(component.saveMessage).toBeNull();
    }));

    it('should set error saveMessage on failure', fakeAsync(() => {
      spyOn(console, 'error');
      component.saveSettings();
      const req = httpMock.expectOne('/api/config/k-app');
      req.error(new ProgressEvent('error'));
      tick();

      expect(component.saveMessage).toContain('Failed to save settings');
      expect(component.restartRequired).toBeFalse();
      expect(component.isLoading).toBeFalse();
    }));
  });

  describe('getSelectedStoreOption', () => {
    beforeEach(fakeAsync(() => {
      initComponent();
      tick();
    }));

    it('should return the matching vector store option', () => {
      component.config.vectorStoreType = 'ANSERINI';
      const option = component.getSelectedStoreOption();
      expect(option).toBeDefined();
      expect(option!.value).toBe('ANSERINI');
      expect(option!.label).toBe('Anserini (Embedded)');
    });

    it('should return VESPA option when vectorStoreType is VESPA', () => {
      component.config.vectorStoreType = 'VESPA';
      const option = component.getSelectedStoreOption();
      expect(option).toBeDefined();
      expect(option!.value).toBe('VESPA');
    });

    it('should return PGVECTOR option', () => {
      component.config.vectorStoreType = 'PGVECTOR';
      const option = component.getSelectedStoreOption();
      expect(option!.value).toBe('PGVECTOR');
    });

    it('should return CHROMA option', () => {
      component.config.vectorStoreType = 'CHROMA';
      const option = component.getSelectedStoreOption();
      expect(option!.value).toBe('CHROMA');
    });

    it('should return undefined for unknown type', () => {
      (component.config as any).vectorStoreType = 'UNKNOWN';
      const option = component.getSelectedStoreOption();
      expect(option).toBeUndefined();
    });
  });

  describe('vectorStoreOptions', () => {
    beforeEach(fakeAsync(() => {
      initComponent();
      tick();
    }));

    it('should contain 4 options', () => {
      expect(component.vectorStoreOptions.length).toBe(4);
    });

    it('ANSERINI should not require server', () => {
      const anserini = component.vectorStoreOptions.find(o => o.value === 'ANSERINI');
      expect(anserini!.requiresServer).toBeFalse();
    });

    it('VESPA, PGVECTOR, CHROMA should require server', () => {
      const serverRequired = component.vectorStoreOptions.filter(o => o.requiresServer);
      expect(serverRequired.length).toBe(3);
    });
  });
});
