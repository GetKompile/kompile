/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  Component, OnInit, OnDestroy, OnChanges, SimpleChanges,
  Input, ElementRef, ViewChild, AfterViewInit
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import * as d3 from 'd3';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  TrainingMetricsService,
  TrainingMetricsSnapshot,
  TrainingLogEntry,
  DspAlertEvent,
  TrainingCompletedEvent,
  EvalCompleteEvent
} from '../../../services/training-metrics.service';
import { TrainingJobHistory } from '../../../services/training-history.service';

/** EMA smoothing factor (higher = less smooth). */
const EMA_ALPHA = 0.1;

@Component({
  selector: 'app-training-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    MatButtonModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule
  ],
  templateUrl: './training-dashboard.component.html',
  styleUrls: ['./training-dashboard.component.css']
})
export class TrainingDashboardComponent implements OnInit, OnDestroy, OnChanges, AfterViewInit {
  @Input() job!: TrainingJobHistory;

  @ViewChild('lossChart') lossChartRef!: ElementRef;
  @ViewChild('lrChart') lrChartRef!: ElementRef;
  @ViewChild('throughputChart') throughputChartRef!: ElementRef;
  @ViewChild('gradNormChart') gradNormChartRef!: ElementRef;
  @ViewChild('dspChart') dspChartRef!: ElementRef;
  @ViewChild('gpuMemChart') gpuMemChartRef!: ElementRef;
  @ViewChild('heapChart') heapChartRef!: ElementRef;
  @ViewChild('logContainer') logContainerRef!: ElementRef;

  metrics: TrainingMetricsSnapshot[] = [];
  logs: TrainingLogEntry[] = [];
  connected = false;
  isLive = false;

  // Current values for summary cards
  currentStep = 0;
  currentEpoch = 0;
  currentLoss = 0;
  currentEvalLoss = 0;
  currentLR = 0;
  currentTokPerSec = 0;
  currentSampPerSec = 0;
  currentGradNorm = 0;

  // DSP monitoring values
  dspPlanPhase = '';
  dspTotalHits = 0;
  dspTotalMisses = 0;
  dspRecompilationCount = 0;
  dspFrozenExecutionCount = 0;
  dspNumSegments = 0;
  dspHitRate = 0;
  hasDspData = false;

  // Per-segment execution breakdown
  dspSegmentsWarmup = 0;
  dspSegmentsReplayed = 0;
  dspSegmentsCaptured = 0;
  dspSegmentsSlotBySlot = 0;
  dspSegmentsFailed = 0;
  dspCapturedGraphSegments = 0;
  dspStepTimeMs = 0;

  // Buffer pool stats
  dspBufferPoolBytes = 0;
  dspBufferPoolReused = 0;
  dspColoringSavedBytes = 0;

  // GPU memory
  gpuMemUsedBytes = 0;
  gpuMemFreeBytes = 0;
  gpuMemTotalBytes = 0;
  gpuPoolUsedBytes = 0;
  gpuPoolReservedBytes = 0;
  numGpuDevices = 0;
  gpuDeviceNames = '';
  hasGpuData = false;

  // JVM heap
  heapUsedBytes = 0;
  heapMaxBytes = 0;
  heapUsagePercent = 0;
  hasMemoryData = false;

  // DSP recompilation alerts
  dspAlerts: DspAlertEvent[] = [];
  showDspAlertBanner = false;
  latestDspAlert: DspAlertEvent | null = null;

  // Completion state
  completionEvent: TrainingCompletedEvent | null = null;
  evalResults: EvalCompleteEvent | null = null;

  // Readiness state
  metricsReady = false;

  // Chart dimensions
  private chartMargin = { top: 20, right: 20, bottom: 40, left: 60 };
  private chartWidth = 0;
  private chartHeight = 160;

  private subs: Subscription[] = [];
  private resizeObserver: ResizeObserver | null = null;
  private viewReady = false;

  constructor(private metricsService: TrainingMetricsService) {}

  ngOnInit(): void {
    this.subs.push(
      this.metricsService.metrics$.subscribe(m => {
        this.metrics = m;
        this.updateSummary();
        if (this.viewReady) this.renderAllCharts();
      }),
      this.metricsService.logs$.subscribe(l => {
        this.logs = l;
        this.scrollLogsToBottom();
      }),
      this.metricsService.connected$.subscribe(c => this.connected = c),
      this.metricsService.metricsReady$.subscribe(r => this.metricsReady = r),
      this.metricsService.dspAlert$.subscribe(alert => {
        this.dspAlerts.push(alert);
        this.latestDspAlert = alert;
        this.showDspAlertBanner = true;
      }),
      this.metricsService.statusUpdate$.subscribe(status => {
        if (status?.status === 'COMPLETED' || status?.status === 'FAILED' || status?.status === 'CANCELLED') {
          this.isLive = false;
          if (this.job) {
            this.job.status = status.status as any;
          }
        }
      }),
      this.metricsService.completed$.subscribe(event => {
        this.completionEvent = event;
        this.isLive = false;
        if (this.job) {
          this.job.status = 'COMPLETED';
          this.job.finalLoss = event.finalLoss;
          this.job.totalSteps = event.totalSteps;
          this.job.totalEpochs = event.totalEpochs;
        }
      }),
      this.metricsService.evalComplete$.subscribe(event => {
        this.evalResults = event;
      })
    );
  }

  ngAfterViewInit(): void {
    this.viewReady = true;
    // Observe container resize for responsive charts
    if (this.lossChartRef) {
      this.resizeObserver = new ResizeObserver(() => this.renderAllCharts());
      this.resizeObserver.observe(this.lossChartRef.nativeElement.parentElement);
    }
    this.loadJobData();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['job'] && !changes['job'].firstChange) {
      this.loadJobData();
    }
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
    this.metricsService.disconnect();
    if (this.resizeObserver) this.resizeObserver.disconnect();
  }

  private loadJobData(): void {
    if (!this.job) return;
    this.metricsService.disconnect();

    this.isLive = this.job.status === 'RUNNING' || this.job.status === 'QUEUED';
    if (this.isLive) {
      this.metricsService.connectToStream(this.job.taskId);
      // Also load any existing historical data for this job
      this.metricsService.getMetricsHistory(this.job.taskId).subscribe({
        next: m => {
          if (m && m.length > 0) {
            this.metricsService.metrics$.next(m);
          }
        },
        error: () => {}
      });
    } else {
      this.metricsService.loadHistorical(this.job.taskId);
    }
  }

  private updateSummary(): void {
    if (this.metrics.length === 0) return;
    const last = this.metrics[this.metrics.length - 1];
    this.currentStep = last.step;
    this.currentEpoch = last.epoch;
    this.currentLoss = last.trainLoss;
    this.currentEvalLoss = last.evalLoss;
    this.currentLR = last.learningRate;
    this.currentTokPerSec = last.tokensPerSecond;
    this.currentSampPerSec = last.samplesPerSecond;
    this.currentGradNorm = last.customMetrics?.['gradNorm'] || 0;

    // DSP monitoring summary
    this.dspPlanPhase = last.dspPlanPhase || '';
    this.dspFrozenExecutionCount = last.dspFrozenExecutionCount || 0;
    this.dspNumSegments = last.dspNumSegments || 0;
    this.dspRecompilationCount = last.dspRecompilationCount || 0;

    // Accumulate total hits/misses across all snapshots
    this.dspTotalHits = this.metrics.reduce((sum, m) => sum + (m.dspReplayCacheHits || 0), 0);
    this.dspTotalMisses = this.metrics.reduce((sum, m) => sum + (m.dspReplayCacheMisses || 0), 0);
    const totalAttempts = this.dspTotalHits + this.dspTotalMisses;
    this.dspHitRate = totalAttempts > 0 ? (this.dspTotalHits / totalAttempts) * 100 : 0;
    this.hasDspData = this.metrics.some(m => m.dspPlanPhase != null && m.dspPlanPhase !== '');

    // Per-segment execution breakdown
    this.dspSegmentsWarmup = last.dspSegmentsWarmup || 0;
    this.dspSegmentsReplayed = last.dspSegmentsReplayed || 0;
    this.dspSegmentsCaptured = last.dspSegmentsCaptured || 0;
    this.dspSegmentsSlotBySlot = last.dspSegmentsSlotBySlot || 0;
    this.dspSegmentsFailed = last.dspSegmentsFailed || 0;
    this.dspCapturedGraphSegments = last.dspCapturedGraphSegments || 0;
    this.dspStepTimeMs = last.dspStepTimeMs || 0;

    // Buffer pool stats
    this.dspBufferPoolBytes = last.dspBufferPoolBytes || 0;
    this.dspBufferPoolReused = last.dspBufferPoolReused || 0;
    this.dspColoringSavedBytes = last.dspColoringSavedBytes || 0;

    // GPU memory
    this.gpuMemUsedBytes = last.gpuMemUsedBytes || 0;
    this.gpuMemFreeBytes = last.gpuMemFreeBytes || 0;
    this.gpuMemTotalBytes = last.gpuMemTotalBytes || 0;
    this.gpuPoolUsedBytes = last.gpuPoolUsedBytes || 0;
    this.gpuPoolReservedBytes = last.gpuPoolReservedBytes || 0;
    this.numGpuDevices = last.numGpuDevices || 0;
    this.gpuDeviceNames = last.gpuDeviceNames || '';
    this.hasGpuData = this.gpuMemTotalBytes > 0;

    // JVM heap
    this.heapUsedBytes = last.heapUsedBytes || 0;
    this.heapMaxBytes = last.heapMaxBytes || 0;
    this.heapUsagePercent = last.heapUsagePercent || 0;
    this.hasMemoryData = this.heapMaxBytes > 0 || this.gpuMemTotalBytes > 0;
  }

  private scrollLogsToBottom(): void {
    setTimeout(() => {
      if (this.logContainerRef) {
        const el = this.logContainerRef.nativeElement;
        el.scrollTop = el.scrollHeight;
      }
    }, 0);
  }

  // ==================== Chart Rendering ====================

  private renderAllCharts(): void {
    if (!this.viewReady || this.metrics.length < 2) return;
    this.renderLossChart();
    this.renderLRChart();
    this.renderThroughputChart();
    this.renderGradNormChart();
    if (this.hasDspData) this.renderDspChart();
    if (this.hasGpuData) this.renderGpuMemChart();
    if (this.hasMemoryData) this.renderHeapChart();
  }

  private getChartWidth(el: ElementRef): number {
    if (!el) return 400;
    return el.nativeElement.clientWidth || 400;
  }

  /** Compute EMA from raw values. */
  private computeEMA(values: number[]): number[] {
    if (values.length === 0) return [];
    const ema = [values[0]];
    for (let i = 1; i < values.length; i++) {
      ema.push(EMA_ALPHA * values[i] + (1 - EMA_ALPHA) * ema[i - 1]);
    }
    return ema;
  }

  private renderLossChart(): void {
    const el = this.lossChartRef?.nativeElement;
    if (!el) return;

    const width = this.getChartWidth(this.lossChartRef);
    const m = this.chartMargin;
    const innerW = width - m.left - m.right;
    const innerH = this.chartHeight - m.top - m.bottom;

    d3.select(el).selectAll('*').remove();

    const svg = d3.select(el)
      .append('svg')
      .attr('width', width)
      .attr('height', this.chartHeight);

    const g = svg.append('g').attr('transform', `translate(${m.left},${m.top})`);

    const steps = this.metrics.map(d => d.step);
    const trainLoss = this.metrics.map(d => d.trainLoss);
    const evalLoss = this.metrics.map(d => d.evalLoss);
    const trainEMA = this.computeEMA(trainLoss);

    // Filter out zero/NaN eval losses
    const hasEval = evalLoss.some(v => v > 0 && !isNaN(v));
    const allLoss = [...trainLoss.filter(v => v > 0), ...(hasEval ? evalLoss.filter(v => v > 0) : [])];

    const x = d3.scaleLinear().domain(d3.extent(steps) as [number, number]).range([0, innerW]);
    const y = d3.scaleLinear()
      .domain([Math.min(...allLoss) * 0.95, Math.max(...allLoss) * 1.05])
      .range([innerH, 0]);

    // Axes
    g.append('g').attr('transform', `translate(0,${innerH})`).call(d3.axisBottom(x).ticks(6))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');
    g.append('g').call(d3.axisLeft(y).ticks(5).tickFormat(d3.format('.4f')))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');

    // Grid lines
    g.append('g').attr('class', 'grid')
      .call(d3.axisLeft(y).ticks(5).tickSize(-innerW).tickFormat('' as any))
      .selectAll('line').attr('stroke', '#e0e0e0').attr('stroke-dasharray', '2,2');
    g.selectAll('.grid .domain').remove();

    // Train loss line (raw - faded)
    const trainLine = d3.line<number>()
      .x((_, i) => x(steps[i]))
      .y(d => y(d))
      .defined(d => d > 0 && !isNaN(d));

    g.append('path')
      .datum(trainLoss)
      .attr('fill', 'none')
      .attr('stroke', '#1976d2')
      .attr('stroke-width', 1)
      .attr('stroke-opacity', 0.3)
      .attr('d', trainLine);

    // Train loss EMA (smoothed - bold)
    g.append('path')
      .datum(trainEMA)
      .attr('fill', 'none')
      .attr('stroke', '#1976d2')
      .attr('stroke-width', 2)
      .attr('d', trainLine);

    // Eval loss line
    if (hasEval) {
      const evalLine = d3.line<number>()
        .x((_, i) => x(steps[i]))
        .y(d => y(d))
        .defined(d => d > 0 && !isNaN(d));

      g.append('path')
        .datum(evalLoss)
        .attr('fill', 'none')
        .attr('stroke', '#e65100')
        .attr('stroke-width', 2)
        .attr('stroke-dasharray', '4,2')
        .attr('d', evalLine);
    }

    // Legend
    const legend = g.append('g').attr('transform', `translate(${innerW - 160}, 0)`);
    legend.append('line').attr('x1', 0).attr('x2', 20).attr('y1', 5).attr('y2', 5)
      .attr('stroke', '#1976d2').attr('stroke-width', 2);
    legend.append('text').attr('x', 24).attr('y', 9).text('Train Loss (EMA)')
      .attr('fill', '#555').style('font-size', '10px');
    if (hasEval) {
      legend.append('line').attr('x1', 0).attr('x2', 20).attr('y1', 20).attr('y2', 20)
        .attr('stroke', '#e65100').attr('stroke-width', 2).attr('stroke-dasharray', '4,2');
      legend.append('text').attr('x', 24).attr('y', 24).text('Eval Loss')
        .attr('fill', '#555').style('font-size', '10px');
    }

    // Axis labels
    svg.append('text').attr('x', width / 2).attr('y', this.chartHeight - 4)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Step');
    svg.append('text').attr('transform', 'rotate(-90)').attr('x', -this.chartHeight / 2).attr('y', 14)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Loss');
  }

  private renderLRChart(): void {
    const el = this.lrChartRef?.nativeElement;
    if (!el) return;

    const width = this.getChartWidth(this.lrChartRef);
    const m = this.chartMargin;
    const innerW = width - m.left - m.right;
    const innerH = this.chartHeight - m.top - m.bottom;

    d3.select(el).selectAll('*').remove();

    const svg = d3.select(el)
      .append('svg')
      .attr('width', width)
      .attr('height', this.chartHeight);

    const g = svg.append('g').attr('transform', `translate(${m.left},${m.top})`);

    const steps = this.metrics.map(d => d.step);
    const lrValues = this.metrics.map(d => d.learningRate);

    const x = d3.scaleLinear().domain(d3.extent(steps) as [number, number]).range([0, innerW]);
    const y = d3.scaleLinear()
      .domain([0, Math.max(...lrValues) * 1.1])
      .range([innerH, 0]);

    g.append('g').attr('transform', `translate(0,${innerH})`).call(d3.axisBottom(x).ticks(6))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');
    g.append('g').call(d3.axisLeft(y).ticks(5).tickFormat(d3.format('.2e')))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');

    g.append('g').attr('class', 'grid')
      .call(d3.axisLeft(y).ticks(5).tickSize(-innerW).tickFormat('' as any))
      .selectAll('line').attr('stroke', '#e0e0e0').attr('stroke-dasharray', '2,2');
    g.selectAll('.grid .domain').remove();

    const line = d3.line<number>()
      .x((_, i) => x(steps[i]))
      .y(d => y(d));

    // Gradient fill under the curve
    const area = d3.area<number>()
      .x((_, i) => x(steps[i]))
      .y0(innerH)
      .y1(d => y(d));

    const defs = svg.append('defs');
    const gradient = defs.append('linearGradient').attr('id', 'lr-gradient')
      .attr('x1', '0').attr('y1', '0').attr('x2', '0').attr('y2', '1');
    gradient.append('stop').attr('offset', '0%').attr('stop-color', '#43a047').attr('stop-opacity', 0.3);
    gradient.append('stop').attr('offset', '100%').attr('stop-color', '#43a047').attr('stop-opacity', 0.02);

    g.append('path').datum(lrValues).attr('fill', 'url(#lr-gradient)').attr('d', area);
    g.append('path').datum(lrValues).attr('fill', 'none')
      .attr('stroke', '#43a047').attr('stroke-width', 2).attr('d', line);

    svg.append('text').attr('x', width / 2).attr('y', this.chartHeight - 4)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Step');
    svg.append('text').attr('transform', 'rotate(-90)').attr('x', -this.chartHeight / 2).attr('y', 14)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Learning Rate');
  }

  private renderThroughputChart(): void {
    const el = this.throughputChartRef?.nativeElement;
    if (!el) return;

    const width = this.getChartWidth(this.throughputChartRef);
    const m = this.chartMargin;
    const innerW = width - m.left - m.right;
    const innerH = this.chartHeight - m.top - m.bottom;

    d3.select(el).selectAll('*').remove();

    const svg = d3.select(el)
      .append('svg')
      .attr('width', width)
      .attr('height', this.chartHeight);

    const g = svg.append('g').attr('transform', `translate(${m.left},${m.top})`);

    const steps = this.metrics.map(d => d.step);
    const tokPerSec = this.metrics.map(d => d.tokensPerSecond);
    const sampPerSec = this.metrics.map(d => d.samplesPerSecond);

    const hasTok = tokPerSec.some(v => v > 0);
    const hasSamp = sampPerSec.some(v => v > 0);
    const allVals = [...(hasTok ? tokPerSec.filter(v => v > 0) : []), ...(hasSamp ? sampPerSec.filter(v => v > 0) : [])];
    if (allVals.length === 0) return;

    const x = d3.scaleLinear().domain(d3.extent(steps) as [number, number]).range([0, innerW]);
    const y = d3.scaleLinear().domain([0, Math.max(...allVals) * 1.1]).range([innerH, 0]);

    g.append('g').attr('transform', `translate(0,${innerH})`).call(d3.axisBottom(x).ticks(6))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');
    g.append('g').call(d3.axisLeft(y).ticks(5).tickFormat(d3.format('.0f')))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');

    g.append('g').attr('class', 'grid')
      .call(d3.axisLeft(y).ticks(5).tickSize(-innerW).tickFormat('' as any))
      .selectAll('line').attr('stroke', '#e0e0e0').attr('stroke-dasharray', '2,2');
    g.selectAll('.grid .domain').remove();

    if (hasTok) {
      const tokLine = d3.line<number>()
        .x((_, i) => x(steps[i]))
        .y(d => y(d))
        .defined(d => d > 0);
      g.append('path').datum(tokPerSec).attr('fill', 'none')
        .attr('stroke', '#7b1fa2').attr('stroke-width', 2).attr('d', tokLine);
    }

    if (hasSamp) {
      const sampLine = d3.line<number>()
        .x((_, i) => x(steps[i]))
        .y(d => y(d))
        .defined(d => d > 0);
      g.append('path').datum(sampPerSec).attr('fill', 'none')
        .attr('stroke', '#00838f').attr('stroke-width', 2).attr('stroke-dasharray', '4,2').attr('d', sampLine);
    }

    const legend = g.append('g').attr('transform', `translate(${innerW - 180}, 0)`);
    if (hasTok) {
      legend.append('line').attr('x1', 0).attr('x2', 20).attr('y1', 5).attr('y2', 5)
        .attr('stroke', '#7b1fa2').attr('stroke-width', 2);
      legend.append('text').attr('x', 24).attr('y', 9).text('Tokens/sec')
        .attr('fill', '#555').style('font-size', '10px');
    }
    if (hasSamp) {
      const yOff = hasTok ? 15 : 0;
      legend.append('line').attr('x1', 0).attr('x2', 20).attr('y1', 5 + yOff).attr('y2', 5 + yOff)
        .attr('stroke', '#00838f').attr('stroke-width', 2).attr('stroke-dasharray', '4,2');
      legend.append('text').attr('x', 24).attr('y', 9 + yOff).text('Samples/sec')
        .attr('fill', '#555').style('font-size', '10px');
    }

    svg.append('text').attr('x', width / 2).attr('y', this.chartHeight - 4)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Step');
    svg.append('text').attr('transform', 'rotate(-90)').attr('x', -this.chartHeight / 2).attr('y', 14)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Throughput');
  }

  private renderGradNormChart(): void {
    const el = this.gradNormChartRef?.nativeElement;
    if (!el) return;

    const gradNorms = this.metrics.map(d => d.customMetrics?.['gradNorm'] || 0);
    const hasData = gradNorms.some(v => v > 0);
    if (!hasData) {
      d3.select(el).selectAll('*').remove();
      return;
    }

    const width = this.getChartWidth(this.gradNormChartRef);
    const m = this.chartMargin;
    const innerW = width - m.left - m.right;
    const innerH = this.chartHeight - m.top - m.bottom;

    d3.select(el).selectAll('*').remove();

    const svg = d3.select(el)
      .append('svg')
      .attr('width', width)
      .attr('height', this.chartHeight);

    const g = svg.append('g').attr('transform', `translate(${m.left},${m.top})`);

    const steps = this.metrics.map(d => d.step);
    const validNorms = gradNorms.filter(v => v > 0);

    const x = d3.scaleLinear().domain(d3.extent(steps) as [number, number]).range([0, innerW]);
    const y = d3.scaleLinear().domain([0, Math.max(...validNorms) * 1.1]).range([innerH, 0]);

    g.append('g').attr('transform', `translate(0,${innerH})`).call(d3.axisBottom(x).ticks(6))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');
    g.append('g').call(d3.axisLeft(y).ticks(5).tickFormat(d3.format('.3f')))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');

    g.append('g').attr('class', 'grid')
      .call(d3.axisLeft(y).ticks(5).tickSize(-innerW).tickFormat('' as any))
      .selectAll('line').attr('stroke', '#e0e0e0').attr('stroke-dasharray', '2,2');
    g.selectAll('.grid .domain').remove();

    // Max grad norm threshold line
    if (this.job?.maxGradNorm) {
      g.append('line')
        .attr('x1', 0).attr('x2', innerW)
        .attr('y1', y(this.job.maxGradNorm)).attr('y2', y(this.job.maxGradNorm))
        .attr('stroke', '#c62828').attr('stroke-width', 1).attr('stroke-dasharray', '6,3');
      g.append('text')
        .attr('x', innerW - 4).attr('y', y(this.job.maxGradNorm) - 4)
        .attr('text-anchor', 'end').attr('fill', '#c62828').style('font-size', '9px')
        .text(`clip: ${this.job.maxGradNorm}`);
    }

    const line = d3.line<number>()
      .x((_, i) => x(steps[i]))
      .y(d => y(d))
      .defined(d => d > 0);

    g.append('path').datum(gradNorms).attr('fill', 'none')
      .attr('stroke', '#f57c00').attr('stroke-width', 2).attr('d', line);

    svg.append('text').attr('x', width / 2).attr('y', this.chartHeight - 4)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Step');
    svg.append('text').attr('transform', 'rotate(-90)').attr('x', -this.chartHeight / 2).attr('y', 14)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Grad Norm');
  }

  private renderDspChart(): void {
    const el = this.dspChartRef?.nativeElement;
    if (!el) return;

    const width = this.getChartWidth(this.dspChartRef);
    const m = this.chartMargin;
    const innerW = width - m.left - m.right;
    const innerH = this.chartHeight - m.top - m.bottom;

    d3.select(el).selectAll('*').remove();

    const svg = d3.select(el)
      .append('svg')
      .attr('width', width)
      .attr('height', this.chartHeight);

    const g = svg.append('g').attr('transform', `translate(${m.left},${m.top})`);

    const steps = this.metrics.map(d => d.step);
    const hits = this.metrics.map(d => d.dspReplayCacheHits || 0);
    const misses = this.metrics.map(d => d.dspReplayCacheMisses || 0);

    const allVals = [...hits, ...misses];
    const maxVal = Math.max(...allVals, 1);

    const x = d3.scaleLinear().domain(d3.extent(steps) as [number, number]).range([0, innerW]);
    const y = d3.scaleLinear().domain([0, maxVal * 1.1]).range([innerH, 0]);

    // Axes
    g.append('g').attr('transform', `translate(0,${innerH})`).call(d3.axisBottom(x).ticks(6))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');
    g.append('g').call(d3.axisLeft(y).ticks(5).tickFormat(d3.format('.0f')))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');

    // Grid
    g.append('g').attr('class', 'grid')
      .call(d3.axisLeft(y).ticks(5).tickSize(-innerW).tickFormat('' as any))
      .selectAll('line').attr('stroke', '#e0e0e0').attr('stroke-dasharray', '2,2');
    g.selectAll('.grid .domain').remove();

    // Hits line (green — good)
    const hitsLine = d3.line<number>()
      .x((_, i) => x(steps[i]))
      .y(d => y(d));
    g.append('path').datum(hits).attr('fill', 'none')
      .attr('stroke', '#2e7d32').attr('stroke-width', 2).attr('d', hitsLine);

    // Misses line (red — recompilations)
    const missesLine = d3.line<number>()
      .x((_, i) => x(steps[i]))
      .y(d => y(d));
    g.append('path').datum(misses).attr('fill', 'none')
      .attr('stroke', '#c62828').attr('stroke-width', 2).attr('stroke-dasharray', '4,2').attr('d', missesLine);

    // Miss dots (recompilation events)
    const missPoints = this.metrics
      .map((m, i) => ({ step: m.step, miss: m.dspReplayCacheMisses || 0, idx: i }))
      .filter(p => p.miss > 0);
    g.selectAll('.miss-dot')
      .data(missPoints)
      .enter()
      .append('circle')
      .attr('cx', d => x(d.step))
      .attr('cy', d => y(d.miss))
      .attr('r', 3)
      .attr('fill', '#c62828')
      .attr('stroke', '#fff')
      .attr('stroke-width', 1);

    // Legend
    const legend = g.append('g').attr('transform', `translate(${innerW - 180}, 0)`);
    legend.append('line').attr('x1', 0).attr('x2', 20).attr('y1', 5).attr('y2', 5)
      .attr('stroke', '#2e7d32').attr('stroke-width', 2);
    legend.append('text').attr('x', 24).attr('y', 9).text('Cache Hits (replay)')
      .attr('fill', '#555').style('font-size', '10px');
    legend.append('line').attr('x1', 0).attr('x2', 20).attr('y1', 20).attr('y2', 20)
      .attr('stroke', '#c62828').attr('stroke-width', 2).attr('stroke-dasharray', '4,2');
    legend.append('text').attr('x', 24).attr('y', 24).text('Cache Misses (recompile)')
      .attr('fill', '#555').style('font-size', '10px');

    // Axis labels
    svg.append('text').attr('x', width / 2).attr('y', this.chartHeight - 4)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Step');
    svg.append('text').attr('transform', 'rotate(-90)').attr('x', -this.chartHeight / 2).attr('y', 14)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('DSP Cache');
  }

  private renderGpuMemChart(): void {
    const el = this.gpuMemChartRef?.nativeElement;
    if (!el) return;

    const gpuUsed = this.metrics.map(d => (d.gpuMemUsedBytes || 0) / (1024 * 1024 * 1024));
    const gpuFree = this.metrics.map(d => (d.gpuMemFreeBytes || 0) / (1024 * 1024 * 1024));
    const hasData = gpuUsed.some(v => v > 0) || gpuFree.some(v => v > 0);
    if (!hasData) { d3.select(el).selectAll('*').remove(); return; }

    const width = this.getChartWidth(this.gpuMemChartRef);
    const m = this.chartMargin;
    const innerW = width - m.left - m.right;
    const innerH = this.chartHeight - m.top - m.bottom;

    d3.select(el).selectAll('*').remove();
    const svg = d3.select(el).append('svg').attr('width', width).attr('height', this.chartHeight);
    const g = svg.append('g').attr('transform', `translate(${m.left},${m.top})`);

    const steps = this.metrics.map(d => d.step);
    const gpuTotal = this.metrics.map(d => (d.gpuMemTotalBytes || 0) / (1024 * 1024 * 1024));
    const maxGB = Math.max(...gpuTotal, ...gpuUsed) * 1.05;

    const x = d3.scaleLinear().domain(d3.extent(steps) as [number, number]).range([0, innerW]);
    const y = d3.scaleLinear().domain([0, maxGB || 1]).range([innerH, 0]);

    g.append('g').attr('transform', `translate(0,${innerH})`).call(d3.axisBottom(x).ticks(6))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');
    g.append('g').call(d3.axisLeft(y).ticks(5).tickFormat(d => d + ' GB'))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');

    g.append('g').attr('class', 'grid')
      .call(d3.axisLeft(y).ticks(5).tickSize(-innerW).tickFormat('' as any))
      .selectAll('line').attr('stroke', '#e0e0e0').attr('stroke-dasharray', '2,2');
    g.selectAll('.grid .domain').remove();

    // GPU total line (dashed ceiling)
    const totalLine = d3.line<number>().x((_, i) => x(steps[i])).y(d => y(d));
    g.append('path').datum(gpuTotal).attr('fill', 'none')
      .attr('stroke', '#bdbdbd').attr('stroke-width', 1).attr('stroke-dasharray', '6,3').attr('d', totalLine);

    // Used area fill
    const area = d3.area<number>().x((_, i) => x(steps[i])).y0(innerH).y1(d => y(d));
    const defs = svg.append('defs');
    const gradient = defs.append('linearGradient').attr('id', 'gpu-gradient')
      .attr('x1', '0').attr('y1', '0').attr('x2', '0').attr('y2', '1');
    gradient.append('stop').attr('offset', '0%').attr('stop-color', '#e53935').attr('stop-opacity', 0.4);
    gradient.append('stop').attr('offset', '100%').attr('stop-color', '#e53935').attr('stop-opacity', 0.05);
    g.append('path').datum(gpuUsed).attr('fill', 'url(#gpu-gradient)').attr('d', area);

    // Used line
    const usedLine = d3.line<number>().x((_, i) => x(steps[i])).y(d => y(d));
    g.append('path').datum(gpuUsed).attr('fill', 'none')
      .attr('stroke', '#e53935').attr('stroke-width', 2).attr('d', usedLine);

    // Legend
    const legend = g.append('g').attr('transform', `translate(${innerW - 160}, 0)`);
    legend.append('line').attr('x1', 0).attr('x2', 20).attr('y1', 5).attr('y2', 5)
      .attr('stroke', '#e53935').attr('stroke-width', 2);
    legend.append('text').attr('x', 24).attr('y', 9).text('GPU Used')
      .attr('fill', '#555').style('font-size', '10px');
    legend.append('line').attr('x1', 0).attr('x2', 20).attr('y1', 20).attr('y2', 20)
      .attr('stroke', '#bdbdbd').attr('stroke-width', 1).attr('stroke-dasharray', '6,3');
    legend.append('text').attr('x', 24).attr('y', 24).text('GPU Total')
      .attr('fill', '#555').style('font-size', '10px');

    svg.append('text').attr('x', width / 2).attr('y', this.chartHeight - 4)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Step');
    svg.append('text').attr('transform', 'rotate(-90)').attr('x', -this.chartHeight / 2).attr('y', 14)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('GPU Memory (GB)');
  }

  private renderHeapChart(): void {
    const el = this.heapChartRef?.nativeElement;
    if (!el) return;

    const heapUsed = this.metrics.map(d => (d.heapUsedBytes || 0) / (1024 * 1024));
    const heapMax = this.metrics.map(d => (d.heapMaxBytes || 0) / (1024 * 1024));
    const hasData = heapUsed.some(v => v > 0);
    if (!hasData) { d3.select(el).selectAll('*').remove(); return; }

    const width = this.getChartWidth(this.heapChartRef);
    const m = this.chartMargin;
    const innerW = width - m.left - m.right;
    const innerH = this.chartHeight - m.top - m.bottom;

    d3.select(el).selectAll('*').remove();
    const svg = d3.select(el).append('svg').attr('width', width).attr('height', this.chartHeight);
    const g = svg.append('g').attr('transform', `translate(${m.left},${m.top})`);

    const steps = this.metrics.map(d => d.step);
    const maxMB = Math.max(...heapMax, ...heapUsed) * 1.05;

    const x = d3.scaleLinear().domain(d3.extent(steps) as [number, number]).range([0, innerW]);
    const y = d3.scaleLinear().domain([0, maxMB || 1]).range([innerH, 0]);

    g.append('g').attr('transform', `translate(0,${innerH})`).call(d3.axisBottom(x).ticks(6))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');
    g.append('g').call(d3.axisLeft(y).ticks(5).tickFormat(d => (d as number >= 1024 ? ((d as number) / 1024).toFixed(1) + ' GB' : d + ' MB')))
      .selectAll('text').attr('fill', '#888').style('font-size', '10px');

    g.append('g').attr('class', 'grid')
      .call(d3.axisLeft(y).ticks(5).tickSize(-innerW).tickFormat('' as any))
      .selectAll('line').attr('stroke', '#e0e0e0').attr('stroke-dasharray', '2,2');
    g.selectAll('.grid .domain').remove();

    // Max line (dashed ceiling)
    const maxLine = d3.line<number>().x((_, i) => x(steps[i])).y(d => y(d));
    g.append('path').datum(heapMax).attr('fill', 'none')
      .attr('stroke', '#bdbdbd').attr('stroke-width', 1).attr('stroke-dasharray', '6,3').attr('d', maxLine);

    // Heap used area
    const area = d3.area<number>().x((_, i) => x(steps[i])).y0(innerH).y1(d => y(d));
    const defs = svg.append('defs');
    const gradient = defs.append('linearGradient').attr('id', 'heap-gradient')
      .attr('x1', '0').attr('y1', '0').attr('x2', '0').attr('y2', '1');
    gradient.append('stop').attr('offset', '0%').attr('stop-color', '#1565c0').attr('stop-opacity', 0.35);
    gradient.append('stop').attr('offset', '100%').attr('stop-color', '#1565c0').attr('stop-opacity', 0.05);
    g.append('path').datum(heapUsed).attr('fill', 'url(#heap-gradient)').attr('d', area);

    const usedLine = d3.line<number>().x((_, i) => x(steps[i])).y(d => y(d));
    g.append('path').datum(heapUsed).attr('fill', 'none')
      .attr('stroke', '#1565c0').attr('stroke-width', 2).attr('d', usedLine);

    svg.append('text').attr('x', width / 2).attr('y', this.chartHeight - 4)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('Step');
    svg.append('text').attr('transform', 'rotate(-90)').attr('x', -this.chartHeight / 2).attr('y', 14)
      .attr('text-anchor', 'middle').attr('fill', '#888').style('font-size', '11px').text('JVM Heap');
  }

  getDspPhaseClass(): string {
    switch (this.dspPlanPhase) {
      case 'REPLAYING': return 'dsp-phase-replaying';
      case 'SHAPES_FROZEN': return 'dsp-phase-frozen';
      case 'SLOT_BY_SLOT': return 'dsp-phase-slot';
      case 'REPLAY_BLOCKED': return 'dsp-phase-blocked';
      default: return 'dsp-phase-none';
    }
  }

  getDspPhaseLabel(): string {
    switch (this.dspPlanPhase) {
      case 'REPLAYING': return 'Replaying (optimal)';
      case 'SHAPES_FROZEN': return 'Shapes Frozen';
      case 'SLOT_BY_SLOT': return 'Slot-by-Slot (building)';
      case 'REPLAY_BLOCKED': return 'Replay Blocked';
      default: return 'N/A';
    }
  }

  dismissDspAlert(): void {
    this.showDspAlertBanner = false;
  }

  // ==================== Template Helpers ====================

  formatNumber(v: number, decimals = 4): string {
    if (v == null || isNaN(v)) return '-';
    return v.toFixed(decimals);
  }

  formatScientific(v: number): string {
    if (v == null || isNaN(v) || v === 0) return '-';
    return v.toExponential(2);
  }

  formatThroughput(v: number): string {
    if (v == null || isNaN(v) || v === 0) return '-';
    if (v >= 1000) return (v / 1000).toFixed(1) + 'k';
    return v.toFixed(0);
  }

  getLogLevelClass(level: string): string {
    switch ((level || '').toUpperCase()) {
      case 'ERROR': return 'log-error';
      case 'WARN': case 'WARNING': return 'log-warn';
      case 'INFO': return 'log-info';
      case 'DEBUG': return 'log-debug';
      default: return '';
    }
  }

  getEvalBenchmarks(): string[] {
    if (!this.evalResults?.results) return [];
    return Object.keys(this.evalResults.results);
  }

  getEvalMetrics(benchmark: string): { name: string; value: number }[] {
    if (!this.evalResults?.results?.[benchmark]) return [];
    return Object.entries(this.evalResults.results[benchmark])
      .map(([name, value]) => ({ name, value }));
  }

  formatBytes(bytes: number): string {
    if (!bytes || bytes === 0) return '-';
    if (bytes >= 1024 * 1024 * 1024) return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(0) + ' MB';
    if (bytes >= 1024) return (bytes / 1024).toFixed(0) + ' KB';
    return bytes + ' B';
  }

  getGpuUsagePercent(): number {
    return this.gpuMemTotalBytes > 0 ? (this.gpuMemUsedBytes / this.gpuMemTotalBytes) * 100 : 0;
  }

  getLossChange(): number | null {
    if (this.metrics.length < 10) return null;
    const first = this.metrics[0].trainLoss;
    const last = this.metrics[this.metrics.length - 1].trainLoss;
    if (first === 0) return null;
    return ((last - first) / first) * 100;
  }
}
