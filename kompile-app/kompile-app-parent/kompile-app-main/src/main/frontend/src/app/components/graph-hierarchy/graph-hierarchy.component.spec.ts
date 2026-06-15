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

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick
} from '@angular/core/testing';
import { NO_ERRORS_SCHEMA, SimpleChange } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { GraphHierarchyComponent } from './graph-hierarchy.component';
import { GraphService } from '../../services/graph.service';
import { GraphNode, HierarchyTreeNode, NODE_COLORS } from '../../models/graph-models';

// ─────────────────────────────────────────────────────────────────────────────
// Shared mock data
// ─────────────────────────────────────────────────────────────────────────────

const mockGraphNodes: GraphNode[] = [
  {
    id: 1,
    nodeId: 'src-1',
    nodeType: 'SOURCE',
    title: 'Source A',
    description: 'First source',
    childCount: 3,
    edgeCount: 2
  } as GraphNode,
  {
    id: 2,
    nodeId: 'src-2',
    nodeType: 'SOURCE',
    title: 'Source B',
    description: 'Second source',
    childCount: 1,
    edgeCount: 1
  } as GraphNode
];

const mockHierarchyTree: HierarchyTreeNode = {
  id: 'src-1',
  nodeId: 'src-1',
  type: 'SOURCE',
  nodeType: 'SOURCE',
  label: 'Source A',
  title: 'Source A',
  description: 'First source',
  childCount: 2,
  edgeCount: 2,
  depth: 0,
  children: [
    {
      id: 'doc-1',
      nodeId: 'doc-1',
      type: 'DOCUMENT',
      nodeType: 'DOCUMENT',
      label: 'Doc 1',
      title: 'Doc 1',
      childCount: 1,
      edgeCount: 1,
      depth: 1,
      children: [],
      hasMore: true
    },
    {
      id: 'doc-2',
      nodeId: 'doc-2',
      type: 'DOCUMENT',
      nodeType: 'DOCUMENT',
      label: 'Doc 2',
      title: 'Doc 2',
      childCount: 0,
      edgeCount: 0,
      depth: 1,
      children: [],
      hasMore: false
    }
  ],
  hasMore: false
};

const mockAncestors: GraphNode[] = [
  {
    id: 10,
    nodeId: 'root-1',
    nodeType: 'SOURCE',
    title: 'Root Source',
    childCount: 5,
    edgeCount: 3
  } as GraphNode,
  {
    id: 11,
    nodeId: 'doc-parent',
    nodeType: 'DOCUMENT',
    title: 'Parent Document',
    childCount: 2,
    edgeCount: 1
  } as GraphNode
];

// ─────────────────────────────────────────────────────────────────────────────
// Helper to build the mapped HierarchyTreeNode[] from mockGraphNodes, mirroring
// what loadRootNodes() produces, so tests can verify dataSource contents.
// ─────────────────────────────────────────────────────────────────────────────
function expectedTreeNodes(): HierarchyTreeNode[] {
  return mockGraphNodes.map(n => ({
    id: n.nodeId,
    nodeId: n.nodeId,
    type: n.nodeType,
    nodeType: n.nodeType,
    label: n.title,
    title: n.title,
    description: n.description,
    childCount: n.childCount,
    edgeCount: n.edgeCount,
    confidence: n.confidence,
    isComposite: n.isComposite,
    subGraphId: n.subGraphId,
    depth: 0,
    children: [],
    hasMore: (n.childCount || 0) > 0
  }));
}

// ─────────────────────────────────────────────────────────────────────────────
// Test suite
// ─────────────────────────────────────────────────────────────────────────────

describe('GraphHierarchyComponent', () => {
  let component: GraphHierarchyComponent;
  let fixture: ComponentFixture<GraphHierarchyComponent>;
  let graphServiceSpy: jasmine.SpyObj<GraphService>;

  beforeEach(async () => {
    graphServiceSpy = jasmine.createSpyObj<GraphService>('GraphService', [
      'getNodes',
      'getHierarchy',
      'getAncestors',
      'searchNodes'
    ]);

    // Default happy-path stubs — set BEFORE first detectChanges()
    graphServiceSpy.getNodes.and.returnValue(of(mockGraphNodes));
    graphServiceSpy.getHierarchy.and.returnValue(of(mockHierarchyTree));
    graphServiceSpy.getAncestors.and.returnValue(of(mockAncestors));
    graphServiceSpy.searchNodes.and.returnValue(of(mockGraphNodes));

    await TestBed.configureTestingModule({
      imports: [GraphHierarchyComponent, NoopAnimationsModule],
      providers: [{ provide: GraphService, useValue: graphServiceSpy }],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(GraphHierarchyComponent);
    component = fixture.componentInstance;
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 1. Initialization
  // ═══════════════════════════════════════════════════════════════════════════

  describe('initialization', () => {
    it('should create the component', () => {
      expect(component).toBeTruthy();
    });

    it('should have default startNodeType of SOURCE', () => {
      expect(component.startNodeType).toBe('SOURCE');
    });

    it('should start with loading false', () => {
      // Before detectChanges ngOnInit has not run yet
      expect(component.loading).toBe(false);
    });

    it('should start with empty searchQuery', () => {
      expect(component.searchQuery).toBe('');
    });

    it('should start with null selectedNode', () => {
      expect(component.selectedNode).toBeNull();
    });

    it('should start with empty ancestors array', () => {
      expect(component.ancestors).toEqual([]);
    });

    it('should have treeControl initialized', () => {
      expect(component.treeControl).toBeDefined();
    });

    it('should have dataSource initialized', () => {
      expect(component.dataSource).toBeDefined();
    });

    it('should call loadRootNodes on ngOnInit', fakeAsync(() => {
      fixture.detectChanges(); // triggers ngOnInit
      tick();

      expect(graphServiceSpy.getNodes).toHaveBeenCalledWith('SOURCE', undefined, 100);
    }));

    it('should set dataSource.data after ngOnInit completes', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      expect(component.dataSource.data.length).toBe(2);
    }));

    it('should set loading back to false after successful load', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      expect(component.loading).toBe(false);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 2. loadRootNodes
  // ═══════════════════════════════════════════════════════════════════════════

  describe('loadRootNodes()', () => {
    it('should set loading to false after data is received', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      component.loadRootNodes();
      tick();

      expect(component.loading).toBe(false);
    }));

    it('should reset ancestors on each call', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      component.ancestors = mockAncestors;
      component.loadRootNodes();
      tick();

      expect(component.ancestors).toEqual([]);
    }));

    it('should reset selectedNode on each call', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      component.selectedNode = mockHierarchyTree;
      component.loadRootNodes();
      tick();

      expect(component.selectedNode).toBeNull();
    }));

    it('should map GraphNode[] to HierarchyTreeNode[] correctly', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      const nodes = component.dataSource.data;
      expect(nodes[0].id).toBe('src-1');
      expect(nodes[0].label).toBe('Source A');
      expect(nodes[0].type).toBe('SOURCE');
      expect(nodes[0].depth).toBe(0);
      expect(nodes[0].children).toEqual([]);
    }));

    it('should set hasMore = true when childCount > 0', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      const node = component.dataSource.data[0]; // childCount: 3
      expect(node.hasMore).toBe(true);
    }));

    it('should set hasMore = false when childCount is 0', fakeAsync(() => {
      graphServiceSpy.getNodes.and.returnValue(of([
        { ...mockGraphNodes[0], childCount: 0 } as GraphNode
      ]));

      fixture.detectChanges();
      tick();

      expect(component.dataSource.data[0].hasMore).toBe(false);
    }));

    it('should set dataSource.data to [] when getNodes returns empty array', fakeAsync(() => {
      graphServiceSpy.getNodes.and.returnValue(of([]));

      fixture.detectChanges();
      tick();

      expect(component.dataSource.data).toEqual([]);
    }));

    it('should set dataSource.data to [] and loading to false on error', fakeAsync(() => {
      graphServiceSpy.getNodes.and.returnValue(throwError(() => new Error('network error')));

      fixture.detectChanges();
      tick();

      expect(component.dataSource.data).toEqual([]);
      expect(component.loading).toBe(false);
    }));

    it('should use the current startNodeType when calling getNodes', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      component.startNodeType = 'ENTITY';
      component.loadRootNodes();
      tick();

      expect(graphServiceSpy.getNodes).toHaveBeenCalledWith('ENTITY', undefined, 100);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 3. toggleNode — expand, collapse, lazy-load
  // ═══════════════════════════════════════════════════════════════════════════

  describe('toggleNode()', () => {
    let mockEvent: Event;
    let nodeWithMore: HierarchyTreeNode;
    let nodeWithoutMore: HierarchyTreeNode;

    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();

      mockEvent = { stopPropagation: jasmine.createSpy('stopPropagation') } as any;

      nodeWithMore = {
        id: 'src-1',
        nodeId: 'src-1',
        type: 'SOURCE',
        nodeType: 'SOURCE',
        label: 'Source A',
        depth: 0,
        children: [],
        hasMore: true
      };

      nodeWithoutMore = {
        id: 'doc-2',
        nodeId: 'doc-2',
        type: 'DOCUMENT',
        nodeType: 'DOCUMENT',
        label: 'Doc 2',
        depth: 1,
        children: [],
        hasMore: false
      };
    }));

    it('should call event.stopPropagation()', fakeAsync(() => {
      component.toggleNode(nodeWithoutMore, mockEvent);
      tick();

      expect(mockEvent.stopPropagation).toHaveBeenCalled();
    }));

    it('should collapse an already-expanded node', fakeAsync(() => {
      component.treeControl.expand(nodeWithoutMore);
      component.toggleNode(nodeWithoutMore, mockEvent);
      tick();

      expect(component.treeControl.isExpanded(nodeWithoutMore)).toBe(false);
    }));

    it('should expand a node that has existing children (no lazy-load)', fakeAsync(() => {
      const nodeWithChildren: HierarchyTreeNode = {
        id: 'loaded',
        nodeId: 'loaded',
        type: 'SOURCE',
        nodeType: 'SOURCE',
        label: 'Loaded',
        depth: 0,
        children: [nodeWithoutMore],
        hasMore: false
      };

      component.toggleNode(nodeWithChildren, mockEvent);
      tick();

      expect(component.treeControl.isExpanded(nodeWithChildren)).toBe(true);
      // getHierarchy should NOT have been called because children already loaded
      expect(graphServiceSpy.getHierarchy).not.toHaveBeenCalled();
    }));

    it('should lazy-load children when node has no children but hasMore=true', fakeAsync(() => {
      graphServiceSpy.getHierarchy.and.returnValue(of(mockHierarchyTree));

      component.toggleNode(nodeWithMore, mockEvent);
      tick();

      expect(graphServiceSpy.getHierarchy).toHaveBeenCalledWith('src-1', 2);
    }));

    it('should set node.children from tree.children after lazy-load', fakeAsync(() => {
      graphServiceSpy.getHierarchy.and.returnValue(of(mockHierarchyTree));

      component.toggleNode(nodeWithMore, mockEvent);
      tick();

      expect(nodeWithMore.children!.length).toBe(2);
    }));

    it('should set node.hasMore = false after lazy-load', fakeAsync(() => {
      graphServiceSpy.getHierarchy.and.returnValue(of(mockHierarchyTree));

      component.toggleNode(nodeWithMore, mockEvent);
      tick();

      expect(nodeWithMore.hasMore).toBe(false);
    }));

    it('should expand the node after successful lazy-load', fakeAsync(() => {
      graphServiceSpy.getHierarchy.and.returnValue(of(mockHierarchyTree));

      component.toggleNode(nodeWithMore, mockEvent);
      tick();

      expect(component.treeControl.isExpanded(nodeWithMore)).toBe(true);
    }));

    it('should refresh dataSource.data array reference after lazy-load', fakeAsync(() => {
      component.dataSource.data = [nodeWithMore];
      const originalRef = component.dataSource.data;

      graphServiceSpy.getHierarchy.and.returnValue(of(mockHierarchyTree));
      component.toggleNode(nodeWithMore, mockEvent);
      tick();

      // dataSource.data must be a new array reference to trigger CDK tree refresh
      expect(component.dataSource.data).not.toBe(originalRef);
    }));

    it('should expand a node with no children and no hasMore directly', fakeAsync(() => {
      component.toggleNode(nodeWithoutMore, mockEvent);
      tick();

      expect(component.treeControl.isExpanded(nodeWithoutMore)).toBe(true);
      expect(graphServiceSpy.getHierarchy).not.toHaveBeenCalled();
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 4. selectNode
  // ═══════════════════════════════════════════════════════════════════════════

  describe('selectNode()', () => {
    let treeNode: HierarchyTreeNode;

    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();

      treeNode = component.dataSource.data[0];
    }));

    it('should set selectedNode to the clicked node', () => {
      component.selectNode(treeNode);

      expect(component.selectedNode).toBe(treeNode);
    });

    it('should call getAncestors with the node id', fakeAsync(() => {
      component.selectNode(treeNode);
      tick();

      expect(graphServiceSpy.getAncestors).toHaveBeenCalledWith(treeNode.id);
    }));

    it('should populate ancestors from service response', fakeAsync(() => {
      graphServiceSpy.getAncestors.and.returnValue(of(mockAncestors));

      component.selectNode(treeNode);
      tick();

      expect(component.ancestors).toEqual(mockAncestors);
    }));

    it('should set ancestors to [] when getAncestors errors', fakeAsync(() => {
      graphServiceSpy.getAncestors.and.returnValue(throwError(() => new Error('fail')));
      component.ancestors = mockAncestors;

      component.selectNode(treeNode);
      tick();

      expect(component.ancestors).toEqual([]);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 5. drillIntoNode
  // ═══════════════════════════════════════════════════════════════════════════

  describe('drillIntoNode()', () => {
    let treeNode: HierarchyTreeNode;

    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      treeNode = component.dataSource.data[0];
    }));

    it('should call getHierarchy with depth 5', fakeAsync(() => {
      component.drillIntoNode(treeNode);
      tick();

      expect(graphServiceSpy.getHierarchy).toHaveBeenCalledWith(treeNode.id, 5);
    }));

    it('should replace dataSource.data with the returned tree as a single-element array', fakeAsync(() => {
      component.drillIntoNode(treeNode);
      tick();

      expect(component.dataSource.data.length).toBe(1);
      expect(component.dataSource.data[0]).toEqual(mockHierarchyTree);
    }));

    it('should expand the drilled-into tree node', fakeAsync(() => {
      component.drillIntoNode(treeNode);
      tick();

      expect(component.treeControl.isExpanded(mockHierarchyTree)).toBe(true);
    }));

    it('should set loading to true then false after success', fakeAsync(() => {
      let loadingDuring = false;
      graphServiceSpy.getHierarchy.and.callFake(() => {
        loadingDuring = component.loading;
        return of(mockHierarchyTree);
      });

      component.drillIntoNode(treeNode);
      tick();

      expect(loadingDuring).toBe(true);
      expect(component.loading).toBe(false);
    }));

    it('should set loading to false on error', fakeAsync(() => {
      graphServiceSpy.getHierarchy.and.returnValue(throwError(() => new Error('drill error')));

      component.drillIntoNode(treeNode);
      tick();

      expect(component.loading).toBe(false);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 6. navigateToNode
  // ═══════════════════════════════════════════════════════════════════════════

  describe('navigateToNode()', () => {
    it('should call getHierarchy using node.nodeId with depth 5', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      const ancestor = mockAncestors[0];
      component.navigateToNode(ancestor);
      tick();

      expect(graphServiceSpy.getHierarchy).toHaveBeenCalledWith(ancestor.nodeId, 5);
    }));

    it('should set dataSource.data to [tree] after navigation', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      component.navigateToNode(mockAncestors[0]);
      tick();

      expect(component.dataSource.data.length).toBe(1);
      expect(component.dataSource.data[0]).toEqual(mockHierarchyTree);
    }));

    it('should expand the tree root returned by navigation', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      component.navigateToNode(mockAncestors[0]);
      tick();

      expect(component.treeControl.isExpanded(mockHierarchyTree)).toBe(true);
    }));

    it('should set loading to false on error during navigation', fakeAsync(() => {
      graphServiceSpy.getHierarchy.and.returnValue(throwError(() => new Error('nav error')));

      fixture.detectChanges();
      tick();

      component.navigateToNode(mockAncestors[0]);
      tick();

      expect(component.loading).toBe(false);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 7. searchNodes
  // ═══════════════════════════════════════════════════════════════════════════

  describe('searchNodes()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should call loadRootNodes (getNodes) when searchQuery is empty', fakeAsync(() => {
      graphServiceSpy.getNodes.calls.reset();
      component.searchQuery = '';

      component.searchNodes();
      tick();

      expect(graphServiceSpy.getNodes).toHaveBeenCalled();
      expect(graphServiceSpy.searchNodes).not.toHaveBeenCalled();
    }));

    it('should call loadRootNodes when searchQuery is only whitespace', fakeAsync(() => {
      graphServiceSpy.getNodes.calls.reset();
      component.searchQuery = '   ';

      component.searchNodes();
      tick();

      expect(graphServiceSpy.getNodes).toHaveBeenCalled();
    }));

    it('should call graphService.searchNodes with trimmed query when query is non-empty', fakeAsync(() => {
      component.searchQuery = 'source alpha';

      component.searchNodes();
      tick();

      expect(graphServiceSpy.searchNodes).toHaveBeenCalledWith('source alpha', undefined, 50);
    }));

    it('should populate dataSource.data with search results', fakeAsync(() => {
      const searchResult: GraphNode[] = [mockGraphNodes[0]];
      graphServiceSpy.searchNodes.and.returnValue(of(searchResult));
      component.searchQuery = 'alpha';

      component.searchNodes();
      tick();

      expect(component.dataSource.data.length).toBe(1);
      expect(component.dataSource.data[0].id).toBe('src-1');
    }));

    it('should set loading to false after successful search', fakeAsync(() => {
      component.searchQuery = 'test';

      component.searchNodes();
      tick();

      expect(component.loading).toBe(false);
    }));

    it('should set loading to false after failed search', fakeAsync(() => {
      graphServiceSpy.searchNodes.and.returnValue(throwError(() => new Error('search fail')));
      component.searchQuery = 'test';

      component.searchNodes();
      tick();

      expect(component.loading).toBe(false);
    }));

    it('should map search results to HierarchyTreeNodes with depth 0', fakeAsync(() => {
      graphServiceSpy.searchNodes.and.returnValue(of(mockGraphNodes));
      component.searchQuery = 'source';

      component.searchNodes();
      tick();

      component.dataSource.data.forEach(node => {
        expect(node.depth).toBe(0);
        expect(node.children).toEqual([]);
      });
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 8. getNodeColor
  // ═══════════════════════════════════════════════════════════════════════════

  describe('getNodeColor()', () => {
    it('should return the color for SOURCE from NODE_COLORS', () => {
      expect(component.getNodeColor('SOURCE')).toBe(NODE_COLORS['SOURCE']);
    });

    it('should return the color for DOCUMENT', () => {
      expect(component.getNodeColor('DOCUMENT')).toBe(NODE_COLORS['DOCUMENT']);
    });

    it('should return the color for ENTITY', () => {
      expect(component.getNodeColor('ENTITY')).toBe(NODE_COLORS['ENTITY']);
    });

    it('should return the color for SNIPPET', () => {
      expect(component.getNodeColor('SNIPPET')).toBe(NODE_COLORS['SNIPPET']);
    });

    it('should return the color for ATTACHMENT', () => {
      expect(component.getNodeColor('ATTACHMENT')).toBe(NODE_COLORS['ATTACHMENT']);
    });

    it('should return the color for TABLE', () => {
      expect(component.getNodeColor('TABLE')).toBe(NODE_COLORS['TABLE']);
    });

    it('should return fallback color #607D8B for unknown type', () => {
      expect(component.getNodeColor('UNKNOWN_TYPE')).toBe('#607D8B');
    });

    it('should return fallback color for null-ish type', () => {
      expect(component.getNodeColor(undefined as any)).toBe('#607D8B');
    });

    it('should be case-insensitive (lower-case input)', () => {
      expect(component.getNodeColor('source')).toBe(NODE_COLORS['SOURCE']);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 9. getNodeIcon
  // ═══════════════════════════════════════════════════════════════════════════

  describe('getNodeIcon()', () => {
    it('should return "dns" for SOURCE', () => {
      expect(component.getNodeIcon('SOURCE')).toBe('dns');
    });

    it('should return "description" for DOCUMENT', () => {
      expect(component.getNodeIcon('DOCUMENT')).toBe('description');
    });

    it('should return "short_text" for SNIPPET', () => {
      expect(component.getNodeIcon('SNIPPET')).toBe('short_text');
    });

    it('should return "label" for ENTITY', () => {
      expect(component.getNodeIcon('ENTITY')).toBe('label');
    });

    it('should return "attach_file" for ATTACHMENT', () => {
      expect(component.getNodeIcon('ATTACHMENT')).toBe('attach_file');
    });

    it('should return "table_chart" for TABLE', () => {
      expect(component.getNodeIcon('TABLE')).toBe('table_chart');
    });

    it('should return "extension" for CUSTOM', () => {
      expect(component.getNodeIcon('CUSTOM')).toBe('extension');
    });

    it('should return "circle" for unknown type', () => {
      expect(component.getNodeIcon('SOMETHING_ELSE')).toBe('circle');
    });

    it('should return "circle" for undefined type', () => {
      expect(component.getNodeIcon(undefined as any)).toBe('circle');
    });

    it('should be case-insensitive (lower-case input)', () => {
      expect(component.getNodeIcon('entity')).toBe('label');
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 10. ngOnChanges
  // ═══════════════════════════════════════════════════════════════════════════

  describe('ngOnChanges()', () => {
    it('should call loadRootNodes when factSheetId changes', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      graphServiceSpy.getNodes.calls.reset();
      component.factSheetId = 42;
      component.ngOnChanges({
        factSheetId: new SimpleChange(null, 42, false)
      });
      tick();

      expect(graphServiceSpy.getNodes).toHaveBeenCalledWith('SOURCE', undefined, 100);
    }));

    it('should call loadRootNodes when factSheetId changes from a number to null', fakeAsync(() => {
      component.factSheetId = 1;
      fixture.detectChanges();
      tick();

      graphServiceSpy.getNodes.calls.reset();
      component.factSheetId = null;
      component.ngOnChanges({
        factSheetId: new SimpleChange(1, null, false)
      });
      tick();

      expect(graphServiceSpy.getNodes).toHaveBeenCalled();
    }));

    it('should NOT call loadRootNodes when an unrelated input changes', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      graphServiceSpy.getNodes.calls.reset();
      component.ngOnChanges({
        someOtherInput: new SimpleChange(false, true, false)
      });
      tick();

      expect(graphServiceSpy.getNodes).not.toHaveBeenCalled();
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 11. Output events
  // ═══════════════════════════════════════════════════════════════════════════

  describe('@Output events', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should emit navigateToGraph when navigateToGraph.emit() is called', () => {
      let emitted: any;
      component.navigateToGraph.subscribe((val: any) => (emitted = val));

      const node = component.dataSource.data[0];
      component.navigateToGraph.emit(node);

      expect(emitted).toBe(node);
    });

    it('should emit viewSubGraph when viewSubGraph.emit() is called', () => {
      let emitted: any;
      component.viewSubGraph.subscribe((val: any) => (emitted = val));

      const compositeNode: HierarchyTreeNode = {
        id: 'ent-1',
        nodeId: 'ent-1',
        type: 'ENTITY',
        nodeType: 'ENTITY',
        label: 'Composite Entity',
        depth: 0,
        children: [],
        isComposite: true,
        subGraphId: 'sub-1'
      };

      component.viewSubGraph.emit(compositeNode);

      expect(emitted).toBe(compositeNode);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 12. Error handling edge cases
  // ═══════════════════════════════════════════════════════════════════════════

  describe('error handling', () => {
    it('should gracefully handle getNodes returning empty on init', fakeAsync(() => {
      graphServiceSpy.getNodes.and.returnValue(of([]));

      fixture.detectChanges();
      tick();

      expect(component.dataSource.data).toEqual([]);
      expect(component.loading).toBe(false);
    }));

    it('should not throw when toggleNode lazy-load returns tree with no children', fakeAsync(() => {
      const emptyTree: HierarchyTreeNode = { ...mockHierarchyTree, children: [] };
      graphServiceSpy.getHierarchy.and.returnValue(of(emptyTree));

      fixture.detectChanges();
      tick();

      const mockEvent = { stopPropagation: jasmine.createSpy() } as any;
      const node: HierarchyTreeNode = {
        id: 'x',
        nodeId: 'x',
        type: 'DOCUMENT',
        nodeType: 'DOCUMENT',
        label: 'X',
        depth: 1,
        children: [],
        hasMore: true
      };

      expect(() => {
        component.toggleNode(node, mockEvent);
        tick();
      }).not.toThrow();
      expect(node.children).toEqual([]);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 13. Empty states
  // ═══════════════════════════════════════════════════════════════════════════

  describe('empty states', () => {
    it('should have dataSource.data empty initially before detectChanges', () => {
      expect(component.dataSource.data.length).toBe(0);
    });

    it('should show empty dataSource when getNodes returns []', fakeAsync(() => {
      graphServiceSpy.getNodes.and.returnValue(of([]));

      fixture.detectChanges();
      tick();

      expect(component.dataSource.data.length).toBe(0);
    }));

    it('should have empty ancestors when no node is selected', fakeAsync(() => {
      fixture.detectChanges();
      tick();

      // No selectNode called — ancestors should remain empty from loadRootNodes reset
      expect(component.ancestors.length).toBe(0);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 14. Composite / confidence fields preserved through mapping
  // ═══════════════════════════════════════════════════════════════════════════

  describe('composite and confidence field mapping', () => {
    it('should preserve confidence from GraphNode in mapped HierarchyTreeNode', fakeAsync(() => {
      const nodesWithConf: GraphNode[] = [
        { ...mockGraphNodes[0], confidence: 0.87, isComposite: true, subGraphId: 'sg-1' } as GraphNode
      ];
      graphServiceSpy.getNodes.and.returnValue(of(nodesWithConf));

      fixture.detectChanges();
      tick();

      const mapped = component.dataSource.data[0];
      expect(mapped.confidence).toBe(0.87);
      expect(mapped.isComposite).toBe(true);
      expect(mapped.subGraphId).toBe('sg-1');
    }));
  });
});
