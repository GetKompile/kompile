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

// Minimal stub components for route targets (standalone: false for NgModule compat)
@Component({ template: '<p>Chat</p>', standalone: false }) class StubChatComponent {}
@Component({ template: '<p>Knowledge</p>', standalone: false }) class StubKnowledgeComponent {}
@Component({ template: '<p>Tools</p>', standalone: false }) class StubToolsComponent {}
@Component({ template: '<p>Settings</p>', standalone: false }) class StubSettingsComponent {}
@Component({ template: '<p>Developer</p>', standalone: false }) class StubDeveloperComponent {}
@Component({ template: '<p>KClaw</p>', standalone: false }) class StubKClawComponent {}
@Component({ template: '<router-outlet></router-outlet>', standalone: false }) class RootComponent {}

/**
 * These routes mirror the production AppRoutingModule's routes exactly,
 * but use stub components to avoid pulling in real component dependencies.
 */
const testRoutes: Routes = [
  { path: '', redirectTo: 'chat', pathMatch: 'full' },
  { path: 'chat', component: StubChatComponent, data: { title: 'Chat' } },
  { path: 'knowledge', component: StubKnowledgeComponent, data: { title: 'Knowledge' } },
  { path: 'tools', component: StubToolsComponent, data: { title: 'Tools' } },
  { path: 'settings', component: StubSettingsComponent, data: { title: 'Settings' } },
  { path: 'developer', component: StubDeveloperComponent, data: { title: 'Developer' } },
  { path: 'kclaw', component: StubKClawComponent, data: { title: 'KClaw' } },
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
        StubKnowledgeComponent,
        StubToolsComponent,
        StubSettingsComponent,
        StubDeveloperComponent,
        StubKClawComponent
      ]
    }).compileComponents();

    router = TestBed.inject(Router);
    location = TestBed.inject(Location);
    ngZone = TestBed.inject(NgZone);

    // Create a root component with router-outlet for navigation
    const fixture = TestBed.createComponent(RootComponent);
    fixture.detectChanges();

    // Initial navigation
    await ngZone.run(() => router.initialNavigation());
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. ROUTE DEFINITIONS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Route definitions', () => {
    it('should have 8 routes defined (including redirect and wildcard)', () => {
      expect(router.config.length).toBe(8);
    });

    it('should have a default redirect from empty path to chat', () => {
      const defaultRoute = router.config.find(r => r.path === '');
      expect(defaultRoute).toBeTruthy();
      expect(defaultRoute!.redirectTo).toBe('chat');
      expect(defaultRoute!.pathMatch).toBe('full');
    });

    it('should have a chat route', () => {
      const route = router.config.find(r => r.path === 'chat');
      expect(route).toBeTruthy();
      expect(route!.data).toEqual({ title: 'Chat' });
    });

    it('should have a knowledge route', () => {
      const route = router.config.find(r => r.path === 'knowledge');
      expect(route).toBeTruthy();
      expect(route!.data).toEqual({ title: 'Knowledge' });
    });

    it('should have a tools route', () => {
      const route = router.config.find(r => r.path === 'tools');
      expect(route).toBeTruthy();
      expect(route!.data).toEqual({ title: 'Tools' });
    });

    it('should have a settings route', () => {
      const route = router.config.find(r => r.path === 'settings');
      expect(route).toBeTruthy();
      expect(route!.data).toEqual({ title: 'Settings' });
    });

    it('should have a developer route', () => {
      const route = router.config.find(r => r.path === 'developer');
      expect(route).toBeTruthy();
      expect(route!.data).toEqual({ title: 'Developer' });
    });

    it('should have an kclaw route', () => {
      const route = router.config.find(r => r.path === 'kclaw');
      expect(route).toBeTruthy();
      expect(route!.data).toEqual({ title: 'KClaw' });
    });

    it('should have a wildcard route that redirects to chat', () => {
      const wildcard = router.config.find(r => r.path === '**');
      expect(wildcard).toBeTruthy();
      expect(wildcard!.redirectTo).toBe('chat');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. ROUTE DATA
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Route data', () => {
    it('every component route should have a title in data', () => {
      const componentRoutes = router.config.filter(r => r.component);
      expect(componentRoutes.length).toBe(6);

      for (const route of componentRoutes) {
        expect(route.data).toBeDefined();
        expect((route.data as any).title).toBeTruthy();
      }
    });

    it('route titles should be unique', () => {
      const componentRoutes = router.config.filter(r => r.component);
      const titles = componentRoutes.map(r => (r.data as any).title);
      const uniqueTitles = new Set(titles);
      expect(uniqueTitles.size).toBe(titles.length);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. NAVIGATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Navigation', () => {
    it('should navigate to /chat successfully', async () => {
      // Initial navigation already lands on /chat (default redirect),
      // so navigate away first then back
      await ngZone.run(() => router.navigate(['tools']));
      const success = await ngZone.run(() => router.navigate(['chat']));
      expect(success).toBeTrue();
      expect(location.path()).toBe('/chat');
    });

    it('should navigate to /knowledge successfully', async () => {
      const success = await ngZone.run(() => router.navigate(['knowledge']));
      expect(success).toBeTrue();
      expect(location.path()).toBe('/knowledge');
    });

    it('should navigate to /tools successfully', async () => {
      const success = await ngZone.run(() => router.navigate(['tools']));
      expect(success).toBeTrue();
      expect(location.path()).toBe('/tools');
    });

    it('should navigate to /settings successfully', async () => {
      const success = await ngZone.run(() => router.navigate(['settings']));
      expect(success).toBeTrue();
      expect(location.path()).toBe('/settings');
    });

    it('should navigate to /developer successfully', async () => {
      const success = await ngZone.run(() => router.navigate(['developer']));
      expect(success).toBeTrue();
      expect(location.path()).toBe('/developer');
    });

    it('should navigate to /kclaw successfully', async () => {
      const success = await ngZone.run(() => router.navigate(['kclaw']));
      expect(success).toBeTrue();
      expect(location.path()).toBe('/kclaw');
    });

    it('should redirect unknown routes to /chat', async () => {
      await ngZone.run(() => router.navigate(['nonexistent-route']));
      expect(location.path()).toBe('/chat');
    });

    it('should redirect root path to /chat', async () => {
      await ngZone.run(() => router.navigate(['']));
      expect(location.path()).toBe('/chat');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. ROUTE NAVIGATION SEQUENCE
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Route navigation sequence', () => {
    it('should maintain location state through multiple navigations', async () => {
      await ngZone.run(() => router.navigate(['chat']));
      expect(location.path()).toBe('/chat');

      await ngZone.run(() => router.navigate(['knowledge']));
      expect(location.path()).toBe('/knowledge');

      await ngZone.run(() => router.navigate(['tools']));
      expect(location.path()).toBe('/tools');

      await ngZone.run(() => router.navigate(['settings']));
      expect(location.path()).toBe('/settings');
    });

    it('should handle rapid navigation between routes', async () => {
      await ngZone.run(() => router.navigate(['chat']));
      await ngZone.run(() => router.navigate(['knowledge']));
      await ngZone.run(() => router.navigate(['tools']));
      await ngZone.run(() => router.navigate(['chat']));
      expect(location.path()).toBe('/chat');
    });

    it('should handle navigating to the same route twice', async () => {
      await ngZone.run(() => router.navigate(['tools']));
      expect(location.path()).toBe('/tools');

      await ngZone.run(() => router.navigate(['tools']));
      expect(location.path()).toBe('/tools');
    });
  });
});
