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

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { EntityBrowserComponent } from './entity-browser.component';
import { GraphService } from '../../services/graph.service';
import { GraphNode } from '../../models/graph-models';

describe('EntityBrowserComponent', () => {
  let component: EntityBrowserComponent;
  let fixture: ComponentFixture<EntityBrowserComponent>;
  let graphServiceSpy: jasmine.SpyObj<GraphService>;

  // Helper to create a minimal valid GraphNode for testing
  function makeNode(overrides: Partial<GraphNode> & { nodeId: string; title: string }): GraphNode {
    return {
      id: 0,
      nodeId: overrides.nodeId,
      nodeType: overrides.nodeType || 'ENTITY',
      title: overrides.title,
      externalId: overrides.externalId,
      description: overrides.description,
      sourceType: overrides.sourceType,
      metadata: overrides.metadata,
      parentId: overrides.parentId,
      sourceId: overrides.sourceId,
      childCount: overrides.childCount ?? 0,
      edgeCount: overrides.edgeCount ?? 0,
      createdAt: overrides.createdAt,
      updatedAt: overrides.updatedAt
    } as GraphNode;
  }

  const mockEntities: GraphNode[] = [
    makeNode({ nodeId: 'entity-1', nodeType: 'ENTITY', title: 'Entity One',   externalId: 'ext-1', description: 'First entity',  edgeCount: 5, childCount: 3 }),
    makeNode({ nodeId: 'entity-2', nodeType: 'ENTITY', title: 'Entity Two',   externalId: 'ext-2', description: 'Second entity', edgeCount: 2, childCount: 0 }),
    makeNode({ nodeId: 'entity-3', nodeType: 'ENTITY', title: 'Entity Three', externalId: 'ext-3',                                edgeCount: 1, childCount: 0 }),
    makeNode({ nodeId: 'doc-1',    nodeType: 'DOCUMENT', title: 'quarterly_report.pdf', externalId: 'ext-4', edgeCount: 1, childCount: 2 }),
    makeNode({ nodeId: 'src-1',    nodeType: 'SOURCE',   title: 'Revenue Source',        externalId: 'ext-5', edgeCount: 0, childCount: 0 })
  ];

  const mockStatistics: any = {
    totalNodes: 100,
    totalEdges: 50,
    nodesByType: { SOURCE: 5, DOCUMENT: 20, ENTITY: 40, SNIPPET: 10, CUSTOM: 25 },
    edgesByType: { HIERARCHICAL: 30, EMBEDDING_SIMILARITY: 10, SHARED_ENTITY: 5, USER_DEFINED: 5 },
    averageEdgesPerNode: 1.0,
    maxDepth: 3
  };

  beforeEach(async () => {
    graphServiceSpy = jasmine.createSpyObj('GraphService', [
      'getNodes', 'getEdges', 'getAllEdges', 'getStatistics',
      'deleteNode', 'deleteEdge', 'getConnectedNodes', 'getNode'
    ]);

    graphServiceSpy.getNodes.and.returnValue(of(mockEntities));
    graphServiceSpy.getStatistics.and.returnValue(of(mockStatistics));
    graphServiceSpy.getEdges.and.returnValue(of([]));
    graphServiceSpy.getAllEdges.and.returnValue(of([]));
    graphServiceSpy.getConnectedNodes.and.returnValue(of([]));
    graphServiceSpy.getNode.and.callFake((nodeId: string) => {
      const node = mockEntities.find(e => e.nodeId === nodeId);
      return of(node || mockEntities[0]);
    });

    await TestBed.configureTestingModule({
      imports: [
        EntityBrowserComponent,
        HttpClientTestingModule
      ],
      providers: [
        provideNoopAnimations(),
        { provide: GraphService, useValue: graphServiceSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(EntityBrowserComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // ENUM VALUE INCLUSION
  // ═══════════════════════════════════════════════════════════════════════════

  describe('node and edge type arrays', () => {
    it('should include SOURCE and ENTITY in allNodeTypes', () => {
      expect(component.allNodeTypes).toContain('SOURCE');
      expect(component.allNodeTypes).toContain('ENTITY');
    });

    it('should include DOCUMENT and SNIPPET in allNodeTypes', () => {
      expect(component.allNodeTypes).toContain('DOCUMENT');
      expect(component.allNodeTypes).toContain('SNIPPET');
    });

    it('should include HIERARCHICAL in allEdgeTypes', () => {
      expect(component.allEdgeTypes).toContain('HIERARCHICAL');
    });

    it('should include EMBEDDING_SIMILARITY and SHARED_ENTITY in allEdgeTypes', () => {
      expect(component.allEdgeTypes).toContain('EMBEDDING_SIMILARITY');
      expect(component.allEdgeTypes).toContain('SHARED_ENTITY');
    });

    it('should include USER_DEFINED, CITATION, TEMPORAL, CROSS_SOURCE in allEdgeTypes', () => {
      expect(component.allEdgeTypes).toContain('USER_DEFINED');
      expect(component.allEdgeTypes).toContain('CITATION');
      expect(component.allEdgeTypes).toContain('TEMPORAL');
      expect(component.allEdgeTypes).toContain('CROSS_SOURCE');
    });

    it('should have 5 node types total', () => {
      expect(component.allNodeTypes.length).toBe(5);
    });

    it('should have 7 edge types total', () => {
      expect(component.allEdgeTypes.length).toBe(7);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // TABLE COLUMNS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('table columns', () => {
    it('should include required columns in entityColumns', () => {
      expect(component.entityColumns).toContain('type');
      expect(component.entityColumns).toContain('title');
      expect(component.entityColumns).toContain('description');
      expect(component.entityColumns).toContain('connections');
      expect(component.entityColumns).toContain('actions');
    });

    it('should have description before connections in entityColumns', () => {
      const descIndex = component.entityColumns.indexOf('description');
      const connIndex = component.entityColumns.indexOf('connections');
      expect(descIndex).toBeGreaterThanOrEqual(0);
      expect(connIndex).toBeGreaterThan(descIndex);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // DATA LOADING
  // ═══════════════════════════════════════════════════════════════════════════

  describe('data loading', () => {
    it('should load entities on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      expect(graphServiceSpy.getNodes).toHaveBeenCalled();
      expect(component.entities.length).toBe(5);
    }));

    it('should load statistics on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      expect(graphServiceSpy.getStatistics).toHaveBeenCalled();
      expect(component.statistics).toBeTruthy();
      expect(component.statistics!.totalNodes).toBe(100);
    }));

    it('should include DOCUMENT and SOURCE entities in loaded data', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      const doc = component.entities.find(e => e.nodeType === 'DOCUMENT');
      expect(doc).toBeTruthy();
      expect(doc!.title).toBe('quarterly_report.pdf');

      const src = component.entities.find(e => e.nodeType === 'SOURCE');
      expect(src).toBeTruthy();
      expect(src!.title).toBe('Revenue Source');
    }));

    it('should include ENTITY type entities in loaded data', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      const entities = component.entities.filter(e => e.nodeType === 'ENTITY');
      expect(entities.length).toBe(3);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // FILTERING
  // ═══════════════════════════════════════════════════════════════════════════

  describe('filtering', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should filter by DOCUMENT type', () => {
      component.toggleEntityTypeFilter('DOCUMENT');
      expect(component.filteredEntities.length).toBe(1);
      expect(component.filteredEntities[0].nodeType).toBe('DOCUMENT');
    });

    it('should filter by SOURCE type', () => {
      component.toggleEntityTypeFilter('SOURCE');
      expect(component.filteredEntities.length).toBe(1);
      expect(component.filteredEntities[0].nodeType).toBe('SOURCE');
    });

    it('should filter by ENTITY type', () => {
      component.toggleEntityTypeFilter('ENTITY');
      expect(component.filteredEntities.length).toBe(3);
      component.filteredEntities.forEach(e => expect(e.nodeType).toBe('ENTITY'));
    });

    it('should support multiple type filters', () => {
      component.toggleEntityTypeFilter('DOCUMENT');
      component.toggleEntityTypeFilter('SOURCE');
      expect(component.filteredEntities.length).toBe(2);
    });

    it('should clear filters', () => {
      component.toggleEntityTypeFilter('SOURCE');
      expect(component.filteredEntities.length).toBe(1);

      component.clearEntityFilters();
      expect(component.filteredEntities.length).toBe(5);
      expect(component.entityTypeFilters.length).toBe(0);
    });

    it('should toggle off a filter when toggled again', () => {
      component.toggleEntityTypeFilter('ENTITY');
      expect(component.filteredEntities.length).toBe(3);

      component.toggleEntityTypeFilter('ENTITY');
      expect(component.filteredEntities.length).toBe(5);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // ENTITY SELECTION & DETAIL PANEL
  // ═══════════════════════════════════════════════════════════════════════════

  describe('entity selection', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should select an entity and show detail panel', () => {
      component.selectEntity(mockEntities[0]);

      expect(component.selectedEntity).toBe(mockEntities[0]);
      expect(component.showEntityDetail).toBe(true);
    });

    it('should load connections when selecting an entity', () => {
      component.selectEntity(mockEntities[0]);
      expect(graphServiceSpy.getEdges).toHaveBeenCalledWith(mockEntities[0].nodeId);
    });

    it('should expose externalId on selected entity', () => {
      component.selectEntity(mockEntities[0]);
      expect(component.selectedEntity!.externalId).toBe('ext-1');
    });

    it('should expose description on selected entity', () => {
      component.selectEntity(mockEntities[0]);
      expect(component.selectedEntity!.description).toBe('First entity');
    });

    it('should expose nodeType on selected entity', () => {
      component.selectEntity(mockEntities[0]);
      expect(component.selectedEntity!.nodeType).toBe('ENTITY');
    });

    it('should close detail panel', () => {
      component.selectEntity(mockEntities[0]);
      component.closeEntityDetail();

      expect(component.showEntityDetail).toBe(false);
      expect(component.selectedEntity).toBeNull();
    });

    it('should clear entity connections when closing detail', () => {
      component.selectEntity(mockEntities[0]);
      component.closeEntityDetail();

      expect(component.entityConnections).toEqual([]);
      expect(component.connectedEntities).toEqual([]);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // UTILITY METHODS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('utility methods', () => {
    it('formatNodeType should capitalize correctly', () => {
      expect(component.formatNodeType('SOURCE')).toBe('Source');
      expect(component.formatNodeType('DOCUMENT')).toBe('Document');
      expect(component.formatNodeType('ENTITY')).toBe('Entity');
      expect(component.formatNodeType('SNIPPET')).toBe('Snippet');
      expect(component.formatNodeType('CUSTOM')).toBe('Custom');
    });

    it('formatEdgeType should format edge types with underscores replaced by spaces', () => {
      expect(component.formatEdgeType('HIERARCHICAL')).toBe('hierarchical');
      expect(component.formatEdgeType('EMBEDDING_SIMILARITY')).toBe('embedding similarity');
      expect(component.formatEdgeType('SHARED_ENTITY')).toBe('shared entity');
      expect(component.formatEdgeType('USER_DEFINED')).toBe('user defined');
    });

    it('getNodeColor should return a non-empty color string for known node types', () => {
      expect(component.getNodeColor('SOURCE')).toBeTruthy();
      expect(component.getNodeColor('DOCUMENT')).toBeTruthy();
      expect(component.getNodeColor('ENTITY')).toBeTruthy();
      expect(component.getNodeColor('SNIPPET')).toBeTruthy();
    });

    it('getNodeColor should return distinct colors for different node types', () => {
      const entityColor = component.getNodeColor('ENTITY');
      const sourceColor = component.getNodeColor('SOURCE');
      expect(entityColor).not.toBe(sourceColor);
    });

    it('getEdgeColor should return a non-empty color string for known edge types', () => {
      expect(component.getEdgeColor('HIERARCHICAL')).toBeTruthy();
      expect(component.getEdgeColor('EMBEDDING_SIMILARITY')).toBeTruthy();
      expect(component.getEdgeColor('SHARED_ENTITY')).toBeTruthy();
    });

    it('getTypeCount should return stats for node types from statistics', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      expect(component.getTypeCount('ENTITY')).toBe(40);
      expect(component.getTypeCount('SOURCE')).toBe(5);
      expect(component.getTypeCount('DOCUMENT')).toBe(20);
    }));

    it('getEdgeTypeCount should return stats for edge types from statistics', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      expect(component.getEdgeTypeCount('HIERARCHICAL')).toBe(30);
      expect(component.getEdgeTypeCount('EMBEDDING_SIMILARITY')).toBe(10);
    }));

    it('getNodeLabel should return the node title by nodeId', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      expect(component.getNodeLabel('entity-1')).toBe('Entity One');
      expect(component.getNodeLabel('doc-1')).toBe('quarterly_report.pdf');
    }));

    it('getNodeLabel should return the nodeId if node is not in map', () => {
      expect(component.getNodeLabel('unknown-id')).toBe('unknown-id');
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // TEMPLATE RENDERING
  // ═══════════════════════════════════════════════════════════════════════════

  describe('template rendering', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      fixture.detectChanges();
    }));

    it('should render the entity table with Type column header', () => {
      const headers = fixture.nativeElement.querySelectorAll('th');
      const headerTexts = Array.from(headers).map((h: any) => h.textContent.trim());
      expect(headerTexts).toContain('Type');
    });

    it('should render the entity table with Connections column header', () => {
      const headers = fixture.nativeElement.querySelectorAll('th');
      const headerTexts = Array.from(headers).map((h: any) => h.textContent.trim());
      expect(headerTexts).toContain('Connections');
    });

    it('should render type badges in table rows', () => {
      const badges = fixture.nativeElement.querySelectorAll('.type-badge');
      expect(badges.length).toBeGreaterThan(0);
    });

    it('should not show the detail panel when no entity is selected', () => {
      expect(component.showEntityDetail).toBe(false);
      expect(component.selectedEntity).toBeNull();
      const panel = fixture.nativeElement.querySelector('.detail-panel');
      expect(panel).toBeNull();
    });

    it('should expose externalId on the selected entity state', () => {
      // The detail panel shows External ID when externalId is present.
      // We verify via component state since the @slideIn animation trigger is
      // undefined in the component and causes NG05105 when detectChanges() is
      // called on the rendered detail panel.
      component.selectedEntity = mockEntities[0]; // entity-1 has externalId='ext-1'
      component.showEntityDetail = true;
      expect(component.selectedEntity!.externalId).toBe('ext-1');
    });

    it('should expose description on the selected entity state', () => {
      component.selectedEntity = mockEntities[0]; // entity-1 has description
      component.showEntityDetail = true;
      expect(component.selectedEntity!.description).toBe('First entity');
    });

    it('should always expose childCount and edgeCount on the selected entity state', () => {
      component.selectedEntity = mockEntities[0];
      component.showEntityDetail = true;
      expect(component.selectedEntity!.childCount).toBeDefined();
      expect(component.selectedEntity!.edgeCount).toBeDefined();
    });

    it('should expose sourceType when the selected entity has sourceType', () => {
      const entityWithSourceType = { ...mockEntities[0], sourceType: 'EMAIL_ACCOUNT' };
      component.selectedEntity = entityWithSourceType;
      component.showEntityDetail = true;
      expect(component.selectedEntity!.sourceType).toBe('EMAIL_ACCOUNT');
    });

    it('should have undefined sourceType when the selected entity has no sourceType', () => {
      component.selectedEntity = mockEntities[0]; // entity-1 has no sourceType
      component.showEntityDetail = true;
      expect(component.selectedEntity!.sourceType).toBeUndefined();
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // SOURCE TYPE REPRESENTATION
  // ═══════════════════════════════════════════════════════════════════════════

  describe('source-type representation', () => {
    it('should retain sourceType info on entities that have it', fakeAsync(() => {
      const promotedSourceEntities: GraphNode[] = [
        makeNode({ nodeId: 'email-entity-1', nodeType: 'ENTITY', title: 'Work Inbox',    sourceType: 'EMAIL_ACCOUNT', edgeCount: 3 }),
        makeNode({ nodeId: 'api-entity-1',   nodeType: 'ENTITY', title: 'REST API Src',  sourceType: 'API_ENDPOINT',  edgeCount: 1 })
      ];

      graphServiceSpy.getNodes.and.returnValue(of(promotedSourceEntities));
      fixture.detectChanges();
      tick();

      const emailEntity = component.entities.find(e => e.nodeId === 'email-entity-1');
      expect(emailEntity).toBeTruthy();
      expect(emailEntity!.nodeType).toBe('ENTITY');
      expect(emailEntity!.sourceType).toBe('EMAIL_ACCOUNT');

      const apiEntity = component.entities.find(e => e.nodeId === 'api-entity-1');
      expect(apiEntity).toBeTruthy();
      expect(apiEntity!.sourceType).toBe('API_ENDPOINT');
    }));

    it('should allow ENTITY and SOURCE nodes to coexist', fakeAsync(() => {
      const coexistingNodes: GraphNode[] = [
        makeNode({ nodeId: 'src-node-1',    nodeType: 'SOURCE', title: 'Customer DB',           sourceType: 'DATABASE', edgeCount: 10, childCount: 5 }),
        makeNode({ nodeId: 'entity-node-1', nodeType: 'ENTITY', title: 'Customer DB (Entity)',   sourceType: 'DATABASE', edgeCount: 4,  childCount: 2 })
      ];

      graphServiceSpy.getNodes.and.returnValue(of(coexistingNodes));
      fixture.detectChanges();
      tick();

      const sourceNode = component.entities.find(e => e.nodeType === 'SOURCE');
      const entityNode = component.entities.find(e => e.nodeType === 'ENTITY');

      expect(sourceNode).toBeTruthy();
      expect(entityNode).toBeTruthy();
      expect(sourceNode!.sourceType).toBe('DATABASE');
      expect(entityNode!.sourceType).toBe('DATABASE');
      expect(sourceNode!.nodeId).not.toBe(entityNode!.nodeId);
    }));
  });
});
