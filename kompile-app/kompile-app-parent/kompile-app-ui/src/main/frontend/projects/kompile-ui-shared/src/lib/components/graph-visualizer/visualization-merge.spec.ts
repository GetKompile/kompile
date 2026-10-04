/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { mergeVisualization, storedEdgeLinkIds, visualizationLinkKey, withoutVisualization } from './visualization-merge';
import { D3Link, D3Node, D3VisualizationData } from '../../models/graph-models';

function node(id: string, label = id): D3Node {
  return { id, type: 'ENTITY', label };
}

function link(source: string, target: string, id = `${source}::${target}::RELATED`): D3Link {
  return { id, source, target, type: 'RELATED', weight: 1 };
}

describe('mergeVisualization', () => {
  it('adds the nodes and links the view lacks, after the existing ones', () => {
    const data: D3VisualizationData = {
      nodes: [node('a'), node('b')],
      links: [link('a', 'b')],
      statistics: { totalNodes: 5000 }
    };
    const extra: D3VisualizationData = { nodes: [node('b'), node('c')], links: [link('a', 'b'), link('b', 'c')] };

    const merged = mergeVisualization(data, extra);

    expect(merged.nodes.map(n => n.id)).toEqual(['a', 'b', 'c']);
    expect(merged.links.map(l => l.id)).toEqual(['a::b::RELATED', 'b::c::RELATED']);
    expect(merged.statistics).toBe(data.statistics);
    expect(data.nodes.length).withContext('the input is not mutated').toBe(2);
  });

  it('keeps the view\'s copy of a node both payloads carry', () => {
    const data: D3VisualizationData = { nodes: [node('a', 'fresh')], links: [] };
    const extra: D3VisualizationData = { nodes: [node('a', 'stale'), node('b')], links: [] };

    expect(mergeVisualization(data, extra).nodes[0].label).toBe('fresh');
  });

  it('returns the same object when nothing is new, so the view does not redraw', () => {
    const data: D3VisualizationData = { nodes: [node('a'), node('b')], links: [link('a', 'b')] };

    expect(mergeVisualization(data, { nodes: [node('a')], links: [link('a', 'b')] })).toBe(data);
    expect(mergeVisualization(data, null)).toBe(data);
    expect(mergeVisualization(data, undefined)).toBe(data);
  });

  it('matches id-less links by endpoints and type, as the canvas keys them', () => {
    const data: D3VisualizationData = {
      nodes: [node('a'), node('b'), node('c')],
      links: [{ id: '', source: 'a', target: 'b', type: 'RELATED', weight: 1 }]
    };
    const extra: D3VisualizationData = {
      nodes: [],
      links: [
        { id: '', source: node('b'), target: node('c'), type: 'CITATION', weight: 1 },
        { id: '', source: 'b', target: 'c', type: 'CITATION', weight: 1 },
        { id: '', source: 'b', target: 'c', type: 'RELATED', weight: 1 }
      ]
    };

    const merged = mergeVisualization(data, extra);

    expect(merged.links.map(visualizationLinkKey)).toEqual(['a→b:RELATED', 'b→c:CITATION', 'b→c:RELATED']);
  });

  it('drops an extra link between two nodes the view already links, in either direction', () => {
    const data: D3VisualizationData = {
      nodes: [node('a'), node('b'), node('c')],
      links: [link('a', 'b', 'stored-edge-1')]
    };
    const extra: D3VisualizationData = {
      nodes: [],
      links: [link('a', 'b'), link('b', 'a'), link('b', 'c'), link('b', 'c', 'b::c::CITATION')]
    };

    expect(mergeVisualization(data, extra).links.map(l => l.id))
      .toEqual(['stored-edge-1', 'b::c::RELATED', 'b::c::CITATION']);
  });
});

describe('withoutVisualization', () => {
  it('drops the named nodes and links, and every link touching a dropped node', () => {
    const data: D3VisualizationData = {
      nodes: [node('a'), node('b'), node('c'), node('d')],
      links: [
        link('a', 'b', 'ab'),
        { id: 'ca', source: node('c'), target: node('a'), type: 'RELATED', weight: 1 },
        link('b', 'c', 'bc'),
        link('b', 'd', 'bd'),
        link('a', 'd', 'ad')
      ],
      statistics: { totalNodes: 4 }
    };

    const kept = withoutVisualization(data, new Set(['c']), new Set(['ab']));

    expect(kept.nodes.map(n => n.id)).toEqual(['a', 'b', 'd']);
    expect(kept.links.map(l => l.id)).toEqual(['bd', 'ad']);
    expect(kept.statistics).toBe(data.statistics);
    expect(data.links.length).withContext('the input is not mutated').toBe(5);
  });

  it('returns the same object when nothing matches', () => {
    const data: D3VisualizationData = { nodes: [node('a'), node('b')], links: [link('a', 'b')] };

    expect(withoutVisualization(data, new Set(['z']), new Set(['y::z::RELATED']))).toBe(data);
  });
});

describe('storedEdgeLinkIds', () => {
  it('names both directions of a matrix edge id, which a bidirectional edge is drawn under', () => {
    expect(storedEdgeLinkIds('a::b::worksFor', 'a', 'b')).toEqual(['a::b::worksFor', 'b::a::worksFor']);
  });

  it('keeps any other id as its only key', () => {
    expect(storedEdgeLinkIds('edge-17', 'a', 'b')).toEqual(['edge-17']);
  });
});
