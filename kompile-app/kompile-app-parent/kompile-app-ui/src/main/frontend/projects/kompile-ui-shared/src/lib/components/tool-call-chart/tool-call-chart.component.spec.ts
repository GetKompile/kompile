/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ToolCallChartComponent } from './tool-call-chart.component';
import { ToolCallGraphChart, ToolCallSeriesChart } from '../../models/api-models';

describe('ToolCallChartComponent', () => {
  let fixture: ComponentFixture<ToolCallChartComponent>;
  let host: HTMLElement;

  const runs: ToolCallSeriesChart = {
    v: 1, kind: 'bar', title: 'Test runs', unit: 'runs', labels: ['mon', 'tue', 'wed'],
    series: [{ name: 'passing', values: [3, 4, null] }, { name: 'failing', values: [1, 0, 2] }]
  };
  const passRate: ToolCallSeriesChart = {
    v: 1, kind: 'line', title: 'Pass rate for core', unit: '%', labels: ['1', '2', '3', '4'],
    series: [{ name: 'passed', values: [90, null, 80, 87.5] }]
  };
  const alice: ToolCallGraphChart = {
    v: 1, kind: 'graph', title: 'Around Alice in People', factSheet: { id: '7', name: 'People' }, focus: 'a',
    nodes: [{ id: 'a', label: 'Alice', type: 'Person' }, { id: 'b', label: 'Acme', type: 'Org' },
      { id: 'c', label: 'Bob', type: 'Person' }],
    edges: [
      { source: 'a', target: 'b', label: 'worksFor', directed: true },
      { source: 'b', target: 'a', label: 'employs', directed: true },
      { source: 'c', target: 'a', label: 'knows', directed: false },
      { source: 'a', target: 'a', label: 'mentors', directed: true }
    ],
    omitted: 3,
    link: '#/graph?factSheetId=7&focusNode=a'
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ToolCallChartComponent] }).compileComponents();
    fixture = TestBed.createComponent(ToolCallChartComponent);
    host = fixture.nativeElement;
  });

  function render(chart: unknown): void {
    fixture.componentRef.setInput('chart', chart);
    fixture.detectChanges();
  }

  const all = (selector: string) => Array.from(host.querySelectorAll(selector));
  const text = (selector: string) => host.querySelector(selector)?.textContent?.trim();
  /** The tooltip of each match: the first title inside it. */
  const titles = (selector: string) => all(selector).map(element => element.querySelector('title')?.textContent);

  it('draws one bar per value, grouped by label, with a tooltip each and a legend of the series', () => {
    render(runs);
    expect(host.querySelector('figure')!.getAttribute('data-kind')).toBe('bar');
    expect(text('figcaption')).toBe('Test runs · runs');
    expect(all('rect').length).toBe(5);
    expect(titles('rect')).toContain('mon · passing: 3 runs');
    expect(titles('rect')).toContain('tue · failing: 0 runs');
    expect(all('.legend li').map(item => item.textContent!.trim())).toEqual(['passing', 'failing']);
  });

  it('breaks a line where a value is missing and marks each value it has', () => {
    render(passRate);
    expect(host.querySelector('path.line')!.getAttribute('d')!.match(/M/g)!.length).toBe(2);
    expect(all('.points circle').length).toBe(3);
    expect(titles('.points circle')).toContain('4 · passed: 87.5%');
    expect(host.querySelector('.legend')).toBeNull();
  });

  it('reads a missing value as a gap and drops values past the last label', () => {
    render({ ...runs, series: [{ name: 'short', values: [1] }, { name: 'long', values: [1, 2, 3, 4] }] });
    expect(all('rect').length).toBe(4);
  });

  it('writes at most eight x labels and keeps every label in a tooltip', () => {
    const labels = Array.from({ length: 30 }, (_, i) => 'day ' + i);
    render({ v: 1, kind: 'line', title: 'Latency of read', labels, series: [{ name: 'p50', values: labels.map((_, i) => i) }] });
    expect(all('.axis.x .tick').length).toBe(8);
    expect(all('.points circle').length).toBe(30);
  });

  it('draws the focus with one spoke per neighbour, an arrow per direction and a loop', () => {
    render(alice);
    expect(host.querySelector('figure')!.getAttribute('data-kind')).toBe('graph');
    expect(all('.node').length).toBe(3);
    expect(titles('.focus')).toEqual(['Alice · Person']);
    expect(all('.edge:not(.loop)').length).toBe(2);
    expect(titles('.edge:not(.loop)')).toEqual(['worksFor, employs', 'knows']);
    expect(all('.arrow').length).toBe(2);
    expect(titles('.loop')).toEqual(['mentors']);
    expect(all('.legend li').map(item => item.textContent!.trim())).toEqual(['Person', 'Org']);
  });

  it('says how many relations it left out and links to the graph page', () => {
    render(alice);
    expect(text('.graph-footer span')).toBe('+3 more relations not drawn');
    const link = host.querySelector('.graph-footer a')!;
    expect(link.getAttribute('href')).toBe('#/graph?factSheetId=7&focusNode=a');
    expect(link.getAttribute('rel')).toBe('noopener');
  });

  it('links only to the chat app graph page', () => {
    for (const link of ['javascript:alert(1)', 'https://example.com/#/graph', '#/graphs', undefined]) {
      render({ ...alice, link, omitted: 0 });
      expect(host.querySelector('figure')).not.toBeNull();
      expect(host.querySelector('a')).toBeNull();
      expect(host.querySelector('.graph-footer')).toBeNull();
    }
  });

  it('draws a node the list leaves out under its id', () => {
    render({ ...alice, nodes: [] });
    expect(text('.focus text')).toBe('a');
    expect(host.querySelector('.legend')).toBeNull();
  });

  it('draws nothing for a version or kind it does not know', () => {
    for (const chart of [{ ...runs, v: 2 }, { ...runs, v: undefined }, { v: 1, kind: 'pie', title: 'Share' },
      { ...runs, labels: [] }, null, undefined]) {
      render(chart);
      expect(host.querySelector('figure')).toBeNull();
    }
  });

  it('writes labels as text, never as markup', () => {
    render({ ...runs, title: '<b>runs</b>', labels: ['<img src=x onerror=alert(1)>', 'tue', 'wed'] });
    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('b')).toBeNull();
    expect(text('figcaption')).toContain('<b>runs</b>');
    expect(titles('.axis.x .tick')[0]).toBe('<img src=x onerror=alert(1)>');
  });

  it('replaces the drawing when the chart changes and clears it when the chart goes', () => {
    render(runs);
    render(alice);
    expect(all('svg').length).toBe(1);
    expect(all('rect').length).toBe(0);
    expect(all('.node').length).toBe(3);
    render(null);
    expect(all('svg').length).toBe(0);
    render(passRate);
    expect(all('svg').length).toBe(1);
    expect(all('path.line').length).toBe(1);
  });
});
