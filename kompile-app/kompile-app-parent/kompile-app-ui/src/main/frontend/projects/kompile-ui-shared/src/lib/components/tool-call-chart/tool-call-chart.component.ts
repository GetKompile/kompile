/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { ChangeDetectionStrategy, Component, ElementRef, Input, OnChanges, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import * as d3 from 'd3';
import { ToolCallChart, ToolCallGraphChart, ToolCallSeriesChart } from '../../models/api-models';

/** The newest chart description drawn here; a newer one is left to the tool's text answer. */
const CHART_VERSION = 1;
/** The one place a drawn graph may link to: the chat app's own graph page. */
const GRAPH_LINK = /^#\/graph(\?|$)/;
const SERIES_BOX = { width: 560, height: 200, top: 10, right: 12, bottom: 34, left: 46 };
const GRAPH_BOX = { width: 560, height: 260, rx: 165, ry: 88 };
const FOCUS_RADIUS = 9;
const NODE_RADIUS = 6;
/** At most this many x labels are written; every value still names its label in its tooltip. */
const MAX_TICKS = 8;

/** A legend entry. */
interface Swatch {
  name: string;
  color: string;
}

interface SeriesView {
  kind: 'bar' | 'line';
  title: string;
  unit?: string;
  labels: string[];
  /** One value per label, null where the series has none. */
  series: { name: string; color: string; values: (number | null)[] }[];
}

interface GraphNode {
  label: string;
  type?: string;
  /** The type's color; an untyped node keeps the stylesheet's. */
  color: string | null;
}

/** A neighbour of the focus, with every edge between the two drawn as one line. */
interface Spoke {
  node: GraphNode;
  labels: string[];
  outgoing: boolean;
  incoming: boolean;
}

interface GraphView {
  kind: 'graph';
  title: string;
  unit?: undefined;
  focus: GraphNode;
  spokes: Spoke[];
  /** The labels of the focus's edges to itself, or null when it has none. */
  loop: string[] | null;
  types: Swatch[];
  omitted: number;
  link: string | null;
}

type ChartView = SeriesView | GraphView;

/**
 * A tool's answer drawn under its tool-call row: bars, lines, or the graph around one node.
 *
 * The description comes from kompile-cli-insights Charts, the same answer the CLI prints as a table
 * with sparklines. A description this card cannot read draws nothing and leaves the text to say it.
 */
@Component({
  selector: 'app-tool-call-chart',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure *ngIf="view as v" data-testid="tool-call-chart" [attr.data-kind]="v.kind">
      <figcaption>{{ v.title }}<span *ngIf="v.unit" class="dim"> · {{ v.unit }}</span></figcaption>
      <div #canvas></div>
      <ul *ngIf="swatches.length > 1" class="legend dim">
        <li *ngFor="let swatch of swatches"><i [style.background]="swatch.color"></i>{{ swatch.name }}</li>
      </ul>
      <div *ngIf="graph && (graph.omitted > 0 || graph.link)" class="graph-footer dim">
        <span *ngIf="graph.omitted > 0">+{{ graph.omitted }} more {{ graph.omitted === 1 ? 'relation' : 'relations' }} not drawn</span>
        <a *ngIf="graph.link" [href]="graph.link" target="_blank" rel="noopener">Open in graph</a>
      </div>
    </figure>
  `,
  styleUrls: ['./tool-call-chart.component.css']
})
export class ToolCallChartComponent implements OnChanges {
  /** The chart as kompile-cli-insights Charts describes it. */
  @Input() chart?: ToolCallChart | null;

  view: ChartView | null = null;
  graph: GraphView | null = null;
  swatches: Swatch[] = [];

  private canvasElement?: HTMLElement;

  /** The drawing surface appears with the figure, so the first drawing waits for it. */
  @ViewChild('canvas')
  set canvas(ref: ElementRef<HTMLElement> | undefined) {
    this.canvasElement = ref?.nativeElement;
    this.draw();
  }

  ngOnChanges(): void {
    this.view = toView(this.chart);
    this.graph = this.view?.kind === 'graph' ? this.view : null;
    this.swatches = !this.view ? []
      : this.view.kind === 'graph' ? this.view.types
      : this.view.series.map(series => ({ name: series.name, color: series.color }));
    this.draw();
  }

  private draw(): void {
    const host = this.canvasElement;
    if (!host) {
      return;
    }
    d3.select(host).selectAll('*').remove();
    if (this.view?.kind === 'graph') {
      drawGraph(host, this.view);
    } else if (this.view) {
      drawSeries(host, this.view);
    }
  }
}

/** The chart in drawable form, or null when it is missing or of a version or kind this card cannot draw. */
function toView(chart: ToolCallChart | null | undefined): ChartView | null {
  if (!chart || typeof chart.v !== 'number' || chart.v > CHART_VERSION) {
    return null;
  }
  switch (chart.kind) {
    case 'bar':
    case 'line':
      return seriesView(chart);
    case 'graph':
      return graphView(chart);
    default:
      return null;
  }
}

function seriesView(chart: ToolCallSeriesChart): SeriesView | null {
  const labels = list(chart.labels).map(label => String(label));
  // Every series gets one value per label: missing ones are gaps, extra ones have no label to sit on.
  const series = list(chart.series).map((one, i) => {
    const values = list(one?.values);
    return {
      name: String(one?.name ?? ''),
      color: d3.schemeTableau10[i % d3.schemeTableau10.length],
      values: labels.map((_, j) => finite(values[j]))
    };
  });
  return labels.length && series.length
    ? { kind: chart.kind, title: String(chart.title ?? ''), unit: chart.unit || undefined, labels, series }
    : null;
}

function graphView(chart: ToolCallGraphChart): GraphView | null {
  if (chart.focus == null) {
    return null;
  }
  const focusId = String(chart.focus);
  const listed = new Map<string, { label: string; type?: string }>();
  for (const node of list(chart.nodes)) {
    if (node?.id != null && !listed.has(String(node.id))) {
      listed.set(String(node.id), { label: String(node.label ?? node.id), type: node.type || undefined });
    }
  }
  const types: Swatch[] = [];
  const typeColor = d3.scaleOrdinal<string, string>(d3.schemeTableau10);
  // A node the list leaves out, the focus included, is still drawn, under its id.
  const node = (id: string): GraphNode => {
    const label = listed.get(id)?.label ?? id;
    const type = listed.get(id)?.type;
    if (!type) {
      return { label, color: null };
    }
    const color = typeColor(type);
    if (!types.some(swatch => swatch.name === type)) {
      types.push({ name: type, color });
    }
    return { label, type, color };
  };
  const focus = node(focusId);
  const spokes = new Map<string, Spoke>();
  let loop: string[] | null = null;
  for (const edge of list(chart.edges)) {
    if (!edge) {
      continue;
    }
    const source = String(edge.source);
    const target = String(edge.target);
    const label = edge.label ? String(edge.label) : '';
    if (source === focusId && target === focusId) {
      loop = loop ?? [];
      if (label && !loop.includes(label)) {
        loop.push(label);
      }
      continue;
    }
    const other = source === focusId ? target : target === focusId ? source : null;
    if (other === null) {
      continue;
    }
    let spoke = spokes.get(other);
    if (!spoke) {
      spoke = { node: node(other), labels: [], outgoing: false, incoming: false };
      spokes.set(other, spoke);
    }
    if (label && !spoke.labels.includes(label)) {
      spoke.labels.push(label);
    }
    if (edge.directed) {
      spoke.outgoing ||= source === focusId;
      spoke.incoming ||= target === focusId;
    }
  }
  return {
    kind: 'graph',
    title: String(chart.title ?? ''),
    focus,
    spokes: [...spokes.values()],
    loop,
    types,
    omitted: Math.max(0, finite(chart.omitted) ?? 0),
    link: typeof chart.link === 'string' && GRAPH_LINK.test(chart.link) ? chart.link : null
  };
}

function drawSeries(host: HTMLElement, view: SeriesView): void {
  const box = SERIES_BOX;
  const width = box.width - box.left - box.right;
  const height = box.height - box.top - box.bottom;
  const plot = svg(host, box.width, box.height, view.title)
    .append('g').attr('transform', `translate(${box.left},${box.top})`);
  const values = view.series.flatMap(series => series.values).filter((value): value is number => value !== null);
  const low = Math.min(0, d3.min(values) ?? 0);
  const high = Math.max(0, d3.max(values) ?? 0);
  const y = d3.scaleLinear().domain([low, high > low ? high : low + 1]).nice().range([height, 0]);
  const indices = d3.range(view.labels.length);
  let x: d3.AxisScale<number>;
  if (view.kind === 'bar') {
    const band = d3.scaleBand<number>().domain(indices).range([0, width]).paddingInner(0.2).paddingOuter(0.1);
    const slot = d3.scaleBand<number>().domain(d3.range(view.series.length)).range([0, band.bandwidth()]).padding(0.08);
    view.series.forEach((series, s) => {
      plot.append('g').attr('fill', series.color)
        .selectAll('rect')
        .data(points(series.values))
        .join('rect')
        .attr('x', point => (band(point.index) ?? 0) + (slot(s) ?? 0))
        .attr('y', point => y(Math.max(0, point.value)))
        .attr('width', slot.bandwidth())
        .attr('height', point => Math.abs(y(point.value) - y(0)))
        .append('title').text(point => tooltip(view, series.name, point));
    });
    x = band;
  } else {
    const position = d3.scalePoint<number>().domain(indices).range([0, width]).padding(0.5);
    // A missing value breaks the line rather than dropping it to zero.
    const line = d3.line<number | null>()
      .defined(value => value !== null)
      .x((_, index) => position(index) ?? 0)
      .y(value => y(value ?? 0));
    view.series.forEach(series => {
      plot.append('path').attr('class', 'line').attr('d', line(series.values))
        .attr('fill', 'none').attr('stroke', series.color).attr('stroke-width', 2);
      plot.append('g').attr('class', 'points').attr('fill', series.color)
        .selectAll('circle')
        .data(points(series.values))
        .join('circle')
        .attr('cx', point => position(point.index) ?? 0)
        .attr('cy', point => y(point.value))
        .attr('r', 3)
        .append('title').text(point => tooltip(view, series.name, point));
    });
    x = position;
  }
  const every = Math.ceil(indices.length / MAX_TICKS);
  plot.append('g').attr('class', 'axis x').attr('transform', `translate(0,${height})`)
    .call(d3.axisBottom(x).tickValues(indices.filter(index => index % every === 0))
      .tickFormat(index => clip(view.labels[index], 12)).tickSizeOuter(0))
    .selectAll<SVGGElement, number>('.tick').append('title').text(index => view.labels[index]);
  const [bottom, top] = y.domain();
  plot.append('g').attr('class', 'axis y')
    .call(d3.axisLeft(y).ticks(4).tickFormat(d3.format(Math.max(-bottom, top) >= 1000 ? '~s' : '~g')));
  // The axes take the card's font rather than d3's sans-serif.
  plot.selectAll('.axis').attr('font-family', null).attr('font-size', null);
}

function drawGraph(host: HTMLElement, view: GraphView): void {
  const { width, height, rx, ry } = GRAPH_BOX;
  const cx = width / 2;
  const cy = height / 2;
  const root = svg(host, width, height, view.title);
  const edges = root.append('g');
  const nodes = root.append('g');
  const place = (node: GraphNode, x: number, y: number, radius: number, angle: number | null, max: number) => {
    const group = nodes.append('g').attr('class', angle === null ? 'node focus' : 'node');
    group.append('title').text(node.type ? `${node.label} · ${node.type}` : node.label);
    const circle = group.append('circle').attr('cx', x).attr('cy', y).attr('r', radius);
    if (node.color) {
      circle.style('fill', node.color);
    }
    // A neighbour's label sits outward along its spoke; the focus's sits below it.
    const cos = angle === null ? 0 : Math.cos(angle);
    const sin = angle === null ? 1 : Math.sin(angle);
    group.append('text').attr('class', 'node-label')
      .attr('x', x + cos * (radius + 3)).attr('y', y + sin * (radius + 3))
      .attr('dy', sin > 0.3 ? '0.8em' : sin < -0.3 ? '-0.25em' : '0.35em')
      .attr('text-anchor', cos > 0.3 ? 'start' : cos < -0.3 ? 'end' : 'middle')
      .text(clip(node.label, max));
  };
  const count = view.spokes.length;
  // A loop sits above the focus, so the first neighbour moves off the top.
  const start = -Math.PI / 2 + (view.loop && count ? Math.PI / count : 0);
  view.spokes.forEach((spoke, i) => {
    const angle = start + (2 * Math.PI * i) / count;
    const nx = cx + rx * Math.cos(angle);
    const ny = cy + ry * Math.sin(angle);
    const length = Math.hypot(nx - cx, ny - cy);
    const ux = (nx - cx) / length;
    const uy = (ny - cy) / length;
    const x1 = cx + ux * (FOCUS_RADIUS + 2);
    const y1 = cy + uy * (FOCUS_RADIUS + 2);
    const x2 = nx - ux * (NODE_RADIUS + 2);
    const y2 = ny - uy * (NODE_RADIUS + 2);
    const edge = edges.append('g').attr('class', 'edge');
    const label = spoke.labels.join(', ');
    if (label) {
      edge.append('title').text(label);
    }
    edge.append('line').attr('x1', x1).attr('y1', y1).attr('x2', x2).attr('y2', y2);
    const heading = Math.atan2(uy, ux);
    if (spoke.outgoing) {
      edge.append('path').attr('class', 'arrow').attr('d', arrow(x2, y2, heading));
    }
    if (spoke.incoming) {
      edge.append('path').attr('class', 'arrow').attr('d', arrow(x1, y1, heading + Math.PI));
    }
    if (label) {
      edge.append('text').attr('class', 'edge-label')
        .attr('x', x1 + (x2 - x1) * 0.55).attr('y', y1 + (y2 - y1) * 0.55).attr('dy', '0.35em')
        .attr('text-anchor', 'middle')
        .text(clip(label, 24));
    }
    place(spoke.node, nx, ny, NODE_RADIUS, angle, 14);
  });
  if (view.loop) {
    const loop = edges.append('g').attr('class', 'edge loop');
    const label = view.loop.join(', ');
    if (label) {
      loop.append('title').text(label);
    }
    loop.append('circle').attr('cx', cx).attr('cy', cy - FOCUS_RADIUS - 8).attr('r', 8);
    if (label) {
      loop.append('text').attr('class', 'edge-label')
        .attr('x', cx + 11).attr('y', cy - FOCUS_RADIUS - 12)
        .text(clip(label, 20));
    }
  }
  place(view.focus, cx, cy, FOCUS_RADIUS, null, 18);
}

function svg(host: HTMLElement, width: number, height: number, title: string) {
  return d3.select(host).append('svg')
    .attr('viewBox', `0 0 ${width} ${height}`)
    .attr('role', 'img')
    .attr('aria-label', title);
}

/** A filled arrowhead with its tip at (x, y), pointing along the heading. */
function arrow(x: number, y: number, heading: number): string {
  const backX = x - 7 * Math.cos(heading);
  const backY = y - 7 * Math.sin(heading);
  const sideX = 3.5 * Math.sin(heading);
  const sideY = 3.5 * Math.cos(heading);
  return `M${x},${y}L${backX + sideX},${backY - sideY}L${backX - sideX},${backY + sideY}Z`;
}

/** The values a series has, each with the index of its label. */
function points(values: (number | null)[]): { index: number; value: number }[] {
  return values.flatMap((value, index) => value === null ? [] : [{ index, value }]);
}

function tooltip(view: SeriesView, series: string, point: { index: number; value: number }): string {
  const unit = !view.unit ? '' : view.unit === '%' ? '%' : ' ' + view.unit;
  return `${view.labels[point.index]} · ${series}: ${amount(point.value)}${unit}`;
}

function amount(value: number): string {
  return d3.format(Number.isInteger(value) ? ',' : ',.4~g')(value);
}

function clip(text: string, max: number): string {
  return text.length > max ? text.slice(0, max - 1) + '…' : text;
}

function finite(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function list<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value : [];
}
