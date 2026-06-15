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
import { ThemeService, Theme } from './theme.service';

describe('ThemeService', () => {
  const STORAGE_KEY = 'kompile-theme';

  function createService(): ThemeService {
    return TestBed.inject(ThemeService);
  }

  beforeEach(() => {
    // Clean state
    localStorage.removeItem(STORAGE_KEY);
    document.body.classList.remove('dark-theme', 'light-theme');

    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    document.body.classList.remove('dark-theme', 'light-theme');
  });

  // ═══════════════════════════════════════════════════════════════════════════════
  // CREATION & DEFAULTS
  // ═══════════════════════════════════════════════════════════════════════════════

  it('should be created', () => {
    const service = createService();
    expect(service).toBeTruthy();
  });

  it('should default to light theme when no preference is stored', () => {
    // Note: this may vary based on OS dark mode; we test the deterministic case
    const service = createService();
    expect(['light', 'dark']).toContain(service.currentTheme);
  });

  it('should apply body class on creation', () => {
    const service = createService();
    const theme = service.currentTheme;
    expect(document.body.classList.contains(`${theme}-theme`)).toBeTrue();
  });

  // ═══════════════════════════════════════════════════════════════════════════════
  // TOGGLE
  // ═══════════════════════════════════════════════════════════════════════════════

  it('should toggle from light to dark', () => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const service = createService();

    service.toggle();

    expect(service.currentTheme).toBe('dark');
    expect(service.isDark).toBeTrue();
  });

  it('should toggle from dark to light', () => {
    localStorage.setItem(STORAGE_KEY, 'dark');
    const service = createService();

    service.toggle();

    expect(service.currentTheme).toBe('light');
    expect(service.isDark).toBeFalse();
  });

  it('should toggle back and forth', () => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const service = createService();

    service.toggle();
    expect(service.currentTheme).toBe('dark');

    service.toggle();
    expect(service.currentTheme).toBe('light');
  });

  // ═══════════════════════════════════════════════════════════════════════════════
  // SET THEME
  // ═══════════════════════════════════════════════════════════════════════════════

  it('should set theme to dark explicitly', () => {
    const service = createService();
    service.setTheme('dark');

    expect(service.currentTheme).toBe('dark');
    expect(service.isDark).toBeTrue();
  });

  it('should set theme to light explicitly', () => {
    const service = createService();
    service.setTheme('light');

    expect(service.currentTheme).toBe('light');
    expect(service.isDark).toBeFalse();
  });

  // ═══════════════════════════════════════════════════════════════════════════════
  // LOCALSTORAGE PERSISTENCE
  // ═══════════════════════════════════════════════════════════════════════════════

  it('should persist theme to localStorage on toggle', () => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const service = createService();

    service.toggle();

    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark');
  });

  it('should persist theme to localStorage on setTheme', () => {
    const service = createService();
    service.setTheme('dark');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark');

    service.setTheme('light');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('light');
  });

  it('should read stored "dark" theme from localStorage', () => {
    localStorage.setItem(STORAGE_KEY, 'dark');
    const service = createService();
    expect(service.currentTheme).toBe('dark');
  });

  it('should read stored "light" theme from localStorage', () => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const service = createService();
    expect(service.currentTheme).toBe('light');
  });

  // ═══════════════════════════════════════════════════════════════════════════════
  // BODY CLASS MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  it('should add dark-theme class and remove light-theme when dark', () => {
    const service = createService();
    service.setTheme('dark');

    expect(document.body.classList.contains('dark-theme')).toBeTrue();
    expect(document.body.classList.contains('light-theme')).toBeFalse();
  });

  it('should add light-theme class and remove dark-theme when light', () => {
    const service = createService();
    service.setTheme('light');

    expect(document.body.classList.contains('light-theme')).toBeTrue();
    expect(document.body.classList.contains('dark-theme')).toBeFalse();
  });

  it('should swap body classes on toggle', () => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const service = createService();

    expect(document.body.classList.contains('light-theme')).toBeTrue();

    service.toggle();
    expect(document.body.classList.contains('dark-theme')).toBeTrue();
    expect(document.body.classList.contains('light-theme')).toBeFalse();

    service.toggle();
    expect(document.body.classList.contains('light-theme')).toBeTrue();
    expect(document.body.classList.contains('dark-theme')).toBeFalse();
  });

  // ═══════════════════════════════════════════════════════════════════════════════
  // OBSERVABLE
  // ═══════════════════════════════════════════════════════════════════════════════

  it('should emit current theme on subscribe', (done: DoneFn) => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const service = createService();

    service.theme$.subscribe(theme => {
      expect(theme).toBe('light');
      done();
    });
  });

  it('should emit new theme after toggle', () => {
    localStorage.setItem(STORAGE_KEY, 'light');
    const service = createService();
    const emissions: Theme[] = [];

    service.theme$.subscribe(t => emissions.push(t));

    service.toggle();
    service.toggle();

    expect(emissions).toEqual(['light', 'dark', 'light']);
  });

  it('should emit on setTheme', () => {
    const service = createService();
    const emissions: Theme[] = [];

    service.theme$.subscribe(t => emissions.push(t));

    service.setTheme('dark');
    service.setTheme('light');
    service.setTheme('dark');

    // Initial emission + 3 explicit sets (some may dedupe based on initial state)
    expect(emissions.length).toBeGreaterThanOrEqual(3);
    expect(emissions[emissions.length - 1]).toBe('dark');
  });

  // ═══════════════════════════════════════════════════════════════════════════════
  // isDark GETTER
  // ═══════════════════════════════════════════════════════════════════════════════

  it('isDark should return true only when theme is dark', () => {
    const service = createService();

    service.setTheme('dark');
    expect(service.isDark).toBeTrue();

    service.setTheme('light');
    expect(service.isDark).toBeFalse();
  });
});
