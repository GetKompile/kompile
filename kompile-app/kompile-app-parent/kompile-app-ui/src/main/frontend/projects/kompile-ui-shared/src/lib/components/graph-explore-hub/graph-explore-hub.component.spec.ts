/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, EventEmitter, Input, Output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, NavigationExtras, Params, Router, convertToParamMap } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';

import { GraphExploreHubComponent } from './graph-explore-hub.component';
import { GraphVisualizerComponent } from '../graph-visualizer/graph-visualizer.component';
import { GraphHierarchyComponent } from '../graph-hierarchy/graph-hierarchy.component';
import { GraphOverviewComponent } from '../graph-overview/graph-overview.component';
import { CommunityPanelComponent } from '../community-panel/community-panel.component';
import { PartitionCoveragePanelComponent } from '../partition-coverage-panel/partition-coverage-panel.component';
import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';

// ─────────────────────────────────────────────────────────────────────────────
// Panel stubs: the hub only decides which sheet and node they get
// ─────────────────────────────────────────────────────────────────────────────
@Component({ selector: 'app-graph-visualizer', standalone: true, template: '' })
class GraphVisualizerStubComponent {
  @Input() factSheetId: number | null = null;
  @Input() factSheetName = '';
  @Input() focusNodeId: string | null = null;
}

@Component({ selector: 'app-graph-hierarchy', standalone: true, template: '' })
class GraphHierarchyStubComponent {
  @Input() factSheetId: number | null = null;
  @Output() navigateToGraph = new EventEmitter<unknown>();
  @Output() viewSubGraph = new EventEmitter<unknown>();
}

@Component({ selector: 'app-graph-overview', standalone: true, template: '' })
class GraphOverviewStubComponent {}

@Component({ selector: 'app-community-panel', standalone: true, template: '' })
class CommunityPanelStubComponent {
  @Input() factSheetId: number | null = null;
}

@Component({ selector: 'app-partition-coverage-panel', standalone: true, template: '' })
class PartitionCoveragePanelStubComponent {
  @Input() factSheetId: number | null = null;
}

/**
 * ActivatedRoute whose query parameters a test sets, and which the Router fake below updates the
 * way a real merge navigation would (null drops a parameter).
 */
class RouteStub {
  private readonly params$ = new BehaviorSubject<Params>({});
  readonly queryParams: Observable<Params> = this.params$.asObservable();

  get snapshot(): { queryParamMap: ReturnType<typeof convertToParamMap> } {
    return { queryParamMap: convertToParamMap(this.params$.value) };
  }

  setQueryParams(params: Params): void {
    this.params$.next(params);
  }

  mergeQueryParams(changes: Params): void {
    const next: Params = { ...this.params$.value };
    for (const [key, value] of Object.entries(changes)) {
      if (value == null) {
        delete next[key];
      } else {
        next[key] = value;
      }
    }
    this.params$.next(next);
  }
}

const ACTIVE = { id: 1, name: 'Active sheet', factCount: 12 } as FactSheet;
const LINKED = { id: 7, name: 'Linked sheet', factCount: 40 } as FactSheet;
const OTHER = { id: 3, name: 'Other sheet', factCount: 5 } as FactSheet;
const EMPTY = { id: 9, name: 'Empty sheet', factCount: 0 } as FactSheet;

const CLEAR_LINK: NavigationExtras = {
  queryParams: { factSheetId: null, focusNode: null },
  queryParamsHandling: 'merge',
  replaceUrl: true
};

describe('GraphExploreHubComponent', () => {
  let fixture: ComponentFixture<GraphExploreHubComponent>;
  let component: GraphExploreHubComponent;
  let activeSheet$: BehaviorSubject<FactSheet | null>;
  let factSheetService: jasmine.SpyObj<FactSheetService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let router: jasmine.SpyObj<Router>;
  let route: RouteStub;

  beforeEach(async () => {
    activeSheet$ = new BehaviorSubject<FactSheet | null>(ACTIVE);
    factSheetService = jasmine.createSpyObj<FactSheetService>('FactSheetService', ['getSheet', 'activateSheet'], {
      activeSheet$: activeSheet$.asObservable()
    });
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    route = new RouteStub();
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.callFake((_commands: unknown[], extras?: NavigationExtras) => {
      if (extras?.queryParams) {
        route.mergeQueryParams(extras.queryParams);
      }
      return Promise.resolve(true);
    });

    await TestBed.configureTestingModule({
      imports: [GraphExploreHubComponent, NoopAnimationsModule],
      providers: [
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: router }
      ]
    })
      .overrideComponent(GraphExploreHubComponent, {
        remove: {
          imports: [
            GraphVisualizerComponent,
            GraphHierarchyComponent,
            GraphOverviewComponent,
            CommunityPanelComponent,
            PartitionCoveragePanelComponent
          ]
        },
        add: {
          imports: [
            GraphVisualizerStubComponent,
            GraphHierarchyStubComponent,
            GraphOverviewStubComponent,
            CommunityPanelStubComponent,
            PartitionCoveragePanelStubComponent
          ]
        }
      })
      // The hub imports MatSnackBarModule, whose provider would shadow a root-level mock.
      .overrideProvider(FactSheetService, { useValue: factSheetService })
      .overrideProvider(MatSnackBar, { useValue: snackBar })
      .compileComponents();
  });

  function create(params: Params = {}): void {
    route.setQueryParams(params);
    fixture = TestBed.createComponent(GraphExploreHubComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function visualizer(): GraphVisualizerStubComponent | null {
    const el = fixture.debugElement.query(By.directive(GraphVisualizerStubComponent));
    return el ? el.componentInstance as GraphVisualizerStubComponent : null;
  }

  function query(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  it('shows the active sheet when the link names no fact sheet', () => {
    create({ focusNode: 'acme' });

    expect(factSheetService.getSheet).not.toHaveBeenCalled();
    expect(component.shownSheet).toBe(ACTIVE);
    expect(visualizer()!.factSheetId).toBe(1);
    expect(visualizer()!.focusNodeId).toBe('acme');
    expect(query('.linked-sheet-bar')).toBeNull();
  });

  it('shows the fact sheet a link names without activating it', () => {
    factSheetService.getSheet.and.returnValue(of(LINKED));

    create({ factSheetId: '7', focusNode: 'acme' });

    expect(factSheetService.getSheet).toHaveBeenCalledOnceWith(7);
    expect(factSheetService.activateSheet).not.toHaveBeenCalled();
    expect(component.shownSheet).toBe(LINKED);
    expect(component.activeTab).toBe('visualizer');
    expect(visualizer()!.factSheetId).toBe(7);
    expect(visualizer()!.factSheetName).toBe('Linked sheet');
    expect(visualizer()!.focusNodeId).toBe('acme');
    const notice = query('.linked-sheet-bar')!.textContent!;
    expect(notice).toContain('Linked sheet');
    expect(notice).toContain('Active sheet');
  });

  it('shows no panels while the linked sheet is being fetched', () => {
    const pending = new Subject<FactSheet>();
    factSheetService.getSheet.and.returnValue(pending);

    create({ factSheetId: '7', focusNode: 'acme' });

    expect(component.openingLinkedSheet).toBeTrue();
    expect(component.shownSheet).toBeNull();
    expect(visualizer()).toBeNull();
    expect(query('.no-fact-sheet-state')).toBeNull();
    expect(query('.linked-sheet-bar')!.textContent).toContain('Opening fact sheet');

    pending.next(LINKED);
    fixture.detectChanges();

    expect(component.openingLinkedSheet).toBeFalse();
    expect(visualizer()!.factSheetId).toBe(7);
  });

  it('falls back to the active sheet, without the link\'s node, when the sheet cannot be fetched', () => {
    factSheetService.getSheet.and.returnValue(throwError(() => new Error('404')));

    create({ factSheetId: '7', focusNode: 'acme' });

    expect(snackBar.open).toHaveBeenCalledOnceWith(
      'Fact sheet 7 could not be opened; showing the active fact sheet', 'Dismiss', { duration: 5000 });
    expect(component.openingLinkedSheet).toBeFalse();
    expect(component.shownSheet).toBe(ACTIVE);
    expect(visualizer()!.factSheetId).toBe(1);
    expect(visualizer()!.focusNodeId).toBeNull();
  });

  for (const bad of ['abc', '-1', '7.5', '1e3', '99999999999999999999']) {
    it(`does not fetch the malformed fact sheet id "${bad}"`, () => {
      create({ factSheetId: bad, focusNode: 'acme' });

      expect(factSheetService.getSheet).not.toHaveBeenCalled();
      expect(snackBar.open).toHaveBeenCalledOnceWith(
        `Fact sheet ${bad} could not be opened; showing the active fact sheet`, 'Dismiss', { duration: 5000 });
      expect(component.shownSheet).toBe(ACTIVE);
      expect(component.focusNodeId).toBeNull();
    });
  }

  it('does not pin a link that names the active sheet', () => {
    create({ factSheetId: '1', focusNode: 'acme' });

    expect(factSheetService.getSheet).not.toHaveBeenCalled();
    expect(component.linkedFactSheet).toBeNull();
    expect(component.shownSheet).toBe(ACTIVE);
    expect(visualizer()!.focusNodeId).toBe('acme');
    expect(query('.linked-sheet-bar')).toBeNull();
  });

  it('drops the pin when the linked sheet turns out to be the active one', () => {
    activeSheet$.next(null); // the active sheet has not loaded yet
    factSheetService.getSheet.and.returnValue(of(LINKED));
    create({ factSheetId: '7', focusNode: 'acme' });
    expect(component.linkedFactSheet).toBe(LINKED);

    const loaded = { ...LINKED } as FactSheet;
    activeSheet$.next(loaded);
    fixture.detectChanges();

    expect(component.linkedFactSheet).toBeNull();
    expect(component.shownSheet).toBe(loaded);
    expect(visualizer()!.focusNodeId).toBe('acme');
    expect(router.navigate).not.toHaveBeenCalled();
    expect(query('.linked-sheet-bar')).toBeNull();
  });

  it('leaves the link, and clears it from the URL, when the user switches sheets', () => {
    factSheetService.getSheet.and.returnValue(of(LINKED));
    create({ factSheetId: '7', focusNode: 'acme', tab: 'visualizer' });

    activeSheet$.next(OTHER);
    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledOnceWith([], jasmine.objectContaining(CLEAR_LINK));
    expect(router.navigate.calls.mostRecent().args[1]!.relativeTo).toBe(route as unknown as ActivatedRoute);
    expect(component.linkedFactSheet).toBeNull();
    expect(component.shownSheet).toBe(OTHER);
    expect(visualizer()!.factSheetId).toBe(3);
    expect(visualizer()!.focusNodeId).toBeNull();
    expect(route.snapshot.queryParamMap.get('tab')).toBe('visualizer');
    expect(factSheetService.getSheet).toHaveBeenCalledTimes(1);
    expect(factSheetService.activateSheet).not.toHaveBeenCalled();
  });

  it('"Show the active sheet" leaves the link without activating anything', () => {
    factSheetService.getSheet.and.returnValue(of(LINKED));
    create({ factSheetId: '7', focusNode: 'acme' });

    query('.show-active-sheet-btn')!.click();
    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledOnceWith([], jasmine.objectContaining(CLEAR_LINK));
    expect(component.shownSheet).toBe(ACTIVE);
    expect(visualizer()!.factSheetId).toBe(1);
    expect(visualizer()!.focusNodeId).toBeNull();
    expect(query('.linked-sheet-bar')).toBeNull();
    expect(factSheetService.activateSheet).not.toHaveBeenCalled();
  });

  it('opens the sheet a later link names, dropping a fetch still in flight', () => {
    const slow = new Subject<FactSheet>();
    factSheetService.getSheet.and.callFake((id: number) => id === 7 ? slow : of(OTHER));
    create({ factSheetId: '7' });

    route.setQueryParams({ factSheetId: '3' });
    fixture.detectChanges();
    slow.next(LINKED);
    fixture.detectChanges();

    expect(component.shownSheet).toBe(OTHER);
    expect(visualizer()!.factSheetId).toBe(3);
  });

  it('shows the cold-start banner, even a dismissed one, when a link opens a sheet with no facts', () => {
    activeSheet$.next({ id: 1, name: 'Active sheet', factCount: 0 } as FactSheet);
    factSheetService.getSheet.and.returnValue(of(EMPTY));
    create();
    expect(component.showColdStartBanner).toBeTrue();
    query('.banner-dismiss')!.click();
    fixture.detectChanges();
    expect(query('.cold-start-banner')).toBeNull();

    route.setQueryParams({ factSheetId: '9' });
    fixture.detectChanges();

    expect(component.showColdStartBanner).toBeTrue();
    expect(query('.cold-start-banner')).not.toBeNull();
  });

  it('hides the cold-start banner on leaving a link to a sheet with no facts', () => {
    factSheetService.getSheet.and.returnValue(of(EMPTY));
    create({ factSheetId: '9' });
    expect(query('.cold-start-banner')).not.toBeNull();

    query('.show-active-sheet-btn')!.click();
    fixture.detectChanges();

    expect(component.showColdStartBanner).toBeFalse();
    expect(query('.cold-start-banner')).toBeNull();
  });
});
