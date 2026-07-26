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

import { TestBed, ComponentFixture, fakeAsync, tick } from '@angular/core/testing';
import { Router } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { of, Subject } from 'rxjs';
import { AppShellComponent } from './app-shell.component';
import { ConfigService } from '../../services/config.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { DocumentService } from '../../services/document.service';
import { WebSocketService } from '../../services/websocket.service';
import { MainPanelNavigationService } from '../../services/main-panel-navigation.service';
import { ThemeService } from '../../services/theme.service';

/**
 * Ported from the monolith's app.component.spec.ts. The behaviour it covered — legacy-key
 * navigation, the model-staging jump, and the MainPanelNavigationService subscription — moved
 * into this shell verbatim, but the destinations are now inputs rather than a hardcoded switch.
 * So each case supplies the map the persona app would supply, and there is a new group for the
 * case that only exists after the split: an app that does *not* host the target route.
 */
describe('AppShellComponent', () => {
  let router: Router;
  let focusMainPanelSubject: Subject<void>;

  // The full map the admin console passes; the end-user apps pass narrower ones.
  const ADMIN_KEY_MAP: Record<string, string> = {
    sources: '/fact-sheets',
    developer: '/developer',
    kclaw: '/agents',
    enforcer: '/enforcer',
    archiveAssembly: '/developer'
  };

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
      imports: [RouterTestingModule.withRoutes([]), AppShellComponent],
      providers: [
        { provide: ConfigService, useValue: configServiceStub },
        { provide: FactSheetService, useValue: factSheetServiceStub },
        { provide: DocumentService, useValue: documentServiceStub },
        { provide: WebSocketService, useValue: webSocketServiceStub },
        { provide: MainPanelNavigationService, useValue: mainPanelNavigationServiceStub },
        { provide: ThemeService, useValue: themeServiceStub }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
      /*
       * Render nothing. The shell is standalone and `imports` its own header children
       * (branding, index-status-banner, model-status-indicator, project-explorer), so
       * createComponent builds that whole tree for real and every descendant is handed
       * the stubs above. ModelStatusIndicatorComponent calls
       * webSocketService.unsubscribeFromModelStatus() on destroy, which a two-method stub
       * does not have — and a throw in teardown fails the spec that just passed, so all 13
       * went red at once.
       *
       * Widening the stub would make this spec track every descendant's service surface
       * forever. Nothing below asserts on the DOM — every case calls a component method and
       * checks router.navigate — so the children have no business being built here.
       */
      .overrideTemplate(AppShellComponent, '<div></div>')
      .compileComponents();

    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.returnValue(Promise.resolve(true));
  });

  /** Create the shell already configured the way a persona app would configure it. */
  function shellWith(
    legacyKeyMap: Record<string, string>,
    stagingRoute: string | null = null
  ): ComponentFixture<AppShellComponent> {
    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.componentInstance.navItems = [];
    fixture.componentInstance.legacyKeyMap = legacyKeyMap;
    fixture.componentInstance.stagingRoute = stagingRoute;
    fixture.componentInstance.ngOnInit();
    return fixture;
  }

  it('should create the shell', () => {
    expect(shellWith(ADMIN_KEY_MAP).componentInstance).toBeTruthy();
  });

  describe('handleBannerNavigation', () => {
    it('should navigate to /chat for unifiedChat in an app that maps it', () => {
      shellWith({ unifiedChat: '/chat' }).componentInstance.handleBannerNavigation('unifiedChat');
      expect(router.navigate).toHaveBeenCalledWith(['/chat']);
    });

    it('should navigate to /fact-sheets for sources', () => {
      shellWith(ADMIN_KEY_MAP).componentInstance.handleBannerNavigation('sources');
      expect(router.navigate).toHaveBeenCalledWith(['/fact-sheets']);
    });

    it('should navigate to /data for tools in an app that maps it', () => {
      shellWith({ tools: '/data' }).componentInstance.handleBannerNavigation('tools');
      expect(router.navigate).toHaveBeenCalledWith(['/data']);
    });

    it('should navigate to /agents for kclaw', () => {
      shellWith(ADMIN_KEY_MAP).componentInstance.handleBannerNavigation('kclaw');
      expect(router.navigate).toHaveBeenCalledWith(['/agents']);
    });

    it('should navigate to /developer for archiveAssembly', () => {
      shellWith(ADMIN_KEY_MAP).componentInstance.handleBannerNavigation('archiveAssembly');
      expect(router.navigate).toHaveBeenCalledWith(['/developer']);
    });

    it('should navigate to /enforcer for enforcer', () => {
      shellWith(ADMIN_KEY_MAP).componentInstance.handleBannerNavigation('enforcer');
      expect(router.navigate).toHaveBeenCalledWith(['/enforcer']);
    });

    it('should console.warn for unknown keys and not navigate', () => {
      const shell = shellWith(ADMIN_KEY_MAP).componentInstance;
      spyOn(console, 'warn');
      shell.handleBannerNavigation('bogusKey');
      expect(console.warn).toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('should warn rather than navigate for a key this app does not host', () => {
      // The chat app has no /developer route. The banner can still emit the key, and the shell
      // must not invent a route for it.
      const shell = shellWith({ sources: '/fact-sheets' }).componentInstance;
      spyOn(console, 'warn');
      shell.handleBannerNavigation('developer');
      expect(console.warn).toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  describe('openModelStaging', () => {
    it('should navigate to stagingRoute when the app hosts one', () => {
      shellWith(ADMIN_KEY_MAP, '/developer').componentInstance.openModelStaging();
      expect(router.navigate).toHaveBeenCalledWith(['/developer']);
    });

    it('should be inert when the app hosts no staging route', () => {
      shellWith({ sources: '/fact-sheets' }, null).componentInstance.openModelStaging();
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  describe('MainPanelNavigationService integration', () => {
    it('should navigate to the sources route when focusMainPanel$ fires', fakeAsync(() => {
      shellWith(ADMIN_KEY_MAP);
      focusMainPanelSubject.next();
      tick();
      expect(router.navigate).toHaveBeenCalledWith(['/fact-sheets']);
    }));

    it('should not navigate when the app maps no sources route', fakeAsync(() => {
      shellWith({});
      focusMainPanelSubject.next();
      tick();
      expect(router.navigate).not.toHaveBeenCalled();
    }));
  });
});
