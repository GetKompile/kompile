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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { APP_INITIALIZER, Inject, Injectable, InjectionToken, NgModule, Optional } from '@angular/core';
import {
  HTTP_INTERCEPTORS,
  HttpClient,
  HttpEvent,
  HttpHandler,
  HttpInterceptor,
  HttpRequest
} from '@angular/common/http';
import { Observable, firstValueFrom } from 'rxjs';

export interface ManagedServiceEndpoints {
  adminUrl: string;
  chatUrl: string;
  crawlUrl: string;
  stagingUrl: string;
  servingUrl: string;
  routes: Record<string, string>;
}

export type RoutedPersona = 'admin' | 'chat' | 'crawl';

/** The hosting UI's persona; omitted by embeddings that need topology-only routing. */
export const CURRENT_SERVICE_PERSONA = new InjectionToken<RoutedPersona>('CURRENT_SERVICE_PERSONA');

export interface ManagedDependencyStatus {
  dependency: string;
  configured: boolean;
  endpointUrl: string;
  reachable: boolean;
  statusCode: number;
  error?: string;
}

/**
 * Browser-side companion to the CLI's managed service endpoint router.
 *
 * Each split UI obtains the same service-endpoints.json view from its own
 * /api/service-endpoints controller before Angular starts. Requests explicitly
 * owned by another persona are then sent to that configured base URL. Shared
 * contracts remain same-origin, preserving standalone component operation.
 */
@Injectable({ providedIn: 'root' })
export class ServiceEndpointRouter {
  private endpoints: ManagedServiceEndpoints | null = null;
  private dependencies: Partial<Record<RoutedPersona, ManagedDependencyStatus>> = {};

  constructor(
    private readonly http: HttpClient,
    @Optional() @Inject(CURRENT_SERVICE_PERSONA) private readonly currentPersona: RoutedPersona | null = null
  ) {}

  async load(): Promise<void> {
    try {
      const endpoints = await firstValueFrom(
        this.http.get<ManagedServiceEndpoints>('/api/service-endpoints')
      );
      if (endpoints && typeof endpoints === 'object') {
        this.endpoints = {
          ...endpoints,
          routes: endpoints.routes && typeof endpoints.routes === 'object'
            ? endpoints.routes
            : {}
        };
        this.dependencies = {};
        await this.loadPersonaDependencies();
      }
    } catch (error) {
      // Development servers and deliberately minimal embeddings may not expose
      // managed routing. In that case the historical same-origin behavior is
      // still the safest fallback.
      console.warn('Managed service endpoint routing is unavailable; using same-origin APIs.', error);
    }
  }

  /** Unknown health stays permissive for minimal/dev embeddings; an explicit offline probe gates it. */
  isReachable(persona: RoutedPersona): boolean {
    return this.dependencies[persona]?.reachable !== false;
  }

  dependencyStatus(persona: RoutedPersona): ManagedDependencyStatus | null {
    return this.dependencies[persona] ?? null;
  }

  private async loadPersonaDependencies(): Promise<void> {
    const personas: RoutedPersona[] = ['admin', 'chat', 'crawl'];
    await Promise.all(personas.map(async persona => {
      try {
        const status = await firstValueFrom(
          this.http.get<ManagedDependencyStatus>(`/api/service-endpoints/dependencies/${persona}`)
        );
        if (status && typeof status === 'object') {
          this.dependencies[persona] = status;
        }
      } catch (error) {
        // Older/minimal embeddings may not expose health probes. Preserve their historical
        // same-origin behavior instead of declaring a dependency offline without evidence.
        console.warn(`Managed ${persona} dependency health is unavailable.`, error);
      }
    }));
  }

  resolve(requestUrl: string): string {
    if (!this.endpoints || typeof window === 'undefined' || !window.location) {
      return requestUrl;
    }

    const currentOrigin = window.location.origin;
    let url: URL;
    try {
      url = new URL(requestUrl, currentOrigin);
    } catch {
      return requestUrl;
    }

    if (url.origin !== currentOrigin
        || !url.pathname.startsWith('/api/')
        || url.pathname === '/api/service-endpoints'
        || url.pathname.startsWith('/api/service-endpoints/')
        || url.pathname === '/api/channel-integrations/browser-sessions/exchange') {
      return requestUrl;
    }

    const target = this.ownerForPath(url.pathname);
    // The browser origin is authoritative for this UI, including LAN hosts and
    // dynamically assigned ports that differ from the managed topology defaults.
    if (!target || target === this.currentPersona) {
      return requestUrl;
    }

    const targetBase = this.baseUrl(target);
    if (!targetBase) {
      return requestUrl;
    }

    return `${targetBase}${url.pathname}${url.search}${url.hash}`;
  }

  private ownerForPath(path: string): RoutedPersona | null {
    let owner: RoutedPersona | null = null;
    let longestPrefix = -1;

    for (const [rawPrefix, rawOwner] of Object.entries(this.endpoints?.routes ?? {})) {
      const prefix = this.normalizePrefix(rawPrefix);
      const candidate = this.asPersona(rawOwner);
      if (!prefix || !candidate || prefix.length <= longestPrefix) {
        continue;
      }
      if (path === prefix || path.startsWith(`${prefix}/`)) {
        owner = candidate;
        longestPrefix = prefix.length;
      }
    }

    return owner;
  }

  private baseUrl(persona: RoutedPersona): string | null {
    const raw = persona === 'admin'
      ? this.endpoints?.adminUrl
      : persona === 'chat'
        ? this.endpoints?.chatUrl
        : this.endpoints?.crawlUrl;
    if (!raw || typeof raw !== 'string') {
      return null;
    }
    return raw.replace(/\/+$/, '');
  }

  private normalizePrefix(prefix: string): string | null {
    if (!prefix || typeof prefix !== 'string') {
      return null;
    }
    const normalized = prefix.trim().replace(/\/+$/, '');
    return normalized.startsWith('/api/') ? normalized : null;
  }

  private asPersona(value: string): RoutedPersona | null {
    return value === 'admin' || value === 'chat' || value === 'crawl' ? value : null;
  }
}

@Injectable()
export class ServiceEndpointRoutingInterceptor implements HttpInterceptor {
  constructor(private readonly router: ServiceEndpointRouter) {}

  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    const routedUrl = this.router.resolve(request.url);
    const path = this.requestPath(routedUrl);
    if (!this.requiresIntegrationSession(path, request.method)) {
      return next.handle(routedUrl === request.url ? request : request.clone({ url: routedUrl }));
    }

    let headers = request.headers;
    if (this.isMutation(request.method) && !this.usesExternalAuthentication(path)) {
      headers = headers.set('X-Kompile-Channel-Request', '1');
      if (typeof sessionStorage !== 'undefined') {
        const csrf = sessionStorage.getItem('kompile.channel.csrf');
        if (csrf) headers = headers.set('X-Kompile-Channel-CSRF', csrf);
      }
    }
    return next.handle(request.clone({
      url: routedUrl,
      headers,
      withCredentials: true
    }));
  }

  private requestPath(url: string): string {
    try {
      const origin = typeof window !== 'undefined' && window.location
        ? window.location.origin : 'http://localhost';
      return new URL(url, origin).pathname;
    } catch {
      return url;
    }
  }

  private requiresIntegrationSession(path: string, method: string): boolean {
    const integrationPath = [
      '/api/channel-integrations', '/api/kclaw', '/api/sync', '/api/oauth',
      '/api/source-providers'
    ].some(prefix => path === prefix || path.startsWith(`${prefix}/`));
    const sourceMutation = this.isMutation(method) && [
      '/api/unified-crawl', '/api/documents'
    ].some(prefix => path === prefix || path.startsWith(`${prefix}/`));
    return integrationPath || sourceMutation;
  }

  private usesExternalAuthentication(path: string): boolean {
    return path === '/api/channel-integrations/browser-sessions/exchange'
      || /^\/api\/oauth\/[^/]+\/callback$/.test(path)
      || path === '/api/sync/webhook/notion'
      || path === '/api/sync/webhook/notion/verify'
      || path === '/api/kclaw/channels/webhook/whatsapp';
  }

  private isMutation(method: string): boolean {
    return ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method.toUpperCase());
  }
}

export function initializeServiceEndpointRouting(
  router: ServiceEndpointRouter
): () => Promise<void> {
  return () => router.load();
}

/**
 * Imported once by each persona app. APP_INITIALIZER prevents shared chrome
 * from racing its first API calls ahead of the managed topology.
 */
@NgModule({
  providers: [
    {
      provide: APP_INITIALIZER,
      useFactory: initializeServiceEndpointRouting,
      deps: [ServiceEndpointRouter],
      multi: true
    },
    {
      provide: HTTP_INTERCEPTORS,
      useClass: ServiceEndpointRoutingInterceptor,
      multi: true
    }
  ]
})
export class ServiceEndpointRoutingModule {}
