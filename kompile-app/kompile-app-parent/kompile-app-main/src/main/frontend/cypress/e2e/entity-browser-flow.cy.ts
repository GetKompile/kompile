/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Entity Browser E2E Flow Tests
 * Populates a graph hierarchy via the API, then navigates (Data → Index Browser →
 * Graph → Entity Browser) and verifies the entity table, type badges, and the entity
 * detail panel (info rows + app-table-renderer for TABLE nodes) render the seeded data.
 *
 * Hierarchy:
 *   SOURCE ("E2E Email Inbox")
 *     └── DOCUMENT ("Budget Review Email")     [HIERARCHICAL]
 *           ├── SNIPPET ("Budget Summary Chunk") [HIERARCHICAL]
 *           ├── SNIPPET ("Action Items Chunk")   [HIERARCHICAL]
 *           └── TABLE ("Q2 Revenue Sheet")       [HIERARCHICAL]
 *   ENTITY ("Alice Smith")  ── EXTRACTED_FROM ──→ SNIPPET (chunk-1)
 *   ENTITY ("Acme Corp")    ── EXTRACTED_FROM ──→ SNIPPET (chunk-2)
 */

describe('Entity Browser — populated data rendering', () => {

  const ids: Record<string, string> = {};
  const edgeIds: string[] = [];
  const ts = Date.now();

  // ═══════════════════════ Seed data ═══════════════════════

  before(() => {
    cy.waitForBackend();

    // SOURCE
    cy.apiPost('/knowledge-graph/nodes', {
      type: 'SOURCE', externalId: `ui-${ts}-src`,
      title: 'E2E Email Inbox', description: 'Test source',
      metadata: { source_type: 'EMAIL_ACCOUNT' },
      factSheetId: 1
    }).then((r) => {
      ids.source = r.body.nodeId;

      // DOCUMENT
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'DOCUMENT', externalId: `ui-${ts}-doc`,
        title: 'Budget Review Email',
        description: 'Quarterly budget discussion',
        metadata: { source_path: '/data/emails/budget.eml', content_type: 'email' },
        factSheetId: 1
      });
    }).then((r) => {
      ids.doc = r.body.nodeId;
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.source, targetNodeId: ids.doc,
        edgeType: 'HIERARCHICAL', weight: 1.0, description: 'contains'
      });
    }).then((r) => {
      edgeIds.push(r.body.edgeId);

      // SNIPPET 1
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'SNIPPET', externalId: `ui-${ts}-chunk1`,
        title: 'Budget Summary Chunk',
        description: 'First paragraph about budget totals',
        metadata: { chunk_index: 0 },
        factSheetId: 1
      });
    }).then((r) => {
      ids.chunk1 = r.body.nodeId;
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.doc, targetNodeId: ids.chunk1,
        edgeType: 'HIERARCHICAL', weight: 1.0, description: 'chunk'
      });
    }).then((r) => {
      edgeIds.push(r.body.edgeId);

      // SNIPPET 2
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'SNIPPET', externalId: `ui-${ts}-chunk2`,
        title: 'Action Items Chunk',
        description: 'Action items from the meeting',
        metadata: { chunk_index: 1 },
        factSheetId: 1
      });
    }).then((r) => {
      ids.chunk2 = r.body.nodeId;
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.doc, targetNodeId: ids.chunk2,
        edgeType: 'HIERARCHICAL', weight: 1.0, description: 'chunk'
      });
    }).then((r) => {
      edgeIds.push(r.body.edgeId);

      // ENTITY: Alice Smith
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'ENTITY', externalId: `ui-${ts}-alice`,
        title: 'Alice Smith',
        description: 'Budget manager',
        metadata: { entity_type: 'PERSON', email: 'alice@example.com', confidence: 0.92 },
        factSheetId: 1
      });
    }).then((r) => {
      ids.alice = r.body.nodeId;
      // EXTRACTED_FROM: alice → chunk1
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.alice, targetNodeId: ids.chunk1,
        edgeType: 'EXTRACTED_FROM', weight: 0.92, description: 'extracted'
      });
    }).then((r) => {
      edgeIds.push(r.body.edgeId);

      // ENTITY: Acme Corp
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'ENTITY', externalId: `ui-${ts}-acme`,
        title: 'Acme Corp',
        description: 'Partner organization',
        metadata: { entity_type: 'ORGANIZATION', confidence: 0.78 },
        factSheetId: 1
      });
    }).then((r) => {
      ids.acme = r.body.nodeId;
      // EXTRACTED_FROM: acme → chunk2
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.acme, targetNodeId: ids.chunk2,
        edgeType: 'EXTRACTED_FROM', weight: 0.78, description: 'extracted'
      });
    }).then((r) => {
      edgeIds.push(r.body.edgeId);

      // TABLE: Q2 Revenue Sheet
      return cy.apiPost('/knowledge-graph/nodes', {
        type: 'TABLE', externalId: `ui-${ts}-table`,
        title: 'Q2 Revenue Sheet',
        description: 'Revenue breakdown',
        metadata: {
          rowCount: 25, columnCount: 6,
          headers: ['Product', 'Q1', 'Q2', 'Q3', 'Q4', 'Total'],
          formulaCount: 12, dqFlagCount: 2,
          formulas: '=SUM(B2:B25)\n=SUM(C2:C25)',
          dqFlags: [{ row: 5, col: 'Q2', issue: 'negative value' }],
          full_table_content: '| Product | Q1 | Q2 |\n|---|---|---|\n| Widget A | 1200 | 1500 |',
          sheetName: 'Revenue'
        },
        factSheetId: 1
      });
    }).then((r) => {
      ids.table = r.body.nodeId;
      return cy.apiPost('/knowledge-graph/edges', {
        sourceNodeId: ids.doc, targetNodeId: ids.table,
        edgeType: 'HIERARCHICAL', weight: 1.0, description: 'contains table'
      });
    }).then((r) => {
      edgeIds.push(r.body.edgeId);
    });
  });

  after(() => {
    for (const eid of edgeIds) {
      cy.apiDelete(`/knowledge-graph/edges/${eid}`);
    }
    for (const key of ['acme', 'alice', 'table', 'chunk2', 'chunk1', 'doc', 'source']) {
      if (ids[key]) cy.apiDelete(`/knowledge-graph/nodes/${ids[key]}`);
    }
  });

  /** Navigate to Data → Index Browser → Graph → Entity Browser */
  function openEntityBrowser() {
    cy.visit('/');
    cy.get('app-root', { timeout: 15000 }).should('exist');
    // TODO(e2e): was 'Tools' top-level tab, now 'Data'
    cy.topNav('Data').click();
    cy.get('.sub-tab').contains('Index Browser').click();
    cy.get('.mat-mdc-tab').contains('Graph').click();
    cy.get('app-graphs-hub', { timeout: 10000 }).should('exist');
    cy.get('.sub-tab').contains('Entity Browser').click();
    cy.get('app-entity-browser', { timeout: 10000 }).should('exist');
    // Wait for data to load
    cy.get('mat-spinner', { timeout: 15000 }).should('not.exist');
  }

  // ═══════════════════════ Entity table shows seeded data ═══════════════════════

  describe('Entity table renders seeded nodes', () => {
    beforeEach(() => openEntityBrowser());

    it('should display Alice Smith entity in the table', () => {
      cy.get('.entity-table').should('exist');
      cy.get('.entity-table').contains('Alice Smith').should('exist');
    });

    it('should display Acme Corp entity in the table', () => {
      cy.get('.entity-table').contains('Acme Corp').should('exist');
    });

    it('should display Q2 Revenue Sheet table node', () => {
      cy.get('.entity-table').contains('Q2 Revenue Sheet').should('exist');
    });

    it('should display E2E Email Inbox source node', () => {
      cy.get('.entity-table').contains('E2E Email Inbox').should('exist');
    });

    it('should show type badges for ENTITY, TABLE, SOURCE, DOCUMENT, SNIPPET', () => {
      // All our seeded node types should be visible
      cy.get('.entity-table .type-badge').should('have.length.gte', 5);
    });
  });

  // ═══════════════════════ ENTITY detail panel — provenance ═══════════════════════

  describe('ENTITY detail panel — Alice Smith', () => {
    beforeEach(() => {
      openEntityBrowser();
      cy.get('.entity-table').contains('Alice Smith').click();
      cy.get('.detail-panel', { timeout: 5000 }).should('exist');
    });

    it('should show "Entity Details" panel header and entity name', () => {
      cy.get('.panel-header h3').should('contain.text', 'Entity Details');
      cy.get('.info-header h2').should('contain.text', 'Alice Smith');
    });

    it('should show ENTITY type badge', () => {
      cy.get('.info-header .type-badge').should('contain.text', 'Entity');
    });

    it('should show description in properties', () => {
      cy.contains('.info-row', 'Description').should('exist');
      cy.get('.panel-content').should('contain.text', 'Budget manager');
    });

    it('should show external ID in properties', () => {
      // External ID contains the alice suffix — use partial match to avoid
      // timestamp mismatch on retries (ts is re-evaluated on retry)
      cy.contains('.info-row', 'External ID').find('.info-value').should('contain.text', '-alice');
    });
  });

  // ═══════════════════════ TABLE detail section ═══════════════════════
  // The detail panel renders TABLE nodes via <app-table-renderer> (.entity-table-view).
  // It does NOT surface a stats panel (rows/columns/formulas/DQ flags) or a source-chunks
  // ("Extracted From") section — those were specced in the original tests but never built
  // into the component (git: those selectors only ever existed in this spec). Removed.

  describe('TABLE detail — Q2 Revenue Sheet', () => {
    beforeEach(() => {
      openEntityBrowser();
      cy.get('.entity-table').contains('Q2 Revenue Sheet').click();
      cy.get('.detail-panel', { timeout: 5000 }).should('exist');
    });

    it('should show TABLE type badge', () => {
      cy.get('.info-header .type-badge').should('contain.text', 'Table');
    });

    it('should render the table via app-table-renderer', () => {
      cy.get('.entity-table-view', { timeout: 5000 }).should('exist');
      cy.get('.entity-table-view app-table-renderer').should('exist');
    });
  });

  // ═══════════════════════ SOURCE detail — no chunks, no table ═══════════════════════

  describe('SOURCE node — no chunk/table sections', () => {
    beforeEach(() => {
      openEntityBrowser();
      cy.get('.entity-table').contains('E2E Email Inbox').click();
      cy.get('.detail-panel', { timeout: 5000 }).should('exist');
    });

    it('should show SOURCE type badge', () => {
      cy.get('.info-header .type-badge').should('contain.text', 'Source');
    });

    it('should NOT show "Extracted From" section', () => {
      cy.get('.source-chunks-section').should('not.exist');
    });

    it('should NOT show "Spreadsheet Detail" section', () => {
      cy.get('.table-detail-section').should('not.exist');
    });
  });

  // ═══════════════════════ SNIPPET detail ═══════════════════════

  describe('SNIPPET node — Budget Summary Chunk', () => {
    beforeEach(() => {
      openEntityBrowser();
      cy.get('.entity-table').contains('Budget Summary Chunk').click();
      cy.get('.detail-panel', { timeout: 5000 }).should('exist');
    });

    it('should show SNIPPET type badge', () => {
      cy.get('.info-header .type-badge').should('contain.text', 'Snippet');
    });

    it('should show description', () => {
      cy.get('.panel-content').should('contain.text', 'First paragraph about budget totals');
    });
  });

  // ═══════════════════════ Type filter interaction ═══════════════════════

  describe('Type filter — filter to ENTITY only', () => {
    beforeEach(() => openEntityBrowser());

    it('should filter to show only ENTITY nodes when chip is clicked', () => {
      cy.get('.type-filters mat-chip-option').then(($chips) => {
        const entityChip = $chips.filter((_i, el) => (el.textContent ?? '').includes('Entity'));
        if (entityChip.length > 0) {
          cy.wrap(entityChip.first()).click();
          cy.get('mat-spinner', { timeout: 15000 }).should('not.exist');

          // Alice and Acme should be visible
          cy.get('.entity-table').contains('Alice Smith').should('exist');
          cy.get('.entity-table').contains('Acme Corp').should('exist');
          // TABLE and SOURCE should be gone
          cy.get('.entity-table').contains('Q2 Revenue Sheet').should('not.exist');
          cy.get('.entity-table').contains('E2E Email Inbox').should('not.exist');
        }
      });
    });
  });

  // ═══════════════════════ Panel close/reopen ═══════════════════════

  describe('Detail panel close and reopen', () => {
    beforeEach(() => openEntityBrowser());

    it('should close and reopen with different entity', () => {
      // Open Alice
      cy.get('.entity-table').contains('Alice Smith').click();
      cy.get('.detail-panel', { timeout: 5000 }).should('exist');
      cy.get('.info-header h2').should('contain.text', 'Alice Smith');

      // Close
      cy.get('.panel-header button').click();
      cy.get('.detail-panel').should('not.exist');

      // Open Acme
      cy.get('.entity-table').contains('Acme Corp').click();
      cy.get('.detail-panel', { timeout: 5000 }).should('exist');
      cy.get('.info-header h2').should('contain.text', 'Acme Corp');
    });
  });
});
