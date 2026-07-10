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

import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Router } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { of, Subject } from 'rxjs';
import { AppComponent } from './app.component';
import { ConfigService } from './services/config.service';
import { FactSheetService } from './services/fact-sheet.service';
import { DocumentService } from './services/document.service';
import { WebSocketService } from './services/websocket.service';
import { MainPanelNavigationService } from './services/main-panel-navigation.service';
import { ThemeService } from './services/theme.service';

describe('AppComponent', () => {
  let router: Router;
  let focusMainPanelSubject: Subject<void>;

  const configServiceStub = {
    config$: of({ appTitle: 'Kompile', faviconUrl: undefined })
  };
  const factSheetServiceStub = {
    sheets$: of([]),
    activeSheet$: of(null),
    loadSheets: () => of([]),
    loadActiveSheet: () => of(null)
  };
  const documentServiceStub = {
    getAllIngestTasks: () => of([])
  };
  const webSocketServiceStub = {
    connect: () => {},
    subscribeToAllTasks: () => of()
  };
  const themeServiceStub = {
    isDark: false,
    toggle: () => {}
  };

  beforeEach(async () => {
    focusMainPanelSubject = new Subject<void>();
    const mainPanelNavigationServiceStub = {
      focusMainPanel$: focusMainPanelSubject.asObservable()
    };

    await TestBed.configureTestingModule({
      imports: [RouterTestingModule.withRoutes([])],
      declarations: [AppComponent],
      providers: [
        { provide: ConfigService, useValue: configServiceStub },
        { provide: FactSheetService, useValue: factSheetServiceStub },
        { provide: DocumentService, useValue: documentServiceStub },
        { provide: WebSocketService, useValue: webSocketServiceStub },
        { provide: MainPanelNavigationService, useValue: mainPanelNavigationServiceStub },
        { provide: ThemeService, useValue: themeServiceStub }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.returnValue(Promise.resolve(true));
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  describe('handleBannerNavigation', () => {
    it('should navigate to /chat for unifiedChat', () => {
      const fixture = TestBed.createComponent(AppComponent);
      fixture.componentInstance.ngOnInit();
      fixture.componentInstance.handleBannerNavigation('unifiedChat');
      expect(router.navigate).toHaveBeenCalledWith(['/chat']);
    });

    it('should navigate to /fact-sheets for sources', () => {
      const fixture = TestBed.createComponent(AppComponent);
      fixture.componentInstance.ngOnInit();
      fixture.componentInstance.handleBannerNavigation('sources');
      expect(router.navigate).toHaveBeenCalledWith(['/fact-sheets']);
    });

    it('should navigate to /data for tools', () => {
      const fixture = TestBed.createComponent(AppComponent);
      fixture.componentInstance.ngOnInit();
      fixture.componentInstance.handleBannerNavigation('tools');
      expect(router.navigate).toHaveBeenCalledWith(['/data']);
    });

    it('should navigate to /agents for kclaw', () => {
      const fixture = TestBed.createComponent(AppComponent);
      fixture.componentInstance.ngOnInit();
      fixture.componentInstance.handleBannerNavigation('kclaw');
      expect(router.navigate).toHaveBeenCalledWith(['/agents']);
    });

    it('should navigate to /developer for archiveAssembly', () => {
      const fixture = TestBed.createComponent(AppComponent);
      fixture.componentInstance.ngOnInit();
      fixture.componentInstance.handleBannerNavigation('archiveAssembly');
      expect(router.navigate).toHaveBeenCalledWith(['/developer']);
    });

    it('should navigate to /enforcer for enforcer', () => {
      const fixture = TestBed.createComponent(AppComponent);
      fixture.componentInstance.ngOnInit();
      fixture.componentInstance.handleBannerNavigation('enforcer');
      expect(router.navigate).toHaveBeenCalledWith(['/enforcer']);
    });

    it('should console.warn for unknown keys and not navigate', () => {
      const fixture = TestBed.createComponent(AppComponent);
      fixture.componentInstance.ngOnInit();
      spyOn(console, 'warn');
      fixture.componentInstance.handleBannerNavigation('bogusKey');
      expect(console.warn).toHaveBeenCalled();
      // navigate was NOT called with a bogus route
      expect((router.navigate as jasmine.Spy).calls.allArgs()
        .every((args: any[]) => args[0][0] !== '/bogusKey')).toBeTrue();
    });
  });

  describe('openModelStaging', () => {
    it('should navigate to /developer', () => {
      const fixture = TestBed.createComponent(AppComponent);
      fixture.componentInstance.ngOnInit();
      fixture.componentInstance.openModelStaging();
      expect(router.navigate).toHaveBeenCalledWith(['/developer']);
    });
  });

  describe('MainPanelNavigationService integration', () => {
    it('should navigate to /fact-sheets when focusMainPanel$ fires', fakeAsync(() => {
      const fixture = TestBed.createComponent(AppComponent);
      fixture.componentInstance.ngOnInit();
      focusMainPanelSubject.next();
      tick();
      expect(router.navigate).toHaveBeenCalledWith(['/fact-sheets']);
    }));
  });
});
