/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { AfterViewInit, Component, ElementRef, Input, OnChanges, SimpleChanges, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import * as d3 from 'd3';
import { EventObservationService } from '../../services/event-observation.service';
import { ObservationHistoryPoint } from '../../models/event-observation-models';

/**
 * Renders an event's empirical prior probability as it evolved over successive observations
 * (crawls / mutations), as a D3 line chart. Accepts an {@code eventKey} input or its own lookup box.
 */
@Component({
  selector: 'app-event-prior-history',
  standalone: true,
  imports: [CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  template: `
    <mat-card class="eo-history">
      <div class="lookup">
        <mat-form-field appearance="outline" class="key-field">
          <mat-label>Event key (e.g. entity:NODE_ID or conn:A:TYPE:B)</mat-label>
          <input matInput [(ngModel)]="eventKey" (keyup.enter)="load()">
        </mat-form-field>
        <button mat-raised-button color="primary" (click)="load()">
          <mat-icon>show_chart</mat-icon> Load history
        </button>
      </div>

      <div *ngIf="loaded && points.length === 0" class="empty">No observations recorded for this event yet.</div>
      <div class="chart-wrap" [class.hidden]="points.length === 0">
        <svg #chart></svg>
      </div>
      <p *ngIf="points.length" class="caption">
        {{ points.length }} observations &middot; current prior
        <strong>{{ (points[points.length - 1].probability * 100) | number:'1.1-1' }}%</strong>
      </p>
    </mat-card>
  `,
  styles: [`
    .eo-history { padding: 16px; }
    .lookup { display: flex; gap: 12px; align-items: baseline; }
    .key-field { flex: 1; min-width: 320px; }
    .chart-wrap { margin-top: 8px; }
    .chart-wrap.hidden { display: none; }
    .empty { color: #888; padding: 24px 0; }
    .caption { color: #666; font-size: 12px; }
    svg { width: 100%; height: 260px; }
  `]
})
export class EventPriorHistoryComponent implements AfterViewInit, OnChanges {

  @Input() eventKey = '';
  @ViewChild('chart') chartRef?: ElementRef<SVGSVGElement>;

  points: ObservationHistoryPoint[] = [];
  loaded = false;

  constructor(private svc: EventObservationService) {}

  ngAfterViewInit(): void {
    if (this.eventKey) {
      this.load();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['eventKey'] && this.eventKey) {
      this.load();
    }
  }

  load(): void {
    if (!this.eventKey) {
      return;
    }
    this.svc.getHistory(this.eventKey).subscribe({
      next: pts => { this.points = pts || []; this.loaded = true; setTimeout(() => this.render(), 0); },
      error: () => { this.points = []; this.loaded = true; }
    });
  }

  private render(): void {
    if (!this.chartRef || this.points.length === 0) {
      return;
    }
    const host = this.chartRef.nativeElement;
    d3.select(host).selectAll('*').remove();

    const width = host.clientWidth || 600;
    const height = host.clientHeight || 260;
    const margin = { top: 12, right: 16, bottom: 28, left: 40 };
    const innerW = Math.max(10, width - margin.left - margin.right);
    const innerH = Math.max(10, height - margin.top - margin.bottom);

    const g = d3.select(host).append('g').attr('transform', `translate(${margin.left},${margin.top})`);
    const data = this.points.map((p, i) => ({ i, y: p.probability }));

    const x = d3.scaleLinear().domain([0, Math.max(1, data.length - 1)]).range([0, innerW]);
    const y = d3.scaleLinear().domain([0, 1]).range([innerH, 0]);

    g.append('g').attr('transform', `translate(0,${innerH})`).call(d3.axisBottom(x).ticks(Math.min(8, data.length)));
    g.append('g').call(d3.axisLeft(y).ticks(5).tickFormat(d3.format('.0%')));
    g.append('g').call(d3.axisLeft(y).ticks(5).tickSize(-innerW).tickFormat(() => ''))
      .selectAll('line').attr('stroke', '#e6e6e6').attr('stroke-dasharray', '2,2');

    const line = d3.line<{ i: number; y: number }>().x(d => x(d.i)).y(d => y(d.y));
    g.append('path').datum(data).attr('fill', 'none').attr('stroke', '#3f51b5')
      .attr('stroke-width', 2).attr('d', line);
    g.selectAll('circle').data(data).enter().append('circle')
      .attr('cx', d => x(d.i)).attr('cy', d => y(d.y)).attr('r', 2.5).attr('fill', '#3f51b5');
  }
}
