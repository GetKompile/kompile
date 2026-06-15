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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA, SimpleChange } from '@angular/core';
import { of, throwError } from 'rxjs';

import { GraphNodePopoverComponent } from './graph-node-popover.component';
import { GraphService } from '../../services/graph.service';
import { GraphNode } from '../../models/graph-models';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeGraphNode(overrides: Partial<GraphNode> = {}): GraphNode {
  return {
    id: 1,
    nodeId: 'node-abc-123',
    nodeType: 'DOCUMENT',
    title: 'Invoice Report Q1 2025',
    description: 'Quarterly invoice summary for ACME Corp.',
    contentPreview: 'Total invoices: 42. Amount due: $125,000.',
    sourceType: 'PDF',
    pathOrUrl: '/docs/invoices/q1-2025.pdf',
    confidence: 0.92,
    childCount: 5,
    edgeCount: 3,
    ...overrides
  } as GraphNode;
}

// ─────────────────────────────────────────────────────────────────────────────

describe('GraphNodePopoverComponent', () => {
  let component: GraphNodePopoverComponent;
  let fixture: ComponentFixture<GraphNodePopoverComponent>;
  let mockGraphService: jasmine.SpyObj<GraphService>;

  const mockNode = makeGraphNode();

  beforeEach(async () => {
    mockGraphService = jasmine.createSpyObj('GraphService', ['getNode']);
    mockGraphService.getNode.and.returnValue(of(mockNode));

    await TestBed.configureTestingModule({
      imports: [GraphNodePopoverComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(GraphNodePopoverComponent, {
      set: { providers: [{ provide: GraphService, useValue: mockGraphService }] }
    })
    .compileComponents();

    fixture = TestBed.createComponent(GraphNodePopoverComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Component creation
  // ─────────────────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 2. Loads node details when nodeId is set
  // ─────────────────────────────────────────────────────────────────────────

  describe('Loading node details', () => {
    it('should call graphService.getNode when nodeId and visible are both set', () => {
      component.nodeId = 'node-abc-123';
      component.visible = true;
      component.ngOnChanges({
        nodeId: new SimpleChange(null, 'node-abc-123', true),
        visible: new SimpleChange(false, true, true)
      });
      expect(mockGraphService.getNode).toHaveBeenCalledWith('node-abc-123');
    });

    it('should populate node property after successful load', () => {
      component.nodeId = 'node-abc-123';
      component.visible = true;
      component.ngOnChanges({
        nodeId: new SimpleChange(null, 'node-abc-123', true),
        visible: new SimpleChange(false, true, true)
      });
      expect(component.node).toBeTruthy();
      expect(component.node!.nodeId).toBe('node-abc-123');
    });

    it('should set error when getNode fails', () => {
      mockGraphService.getNode.and.returnValue(throwError(() => new Error('not found')));
      component.nodeId = 'node-missing';
      component.visible = true;
      component.ngOnChanges({
        nodeId: new SimpleChange(null, 'node-missing', true),
        visible: new SimpleChange(false, true, true)
      });
      expect(component.error).toBeTruthy();
      expect(component.node).toBeNull();
    });

    it('should not call getNode when visible is false', () => {
      mockGraphService.getNode.calls.reset();
      component.nodeId = 'node-abc-123';
      component.visible = false;
      component.ngOnChanges({
        nodeId: new SimpleChange(null, 'node-abc-123', true)
      });
      expect(mockGraphService.getNode).not.toHaveBeenCalled();
    });

    it('should not reload if the same nodeId is already loaded', () => {
      // First load
      component.nodeId = 'node-abc-123';
      component.visible = true;
      component.ngOnChanges({
        nodeId: new SimpleChange(null, 'node-abc-123', true),
        visible: new SimpleChange(false, true, true)
      });
      const callCount = mockGraphService.getNode.calls.count();

      // Re-trigger with the same nodeId — should not call again
      component.ngOnChanges({
        visible: new SimpleChange(true, true, false)
      });
      expect(mockGraphService.getNode.calls.count()).toBe(callCount);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. Loading state
  // ─────────────────────────────────────────────────────────────────────────

  describe('Loading state', () => {
    it('should start with loading = false and node = null before any input', () => {
      expect(component.loading).toBeFalse();
      expect(component.node).toBeNull();
    });

    it('should reset node and error when visibility turns false', () => {
      // First make a successful load
      component.nodeId = 'node-abc-123';
      component.visible = true;
      component.ngOnChanges({
        nodeId: new SimpleChange(null, 'node-abc-123', true),
        visible: new SimpleChange(false, true, true)
      });
      expect(component.node).toBeTruthy();

      // Now hide the popover
      component.visible = false;
      component.ngOnChanges({
        visible: new SimpleChange(true, false, false)
      });
      expect(component.node).toBeNull();
      expect(component.error).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. Renders node title, type, description
  // ─────────────────────────────────────────────────────────────────────────

  describe('Node content properties', () => {
    beforeEach(() => {
      component.nodeId = 'node-abc-123';
      component.visible = true;
      component.ngOnChanges({
        nodeId: new SimpleChange(null, 'node-abc-123', true),
        visible: new SimpleChange(false, true, true)
      });
    });

    it('should expose the node title', () => {
      expect(component.node!.title).toBe('Invoice Report Q1 2025');
    });

    it('should expose the node type', () => {
      expect(component.node!.nodeType).toBe('DOCUMENT');
    });

    it('should expose the node description', () => {
      expect(component.node!.description).toBe('Quarterly invoice summary for ACME Corp.');
    });

    it('should expose contentPreview when present', () => {
      expect(component.node!.contentPreview).toContain('invoices');
    });

    it('should expose confidence when present', () => {
      expect(component.node!.confidence).toBe(0.92);
    });

    it('should expose childCount', () => {
      expect(component.node!.childCount).toBe(5);
    });

    it('should expose edgeCount', () => {
      expect(component.node!.edgeCount).toBe(3);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. navigateToGraph (openInGraph) event emission
  // ─────────────────────────────────────────────────────────────────────────

  describe('openInGraph event', () => {
    beforeEach(() => {
      component.nodeId = 'node-abc-123';
      component.visible = true;
      component.ngOnChanges({
        nodeId: new SimpleChange(null, 'node-abc-123', true),
        visible: new SimpleChange(false, true, true)
      });
    });

    it('openInGraph EventEmitter should be defined', () => {
      expect(component.openInGraph).toBeTruthy();
    });

    it('should emit openInGraph with the correct nodeId when triggered', () => {
      const spy = spyOn(component.openInGraph, 'emit');
      component.openInGraph.emit(component.node!.nodeId);
      expect(spy).toHaveBeenCalledWith('node-abc-123');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 6. close() emits closed
  // ─────────────────────────────────────────────────────────────────────────

  describe('close()', () => {
    it('should emit the closed event when close() is called', () => {
      const spy = spyOn(component.closed, 'emit');
      component.close();
      expect(spy).toHaveBeenCalled();
    });

    it('closed EventEmitter should be defined', () => {
      expect(component.closed).toBeTruthy();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 7. Different node types
  // ─────────────────────────────────────────────────────────────────────────

  describe('Different node types', () => {
    const nodeTypes = ['SOURCE', 'DOCUMENT', 'SNIPPET', 'ENTITY', 'CUSTOM'] as const;

    nodeTypes.forEach(nodeType => {
      it(`should load a node of type ${nodeType}`, () => {
        const typedNode = makeGraphNode({ nodeType });
        mockGraphService.getNode.and.returnValue(of(typedNode));

        component.nodeId = `node-type-${nodeType}`;
        component.visible = true;
        component.ngOnChanges({
          nodeId: new SimpleChange(null, `node-type-${nodeType}`, true),
          visible: new SimpleChange(false, true, true)
        });

        expect(component.node).toBeTruthy();
        expect(component.node!.nodeType).toBe(nodeType);
      });
    });
  });
});
