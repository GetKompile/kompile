/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

import { DOCUMENT } from '@angular/common';
import { Inject, Injectable } from '@angular/core';
import { BehaviorSubject, distinctUntilChanged } from 'rxjs';

export const STAGING_PAIRING_STORAGE_KEY = 'kompile.staging.pairing';

@Injectable({ providedIn: 'root' })
export class StagingPairingService {
  private readonly tokenSubject = new BehaviorSubject<string | null>(null);
  private readonly pairingRequiredSubject = new BehaviorSubject<boolean>(false);

  readonly token$ = this.tokenSubject.asObservable().pipe(distinctUntilChanged());
  readonly pairingRequired$ = this.pairingRequiredSubject.asObservable()
    .pipe(distinctUntilChanged());

  constructor(@Inject(DOCUMENT) private readonly document: Document) {
    this.tokenSubject.next(this.readSessionToken());
  }

  get token(): string | null {
    return this.tokenSubject.value;
  }

  get hasToken(): boolean {
    return this.token !== null;
  }

  pair(rawToken: string): boolean {
    const token = rawToken.trim();
    if (!token) {
      return false;
    }

    const storage = this.sessionStorage();
    if (storage === null) {
      return false;
    }

    try {
      storage.setItem(STAGING_PAIRING_STORAGE_KEY, token);
      this.tokenSubject.next(token);
      this.pairingRequiredSubject.next(false);
      return true;
    } catch {
      return false;
    }
  }

  clear(): void {
    try {
      this.sessionStorage()?.removeItem(STAGING_PAIRING_STORAGE_KEY);
    } catch {
      // The in-memory copy must still be removed when browser storage is unavailable.
    }
    this.tokenSubject.next(null);
    this.pairingRequiredSubject.next(false);
  }

  requirePairing(): void {
    this.pairingRequiredSubject.next(true);
  }

  confirmPairing(): void {
    this.pairingRequiredSubject.next(false);
  }

  private readSessionToken(): string | null {
    try {
      const token = this.sessionStorage()?.getItem(STAGING_PAIRING_STORAGE_KEY)?.trim();
      return token || null;
    } catch {
      return null;
    }
  }

  private sessionStorage(): Storage | null {
    try {
      return this.document.defaultView?.sessionStorage ?? null;
    } catch {
      return null;
    }
  }
}
