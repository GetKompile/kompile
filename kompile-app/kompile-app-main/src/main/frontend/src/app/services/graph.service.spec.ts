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
  GraphEdge,
  HierarchyTreeNode,
  CreateCompositeEntityRequest,
  AddDocumentRequest,
  CreateAttachmentRequest,
  CreateTableRequest,
  NamedGraph,
  CreateNamedGraphRequest
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

  // ═══════════════════════════════════════════════════════════════════════════
  // HIERARCHY OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('getHierarchy', () => {
    it('should GET hierarchy for a node with default maxDepth', (done) => {
      const mockHierarchy: HierarchyTreeNode = {
        id: 'root-1',
        type: 'SOURCE',
        label: 'Root',
        title: 'Root Source',
        depth: 0,
        isComposite: false,
        children: [
          {
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
        r.url.endsWith('/knowledge-graph/hierarchy/root-1') &&
        r.params.get('maxDepth') === '5'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockHierarchy);
    });

    it('should pass custom maxDepth parameter', (done) => {
      service.getHierarchy('node-1', 3).subscribe(() => done());

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/hierarchy/node-1') &&
        r.params.get('maxDepth') === '3'
      );
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

  describe('getSubGraph', () => {
    it('should GET sub-graph data for a composite entity', (done) => {
      const mockResponse = {
        nodes: [
          { id: 'comp-1', type: 'entity', label: 'Composite' },
          { id: 'sub-1', type: 'entity', label: 'Sub Entity' }
        ],
        edges: [
          { id: 'e1', source: 'comp-1', target: 'sub-1', type: 'contains' }
        ],
        metadata: { nodeCount: 2, edgeCount: 1 }
      };

      service.getSubGraph('comp-1', 3).subscribe(result => {
        expect(result.nodes.length).toBe(2);
        expect(result.links.length).toBe(1);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/comp-1/sub-graph') &&
        r.params.get('maxDepth') === '3'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });
  });

  describe('promoteSourceToEntity', () => {
    it('should POST promote request', (done) => {
      const mockResponse: GraphNode = {
        nodeId: 'entity-uuid',
        nodeType: 'ENTITY',
        title: 'Email Source',
        externalId: 'entity-src-1',
        confidence: 0.95
      } as GraphNode;

      service.promoteSourceToEntity('src-1', 'EMAIL_ACCOUNT', 0.95).subscribe(result => {
        expect(result.nodeType).toBe('ENTITY');
        expect(result.confidence).toBe(0.95);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/src-1/promote-to-entity')
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body.entityType).toBe('EMAIL_ACCOUNT');
      expect(req.request.body.confidence).toBe(0.95);
      req.flush(mockResponse);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // DOCUMENT & ATTACHMENT OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('addDocument', () => {
    it('should POST document creation request', (done) => {
      const request: AddDocumentRequest = {
        sourceExternalId: 'inbox-1',
        sourceTitle: 'Work Inbox',
        sourceType: 'EMAIL',
        docExternalId: 'email-42',
        docTitle: 'Q4 Report',
        content: 'The quarterly report shows...',
        metadata: { from: 'cfo@company.com' }
      };

      const mockResponse: GraphNode = {
        nodeId: 'doc-uuid',
        nodeType: 'DOCUMENT',
        title: 'Q4 Report',
        externalId: 'email-42'
      } as GraphNode;

      service.addDocument(request).subscribe(result => {
        expect(result.nodeType).toBe('DOCUMENT');
        expect(result.title).toBe('Q4 Report');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/documents')
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockResponse);
    });
  });

  describe('createAttachment', () => {
    it('should POST attachment creation request', (done) => {
      const request: CreateAttachmentRequest = {
        parentNodeId: 'doc-1',
        externalId: 'attach-1',
        title: 'quarterly_report.xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        metadata: { size: 52480 }
      };

      const mockResponse: GraphNode = {
        nodeId: 'attach-uuid',
        nodeType: 'ATTACHMENT',
        title: 'quarterly_report.xlsx',
        externalId: 'attach-1'
      } as GraphNode;

      service.createAttachment(request).subscribe(result => {
        expect(result.nodeType).toBe('ATTACHMENT');
        expect(result.title).toBe('quarterly_report.xlsx');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/attachment')
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body.mimeType).toBe('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
      req.flush(mockResponse);
    });
  });

  describe('createTable', () => {
    it('should POST table creation request', (done) => {
      const request: CreateTableRequest = {
        parentNodeId: 'attach-1',
        externalId: 'sheet-1',
        title: 'Revenue Data',
        rowCount: 100,
        columnCount: 5,
        headers: ['Quarter', 'Revenue', 'Costs', 'Profit', 'Growth'],
        content: 'Q1,1000,800,200,5%',
        metadata: { sheetIndex: 0 }
      };

      const mockResponse: GraphNode = {
        nodeId: 'table-uuid',
        nodeType: 'TABLE',
        title: 'Revenue Data',
        externalId: 'sheet-1'
      } as GraphNode;

      service.createTable(request).subscribe(result => {
        expect(result.nodeType).toBe('TABLE');
        expect(result.title).toBe('Revenue Data');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/table')
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body.rowCount).toBe(100);
      expect(req.request.body.headers?.length).toBe(5);
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
        r.url.endsWith('/knowledge-graph/hierarchy/bad-id')
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
    it('should pass maxDepth=1 to getSubGraph for shallow traversal', (done) => {
      const mockResponse = {
        nodes: [{ id: 'comp-1', type: 'entity', label: 'Root Composite' }],
        edges: [],
        metadata: { nodeCount: 1, edgeCount: 0 }
      };

      service.getSubGraph('comp-1', 1).subscribe(result => {
        expect(result.nodes.length).toBe(1);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/comp-1/sub-graph') &&
        r.params.get('maxDepth') === '1'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });

    it('should pass maxDepth=10 to getSubGraph for deep traversal', (done) => {
      const mockResponse = {
        nodes: [
          { id: 'comp-1', type: 'entity', label: 'Root' },
          { id: 'sub-1', type: 'entity', label: 'Level 1' },
          { id: 'sub-2', type: 'entity', label: 'Level 2' }
        ],
        edges: [
          { id: 'e1', source: 'comp-1', target: 'sub-1', type: 'contains' },
          { id: 'e2', source: 'sub-1', target: 'sub-2', type: 'contains' }
        ],
        metadata: { nodeCount: 3, edgeCount: 2 }
      };

      service.getSubGraph('comp-1', 10).subscribe(result => {
        expect(result.nodes.length).toBe(3);
        expect(result.links.length).toBe(2);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/comp-1/sub-graph') &&
        r.params.get('maxDepth') === '10'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });

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
        id: 'comp-root',
        type: 'ENTITY',
        label: 'Composite Root',
        title: 'Composite Organization',
        depth: 0,
        isComposite: true,
        subGraphId: 'org-sub-graph',
        children: [
          {
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
        r.url.endsWith('/knowledge-graph/hierarchy/comp-root') &&
        r.params.get('maxDepth') === '5'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockCompositeHierarchy);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // SOURCE-AS-ENTITY (multiple source types)
  // ═══════════════════════════════════════════════════════════════════════════

  describe('promoteSourceToEntity - source type variations', () => {
    it('should POST promote request for EMAIL_ACCOUNT source type', (done) => {
      const mockResponse: GraphNode = {
        nodeId: 'entity-email-uuid',
        nodeType: 'ENTITY',
        title: 'Work Inbox',
        externalId: 'entity-email-src',
        sourceType: 'EMAIL_ACCOUNT',
        confidence: 0.9
      } as GraphNode;

      service.promoteSourceToEntity('email-src-1', 'EMAIL_ACCOUNT', 0.9).subscribe(result => {
        expect(result.nodeType).toBe('ENTITY');
        expect(result.confidence).toBe(0.9);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/email-src-1/promote-to-entity')
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body.entityType).toBe('EMAIL_ACCOUNT');
      expect(req.request.body.confidence).toBe(0.9);
      req.flush(mockResponse);
    });

    it('should POST promote request for API_ENDPOINT source type', (done) => {
      const mockResponse: GraphNode = {
        nodeId: 'entity-api-uuid',
        nodeType: 'ENTITY',
        title: 'REST API',
        externalId: 'entity-api-src',
        sourceType: 'API_ENDPOINT',
        confidence: 0.8
      } as GraphNode;

      service.promoteSourceToEntity('api-src-1', 'API_ENDPOINT', 0.8).subscribe(result => {
        expect(result.nodeType).toBe('ENTITY');
        expect(result.confidence).toBe(0.8);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/api-src-1/promote-to-entity')
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body.entityType).toBe('API_ENDPOINT');
      expect(req.request.body.confidence).toBe(0.8);
      req.flush(mockResponse);
    });

    it('should POST promote request for DATABASE source type', (done) => {
      const mockResponse: GraphNode = {
        nodeId: 'entity-db-uuid',
        nodeType: 'ENTITY',
        title: 'Production DB',
        externalId: 'entity-db-src',
        sourceType: 'DATABASE',
        confidence: 0.99
      } as GraphNode;

      service.promoteSourceToEntity('db-src-1', 'DATABASE', 0.99).subscribe(result => {
        expect(result.nodeType).toBe('ENTITY');
        expect(result.confidence).toBe(0.99);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/db-src-1/promote-to-entity')
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body.entityType).toBe('DATABASE');
      expect(req.request.body.confidence).toBe(0.99);
      req.flush(mockResponse);
    });

    it('should POST promote request with confidence=1.0 (absolute certainty)', (done) => {
      const mockResponse: GraphNode = {
        nodeId: 'entity-certain-uuid',
        nodeType: 'ENTITY',
        title: 'Verified Source',
        externalId: 'entity-certain',
        confidence: 1.0
      } as GraphNode;

      service.promoteSourceToEntity('certain-src', 'DATABASE', 1.0).subscribe(result => {
        expect(result.confidence).toBe(1.0);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/certain-src/promote-to-entity')
      );
      expect(req.request.body.confidence).toBe(1.0);
      req.flush(mockResponse);
    });

    it('should POST promote request with confidence=0.0 (no certainty)', (done) => {
      const mockResponse: GraphNode = {
        nodeId: 'entity-uncertain-uuid',
        nodeType: 'ENTITY',
        title: 'Unverified Source',
        externalId: 'entity-uncertain',
        confidence: 0.0
      } as GraphNode;

      service.promoteSourceToEntity('uncertain-src', 'API_ENDPOINT', 0.0).subscribe(result => {
        expect(result.confidence).toBe(0.0);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/knowledge-graph/nodes/uncertain-src/promote-to-entity')
      );
      expect(req.request.body.confidence).toBe(0.0);
      req.flush(mockResponse);
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

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs') && !r.params.has('query'));
      expect(req.request.method).toBe('GET');
      req.flush(mockNamedGraphList);
    });

    it('should get named graphs with search query', (done) => {
      service.getNamedGraphs('test').subscribe(result => {
        expect(result).toBeTruthy();
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/api/graphs') &&
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

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs/graph-123'));
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

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs'));
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

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs/graph-123'));
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body).toEqual(updates);
      req.flush(updated);
    });

    it('should delete a named graph', (done) => {
      service.deleteNamedGraph('graph-123').subscribe(() => done());

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs/graph-123'));
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

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs/parent-id/children'));
      expect(req.request.method).toBe('GET');
      req.flush(children);
    });

    it('should get graph hierarchy', (done) => {
      const mockHierarchy = {
        graphId: 'root-id',
        name: 'Root',
        children: []
      };

      service.getGraphHierarchy('root-id', 3).subscribe(result => {
        expect(result.graphId).toBe('root-id');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/api/graphs/root-id/hierarchy') &&
        r.params.get('maxDepth') === '3'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockHierarchy);
    });

    it('should get graph ancestors', (done) => {
      const ancestors: NamedGraph[] = [
        { graphId: 'root', name: 'Root', nodeCount: 0, edgeCount: 0, childGraphCount: 1 },
        { graphId: 'child-id', name: 'Child', nodeCount: 0, edgeCount: 0, childGraphCount: 0 }
      ];

      service.getGraphAncestors('child-id').subscribe(result => {
        expect(result.length).toBe(2);
        expect(result[0].graphId).toBe('root');
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs/child-id/ancestors'));
      expect(req.request.method).toBe('GET');
      req.flush(ancestors);
    });

    it('should move a graph', (done) => {
      const moved: NamedGraph = { ...mockNamedGraph, parentGraphId: 'new-parent' };

      service.moveGraph('graph-id', 'new-parent').subscribe(result => {
        expect(result.parentGraphId).toBe('new-parent');
        done();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs/graph-id/move'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ newParentGraphId: 'new-parent' });
      req.flush(moved);
    });

    it('should link node to graph', (done) => {
      service.linkNodeToGraph('node-1', 'graph-1').subscribe(() => done());

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs/graph-1/nodes/node-1'));
      expect(req.request.method).toBe('POST');
      req.flush(null);
    });

    it('should unlink node from graph', (done) => {
      service.unlinkNodeFromGraph('node-1', 'graph-1').subscribe(() => done());

      const req = httpMock.expectOne(r => r.url.endsWith('/api/graphs/graph-1/nodes/node-1'));
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
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
});
