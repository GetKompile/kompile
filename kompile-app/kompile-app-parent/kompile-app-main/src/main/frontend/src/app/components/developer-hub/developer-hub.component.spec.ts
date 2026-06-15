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

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, Subject } from 'rxjs';
import { MatTabChangeEvent } from '@angular/material/tabs';
import { DeveloperHubComponent } from './developer-hub.component';

describe('DeveloperHubComponent', () => {
  let component: DeveloperHubComponent;
  let fixture: ComponentFixture<DeveloperHubComponent>;
  let queryParamsSubject: Subject<any>;

  beforeEach(async () => {
    queryParamsSubject = new Subject<any>();

    await TestBed.configureTestingModule({
      declarations: [DeveloperHubComponent],
      imports: [NoopAnimationsModule],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { queryParams: queryParamsSubject.asObservable() }
        }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(DeveloperHubComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should initialize with selectedTabIndex = 0', () => {
    expect(component.selectedTabIndex).toBe(0);
  });

  describe('ngOnInit - query param handling', () => {
    it('should set selectedTabIndex from valid tab param', fakeAsync(() => {
      queryParamsSubject.next({ tab: '2' });
      tick();
      expect(component.selectedTabIndex).toBe(2);
    }));

    it('should ignore tab param that is NaN', fakeAsync(() => {
      queryParamsSubject.next({ tab: 'abc' });
      tick();
      expect(component.selectedTabIndex).toBe(0);
    }));

    it('should ignore negative tab index', fakeAsync(() => {
      queryParamsSubject.next({ tab: '-1' });
      tick();
      expect(component.selectedTabIndex).toBe(0);
    }));

    it('should ignore tab index greater than 4', fakeAsync(() => {
      queryParamsSubject.next({ tab: '5' });
      tick();
      expect(component.selectedTabIndex).toBe(0);
    }));

    it('should accept tab index 4 (boundary)', fakeAsync(() => {
      queryParamsSubject.next({ tab: '4' });
      tick();
      expect(component.selectedTabIndex).toBe(4);
    }));

    it('should accept tab index 0 (boundary)', fakeAsync(() => {
      queryParamsSubject.next({ tab: '0' });
      tick();
      expect(component.selectedTabIndex).toBe(0);
    }));

    it('should store pending subtab index when subtab param is valid', fakeAsync(() => {
      queryParamsSubject.next({ tab: '1', subtab: '2' });
      tick();
      // pendingSubtabIndex is private; verify via side effects (no error thrown)
      expect(component.selectedTabIndex).toBe(1);
    }));

    it('should handle undefined tab param gracefully', fakeAsync(() => {
      queryParamsSubject.next({});
      tick();
      expect(component.selectedTabIndex).toBe(0);
    }));

    it('should handle negative subtab index gracefully (ignore)', fakeAsync(() => {
      queryParamsSubject.next({ subtab: '-1' });
      tick();
      // negative subtab not >= 0, so pendingSubtabIndex stays null
      expect(component).toBeTruthy();
    }));
  });

  describe('ngAfterViewInit', () => {
    it('should call setSubtab after timeout when pending subtab is set', fakeAsync(() => {
      // Set tab=4 so setSubtab can act on it
      queryParamsSubject.next({ tab: '4', subtab: '1' });
      tick();
      // Simulate AfterViewInit timeout
      tick(10);
      // No error thrown is a pass
      expect(component).toBeTruthy();
    }));
  });

  describe('onTabChange', () => {
    it('should update selectedTabIndex from MatTabChangeEvent', () => {
      const event = { index: 3 } as MatTabChangeEvent;
      component.onTabChange(event);
      expect(component.selectedTabIndex).toBe(3);
    });

    it('should update to index 0', () => {
      component.selectedTabIndex = 3;
      const event = { index: 0 } as MatTabChangeEvent;
      component.onTabChange(event);
      expect(component.selectedTabIndex).toBe(0);
    });
  });
});
