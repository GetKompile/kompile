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

import { D3Link, D3Node, D3VisualizationData } from '../../models/graph-models';

/**
 * Adds the nodes and links of {@code extra} that {@code data} lacks: nodes by id, links by the
 * key the canvas draws them under. Entries already in {@code data} win, so a reload keeps the
 * server's latest copy. An extra link is also dropped when {@code data} already links its two
 * nodes, in either direction: each view names an edge its own way (a stored edge id, or
 * source::target::layer), so one edge can arrive under two keys. Returns {@code data} itself when
 * nothing is new.
 */
export function mergeVisualization(
  data: D3VisualizationData,
  extra: D3VisualizationData | null | undefined
): D3VisualizationData {
  if (!extra) return data;
  const nodeIds = new Set(data.nodes.map(node => node.id));
  const nodes: D3Node[] = [];
  for (const node of extra.nodes) {
    if (!nodeIds.has(node.id)) {
      nodeIds.add(node.id);
      nodes.push(node);
    }
  }
  const linkKeys = new Set(data.links.map(visualizationLinkKey));
  const linkedPairs = new Set(data.links.map(pairKey));
  const links: D3Link[] = [];
  for (const link of extra.links) {
    const key = visualizationLinkKey(link);
    if (!linkKeys.has(key) && !linkedPairs.has(pairKey(link))) {
      linkKeys.add(key);
      links.push(link);
    }
  }
  if (nodes.length === 0 && links.length === 0) return data;
  return { ...data, nodes: [...data.nodes, ...nodes], links: [...data.links, ...links] };
}

/**
 * Removes the given nodes, the links with the given {@link visualizationLinkKey keys}, and every
 * link touching a removed node. Returns {@code data} itself when nothing matches.
 */
export function withoutVisualization(
  data: D3VisualizationData,
  nodeIds: ReadonlySet<string>,
  linkKeys: ReadonlySet<string>
): D3VisualizationData {
  const nodes = data.nodes.filter(node => !nodeIds.has(node.id));
  const links = data.links.filter(link => !linkKeys.has(visualizationLinkKey(link))
    && !nodeIds.has(endpointKey(link.source)) && !nodeIds.has(endpointKey(link.target)));
  return nodes.length === data.nodes.length && links.length === data.links.length
    ? data
    : { ...data, nodes, links };
}

/**
 * The link keys a stored edge is drawn under. A matrix edge id is source::target::layer, the key
 * the expand view gives the edge too; a bidirectional edge is held in both directions, so the
 * reverse key is included. Any other id is its only key.
 */
export function storedEdgeLinkIds(edgeId: string, sourceId: string, targetId: string): string[] {
  const forward = `${sourceId}::${targetId}::`;
  return edgeId.startsWith(forward)
    ? [edgeId, `${targetId}::${sourceId}::${edgeId.slice(forward.length)}`]
    : [edgeId];
}

/** The canvas's edge key: the link id, else {@code source→target:type}. */
export function visualizationLinkKey(link: D3Link): string {
  return link.id || `${endpointKey(link.source)}→${endpointKey(link.target)}:${link.type}`;
}

/** A link's two nodes in a fixed order, so both directions of a pair share one key. */
function pairKey(link: D3Link): string {
  const source = endpointKey(link.source);
  const target = endpointKey(link.target);
  return JSON.stringify(source < target ? [source, target] : [target, source]);
}

function endpointKey(endpoint: D3Link['source']): string {
  return typeof endpoint === 'string' ? endpoint : String(endpoint.id);
}
