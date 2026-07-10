/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { of } from 'rxjs';

import { GraphCanvasComponent } from './graph-canvas.component';
import { D3Link } from '../../models/graph-models';

describe('GraphCanvasComponent reasoning overlay lookup', () => {
  function createComponent(): GraphCanvasComponent {
    return new GraphCanvasComponent(
      { markForCheck: () => undefined } as any,
      { run: (fn: () => unknown) => fn() } as any,
      { theme$: of(null) } as any
    );
  }

  it('uses composite reasoning keys for id-less edges', () => {
    const component = createComponent();
    const link: D3Link = {
      id: '',
      source: 'n2',
      target: 'n3',
      type: 'SHARED_ENTITY',
      weight: 0.4
    };
    component.reasoningLayerOverlayEnabled = true;
    component.reasoningEdgeLayerMap = new Map([
      ['n2->n3:SHARED_ENTITY', { activeLayers: ['ontology'], ontologyViolation: true }]
    ]);

    expect((component as any).resolveEdgeColor(link)).toBe('#F44336');
    expect((component as any).resolveEdgeSize(link)).toBe(2.2);
  });

  it('uses the canvas edge key variant for id-less edges', () => {
    const component = createComponent();
    const link: D3Link = {
      id: '',
      source: 'n2',
      target: 'n3',
      type: 'SHARED_ENTITY',
      weight: 0.4
    };
    component.reasoningLayerOverlayEnabled = true;
    component.reasoningEdgeLayerMap = new Map([
      ['n2→n3:SHARED_ENTITY', { activeLayers: ['ontology'], ontologyViolation: true }]
    ]);

    expect((component as any).resolveEdgeColor(link)).toBe('#F44336');
  });

  it('uses inferred relationship scores for reasoning edge color and size', () => {
    const component = createComponent();
    const link: D3Link = {
      id: 'edge-inferred',
      source: 'wine-red',
      target: 'wine',
      type: 'HIERARCHICAL',
      weight: 0.4
    };
    component.reasoningLayerOverlayEnabled = true;
    component.reasoningEdgeLayerMap = new Map([
      ['edge-inferred', {
        activeLayers: ['ontology'],
        inferredRelationship: true,
        inferredRelationshipScore: 0.9
      }]
    ]);

    expect((component as any).resolveEdgeColor(link)).toBe('rgb(30,171,173)');
    expect((component as any).resolveEdgeSize(link)).toBe(2.8);
  });

  it('prioritizes selected process evidence on nodes and edges', () => {
    const component = createComponent();
    const node = { id: 'evidence-node', type: 'ENTITY', metadata: {} } as any;
    const link: D3Link = {
      id: 'evidence-edge',
      source: 'evidence-node',
      target: 'target-node',
      type: 'HIERARCHICAL',
      weight: 0.4
    };
    component.posteriorOverlay = { 'evidence-node': 0.99 };
    component.reasoningLayerOverlayEnabled = true;
    component.reasoningEdgeLayerMap = new Map([
      ['evidence-edge', { activeLayers: ['ontology'], ontologyViolation: true }]
    ]);
    component.processEvidenceNodeIds = new Set(['evidence-node']);
    component.processEvidenceEdgeIds = new Set(['evidence-edge']);

    expect((component as any).resolveNodeColor(node)).toBe('#00838f');
    expect((component as any).resolveEdgeColor(link)).toBe('#00acc1');
    expect((component as any).resolveEdgeSize(link)).toBe(3.2);
  });
});
