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

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { GraphService } from './graph.service';
import {
  GraphNode,
  HierarchyTreeNode,
  CreateCompositeEntityRequest,
  NamedGraph,
  CreateNamedGraphRequest,
  MoveGraphResult
} from '../models/graph-models';

describe('GraphService', () => {
  let service: GraphService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [GraphService]
    });

    service = TestBed.inject(GraphService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('visualization response safety', () => {
    it('uses exactly one API prefix for reasoning layers', (done) => {
      service.getReasoningLayers(42).subscribe(result => {
        expect(result.factSheetId).toBe(42);
        expect(result.nodes).toEqual([]);
        expect(result.edges).toEqual([]);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/api/graph/42/reasoning-layers')
      );
      expect(req.request.method).toBe('GET');
      expect(req.request.url).not.toContain('/api/api/');
      req.flush({ factSheetId: 42, nodes: [], edges: [], statistics: { nodeCount: 0, edgeCount: 0 } });
    });

    it('treats an empty fact-sheet visualization response as valid', (done) => {
      service.getFactSheetVisualizationData(42).subscribe(result => {
        expect(result.nodes).toEqual([]);
        expect(result.links).toEqual([]);
        expect(result.statistics?.totalNodes).toBe(0);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/api/fact-sheets/42/graph/visualization')
      );
      expect(req.request.params.get('maxNodes')).toBe('0');
      expect(req.request.params.get('maxEdges')).toBe('0');
      req.flush({ nodes: [], edges: [], metadata: { totalNodes: 0, totalEdges: 0 } });
    });

    it('normalizes graph identifiers and drops invalid or dangling records', (done) => {
      service.getVisualizationData().subscribe(result => {
        expect(result.nodes.map(node => node.id)).toEqual(['7', 'node-b', 'node-c']);
        expect(result.links.length).toBe(2);
        expect(result.links[0].id).toBe('valid-edge');
        expect(result.links[0].source).toBe('7');
        expect(result.links[0].target).toBe('node-b');
        expect(result.links[1].id).toBe('node-b→node-c:SHARED_ENTITY');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/visualization')
      );
      req.flush({
        nodes: [
          { id: 7, type: 'ENTITY', label: 'Numeric ID' },
          { id: ' node-b ', type: 'ENTITY', label: 'Trimmed ID' },
          { id: 'node-b', type: 'ENTITY', label: 'Duplicate ID' },
          { nodeId: 'node-c', type: 'ENTITY', label: 'Legacy ID field' },
          { id: '   ', type: 'ENTITY', label: 'Blank ID' }
        ],
        links: [
          {
            id: 'valid-edge',
            source: 7,
            target: { nodeId: ' node-b ' },
            type: 'USER_DEFINED',
            weight: 1
          },
          {
            id: 'dangling-edge',
            source: 'node-b',
            target: 'missing-node',
            type: 'USER_DEFINED',
            weight: 1
          },
          {
            id: 'valid-edge',
            source: 'node-b',
            target: 'node-c',
            type: 'USER_DEFINED',
            weight: 1
          },
          {
            source: 'node-b',
            target: { id: 'node-c' },
            type: 'SHARED_ENTITY',
            weight: 0.5
          }
        ]
      } as any);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // HIERARCHY OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('getHierarchy', () => {
    it('should GET hierarchy for a node with default depth', (done) => {
      const mockHierarchy: HierarchyTreeNode = {
        nodeId: 'root-1',
        nodeType: 'SOURCE',
        id: 'root-1',
        type: 'SOURCE',
        label: 'Root',
        title: 'Root Source',
        depth: 0,
        isComposite: false,
        children: [
          {
            nodeId: 'child-1',
            nodeType: 'DOCUMENT',
            id: 'child-1',
            type: 'DOCUMENT',
            label: 'Doc 1',
            title: 'Doc 1',
            depth: 1,
            isComposite: false,
            children: []
          }
        ]
      };

      service.getHierarchy('root-1').subscribe(result => {
        expect(result).toEqual(mockHierarchy);
        expect(result.children?.length).toBe(1);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/root-1/hierarchy') &&
        r.params.get('depth') === '2'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockHierarchy);
    });

    it('should pass custom depth parameter', (done) => {
      service.getHierarchy('node-1', 3).subscribe(() => done());

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/node-1/hierarchy') &&
        r.params.get('depth') === '3'
      );
      expect(req.request.method).toBe('GET');
      req.flush({});
    });
  });

  describe('getAncestors', () => {
    it('should GET ancestors for a node', (done) => {
      const mockAncestors: GraphNode[] = [
        { nodeId: 'root', nodeType: 'SOURCE', title: 'Root', externalId: 'root' } as GraphNode,
        { nodeId: 'mid', nodeType: 'DOCUMENT', title: 'Doc', externalId: 'mid' } as GraphNode,
        { nodeId: 'leaf', nodeType: 'SNIPPET', title: 'Chunk', externalId: 'leaf' } as GraphNode
      ];

      service.getAncestors('leaf').subscribe(result => {
        expect(result.length).toBe(3);
        expect(result[0].nodeId).toBe('root');
        expect(result[2].nodeId).toBe('leaf');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/leaf/ancestors')
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockAncestors);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // COMPOSITE ENTITY OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('createCompositeEntity', () => {
    it('should POST composite entity creation request', (done) => {
      const request: CreateCompositeEntityRequest = {
        parentNodeId: 'parent-1',
        externalId: 'comp-1',
        title: 'Acme Corp',
        description: 'Organization',
        confidence: 0.9,
        metadata: { industry: 'tech' }
      };

      const mockResponse: GraphNode = {
        nodeId: 'new-uuid',
        nodeType: 'ENTITY',
        title: 'Acme Corp',
        externalId: 'comp-1',
        isComposite: true,
        subGraphId: 'sub-graph-uuid',
        confidence: 0.9
      } as GraphNode;

      service.createCompositeEntity(request).subscribe(result => {
        expect(result.isComposite).toBe(true);
        expect(result.subGraphId).toBe('sub-graph-uuid');
        expect(result.confidence).toBe(0.9);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/composite')
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockResponse);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // ERROR HANDLING
  // ═══════════════════════════════════════════════════════════════════════════

  describe('error handling', () => {
    it('should handle HTTP error on getHierarchy', (done) => {
      service.getHierarchy('bad-id').subscribe({
        error: (err) => {
          expect(err).toBeTruthy();
          done();
        }
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/bad-id/hierarchy')
      );
      req.error(new ProgressEvent('error'), { status: 404 });
    });

    it('should handle HTTP error on createCompositeEntity', (done) => {
      service.createCompositeEntity({
        externalId: 'x',
        title: 'x'
      } as CreateCompositeEntityRequest).subscribe({
        error: (err) => {
          expect(err).toBeTruthy();
          done();
        }
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/composite')
      );
      req.error(new ProgressEvent('error'), { status: 500 });
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // GRAPHS OF GRAPHS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('graphs of graphs', () => {
    it('should createCompositeEntity with a parentNodeId that is itself a composite', (done) => {
      // The parent is a composite entity - verifying request body passes the composite parentNodeId
      const compositeParentId = 'composite-parent-uuid';
      const request: CreateCompositeEntityRequest = {
        parentNodeId: compositeParentId,
        externalId: 'nested-comp-1',
        title: 'Nested Composite Entity',
        description: 'A composite nested within another composite',
        confidence: 0.85,
        metadata: { level: 'nested' }
      };

      const mockResponse: GraphNode = {
        nodeId: 'nested-comp-uuid',
        nodeType: 'ENTITY',
        title: 'Nested Composite Entity',
        externalId: 'nested-comp-1',
        isComposite: true,
        subGraphId: 'nested-sub-graph-uuid',
        confidence: 0.85
      } as GraphNode;

      service.createCompositeEntity(request).subscribe(result => {
        expect(result.isComposite).toBe(true);
        expect(result.subGraphId).toBe('nested-sub-graph-uuid');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/composite')
      );
      expect(req.request.method).toBe('POST');
      // The parentNodeId in the request body points to another composite node
      expect(req.request.body.parentNodeId).toBe(compositeParentId);
      expect(req.request.body.externalId).toBe('nested-comp-1');
      req.flush(mockResponse);
    });

    it('should return composite+subGraphId fields when getHierarchy targets a composite node', (done) => {
      const mockCompositeHierarchy: HierarchyTreeNode = {
        nodeId: 'comp-root',
        nodeType: 'ENTITY',
        id: 'comp-root',
        type: 'ENTITY',
        label: 'Composite Root',
        title: 'Composite Organization',
        depth: 0,
        isComposite: true,
        subGraphId: 'org-sub-graph',
        children: [
          {
            nodeId: 'comp-child-1',
            nodeType: 'ENTITY',
            id: 'comp-child-1',
            type: 'ENTITY',
            label: 'Division A',
            title: 'Division A',
            depth: 1,
            isComposite: true,
            subGraphId: 'division-a-sub-graph',
            children: []
          },
          {
            nodeId: 'comp-child-2',
            nodeType: 'ENTITY',
            id: 'comp-child-2',
            type: 'ENTITY',
            label: 'Division B',
            title: 'Division B',
            depth: 1,
            isComposite: false,
            children: []
          }
        ]
      };

      service.getHierarchy('comp-root').subscribe(result => {
        expect(result.isComposite).toBe(true);
        expect(result.subGraphId).toBe('org-sub-graph');
        // Children can themselves be composite
        expect(result.children?.[0].isComposite).toBe(true);
        expect(result.children?.[0].subGraphId).toBe('division-a-sub-graph');
        // Non-composite child has no subGraphId
        expect(result.children?.[1].isComposite).toBe(false);
        expect(result.children?.[1].subGraphId).toBeUndefined();
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/comp-root/hierarchy') &&
        r.params.get('depth') === '2'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockCompositeHierarchy);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // NAMED GRAPH API
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Named Graph API', () => {
    const mockNamedGraph: NamedGraph = {
      graphId: 'graph-123',
      name: 'Test Graph',
      description: 'A test graph',
      nodeCount: 5,
      edgeCount: 3,
      childGraphCount: 2
    };

    const mockNamedGraphList: NamedGraph[] = [
      mockNamedGraph,
      {
        graphId: 'graph-456',
        name: 'Another Graph',
        nodeCount: 0,
        edgeCount: 0,
        childGraphCount: 0
      }
    ];

    it('should get named graphs', (done) => {
      service.getNamedGraphs().subscribe(result => {
        expect(result.length).toBe(2);
        expect(result[0].graphId).toBe('graph-123');
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/api/knowledge-graph/named-graphs') && !r.params.has('query'));
      expect(req.request.method).toBe('GET');
      req.flush(mockNamedGraphList);
    });

    it('should get named graphs with search query', (done) => {
      service.getNamedGraphs('test').subscribe(result => {
        expect(result).toBeTruthy();
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/api/knowledge-graph/named-graphs') &&
        r.params.get('query') === 'test'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockNamedGraphList);
    });

    it('should get a single named graph', (done) => {
      service.getNamedGraph('graph-123').subscribe(result => {
        expect(result.graphId).toBe('graph-123');
        expect(result.name).toBe('Test Graph');
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/api/knowledge-graph/named-graphs/graph-123'));
      expect(req.request.method).toBe('GET');
      req.flush(mockNamedGraph);
    });

    it('should create a named graph', (done) => {
      const createRequest: CreateNamedGraphRequest = { name: 'Test' };

      service.createNamedGraph(createRequest).subscribe(result => {
        expect(result.graphId).toBe('graph-123');
        expect(result.name).toBe('Test Graph');
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/api/knowledge-graph/named-graphs'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(createRequest);
      req.flush(mockNamedGraph);
    });

    it('should update a named graph', (done) => {
      const updates: Partial<NamedGraph> = { name: 'Updated' };
      const updated: NamedGraph = { ...mockNamedGraph, name: 'Updated' };

      service.updateNamedGraph('graph-123', updates).subscribe(result => {
        expect(result.name).toBe('Updated');
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/api/knowledge-graph/named-graphs/graph-123'));
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(updates);
      req.flush(updated);
    });

    it('should delete a named graph', (done) => {
      service.deleteNamedGraph('graph-123').subscribe(() => done());

      const req = httpMock.expectOne(r => r.url.endsWith('/api/knowledge-graph/named-graphs/graph-123'));
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });

    it('should get child graphs', (done) => {
      const children: NamedGraph[] = [
        { graphId: 'child-1', name: 'Child 1', nodeCount: 1, edgeCount: 0, childGraphCount: 0 }
      ];

      service.getChildGraphs('parent-id').subscribe(result => {
        expect(result.length).toBe(1);
        expect(result[0].graphId).toBe('child-1');
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/api/knowledge-graph/named-graphs/parent-id/children'));
      expect(req.request.method).toBe('GET');
      req.flush(children);
    });

    it('should get graph hierarchy via getHierarchy', (done) => {
      const mockHierarchy: HierarchyTreeNode = {
        nodeId: 'root-id',
        nodeType: 'SOURCE',
        id: 'root-id',
        type: 'SOURCE',
        label: 'Root',
        children: []
      };

      service.getHierarchy('root-id', 3).subscribe(result => {
        expect(result.nodeId).toBe('root-id');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/api/knowledge-graph/nodes/root-id/hierarchy') &&
        r.params.get('depth') === '3'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockHierarchy);
    });

    it('should get graph ancestors via getAncestors', (done) => {
      const ancestors: GraphNode[] = [
        { id: 1, nodeId: 'root', nodeType: 'SOURCE', title: 'Root', childCount: 1, edgeCount: 0 },
        { id: 2, nodeId: 'child-id', nodeType: 'DOCUMENT', title: 'Child', childCount: 0, edgeCount: 0 }
      ];

      service.getAncestors('child-id').subscribe(result => {
        expect(result.length).toBe(2);
        expect(result[0].nodeId).toBe('root');
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/api/knowledge-graph/nodes/child-id/ancestors'));
      expect(req.request.method).toBe('GET');
      req.flush(ancestors);
    });

    it('should move a graph', (done) => {
      const moved: MoveGraphResult = { graphId: 'graph-id', newParentGraphId: 'new-parent', success: true };

      service.moveGraph('graph-id', 'new-parent').subscribe(result => {
        expect(result.newParentGraphId).toBe('new-parent');
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/api/knowledge-graph/named-graphs/graph-id/move'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ newParentGraphId: 'new-parent' });
      req.flush(moved);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // FACT CERTAINTY
  // ═══════════════════════════════════════════════════════════════════════════

  describe('fact certainty', () => {
    it('should include nodes with varying confidence values in getNodes response', (done) => {
      const mockNodes: GraphNode[] = [
        { nodeId: 'n-high', nodeType: 'ENTITY', title: 'High', externalId: 'n-high', confidence: 0.95 } as GraphNode,
        { nodeId: 'n-med', nodeType: 'ENTITY', title: 'Medium', externalId: 'n-med', confidence: 0.55 } as GraphNode,
        { nodeId: 'n-low', nodeType: 'ENTITY', title: 'Low', externalId: 'n-low', confidence: 0.2 } as GraphNode,
        { nodeId: 'n-none', nodeType: 'DOCUMENT', title: 'No Confidence', externalId: 'n-none' } as GraphNode
      ];

      service.getNodes().subscribe(result => {
        expect(result.length).toBe(4);

        const high = result.find(n => n.nodeId === 'n-high');
        expect(high?.confidence).toBe(0.95);

        const med = result.find(n => n.nodeId === 'n-med');
        expect(med?.confidence).toBe(0.55);

        const low = result.find(n => n.nodeId === 'n-low');
        expect(low?.confidence).toBe(0.2);

        const none = result.find(n => n.nodeId === 'n-none');
        expect(none?.confidence).toBeUndefined();

        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes')
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockNodes);
    });

    it('should createCompositeEntity with null confidence (unknown certainty)', (done) => {
      const request: CreateCompositeEntityRequest = {
        parentNodeId: 'parent-1',
        externalId: 'unknown-conf-1',
        title: 'Entity of Unknown Certainty',
        description: 'No confidence value assigned',
        confidence: undefined,
        metadata: {}
      };

      const mockResponse: GraphNode = {
        nodeId: 'unknown-conf-uuid',
        nodeType: 'ENTITY',
        title: 'Entity of Unknown Certainty',
        externalId: 'unknown-conf-1',
        isComposite: false,
        confidence: undefined
      } as GraphNode;

      service.createCompositeEntity(request).subscribe(result => {
        expect(result.confidence).toBeUndefined();
        expect(result.isComposite).toBe(false);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/composite')
      );
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });

    it('should createCompositeEntity with confidence=0.5 (medium certainty)', (done) => {
      const request: CreateCompositeEntityRequest = {
        parentNodeId: 'parent-2',
        externalId: 'medium-conf-1',
        title: 'Somewhat Confident Entity',
        description: 'Medium confidence composite',
        confidence: 0.5,
        metadata: { source: 'inference' }
      };

      const mockResponse: GraphNode = {
        nodeId: 'medium-conf-uuid',
        nodeType: 'ENTITY',
        title: 'Somewhat Confident Entity',
        externalId: 'medium-conf-1',
        isComposite: true,
        subGraphId: 'medium-conf-sub-graph',
        confidence: 0.5
      } as GraphNode;

      service.createCompositeEntity(request).subscribe(result => {
        expect(result.confidence).toBe(0.5);
        expect(result.isComposite).toBe(true);
        expect(result.subGraphId).toBe('medium-conf-sub-graph');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/composite')
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body.confidence).toBe(0.5);
      req.flush(mockResponse);
    });
  });

  // ── getNode ─────────────────────────────────────────────────────────────

  describe('getNode()', () => {
    it('should GET /knowledge-graph/nodes/:nodeId', (done) => {
      const mockNode: GraphNode = {
        id: 1,
        nodeId: 'test-node-1',
        nodeType: 'ENTITY',
        title: 'Test Entity',
        description: 'A test entity node',
        createdAt: '2025-01-01T00:00:00Z',
        childCount: 0,
        edgeCount: 3
      };

      service.getNode('test-node-1').subscribe(node => {
        expect(node.nodeId).toBe('test-node-1');
        expect(node.title).toBe('Test Entity');
        expect(node.edgeCount).toBe(3);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/test-node-1')
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockNode);
    });

    it('should handle URL-encoded node IDs', (done) => {
      const mockNode: GraphNode = {
        id: 2,
        nodeId: 'node/with/slashes',
        nodeType: 'DOCUMENT',
        title: 'Encoded Node',
        createdAt: '2025-01-01T00:00:00Z',
        childCount: 0,
        edgeCount: 0
      };

      service.getNode('node/with/slashes').subscribe(node => {
        expect(node.title).toBe('Encoded Node');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/node%2Fwith%2Fslashes')
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockNode);
    });
  });

  // ── getNodeNeighborhood ─────────────────────────────────────────────────

  describe('getNodeNeighborhood()', () => {
    it('scopes the expansion to the fact sheet being viewed', (done) => {
      service.getNodeNeighborhood('acme', 25, ['MENTIONS'], 7).subscribe(result => {
        expect(result.nodes.map(node => node.id)).toEqual(['acme', 'alice']);
        expect(result.links.map(link => link.id)).toEqual(['alice::acme::MENTIONS']);
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/knowledge-graph/nodes/acme/expand'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('maxNeighbors')).toBe('25');
      expect(req.request.params.get('edgeTypes')).toBe('MENTIONS');
      expect(req.request.params.get('factSheetId')).toBe('7');
      req.flush({
        nodes: [
          { id: 'acme', type: 'ENTITY', label: 'Acme' },
          { id: 'alice', type: 'ENTITY', label: 'Alice' }
        ],
        edges: [{ id: 'alice::acme::MENTIONS', source: 'alice', target: 'acme', type: 'MENTIONS', weight: 1 }]
      } as any);
    });

    it('sends no sheet or edge filter when none is given', () => {
      service.getNodeNeighborhood('acme').subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/knowledge-graph/nodes/acme/expand'));
      expect(req.request.params.get('maxNeighbors')).toBe('50');
      expect(req.request.params.has('edgeTypes')).toBeFalse();
      expect(req.request.params.has('factSheetId')).toBeFalse();
      req.flush({ nodes: [], edges: [] } as any);
    });

    it('keeps the global sheet 0 and sends a node id with reserved characters as one segment', () => {
      service.getNodeNeighborhood('Q3 report #2?draft', 50, undefined, 0).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/Q3%20report%20%232%3Fdraft/expand'));
      expect(req.request.params.get('factSheetId')).toBe('0');
      req.flush({ nodes: [], edges: [] } as any);
    });
  });
});
