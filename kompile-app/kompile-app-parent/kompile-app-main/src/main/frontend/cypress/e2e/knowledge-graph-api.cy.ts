/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Knowledge Graph API E2E Tests
 * Populates a full graph hierarchy via the CRUD API, then verifies
 * all provenance, source-chunk, and table-detail endpoints return
 * the correct topology.
 *
 * Hierarchy under test:
 *   SOURCE (e2e-source)
 *     └── DOCUMENT (e2e-document)  [HIERARCHICAL]
 *           ├── SNIPPET (e2e-chunk-1) [HIERARCHICAL]
 *           └── SNIPPET (e2e-chunk-2) [HIERARCHICAL]
 *   ENTITY (e2e-entity-person) ← EXTRACTED_FROM → SNIPPET (e2e-chunk-1)
 *   TABLE  (e2e-table-revenue) ← child of DOCUMENT
 */

describe('Knowledge Graph API — populated hierarchy', () => {

  const ids: Record<string, string> = {};
  const edgeIds: string[] = [];
  const testPrefix = `e2e-${Date.now()}`;

  before(() => {
    cy.waitForBackend();

    // ═══════════════════ Build the graph ═══════════════════

    // 1. Create SOURCE node
    cy.apiPost('/knowledge-graph/nodes', {
      type: 'SOURCE',
      externalId: `${testPrefix}-source`,
      title: 'E2E Test Email Inbox',
      description: 'Automated test source',
      metadata: { source_type: 'EMAIL_ACCOUNT', test: true }
    }).then((res) => {
      expect(res.status).to.eq(200);
      ids.source = res.body.nodeId;

      // 2. Create DOCUMENT node
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'DOCUMENT',
        externalId: `${testPrefix}-document`,
        title: 'Q2 Budget Review Email',
        description: 'Email about quarterly budget',
        metadata: { source_path: '/data/emails/budget.eml', content_type: 'email' }
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      ids.document = res.body.nodeId;

      // 3. HIERARCHICAL edge: SOURCE → DOCUMENT
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.source,
        targetNodeId: ids.document,
        edgeType: 'HIERARCHICAL',
        weight: 1.0,
        description: 'source contains document'
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      edgeIds.push(res.body.edgeId);

      // 4. Create SNIPPET (chunk 1)
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'SNIPPET',
        externalId: `${testPrefix}-chunk-1`,
        title: 'Budget Summary Chunk',
        description: 'First chunk of the email body',
        metadata: { chunk_index: 0, chunk_size: 512 }
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      ids.chunk1 = res.body.nodeId;

      // 5. HIERARCHICAL edge: DOCUMENT → SNIPPET
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.document,
        targetNodeId: ids.chunk1,
        edgeType: 'HIERARCHICAL',
        weight: 1.0,
        description: 'document contains chunk'
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      edgeIds.push(res.body.edgeId);

      // 6. Create SNIPPET (chunk 2)
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'SNIPPET',
        externalId: `${testPrefix}-chunk-2`,
        title: 'Action Items Chunk',
        description: 'Second chunk with action items',
        metadata: { chunk_index: 1, chunk_size: 256 }
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      ids.chunk2 = res.body.nodeId;

      // 7. HIERARCHICAL: DOCUMENT → SNIPPET 2
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.document,
        targetNodeId: ids.chunk2,
        edgeType: 'HIERARCHICAL',
        weight: 1.0,
        description: 'document contains chunk 2'
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      edgeIds.push(res.body.edgeId);

      // 8. Create ENTITY (person extracted from chunk 1)
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'ENTITY',
        externalId: `${testPrefix}-entity-person`,
        title: 'Alice Smith',
        description: 'Budget manager mentioned in email',
        metadata: {
          entity_type: 'PERSON',
          email: 'alice@example.com',
          confidence: 0.92,
          sourceChunkId: `${testPrefix}-chunk-1`
        }
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      ids.entity = res.body.nodeId;

      // 9. EXTRACTED_FROM edge: ENTITY → SNIPPET (entity was extracted from this chunk)
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.entity,
        targetNodeId: ids.chunk1,
        edgeType: 'EXTRACTED_FROM',
        weight: 0.92,
        description: 'entity extracted from chunk'
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      edgeIds.push(res.body.edgeId);

      // 10. Create TABLE node (spreadsheet from the email attachment)
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'TABLE',
        externalId: `${testPrefix}-table-revenue`,
        title: 'Q2 Revenue Sheet',
        description: 'Revenue breakdown by product line',
        metadata: {
          rowCount: 25,
          columnCount: 6,
          headers: ['Product', 'Q1', 'Q2', 'Q3', 'Q4', 'Total'],
          formulaCount: 12,
          dqFlagCount: 2,
          formulas: '=SUM(B2:B25)\n=SUM(C2:C25)\n=SUM(D2:D25)',
          dqFlags: [{ row: 5, col: 'Q2', issue: 'negative value' }, { row: 18, col: 'Q3', issue: 'missing' }],
          full_table_content: '| Product | Q1 | Q2 | Q3 | Q4 | Total |\n|---|---|---|---|---|---|\n| Widget A | 1200 | 1500 | 1800 | 2000 | 6500 |\n| Widget B | 800 | -50 | 900 | 1100 | 2750 |',
          sheetName: 'Revenue',
          table_extraction_method: 'apache-poi'
        }
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      ids.table = res.body.nodeId;

      // 11. HIERARCHICAL: DOCUMENT → TABLE
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.document,
        targetNodeId: ids.table,
        edgeType: 'HIERARCHICAL',
        weight: 1.0,
        description: 'document contains table'
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      edgeIds.push(res.body.edgeId);

      // 12. Create a second ENTITY (organization extracted from chunk 2)
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'ENTITY',
        externalId: `${testPrefix}-entity-org`,
        title: 'Acme Corp',
        description: 'Organization mentioned in action items',
        metadata: { entity_type: 'ORGANIZATION', confidence: 0.78 }
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      ids.entityOrg = res.body.nodeId;

      // 13. EXTRACTED_FROM: org entity → chunk 2
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.entityOrg,
        targetNodeId: ids.chunk2,
        edgeType: 'EXTRACTED_FROM',
        weight: 0.78,
        description: 'entity extracted from chunk 2'
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      edgeIds.push(res.body.edgeId);

      // 14. SHARED_ENTITY edge between the two entities
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.entity,
        targetNodeId: ids.entityOrg,
        edgeType: 'SHARED_ENTITY',
        weight: 0.5,
        description: 'co-occur in same email'
      });
    }).then((res) => {
      expect(res.status).to.eq(200);
      edgeIds.push(res.body.edgeId);
    });
  });

  after(() => {
    // Cleanup: delete edges first, then nodes (reverse order)
    for (const edgeId of edgeIds) {
      cy.apiDelete(`/knowledge-graph/edges/${edgeId}`);
    }
    for (const key of ['entityOrg', 'entity', 'table', 'chunk2', 'chunk1', 'document', 'source']) {
      if (ids[key]) {
        cy.apiDelete(`/knowledge-graph/nodes/${ids[key]}`);
      }
    }
  });

  // ═══════════════════════ Node detail with provenance ═══════════════════════

  describe('Node detail — provenance fields', () => {
    it('SOURCE node has correct properties', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.source}`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.nodeId).to.eq(ids.source);
        expect(res.body.title).to.eq('E2E Test Email Inbox');
        expect(res.body.nodeType).to.eq('SOURCE');
        expect(res.body.externalId).to.eq(`${testPrefix}-source`);
        expect(res.body.description).to.eq('Automated test source');
        // SOURCE has no parent or source — provenance is null
        expect(res.body.parentId).to.be.null;
        expect(res.body.sourceId).to.be.null;
      });
    });

    it('DOCUMENT node exposes metadataJson with source_path', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.document}`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.title).to.eq('Q2 Budget Review Email');
        expect(res.body.nodeType).to.eq('DOCUMENT');
        // metadataJson should contain source_path
        if (res.body.metadataJson) {
          expect(res.body.metadataJson).to.include('source_path');
          expect(res.body.metadataJson).to.include('/data/emails/budget.eml');
        }
      });
    });

    it('ENTITY node has correct type and metadata', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.entity}`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.title).to.eq('Alice Smith');
        expect(res.body.nodeType).to.eq('ENTITY');
        expect(res.body.description).to.eq('Budget manager mentioned in email');
        if (res.body.metadataJson) {
          expect(res.body.metadataJson).to.include('PERSON');
          expect(res.body.metadataJson).to.include('alice@example.com');
        }
      });
    });

    it('TABLE node has correct type and table metadata', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.table}`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.title).to.eq('Q2 Revenue Sheet');
        expect(res.body.nodeType).to.eq('TABLE');
        if (res.body.metadataJson) {
          expect(res.body.metadataJson).to.include('Revenue');
          expect(res.body.metadataJson).to.include('rowCount');
        }
      });
    });
  });

  // ═══════════════════════ Children ═══════════════════════

  describe('Children endpoint — parent/child topology', () => {
    it('DOCUMENT has 3 children: chunk1, chunk2, table', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.document}/children`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        expect(res.body.length).to.eq(3);

        const childIds = res.body.map((n: any) => n.nodeId);
        expect(childIds).to.include(ids.chunk1);
        expect(childIds).to.include(ids.chunk2);
        expect(childIds).to.include(ids.table);

        const childTypes = res.body.map((n: any) => n.nodeType);
        expect(childTypes).to.include('SNIPPET');
        expect(childTypes).to.include('TABLE');
      });
    });

    it('SOURCE has 1 child: the DOCUMENT', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.source}/children`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.length).to.eq(1);
        expect(res.body[0].nodeId).to.eq(ids.document);
        expect(res.body[0].nodeType).to.eq('DOCUMENT');
      });
    });

    it('SNIPPET has 0 children', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.chunk1}/children`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.length).to.eq(0);
      });
    });
  });

  // ═══════════════════════ Ancestors ═══════════════════════

  describe('Ancestors endpoint — ancestor chain', () => {
    it('SOURCE has no ancestors (root)', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.source}/ancestors`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        expect(res.body.length).to.eq(0);
      });
    });

    it('DOCUMENT has 1 ancestor: SOURCE', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.document}/ancestors`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.length).to.eq(1);
        expect(res.body[0].nodeId).to.eq(ids.source);
        expect(res.body[0].nodeType).to.eq('SOURCE');
        expect(res.body[0].title).to.eq('E2E Test Email Inbox');
      });
    });

    it('SNIPPET has ancestor chain: DOCUMENT → SOURCE', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.chunk1}/ancestors`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.length).to.eq(2);
        // Ordered from immediate parent to root
        const ancestorIds = res.body.map((n: any) => n.nodeId);
        expect(ancestorIds).to.include(ids.document);
        expect(ancestorIds).to.include(ids.source);
      });
    });
  });

  // ═══════════════════════ Source chunks ═══════════════════════

  describe('Source chunks endpoint — EXTRACTED_FROM provenance', () => {
    it('Alice Smith entity has 1 source chunk (chunk-1)', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.entity}/source-chunks`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        expect(res.body.length).to.be.gte(1);

        const chunk = res.body[0];
        expect(chunk.chunkNodeId).to.eq(ids.chunk1);
        expect(chunk.chunkTitle).to.eq('Budget Summary Chunk');
      });
    });

    it('Acme Corp entity has 1 source chunk (chunk-2)', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.entityOrg}/source-chunks`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.length).to.be.gte(1);

        const chunk = res.body[0];
        expect(chunk.chunkNodeId).to.eq(ids.chunk2);
        expect(chunk.chunkTitle).to.eq('Action Items Chunk');
      });
    });

    it('DOCUMENT node has 0 source chunks (not an entity)', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.document}/source-chunks`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.length).to.eq(0);
      });
    });

    it('non-existent node returns empty array', () => {
      cy.apiGet('/knowledge-graph/nodes/does-not-exist-999/source-chunks').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.length).to.eq(0);
      });
    });
  });

  // ═══════════════════════ Table detail ═══════════════════════

  describe('Table detail endpoint — spreadsheet metadata', () => {
    it('TABLE node returns full table metadata', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.table}/table-detail`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.nodeId).to.eq(ids.table);
        expect(res.body.nodeType).to.eq('TABLE');
        expect(res.body.title).to.eq('Q2 Revenue Sheet');

        // Table-specific fields from metadata
        expect(res.body.rowCount).to.eq(25);
        expect(res.body.columnCount).to.eq(6);
        expect(res.body.formulaCount).to.eq(12);
        expect(res.body.dqFlagCount).to.eq(2);
        expect(res.body.sheetName).to.eq('Revenue');
        expect(res.body.extractionMethod).to.eq('apache-poi');
      });
    });

    it('TABLE node returns headers array', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.table}/table-detail`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.headers).to.be.an('array');
        expect(res.body.headers).to.have.length(6);
        expect(res.body.headers).to.include('Product');
        expect(res.body.headers).to.include('Total');
      });
    });

    it('TABLE node returns formulas string', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.table}/table-detail`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.formulas).to.be.a('string');
        expect(res.body.formulas).to.include('=SUM(B2:B25)');
      });
    });

    it('TABLE node returns DQ flags', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.table}/table-detail`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.dqFlags).to.be.an('array');
        expect(res.body.dqFlags).to.have.length(2);
        expect(res.body.dqFlags[0]).to.have.property('issue');
        expect(res.body.dqFlags[0].issue).to.eq('negative value');
      });
    });

    it('TABLE node returns full table content', () => {
      cy.apiGet(`/knowledge-graph/nodes/${ids.table}/table-detail`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.fullTableContent).to.be.a('string');
        expect(res.body.fullTableContent).to.include('Widget A');
        expect(res.body.fullTableContent).to.include('1200');
      });
    });

    it('non-existent node returns 404', () => {
      cy.apiGet('/knowledge-graph/nodes/does-not-exist-999/table-detail').then((res) => {
        expect(res.status).to.eq(404);
      });
    });
  });

  // ═══════════════════════ Edge topology ═══════════════════════

  describe('Edge topology verification', () => {
    it('ENTITY has EXTRACTED_FROM edges', () => {
      cy.apiGet(`/knowledge-graph/edges?nodeId=${ids.entity}&type=EXTRACTED_FROM`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        expect(res.body.length).to.be.gte(1);

        const edge = res.body[0];
        expect(edge.edgeType).to.eq('EXTRACTED_FROM');
      });
    });

    it('ENTITY has SHARED_ENTITY edge to org entity', () => {
      cy.apiGet(`/knowledge-graph/edges?nodeId=${ids.entity}&type=SHARED_ENTITY`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.length).to.be.gte(1);
        expect(res.body[0].edgeType).to.eq('SHARED_ENTITY');
      });
    });

    it('DOCUMENT has HIERARCHICAL edges to children', () => {
      cy.apiGet(`/knowledge-graph/edges?nodeId=${ids.document}&type=HIERARCHICAL`).then((res) => {
        expect(res.status).to.eq(200);
        // SOURCE→DOC + DOC→chunk1 + DOC→chunk2 + DOC→table = at least 3 outgoing
        expect(res.body.length).to.be.gte(3);
      });
    });
  });

  // ═══════════════════════ Visualization ═══════════════════════

  describe('Visualization — D3 format includes provenance', () => {
    it('visualization nodes include all created test nodes', () => {
      cy.apiGet('/knowledge-graph/visualization').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.nodes).to.be.an('array');
        expect(res.body.edges).to.be.an('array');

        const vizNodeIds = res.body.nodes.map((n: any) => n.id);
        expect(vizNodeIds).to.include(ids.source);
        expect(vizNodeIds).to.include(ids.document);
        expect(vizNodeIds).to.include(ids.entity);
        expect(vizNodeIds).to.include(ids.table);
      });
    });

    it('visualization nodes have provenance fields populated', () => {
      cy.apiGet('/knowledge-graph/visualization').then((res) => {
        expect(res.status).to.eq(200);

        const entityNode = res.body.nodes.find((n: any) => n.id === ids.entity);
        expect(entityNode).to.not.be.undefined;
        expect(entityNode.type).to.eq('entity');
        expect(entityNode.label).to.eq('Alice Smith');
        expect(entityNode.externalId).to.eq(`${testPrefix}-entity-person`);
      });
    });

    it('visualization edges include EXTRACTED_FROM edges', () => {
      cy.apiGet('/knowledge-graph/visualization').then((res) => {
        expect(res.status).to.eq(200);

        const extractedEdges = res.body.edges.filter((e: any) => e.type === 'extracted_from');
        expect(extractedEdges.length).to.be.gte(2);
      });
    });
  });

  // ═══════════════════════ Statistics ═══════════════════════

  describe('Statistics reflect created data', () => {
    it('statistics include our test nodes and edges', () => {
      cy.apiGet('/knowledge-graph/statistics').then((res) => {
        expect(res.status).to.eq(200);
        // At minimum our 7 nodes
        expect(res.body.totalNodes).to.be.gte(7);
        // At minimum our 7 edges
        expect(res.body.totalEdges).to.be.gte(7);
      });
    });
  });
});
