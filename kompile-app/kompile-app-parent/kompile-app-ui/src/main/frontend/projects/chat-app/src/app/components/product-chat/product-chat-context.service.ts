import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { CanActivateFn, Router } from '@angular/router';
import { firstValueFrom, timeout } from 'rxjs';
import { backendUrl } from '@shared/services/base.service';
import { ChatHostContext, snapshotChatHostContext } from '../unified-chat/chat-host-context';

export interface ProductChatDescriptor extends ChatHostContext {
  readonly panelsUrl: string;
  readonly domainBaseUrl: string;
  readonly status: string;
}
const PRODUCT_PATH = '/api/agents/chat/product';
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Invalid product descriptor');
  return value as Record<string, unknown>;
}
function text(value: unknown, limit = 1024): string {
  if (typeof value !== 'string' || !value.trim() || value.length > limit || /[\u0000-\u001f]/.test(value)) {
    throw new TypeError('Invalid product descriptor text');
  }
  return value;
}
/** Host paths are closed constants, not arbitrary profile-provided script or API URLs. */
export function productPath(value: unknown, expected: string, origin: string): string {
  const raw = text(value);
  if (raw !== expected && raw !== origin + expected) throw new TypeError('Unexpected product resource URL');
  const url = new URL(raw, origin);
  if (url.origin !== origin || url.username || url.password || url.pathname !== expected || url.search || url.hash) {
    throw new TypeError('Product resources must share the browser origin');
  }
  return expected;
}
export function readProductChatDescriptor(value: unknown, origin: string): ProductChatDescriptor | null {
  const source = object(value);
  if (source['enabled'] === false) return null;
  if (source['enabled'] !== true) throw new TypeError('Product readiness is missing');
  const chat = object(source['chat']);
  const agentName = text(source['agentName'], 128);
  if (!/^role:[A-Za-z0-9][A-Za-z0-9_-]{0,95}$/.test(agentName)) throw new TypeError('A managed role is required');
  const identity = snapshotChatHostContext({ productId: text(source['productId'], 128),
    title: text(source['title'], 256), workingDirectory: text(source['workingDirectory'], 4096),
    agentName, chat: { id: text(chat['id'], 128), name: text(chat['name'], 256) } });
  return Object.freeze({ ...identity,
    panelsUrl: productPath(source['panelsUrl'], PRODUCT_PATH + '/assets/index.html', origin),
    domainBaseUrl: productPath(source['domainBaseUrl'], PRODUCT_PATH + '/domain', origin),
    status: text(source['status'] ?? 'STOPPED', 64) });
}

/** Resolve before creating generic chat chrome or any product view. No eager constructor I/O. */
@Injectable({ providedIn: 'root' })
export class ProductChatContextService {
  private pending?: Promise<ProductChatDescriptor | null>;
  constructor(private readonly http: HttpClient) {}
  load(): Promise<ProductChatDescriptor | null> {
    if (!this.pending) this.pending = firstValueFrom(this.http.get<unknown>(`${backendUrl}/agents/chat/product`)
      .pipe(timeout(10000))).then(value => readProductChatDescriptor(value, window.location.origin))
      .catch((error: unknown) => {
        // Older/non-product chat backends have no descriptor route. Other failures fail closed.
        if (error instanceof HttpErrorResponse && error.status === 404) return null;
        throw error;
      });
    return this.pending;
  }
}

/** Browsing to a generic hash route cannot mount its eager product-management services. */
export const productChatRouteGuard: CanActivateFn = async route => {
  const context = inject(ProductChatContextService);
  const router = inject(Router);
  try {
    const product = await context.load();
    if (product && route.routeConfig?.path !== 'play') return router.createUrlTree(['/play']);
    if (!product && route.routeConfig?.path === 'play') return router.createUrlTree(['/chat']);
    return true;
  } catch { return false; }
};
