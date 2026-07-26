import { TestBed } from '@angular/core/testing';
import {
  STAGING_PAIRING_STORAGE_KEY,
  StagingPairingService
} from './staging-pairing.service';

describe('StagingPairingService', () => {
  let service: StagingPairingService;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.removeItem(STAGING_PAIRING_STORAGE_KEY);
    TestBed.configureTestingModule({});
    service = TestBed.inject(StagingPairingService);
  });

  afterEach(() => {
    service.clear();
    localStorage.removeItem(STAGING_PAIRING_STORAGE_KEY);
  });

  it('keeps a pairing token only in session storage', () => {
    const token = 'pairing-secret-for-this-session';

    expect(service.pair(`  ${token}  `)).toBeTrue();
    expect(service.token).toBe(token);
    expect(sessionStorage.getItem(STAGING_PAIRING_STORAGE_KEY)).toBe(token);
    expect(localStorage.getItem(STAGING_PAIRING_STORAGE_KEY)).toBeNull();
    expect(document.cookie).not.toContain(token);
    expect(window.location.href).not.toContain(token);
  });

  it('clears both the session value and in-memory value', () => {
    expect(service.pair('temporary-secret')).toBeTrue();

    service.clear();

    expect(service.token).toBeNull();
    expect(sessionStorage.getItem(STAGING_PAIRING_STORAGE_KEY)).toBeNull();
  });

  it('does not accept an empty token', () => {
    expect(service.pair('   ')).toBeFalse();
    expect(service.token).toBeNull();
    expect(sessionStorage.getItem(STAGING_PAIRING_STORAGE_KEY)).toBeNull();
  });
});
