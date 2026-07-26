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
import { Router, Route, Routes } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { Location } from '@angular/common';
import { Component, NgZone } from '@angular/core';

@Component({ template: '<router-outlet></router-outlet>', standalone: false })
class HarnessRootComponent {}

@Component({ template: '<p>stub</p>', standalone: false })
class HarnessStubComponent {}

export interface RouteTableExpectations {
  /** Paths this app declares. Each must navigate to itself. */
  declared: string[];
  /** Legacy path -> the path it must redirect to. */
  redirects: Record<string, string>;
  /** Where '' and any unrecognised path must land. */
  fallback: string;
  /**
   * Paths that belong to a *different* persona app. Each must fall through to `fallback`.
   *
   * This is the half of the split that is easy to lose: a stray route added back to an end-user
   * app re-exposes an admin surface, and nothing else in the build would complain. Listing the
   * other apps' paths here turns that into a failing test.
   */
  foreign: string[];
}

/**
 * Assert one persona app's route table.
 *
 * Callers pass the app's *real* exported `routes` array; every `component` is swapped for a stub
 * before the table reaches the router, so the paths, redirects and ordering under test are
 * production's while no real component is instantiated or needs providers. A copy of the table
 * would drift from the app the moment someone edited one and not the other.
 */
export function describeRouteTable(
  appName: string,
  routes: Routes,
  expected: RouteTableExpectations
): void {
  describe(`${appName} route table`, () => {
    let router: Router;
    let location: Location;
    let ngZone: NgZone;

    const stubbed: Routes = routes.map((r: Route) =>
      r.component ? { ...r, component: HarnessStubComponent } : { ...r });

    beforeEach(async () => {
      await TestBed.configureTestingModule({
        imports: [RouterTestingModule.withRoutes(stubbed)],
        declarations: [HarnessRootComponent, HarnessStubComponent]
      }).compileComponents();

      router = TestBed.inject(Router);
      location = TestBed.inject(Location);
      ngZone = TestBed.inject(NgZone);

      const fixture = TestBed.createComponent(HarnessRootComponent);
      fixture.detectChanges();

      await ngZone.run(() => router.initialNavigation());
    });

    /*
     * Park the router somewhere other than `target` before navigating to it.
     *
     * Navigating to the URL you are already on is ignored and resolves false, and every app's
     * fallback is also one of its declared routes — so initialNavigation() leaves the router
     * sitting on the very path the first declared-route case is about to assert. Parking first
     * also makes the assertions real rather than vacuous: the router has to actually move.
     */
    async function parkAwayFrom(target: string): Promise<void> {
      const elsewhere = expected.declared.find(p => p !== target && p !== expected.fallback)
        ?? expected.declared.find(p => p !== target);
      if (elsewhere) {
        await ngZone.run(() => router.navigate([elsewhere]));
      }
    }

    describe('Default redirect', () => {
      it(`should redirect the root path to ${expected.fallback}`, async () => {
        await parkAwayFrom(expected.fallback);
        await ngZone.run(() => router.navigate(['']));
        expect(location.path()).toBe(expected.fallback);
      });

      it(`should redirect unknown paths to ${expected.fallback}`, async () => {
        await parkAwayFrom(expected.fallback);
        await ngZone.run(() => router.navigate(['nonexistent']));
        expect(location.path()).toBe(expected.fallback);
      });
    });

    describe('Declared routes', () => {
      for (const path of expected.declared) {
        it(`should navigate to ${path}`, async () => {
          await parkAwayFrom(path);
          const success = await ngZone.run(() => router.navigate([path]));
          expect(success).toBeTrue();
          expect(location.path()).toBe(path);
        });
      }
    });

    describe('Legacy path redirects', () => {
      for (const [from, to] of Object.entries(expected.redirects)) {
        it(`should redirect ${from} to ${to}`, async () => {
          await parkAwayFrom(to);
          await ngZone.run(() => router.navigate([from]));
          expect(location.path()).toBe(to);
        });
      }
    });

    describe('Routes owned by another persona', () => {
      for (const path of expected.foreign) {
        it(`should not serve ${path} — falls through to ${expected.fallback}`, async () => {
          await parkAwayFrom(expected.fallback);
          await ngZone.run(() => router.navigate([path]));
          expect(location.path()).toBe(expected.fallback);
        });
      }
    });
  });
}
