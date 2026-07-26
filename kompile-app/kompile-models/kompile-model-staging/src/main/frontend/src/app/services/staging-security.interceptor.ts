/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

import { DOCUMENT } from '@angular/common';
import {
  HttpErrorResponse,
  HttpEvent,
  HttpHandler,
  HttpInterceptor,
  HttpRequest
} from '@angular/common/http';
import { Inject, Injectable } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { StagingPairingService } from './staging-pairing.service';

export const STAGING_REQUEST_HEADER = 'X-Kompile-Staging-Request';
export const STAGING_TOKEN_HEADER = 'X-Kompile-Staging-Token';
export const STAGING_PAIRING_REQUIRED_HEADER =
  'X-Kompile-Staging-Pairing-Required';

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const STAGING_API_PATH = /^\/(?:api|v1|actuator)(?:\/|$)/;

@Injectable()
export class StagingSecurityInterceptor implements HttpInterceptor {
  constructor(
    private readonly pairing: StagingPairingService,
    @Inject(DOCUMENT) private readonly document: Document
  ) {}

  intercept(
    request: HttpRequest<unknown>,
    next: HttpHandler
  ): Observable<HttpEvent<unknown>> {
    if (!this.isSameOriginStagingApi(request.url)) {
      return next.handle(request);
    }

    const setHeaders: Record<string, string> = {};
    const token = this.pairing.token;
    if (token !== null) {
      setHeaders[STAGING_TOKEN_HEADER] = token;
    }
    if (MUTATING_METHODS.has(request.method.toUpperCase())) {
      setHeaders[STAGING_REQUEST_HEADER] = '1';
    }

    const securedRequest = Object.keys(setHeaders).length === 0
      ? request
      : request.clone({ setHeaders });

    return next.handle(securedRequest).pipe(
      tap({
        next: () => this.pairing.confirmPairing(),
        error: (error: unknown) => {
          if (error instanceof HttpErrorResponse
              && error.status === 401
              && this.isPairingRequired(error)) {
            this.pairing.requirePairing();
          }
        }
      })
    );
  }

  private isSameOriginStagingApi(url: string): boolean {
    try {
      const baseUrl = this.document.baseURI;
      const target = new URL(url, baseUrl);
      const currentOrigin = new URL(baseUrl).origin;
      return target.origin === currentOrigin
        && STAGING_API_PATH.test(target.pathname);
    } catch {
      return false;
    }
  }

  private isPairingRequired(error: HttpErrorResponse): boolean {
    const header = error.headers.get(STAGING_PAIRING_REQUIRED_HEADER);
    return header === null || header.toLowerCase() === 'true';
  }
}
