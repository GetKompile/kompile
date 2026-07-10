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

import { TestBed } from '@angular/core/testing';
import { Router, Routes } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { Location } from '@angular/common';
import { Component, NgZone } from '@angular/core';

// Minimal stub components for route targets
@Component({ template: '<p>Chat</p>',        standalone: false }) class StubChatComponent {}
@Component({ template: '<p>Project</p>',     standalone: false }) class StubProjectComponent {}
@Component({ template: '<p>FactSheets</p>',  standalone: false }) class StubFactSheetsComponent {}
@Component({ template: '<p>Data</p>',        standalone: false }) class StubDataComponent {}
@Component({ template: '<p>Graph</p>',       standalone: false }) class StubGraphComponent {}
@Component({ template: '<p>Developer</p>',   standalone: false }) class StubDeveloperComponent {}
@Component({ template: '<p>Agents</p>',      standalone: false }) class StubAgentsComponent {}
@Component({ template: '<p>Enforcer</p>',    standalone: false }) class StubEnforcerComponent {}
@Component({ template: '<p>Settings</p>',    standalone: false }) class StubSettingsComponent {}
@Component({ template: '<p>KnGraph</p>',     standalone: false }) class StubKnGraphComponent {}
@Component({ template: '<p>Grounding</p>',   standalone: false }) class StubGroundingComponent {}
@Component({ template: '<p>Simulator</p>',   standalone: false }) class StubSimulatorComponent {}
@Component({ template: '<router-outlet></router-outlet>', standalone: false }) class RootComponent {}

/**
 * These routes mirror the production AppRoutingModule routes,
 * using stub components to avoid real component dependencies.
 */
const testRoutes: Routes = [
  { path: '', redirectTo: 'chat', pathMatch: 'full' },

  // Primary nav tabs
  { path: 'chat',        component: StubChatComponent },
  { path: 'project',     component: StubProjectComponent },
  { path: 'fact-sheets', component: StubFactSheetsComponent },
  { path: 'data',        component: StubDataComponent },
  { path: 'graph',       component: StubGraphComponent },
  { path: 'developer',   component: StubDeveloperComponent },
  { path: 'agents',      component: StubAgentsComponent },
  { path: 'enforcer',    component: StubEnforcerComponent },

  // Utility routes
  { path: 'settings',        component: StubSettingsComponent },
  { path: 'knowledge-graph', component: StubKnGraphComponent },
  { path: 'grounding',       component: StubGroundingComponent },
  { path: 'graph-simulator', component: StubSimulatorComponent },

  // Legacy redirects
  { path: 'knowledge',     redirectTo: 'fact-sheets', pathMatch: 'full' },
  { path: 'tools',         redirectTo: 'data',        pathMatch: 'full' },
  { path: 'kclaw',         redirectTo: 'agents',      pathMatch: 'full' },
  { path: 'code-projects', redirectTo: 'data',        pathMatch: 'full' },

  { path: '**', redirectTo: 'chat' }
];

describe('AppRoutingModule', () => {
  let router: Router;
  let location: Location;
  let ngZone: NgZone;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RouterTestingModule.withRoutes(testRoutes)],
      declarations: [
        RootComponent,
        StubChatComponent,
        StubProjectComponent,
        StubFactSheetsComponent,
        StubDataComponent,
        StubGraphComponent,
        StubDeveloperComponent,
        StubAgentsComponent,
        StubEnforcerComponent,
        StubSettingsComponent,
        StubKnGraphComponent,
        StubGroundingComponent,
        StubSimulatorComponent
      ]
    }).compileComponents();

    router = TestBed.inject(Router);
    location = TestBed.inject(Location);
    ngZone = TestBed.inject(NgZone);

    const fixture = TestBed.createComponent(RootComponent);
    fixture.detectChanges();

    await ngZone.run(() => router.initialNavigation());
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. DEFAULT REDIRECT
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Default redirect', () => {
    it('should redirect root path to /chat', async () => {
      await ngZone.run(() => router.navigate(['']));
      expect(location.path()).toBe('/chat');
    });

    it('should redirect unknown paths to /chat', async () => {
      await ngZone.run(() => router.navigate(['nonexistent']));
      expect(location.path()).toBe('/chat');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. PRIMARY NAV TABS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Primary nav tab routes', () => {
    const primaryRoutes = [
      'chat', 'project', 'fact-sheets', 'data', 'graph',
      'developer', 'agents', 'enforcer'
    ];

    for (const path of primaryRoutes) {
      it(`should navigate to /${path}`, async () => {
        const success = await ngZone.run(() => router.navigate([path]));
        expect(success).toBeTrue();
        expect(location.path()).toBe(`/${path}`);
      });
    }
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. UTILITY ROUTES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Utility routes', () => {
    it('should navigate to /settings', async () => {
      const success = await ngZone.run(() => router.navigate(['settings']));
      expect(success).toBeTrue();
      expect(location.path()).toBe('/settings');
    });

    it('should navigate to /knowledge-graph', async () => {
      const success = await ngZone.run(() => router.navigate(['knowledge-graph']));
      expect(success).toBeTrue();
      expect(location.path()).toBe('/knowledge-graph');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. LEGACY REDIRECTS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Legacy path redirects', () => {
    it('should redirect /knowledge to /fact-sheets', async () => {
      await ngZone.run(() => router.navigate(['knowledge']));
      expect(location.path()).toBe('/fact-sheets');
    });

    it('should redirect /tools to /data', async () => {
      await ngZone.run(() => router.navigate(['tools']));
      expect(location.path()).toBe('/data');
    });

    it('should redirect /kclaw to /agents', async () => {
      await ngZone.run(() => router.navigate(['kclaw']));
      expect(location.path()).toBe('/agents');
    });

    it('should redirect /code-projects to /data', async () => {
      await ngZone.run(() => router.navigate(['code-projects']));
      expect(location.path()).toBe('/data');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. NAVIGATION SEQUENCE
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Navigation sequence', () => {
    it('should maintain location across multiple navigations', async () => {
      await ngZone.run(() => router.navigate(['chat']));
      expect(location.path()).toBe('/chat');

      await ngZone.run(() => router.navigate(['fact-sheets']));
      expect(location.path()).toBe('/fact-sheets');

      await ngZone.run(() => router.navigate(['data']));
      expect(location.path()).toBe('/data');

      await ngZone.run(() => router.navigate(['developer']));
      expect(location.path()).toBe('/developer');
    });
  });
});
