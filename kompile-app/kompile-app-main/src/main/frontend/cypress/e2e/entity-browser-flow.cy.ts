/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Entity Browser E2E Flow Tests
 * Populates a graph hierarchy via the API, then navigates to the
 * Entity Browser and verifies that provenance, source chunks,
 * and spreadsheet detail sections render with the correct data.
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

  /** Navigate to Tools → Knowledge Graph → Entity Browser */
  function openEntityBrowser() {
    cy.visit('/');
    cy.get('app-root', { timeout: 15000 }).should('exist');
    cy.get('.nav-item').contains('Tools').click();
    cy.get('.sub-tab').contains('Knowledge Graph').click();
    cy.get('app-knowledge-graph-hub', { timeout: 10000 }).should('exist');
    cy.contains('button', 'Entity Browser').click();
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
      cy.get('.properties-section').should('exist');
      cy.get('.panel-content').should('contain.text', 'Budget manager');
    });

    it('should show external ID in properties', () => {
      // External ID contains the alice suffix — use partial match to avoid
      // timestamp mismatch on retries (ts is re-evaluated on retry)
      cy.get('.info-value.monospace').should('contain.text', '-alice');
    });
  });

  // ═══════════════════════ Source chunks section ═══════════════════════

  describe('Source chunks — Extracted From display', () => {
    beforeEach(() => {
      openEntityBrowser();
      cy.get('.entity-table').contains('Alice Smith').click();
      cy.get('.detail-panel', { timeout: 5000 }).should('exist');
    });

    it('should show "Extracted From" section with 1 chunk', () => {
      cy.get('.source-chunks-section', { timeout: 5000 }).should('exist');
      cy.get('.source-chunks-section .section-title').should('contain.text', 'Extracted From');
      cy.get('.chunk-panel').should('have.length.gte', 1);
    });

    it('chunk panel header shows chunk title', () => {
      cy.get('.chunk-panel').first().should('contain.text', 'Budget Summary Chunk');
    });

    it('chunk panel shows index #1', () => {
      cy.get('.chunk-index').first().should('contain.text', '#1');
    });

    it('expanding chunk panel reveals chunk detail', () => {
      cy.get('.chunk-panel').first().find('mat-expansion-panel-header').click();
      cy.get('.chunk-detail', { timeout: 3000 }).should('be.visible');
    });
  });

  describe('Source chunks — Acme Corp entity', () => {
    beforeEach(() => {
      openEntityBrowser();
      cy.get('.entity-table').contains('Acme Corp').click();
      cy.get('.detail-panel', { timeout: 5000 }).should('exist');
    });

    it('should show "Extracted From" section linked to chunk-2', () => {
      cy.get('.source-chunks-section', { timeout: 5000 }).should('exist');
      cy.get('.chunk-panel').first().should('contain.text', 'Action Items Chunk');
    });
  });

  // ═══════════════════════ TABLE detail section ═══════════════════════

  describe('TABLE detail — Q2 Revenue Sheet', () => {
    beforeEach(() => {
      openEntityBrowser();
      cy.get('.entity-table').contains('Q2 Revenue Sheet').click();
      cy.get('.detail-panel', { timeout: 5000 }).should('exist');
    });

    it('should show TABLE type badge', () => {
      cy.get('.info-header .type-badge').should('contain.text', 'Table');
    });

    it('should show Spreadsheet Detail section', () => {
      cy.get('.table-detail-section', { timeout: 5000 }).should('exist');
      cy.get('.table-detail-section .section-title').should('contain.text', 'Spreadsheet Detail');
    });

    it('should show table stats: 25 rows, 6 columns', () => {
      cy.get('.table-stats').should('exist');
      cy.get('.table-stat').should('have.length.gte', 2);

      // Verify row count
      cy.get('.table-stat').contains('Rows').parent().find('.stat-value').should('contain.text', '25');
      // Verify column count
      cy.get('.table-stat').contains('Columns').parent().find('.stat-value').should('contain.text', '6');
    });

    it('should show formula count stat', () => {
      cy.get('.table-stat').contains('Formulas').parent().find('.stat-value').should('contain.text', '12');
    });

    it('should show DQ flag count stat with warning style', () => {
      cy.get('.table-stat').contains('DQ Flags').parent().find('.stat-value.warn').should('contain.text', '2');
    });

    it('should show header chips: Product, Q1, Q2, Q3, Q4, Total', () => {
      cy.get('.header-chips').should('exist');
      cy.get('.header-chip').should('have.length', 6);
      cy.get('.header-chip').eq(0).should('contain.text', 'Product');
      cy.get('.header-chip').eq(5).should('contain.text', 'Total');
    });

    it('should have expandable Formulas panel with SUM formulas', () => {
      cy.get('.formulas-panel').should('exist');
      cy.get('.formulas-panel mat-expansion-panel-header').click();
      cy.get('.formula-content', { timeout: 3000 }).should('be.visible');
      cy.get('.formula-content').should('contain.text', '=SUM(B2:B25)');
    });

    it('should have expandable Full Table Content with Widget A data', () => {
      cy.get('.table-content-panel').should('exist');
      cy.get('.table-content-panel mat-expansion-panel-header').click();
      cy.get('.full-table-content', { timeout: 3000 }).should('be.visible');
      cy.get('.full-table-content').should('contain.text', 'Widget A');
      cy.get('.full-table-content').should('contain.text', '1200');
    });

    it('should have expandable DQ Flags panel with negative value issue', () => {
      cy.get('.dq-flags-panel').should('exist');
      cy.get('.dq-flags-panel mat-expansion-panel-header').click();
      cy.get('.dq-flags-content', { timeout: 3000 }).should('be.visible');
      cy.get('.dq-flags-content').should('contain.text', 'negative value');
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
        const entityChip = $chips.filter((_i, el) => el.textContent?.includes('Entity'));
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
