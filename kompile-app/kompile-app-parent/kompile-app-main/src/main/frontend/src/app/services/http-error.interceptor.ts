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

import { Injectable } from '@angular/core';
import {
  HttpRequest,
  HttpHandler,
  HttpEvent,
  HttpInterceptor,
  HttpErrorResponse,
  HttpContextToken
} from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { MatSnackBar } from '@angular/material/snack-bar';

/**
 * Set this token to `true` on a request to suppress the global error snackbar.
 * Use for components that provide their own bespoke error UX.
 *
 * Example:
 *   this.http.get('/api/...', { context: new HttpContext().set(SKIP_ERROR_SNACKBAR, true) })
 */
export const SKIP_ERROR_SNACKBAR = new HttpContextToken<boolean>(() => false);

/**
 * Endpoints whose failure is routine and should never produce a snackbar.
 *
 * Rationale for each exclusion:
 *  - /api/fact-sheets/active/model-status: polled by model-status-indicator while no fact-sheet
 *    is selected; 404 is the expected "nothing active" response.
 *  - /api/staging-config/remote/registry: polled when no staging server is configured; 404/503
 *    is expected when staging is offline.
 *  - /api/staging-config/remote/active: same as above.
 *  - /api/unified-crawl/jobs/active: polled periodically; 404 means no active job (normal).
 *  - /api/models/registry/status: polled by the WS fallback; 503 occurs on startup before
 *    the model subprocess starts.
 *  - /actuator/health: startup / liveness probes; always polled, failure expected during boot.
 *  - /api/kv-cache/: background stats polling; failure is inconsequential.
 */
const SILENT_ENDPOINT_PATTERNS: RegExp[] = [
  /\/api\/fact-sheets\/active\/model-status/,
  /\/api\/staging-config\/remote\/(registry|active)/,
  /\/api\/unified-crawl\/jobs\/active/,
  /\/api\/models\/registry\/status/,
  /\/actuator\/health/,
  /\/api\/kv-cache\//
];

/** Deduplicate: key = "${method}|${url}|${status}" → last shown epoch ms */
const _recentErrors = new Map<string, number>();
const DEDUPE_WINDOW_MS = 10_000;

function isDeduplicated(key: string): boolean {
  const now = Date.now();
  const last = _recentErrors.get(key);
  if (last !== undefined && now - last < DEDUPE_WINDOW_MS) {
    return true;
  }
  _recentErrors.set(key, now);
  // Evict stale entries lazily to prevent unbounded growth
  if (_recentErrors.size > 200) {
    for (const [k, t] of _recentErrors) {
      if (now - t > DEDUPE_WINDOW_MS) {
        _recentErrors.delete(k);
      }
    }
  }
  return false;
}

/** Shorten a URL to the last two path segments for display. */
function shortenUrl(url: string): string {
  try {
    const pathname = new URL(url).pathname;
    const parts = pathname.replace(/\/+$/, '').split('/').filter(Boolean);
    return '/' + parts.slice(-2).join('/');
  } catch {
    // Relative URL or parse failure
    const parts = url.replace(/\?.*$/, '').split('/').filter(Boolean);
    return '/' + parts.slice(-2).join('/');
  }
}

function humanMessage(req: HttpRequest<unknown>, err: HttpErrorResponse): string {
  const method = req.method;
  const endpoint = shortenUrl(req.url);
  if (err.status === 0) {
    return `Network error — cannot reach ${endpoint}`;
  }
  const status = err.statusText && err.statusText !== 'OK' ? err.statusText : String(err.status);
  return `${method} ${endpoint} — ${status} (${err.status})`;
}

@Injectable()
export class HttpErrorInterceptor implements HttpInterceptor {
  constructor(private snackBar: MatSnackBar) {}

  intercept(req: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    return next.handle(req).pipe(
      catchError((err: unknown) => {
        if (err instanceof HttpErrorResponse) {
          const skip = req.context.get(SKIP_ERROR_SNACKBAR);
          const isSilent = SILENT_ENDPOINT_PATTERNS.some(p => p.test(req.url));

          if (!skip && !isSilent) {
            const key = `${req.method}|${req.url}|${err.status}`;
            if (!isDeduplicated(key)) {
              this.snackBar.open(humanMessage(req, err), 'Dismiss', {
                duration: 6000,
                panelClass: ['error-snackbar']
              });
            }
          }
        }
        return throwError(() => err);
      })
    );
  }
}
