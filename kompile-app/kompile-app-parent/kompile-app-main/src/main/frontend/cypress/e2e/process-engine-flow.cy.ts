/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Process Engine UI Flow E2E Tests
 * Tests navigation, tab switching, component rendering, and user interactions
 * within the Process Engine dashboard.
 */

describe('Process Engine UI Flows', () => {

  before(() => {
    cy.waitForBackend();
  });

  beforeEach(() => {
    cy.visit('/');
  });

  // ═══════════════════ Navigation ═══════════════════

  describe('Navigation to Process Engine', () => {
    it('should navigate to Tools and show Process Engine sub-tab', () => {
      // Click the Tools nav item
      cy.get('.nav-items').contains('Tools').click();
      cy.url().should('include', '#/tools');

      // Look for the Process Engine sub-tab button
      cy.get('.sub-tab, button').contains('Process Engine').should('exist');
    });

    it('should show the Process Engine dashboard when sub-tab is clicked', () => {
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();

      // Verify dashboard header renders
      cy.get('app-process-engine-dashboard').should('exist');
      cy.contains('Process Engine').should('be.visible');
    });
  });

  // ═══════════════════ Tab Navigation ═══════════════════

  describe('Dashboard Tab Navigation', () => {
    beforeEach(() => {
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();
    });

    it('should show Ontologies tab content by default', () => {
      cy.get('.mat-mdc-tab').contains('Ontologies').should('exist');
      cy.get('app-process-ontology').should('exist');
    });

    it('should switch to Processes tab', () => {
      cy.get('.mat-mdc-tab').contains('Processes').click();
      cy.get('app-process-definitions').should('exist');
    });

    it('should switch to Runs tab', () => {
      cy.get('.mat-mdc-tab').contains('Runs').click();
      cy.get('app-process-runs').should('exist');
    });

    it('should switch to Approvals tab', () => {
      cy.get('.mat-mdc-tab').contains('Approvals').click();
      cy.get('app-process-approvals').should('exist');
    });

    it('should switch to Controls tab', () => {
      cy.get('.mat-mdc-tab').contains('Controls').click();
      cy.get('app-process-controls').should('exist');
    });
  });

  // ═══════════════════ Ontology Creation ═══════════════════

  describe('Create Ontology via UI', () => {
    const testName = `e2e-ui-ontology-${Date.now()}`;

    beforeEach(() => {
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();
    });

    it('should open create form and submit an ontology', () => {
      // Click "New Ontology" button (or "Create First Ontology" if list is empty)
      cy.get('app-process-ontology').then(($el) => {
        if ($el.find('button:contains("New Ontology")').length) {
          cy.contains('button', 'New Ontology').click();
        } else {
          cy.contains('button', 'Create First Ontology').click();
        }
      });

      // The create form uses a JSON editor textarea — fill it with a valid ontology
      const ontologyJson = JSON.stringify({
        name: testName,
        entityTypes: [{
          name: 'TestEntity',
          description: 'E2E test entity',
          fields: [{ name: 'value', type: 'DECIMAL', required: true }],
          rules: [{ name: 'positive', expression: '#value > 0', severity: 'ERROR', ruleType: 'ASSERTION' }]
        }]
      });
      cy.get('textarea.json-editor').clear().type(ontologyJson, { parseSpecialCharSequences: false, delay: 0 });

      // Submit the form (button says "Create")
      cy.contains('button', 'Create').click();
      cy.wait(1000);

      // Verify it appears in the list
      cy.contains(testName).should('exist');
    });
  });

  // ═══════════════════ Runs Tab ═══════════════════

  describe('Runs Tab Functionality', () => {
    beforeEach(() => {
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();
      cy.get('.mat-mdc-tab').contains('Runs').click();
    });

    it('should show Active/All toggle', () => {
      cy.get('mat-button-toggle-group').should('exist');
      cy.get('mat-button-toggle').contains('Active').should('exist');
      cy.get('mat-button-toggle').contains('All').should('exist');
    });

    it('should show Start New Run button', () => {
      cy.contains('button', 'Start New Run').should('exist');
    });

    it('should open start run form when button clicked', () => {
      cy.contains('button', 'Start New Run').click();
      cy.get('input[placeholder*="proc-compliance"]').should('exist');
    });

    it('should toggle between Active and All views', () => {
      // Click All toggle
      cy.get('mat-button-toggle').contains('All').click();
      cy.wait(500);
      // Click Active toggle
      cy.get('mat-button-toggle').contains('Active').click();
      cy.wait(500);
      // Should not crash or show errors
      cy.get('app-process-runs').should('exist');
    });

    it('should show View Details button on run cards', () => {
      // First create a run via API so we have something to see
      cy.apiPost('/process/definition', {
        name: `e2e-ui-proc-${Date.now()}`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [{ id: 's1', name: 'Auto Step', stepType: 'AUTO' }]
        }]
      }).then((res) => {
        const procId = res.body.id;
        return cy.apiPost(`/process/definition/${procId}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        const procId = res.body.id;
        return cy.apiPost('/process/run', { processDefinitionId: procId, initialData: {} });
      }).then(() => {
        // Switch to All view and refresh
        cy.get('mat-button-toggle').contains('All').click();
        cy.wait(1000);
        // Should have at least one run card with View Details
        cy.contains('button', 'View Details').should('exist');
      });
    });
  });

  // ═══════════════════ Controls Tab ═══════════════════

  describe('Controls Tab Functionality', () => {
    beforeEach(() => {
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();
      cy.get('.mat-mdc-tab').contains('Controls').click();
    });

    it('should show New Control button', () => {
      cy.contains('button', 'New Control').should('exist');
    });

    it('should open create form with expression field', () => {
      cy.contains('button', 'New Control').click();
      cy.contains('SpEL Expression').should('exist');
      cy.contains('Gate Type').should('exist');
      cy.contains('Severity').should('exist');
    });

    it('should create a control and show it in the list', () => {
      const ctrlName = `e2e-ui-ctrl-${Date.now()}`;

      cy.contains('button', 'New Control').click();

      // Fill name
      cy.get('input[placeholder*="sox"], input').first().clear().type(ctrlName);

      // Fill expression
      cy.get('textarea[placeholder*="amount"], textarea').first().clear().type('#amount > 0');

      // Submit
      cy.contains('button', 'Create').click();
      cy.wait(1000);

      // Verify it appears in the list
      cy.contains(ctrlName).should('exist');
    });

    it('should show test expression panel when expression is entered in create view', () => {
      cy.contains('button', 'New Control').click();

      // Enter an expression to trigger the test panel
      cy.get('textarea').first().clear().type('#amount > 0');

      // Test panel should appear
      cy.contains('Test Expression').should('exist');
      cy.contains('Test Context JSON').should('exist');
    });
  });

  // ═══════════════════ Discovery Tab ═══════════════════

  describe('Discovery Tab', () => {
    beforeEach(() => {
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();
      cy.get('.mat-mdc-tab').contains('Discovery').click();
    });

    it('should render the discovery suggestions component', () => {
      cy.get('app-process-discovery-suggestions').should('exist');
    });

    it('should show Discover button', () => {
      cy.contains('button', 'Discover').should('exist');
    });
  });

  // ═══════════════════ Run Detail View ═══════════════════

  describe('Run Detail View', () => {
    let runId: string;

    before(() => {
      // Create and complete a full run via API
      cy.waitForBackend();
      cy.apiPost('/process/definition', {
        name: `e2e-detail-proc-${Date.now()}`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [
            { id: 's1', name: 'Auto Step', stepType: 'AUTO', graphNodeIds: ['detail-test-node'] },
            { id: 's2', name: 'Second Auto', stepType: 'AUTO' }
          ]
        }]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: { testKey: 'testValue' }
        });
      }).then((res) => {
        runId = res.body.id;
      });
    });

    it('should open detail view when View Details is clicked', () => {
      cy.visit('/');
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();
      cy.get('.mat-mdc-tab').contains('Runs').click();

      // Switch to All to see completed runs
      cy.get('mat-button-toggle').contains('All').click();
      cy.wait(1000);

      // Click View Details on the first run
      cy.contains('button', 'View Details').first().click();

      // Detail view should render
      cy.get('app-process-run-detail').should('exist');
    });

    it('should show step pipeline in detail view', () => {
      cy.visit('/');
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();
      cy.get('.mat-mdc-tab').contains('Runs').click();
      cy.get('mat-button-toggle').contains('All').click();
      cy.wait(1000);

      cy.contains('button', 'View Details').first().click();

      // Should show step timeline nodes
      cy.get('.timeline-node').should('have.length.gte', 1);
      // Should show Step Pipeline heading
      cy.contains('Step Pipeline').should('exist');
    });

    it('should show Assess Risk and Explain Step buttons in detail view', () => {
      cy.visit('/');
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();
      cy.get('.mat-mdc-tab').contains('Runs').click();
      cy.get('mat-button-toggle').contains('All').click();
      cy.wait(1000);

      cy.contains('button', 'View Details').first().click();
      cy.get('app-process-run-detail').should('exist');

      // Risk assessment section should have Assess Risk button
      cy.contains('button', 'Assess Risk').should('exist');

      // Click on a step to see the explain button
      cy.get('.timeline-node').first().click();
      cy.contains('button', 'Explain Step').should('exist');
    });

    it('should show back button to return to list', () => {
      cy.visit('/');
      cy.get('.nav-items').contains('Tools').click();
      cy.get('.sub-tab, button').contains('Process Engine').click();
      cy.get('.mat-mdc-tab').contains('Runs').click();
      cy.get('mat-button-toggle').contains('All').click();
      cy.wait(1000);

      cy.contains('button', 'View Details').first().click();
      cy.get('app-process-run-detail').should('exist');

      // Click back button
      cy.get('app-process-run-detail').find('button[mattooltip="Back to list"]').click();

      // Should return to the list view
      cy.get('app-process-run-detail').should('not.exist');
    });
  });
});
