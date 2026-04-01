/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ModelContextService } from './model-context.service';
import { ModelRegistryService } from './model-registry.service';
import { ActiveModelContext } from '../models/api-models';

describe('ModelContextService', () => {
  let service: ModelContextService;
  let httpMock: HttpTestingController;
  let registryService: ModelRegistryService;

  const mockContext: ActiveModelContext = {
    embedding: {
      modelId: 'bge-base-en-v1.5',
      encoderType: 'dense',
      dimensions: 768,
      status: 'ready',
      initialized: true
    },
    reranker: {
      modelId: 'bge-reranker-v2-m3',
      available: true
    },
    staging: {
      connected: true,
      endpointUrl: 'http://localhost:8081',
      uiUrl: 'http://localhost:8081'
    },
    availableEmbeddingModels: ['bge-base-en-v1.5', 'arctic-embed-m-v2.0'],
    availableRerankerModels: ['bge-reranker-v2-m3']
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ModelContextService, ModelRegistryService]
    });

    service = TestBed.inject(ModelContextService);
    httpMock = TestBed.inject(HttpTestingController);
    registryService = TestBed.inject(ModelRegistryService);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('refresh() fetches from /api/models/active-context and updates context$', (done) => {
    service.refresh();

    const req = httpMock.expectOne(r => r.url.endsWith('/models/active-context'));
    expect(req.request.method).toBe('GET');
    req.flush(mockContext);

    service.context$.subscribe(ctx => {
      if (ctx) {
        expect(ctx.embedding?.modelId).toBe('bge-base-en-v1.5');
        expect(ctx.embedding?.dimensions).toBe(768);
        expect(ctx.reranker?.available).toBe(true);
        expect(ctx.staging?.connected).toBe(true);
        done();
      }
    });
  });

  it('getStagingModelCardUrl() returns correct URL when staging connected', () => {
    // Manually push context
    service.refresh();
    const req = httpMock.expectOne(r => r.url.endsWith('/models/active-context'));
    req.flush(mockContext);

    const url = service.getStagingModelCardUrl('bge-base-en-v1.5');
    expect(url).toBe('http://localhost:8081/#/model/bge-base-en-v1.5');
  });

  it('getStagingModelCardUrl() returns null when staging not connected', () => {
    const disconnectedCtx: ActiveModelContext = {
      ...mockContext,
      staging: { connected: false, endpointUrl: null, uiUrl: null }
    };
    service.refresh();
    const req = httpMock.expectOne(r => r.url.endsWith('/models/active-context'));
    req.flush(disconnectedCtx);

    const url = service.getStagingModelCardUrl('bge-base-en-v1.5');
    expect(url).toBeNull();
  });

  it('auto-refreshes on ModelRegistryService change events', () => {
    // Trigger a registry change
    registryService.notifyChange('model_loaded');

    // Should make a request due to auto-refresh
    const req = httpMock.expectOne(r => r.url.endsWith('/models/active-context'));
    expect(req.request.method).toBe('GET');
    req.flush(mockContext);
  });

  it('handles HTTP errors gracefully', (done) => {
    service.refresh();

    const req = httpMock.expectOne(r => r.url.endsWith('/models/active-context'));
    req.error(new ProgressEvent('error'));

    service.context$.subscribe(ctx => {
      // Should remain null on error
      expect(ctx).toBeNull();
      done();
    });

    service.loading$.subscribe(loading => {
      if (!loading) {
        // Loading should reset after error
        expect(loading).toBe(false);
      }
    });
  });
});
