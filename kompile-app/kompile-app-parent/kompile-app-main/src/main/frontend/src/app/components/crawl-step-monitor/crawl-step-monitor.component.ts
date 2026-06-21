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

import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';

import { JobLogViewerComponent } from '../job-history/job-log-viewer/job-log-viewer.component';
import {
  PipelineStepProgress,
  JobSummary,
  JobDetail,
  CrawlStageEvent,
  DocumentGraphProgress,
  RetryEvent,
  LlmCallRecord,
  TuningDecision
} from '../../services/unified-crawl.service';

/** One row in the ENRICHMENT sub-stage display. */
interface EnrichmentSubStage {
  id: string;
  label: string;
  icon: string;
  detail: string;
  state: string;   // 'pending' | 'running' | 'done' | 'failed'
  cssClass: string; // maps to CSS: enrich-pending / enrich-running / enrich-done / enrich-failed
}

/** Lightweight rich-job shape the monitor reads for per-step detail. Both JobSummary and JobDetail
 *  satisfy it; crawler-manager may pass a thinner object (rich sections then degrade gracefully). */
type RichJob = JobSummary | JobDetail | {
  recentEvents?: CrawlStageEvent[];
  recentRetryEvents?: RetryEvent[];
  recentLlmCalls?: LlmCallRecord[];
  recentTuningDecisions?: TuningDecision[];
  documentProgress?: DocumentGraphProgress[];
  errors?: string[];
  status?: string;
  adaptiveBatchSize?: number;
  currentBatchSize?: number;
  currentBatchStep?: string;
  batchSizeAdjustments?: number;
  lastBatchAdjustDirection?: string;
  lastBatchAdjustReason?: string;
  batchEmaLatencyMsX100?: number;
  peakThroughputX100?: number;
};

interface LlmSummary {
  count: number;
  success: number;
  failed: number;
  avgLatencyMs: number;
  avgPromptChars: number;
  avgResponseChars: number;
}

/**
 * Shared, reusable real-time crawl-step monitor. Renders every pipeline step as an expandable
 * accordion row; when expanded each step surfaces ALL available detail in real time — a log id,
 * timings, counts/batches, the LLM graph-extractor batch sizes, the adaptive tuning-decision
 * history, retries, failure points, LLM transcripts, and the activity log.
 *
 * Used by the fact-sheet inline crawl card (compact), the Tools→Crawlers manager, and the unified
 * crawl detail view — so all three render an identical, fully-detailed step UI.
 */
@Component({
  selector: 'app-crawl-step-monitor',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatProgressBarModule, MatTooltipModule, MatChipsModule,
    JobLogViewerComponent
  ],
  templateUrl: './crawl-step-monitor.component.html',
  styleUrls: ['./crawl-step-monitor.component.css']
})
export class CrawlStepMonitorComponent {
  /** The per-step progress array (e.g. job.pipelineSteps). */
  @Input() steps: PipelineStepProgress[] = [];
  /** The rich job object for per-step detail (events/retries/llm-calls/tuning/batch telemetry). */
  @Input() job: RichJob | null = null;
  /** Persisted-log task id (crawl-<jobId>) — passed to the embedded transcript viewer. */
  @Input() taskId = '';
  /** Whether the job is still running (drives LIVE badges + transcript tailing). */
  @Input() isJobRunning = false;
  /** The job id, emitted with run-step requests. */
  @Input() jobId = '';
  /** Compact mode (fact-sheet inline) trims the embedded transcript viewer + document table. */
  @Input() compact = false;

  /** Emitted when the user clicks "Run now" on an archived/deferred step. */
  @Output() runStepRequested = new EventEmitter<{ jobId: string; stepId: string }>();

  /** Expanded steps (multiple may be open). Per-instance so jobs never collide. */
  expandedSteps = new Set<string>();
  /** Steps whose embedded transcript viewer has been opened (lazy — avoids opening WS until asked). */
  transcriptsOpen = new Set<string>();

  // ─── Expansion ──────────────────────────────────────────────────────────────

  toggleStepExpanded(stepId: string): void {
    if (this.expandedSteps.has(stepId)) {
      this.expandedSteps.delete(stepId);
    } else {
      this.expandedSteps.add(stepId);
    }
  }

  isStepExpanded(stepId: string): boolean {
    return this.expandedSteps.has(stepId);
  }

  toggleTranscripts(stepId: string): void {
    if (this.transcriptsOpen.has(stepId)) {
      this.transcriptsOpen.delete(stepId);
    } else {
      this.transcriptsOpen.add(stepId);
    }
  }

  isTranscriptsOpen(stepId: string): boolean {
    return this.transcriptsOpen.has(stepId);
  }

  runStep(stepId: string): void {
    this.runStepRequested.emit({ jobId: this.jobId, stepId });
  }

  trackByStepId(_i: number, step: PipelineStepProgress): string {
    return step.stepId;
  }

  trackByIndex(i: number): number {
    return i;
  }

  // ─── Step classification / icons ─────────────────────────────────────────────

  getStepTypeIcon(stepType: string | undefined): string {
    const normalized = (stepType || '').toUpperCase();
    if (normalized.includes('IO')) return 'folder_open';
    if (normalized.includes('CPU')) return 'settings_suggest';
    if (normalized.includes('LLM')) return 'psychology';
    if (normalized.includes('GRAPH_CONSTRUCTOR')) return 'account_tree';
    if (normalized.includes('GRAPH')) return 'hub';
    if (normalized.includes('EMBEDDING')) return 'memory';
    if (normalized.includes('ENRICH')) return 'auto_awesome';
    if (normalized.includes('PIPELINE')) return 'schema';
    return 'schema';
  }

  getStepStatusClass(status: string | undefined): string {
    return 'step-' + (status || 'pending').toLowerCase();
  }

  isStepRunNowEligible(status: string | undefined): boolean {
    const s = (status || '').toUpperCase();
    return s === 'ARCHIVED' || s === 'DEFERRED';
  }

  /** Steps that drive the LLM graph extractor / embedding batch sizers (own the tuning telemetry). */
  isBatchingStep(step: PipelineStepProgress): boolean {
    const t = (step.stepType || '').toUpperCase();
    return t.includes('LLM') || t.includes('GRAPH') || t.includes('EMBEDDING');
  }

  /** Steps that issue LLM/VLM calls and therefore have transcripts. */
  isLlmStep(step: PipelineStepProgress): boolean {
    const t = (step.stepType || '').toUpperCase();
    return t.includes('LLM') || t.includes('GRAPH');
  }

  /** True when this is the ENRICHMENT pipeline step (graph hydration). */
  isEnrichmentStep(step: PipelineStepProgress): boolean {
    return (step.stepId || '').toUpperCase() === 'ENRICHMENT'
        || (step.stepType || '').toUpperCase().includes('ENRICH');
  }

  /**
   * Build a display list of ENRICHMENT sub-stages with their current state.
   * The backend emits stage IDs in `currentItem` and counts in `message`.
   * Sub-stage order mirrors GraphHydrationOrchestrator: WEIGHT_LEARNING →
   * DERIVATION → PRUNE_COMPACT → HEALTH.
   */
  getEnrichmentSubStages(step: PipelineStepProgress): EnrichmentSubStage[] {
    const activeStageId = (step.currentItem || '').toUpperCase().trim();
    const completed     = step.completedItems || 0;
    const total         = step.totalItems || CrawlStepMonitorComponent.HYDRATION_TOTAL_STAGES;
    const isDone        = step.status === 'COMPLETED';
    const isFailed      = step.status === 'FAILED';
    const message       = step.message || '';

    // Parse counts out of the completion summary message (produced by
    // UnifiedCrawlGraphServiceImpl.completePipelineStep → summary string).
    const parseCount = (key: string): string => {
      const m = message.match(new RegExp(key + '=(\\d+)'));
      return m ? m[1] : '';
    };

    // Derive per-sub-stage counts from the live message when it is the active stage.
    const derivationMsg = activeStageId === 'DERIVATION' ? this.shortMessage(message, 'DERIVATION') : '';
    const pruneMsg      = activeStageId === 'PRUNE_COMPACT' ? this.shortMessage(message, 'PRUNE_COMPACT') : '';
    const healthMsg     = activeStageId === 'HEALTH' ? this.shortMessage(message, 'HEALTH') : '';

    // Completed summary counts (available after step finishes).
    const derived    = parseCount('derived');
    const retracted  = parseCount('retracted');
    const prunedRet  = parseCount('prunedRetracted');
    const prunedConf = parseCount('prunedConfidence');
    const merges     = parseCount('merges');
    const orphans    = parseCount('orphans');
    const comps      = parseCount('components');

    const stages: EnrichmentSubStage[] = [
      {
        id: 'WEIGHT_LEARNING',
        label: 'Reasoning & weight learning — PSL + MEBN',
        icon: 'model_training',
        detail: isDone
          ? (derived ? `${derived} facts derived (PSL MAP); MEBN gradient descent run` : 'PSL minibatch + MEBN gradient descent')
          : (activeStageId === 'WEIGHT_LEARNING' ? this.shortMessage(message, 'WEIGHT_LEARNING') : ''),
        state: this.enrichStageState(0, completed, total, activeStageId, 'WEIGHT_LEARNING', isDone, isFailed),
        cssClass: this.enrichStageCss(0, completed, total, activeStageId, 'WEIGHT_LEARNING', isDone, isFailed),
      },
      {
        id: 'DERIVATION',
        label: 'Rule derivation (MAP inference)',
        icon: 'account_tree',
        detail: isDone
          ? [derived ? `${derived} facts` : '', retracted ? `${retracted} retracted` : ''].filter(Boolean).join(', ')
          : derivationMsg,
        state: this.enrichStageState(1, completed, total, activeStageId, 'DERIVATION', isDone, isFailed),
        cssClass: this.enrichStageCss(1, completed, total, activeStageId, 'DERIVATION', isDone, isFailed),
      },
      {
        id: 'PRUNE_COMPACT',
        label: 'Compaction / pruning',
        icon: 'compress',
        detail: isDone
          ? [prunedRet ? `${prunedRet} retracted edges` : '',
             prunedConf ? `${prunedConf} low-confidence` : '',
             merges ? `${merges} merges` : '',
             orphans ? `${orphans} orphans` : '',
             comps ? `${comps} weak components` : ''].filter(Boolean).join(', ')
          : pruneMsg,
        state: this.enrichStageState(2, completed, total, activeStageId, 'PRUNE_COMPACT', isDone, isFailed),
        cssClass: this.enrichStageCss(2, completed, total, activeStageId, 'PRUNE_COMPACT', isDone, isFailed),
      },
      {
        id: 'HEALTH',
        label: 'Health snapshot',
        icon: 'monitor_heart',
        detail: isDone ? 'Density, orphan ratio, component stats persisted' : healthMsg,
        state: this.enrichStageState(3, completed, total, activeStageId, 'HEALTH', isDone, isFailed),
        cssClass: this.enrichStageCss(3, completed, total, activeStageId, 'HEALTH', isDone, isFailed),
      },
    ];

    return stages;
  }

  /** Number of top-level orchestrator stages (mirrors GraphHydrationOrchestrator.TOTAL_STAGES = 3). */
  private static readonly HYDRATION_TOTAL_STAGES = 3;

  /** Abbreviate a raw message from `[STAGE_ID] text` to just the text part, up to 80 chars. */
  private shortMessage(message: string, stageId: string): string {
    // Messages from recordHydrationSubStageProgress are "[STAGE_ID] text"
    const prefix = '[' + stageId + '] ';
    const text = message.startsWith(prefix) ? message.slice(prefix.length) : message;
    return text.length > 90 ? text.slice(0, 87) + '…' : text;
  }

  /**
   * Determine the display state label for a sub-stage.
   * `stageIndex` is the 0-based position of this sub-stage in the ordered list
   * (WEIGHT_LEARNING=0, DERIVATION=1, PRUNE_COMPACT=2, HEALTH=3).
   * `completed` is the pipeline-step completedItems (0-based stages done).
   */
  private enrichStageState(
    stageIndex: number, completed: number, total: number,
    activeId: string, stageId: string, isDone: boolean, isFailed: boolean
  ): string {
    if (isDone) return 'done';
    if (isFailed) return stageIndex < completed ? 'done' : 'failed';
    if (activeId === stageId) return 'running';
    // WEIGHT_LEARNING fires before DERIVATION completes (completed still 0)
    // but after it fires the active ID moves on — treat it as done once any stage has completed.
    if (stageId === 'WEIGHT_LEARNING') return completed >= 1 ? 'done' : (activeId === '' ? 'pending' : 'pending');
    // For top-level stages: done when stageIndex (1-based in DERIVATION/PRUNE/HEALTH) <= completed
    if (stageIndex <= completed) return 'done';
    return 'pending';
  }

  private enrichStageCss(
    stageIndex: number, completed: number, total: number,
    activeId: string, stageId: string, isDone: boolean, isFailed: boolean
  ): string {
    const state = this.enrichStageState(stageIndex, completed, total, activeId, stageId, isDone, isFailed);
    return 'enrich-' + state;
  }

  // ─── Phase mapping (authoritative; consolidated from the 3 former copies) ─────

  stepIdToPhases(stepId: string): string[] {
    const raw = (stepId || '').toUpperCase();
    // Distributed steps are worker-tagged ("W0:GRAPH_EXTRACTION"); map the base, then re-apply the prefix
    // so worker-tagged events/retries match only the owning worker's step.
    const m = raw.match(/^(W\d+:)(.*)$/);
    const prefix = m ? m[1] : '';
    const base = m ? m[2] : raw;
    const phaseMap: { [key: string]: string[] } = {
      'SOURCE_LOADING':         ['LOADING'],
      'LOADING':                ['LOADING'],
      'SOURCE_DISCOVERY':       ['DISCOVERING'],
      'DISCOVERING':            ['DISCOVERING'],
      'TEXT_CONVERSION':        ['CONVERTING'],
      'CONVERTING':             ['CONVERTING'],
      'DOCUMENT_PREPROCESSING': ['OCR_PROCESSING', 'CONVERTING'],
      'PREPROCESSING':          ['OCR_PROCESSING', 'CONVERTING'],
      'CONTENT_ROUTING':        ['ROUTING'],
      'ROUTING':                ['ROUTING'],
      'RULE_GRAPH_PREP':        ['GRAPH_PREP'],
      'GRAPH_PREP':             ['GRAPH_PREP'],
      'CHUNKING':               ['CHUNKING'],
      'GRAPH_EXTRACTION':       ['GRAPH_EXTRACTION'],
      'CRAWL_SURFACE':          ['CRAWL_SURFACE'],
      'SURFACING':              ['CRAWL_SURFACE'],
      'ENTITY_RESOLUTION':      ['ENTITY_RESOLUTION'],
      'GRAPH_EDGE_CLEANUP':     ['EDGE_COMPUTATION'],
      'EDGE_COMPUTATION':       ['EDGE_COMPUTATION'],
      'EMBEDDING':              ['EMBEDDING', 'VECTOR_INDEXING', 'INDEXING'],
      'VECTOR_INDEXING':        ['EMBEDDING', 'VECTOR_INDEXING', 'INDEXING'],
      'ENRICHMENT':             ['ENRICHMENT'],
    };
    const phases = phaseMap[base] || [base];
    return prefix ? phases.map(p => prefix + p) : phases;
  }

  // ─── Per-step data filters (all guard on optional job-level fields) ───────────

  /** Activity-log entries (recentEvents) filtered to a step's phase(s). */
  getStepEvents(step: PipelineStepProgress): CrawlStageEvent[] {
    const events = this.job?.recentEvents;
    if (!events) return [];
    const phases = this.stepIdToPhases(step.stepId);
    return events.filter(e => phases.includes((e.phase || '').toUpperCase()));
  }

  /** Only the WARN/ERROR events for a step — the failure points. */
  getStepFailureEvents(step: PipelineStepProgress): CrawlStageEvent[] {
    return this.getStepEvents(step).filter(e => {
      const l = (e.level || '').toUpperCase();
      return l === 'ERROR' || l === 'WARN';
    });
  }

  /** Documents currently in a step's phase(s). */
  getStepDocuments(step: PipelineStepProgress): DocumentGraphProgress[] {
    const docs = (this.job as JobDetail | null)?.documentProgress;
    if (!docs) return [];
    const phases = this.stepIdToPhases(step.stepId);
    return docs.filter(d => phases.includes((d.phase || '').toUpperCase()));
  }

  /** Retry events attributed to a step (RetryEvent.stage matches the stepId / its phases). */
  getStepRetries(step: PipelineStepProgress): RetryEvent[] {
    const retries = this.job?.recentRetryEvents;
    if (!retries) return [];
    const sid = (step.stepId || '').toUpperCase();
    const phases = this.stepIdToPhases(step.stepId);
    return retries.filter(r => {
      const stage = (r.stage || '').toUpperCase();
      return stage === sid || phases.includes(stage);
    });
  }

  /** Adaptive tuning decisions attributed to a step. The graph-extraction step owns all three
   *  controllers (item sizer, char-budget sizer, parallelism advisor). */
  getStepTuningDecisions(step: PipelineStepProgress): TuningDecision[] {
    const decisions = this.job?.recentTuningDecisions;
    if (!decisions) return [];
    const sid = (step.stepId || '').toUpperCase();
    // Handle the worker-tag prefix on distributed steps so a worker's step only sees its own decisions.
    const m = sid.match(/^(W\d+:)(.*)$/);
    const prefix = m ? m[1] : '';
    const base = m ? m[2] : sid;
    return decisions.filter(d => {
      const stage = (d.stage || '').toUpperCase();
      if (prefix && !stage.startsWith(prefix)) return false; // a different worker's decision
      const baseStage = prefix ? stage.substring(prefix.length) : stage;
      if (baseStage === base || baseStage.startsWith(base + '_')) return true;
      // Graph-extraction step also owns the char-budget + parallelism controllers.
      return base === 'GRAPH_EXTRACTION' && baseStage.startsWith('GRAPH_');
    });
  }

  /** LLM call records relevant to a step (embedding steps → embedding calls; else llm/vlm calls). */
  getStepLlmCalls(step: PipelineStepProgress): LlmCallRecord[] {
    const calls = this.job?.recentLlmCalls;
    if (!calls || !calls.length) return [];
    const isEmbedding = (step.stepType || '').toUpperCase().includes('EMBEDDING');
    return calls.filter(c => {
      const t = (c.taskType || '').toLowerCase();
      return isEmbedding ? t === 'embedding' : t !== 'embedding';
    });
  }

  /** Aggregate LLM-call stats for a step, or null when there are no matching calls. */
  getStepLlmSummary(step: PipelineStepProgress): LlmSummary | null {
    const calls = this.getStepLlmCalls(step);
    if (!calls.length) return null;
    let success = 0, latency = 0, prompt = 0, response = 0;
    for (const c of calls) {
      if (c.success) success++;
      latency += c.latencyMs || 0;
      prompt += c.promptChars || 0;
      response += c.responseChars || 0;
    }
    const n = calls.length;
    return {
      count: n,
      success,
      failed: n - success,
      avgLatencyMs: Math.round(latency / n),
      avgPromptChars: Math.round(prompt / n),
      avgResponseChars: Math.round(response / n)
    };
  }

  // ─── Formatting helpers (consolidated) ───────────────────────────────────────

  formatElapsed(ms: number | undefined): string {
    if (!ms || ms <= 0) return '';
    const totalSeconds = Math.floor(ms / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`;
    if (minutes > 0) return `${minutes}m ${seconds}s`;
    return `${seconds}s`;
  }

  getStepThroughput(step: PipelineStepProgress): string {
    if (!step.elapsedMs || step.elapsedMs < 1000 || step.completedItems <= 0) return '';
    const rate = step.completedItems / (step.elapsedMs / 1000);
    if (rate >= 1) return `${rate.toFixed(1)}/s`;
    return `${(rate * 60).toFixed(1)}/min`;
  }

  formatEventTime(timestamp: string | undefined): string {
    if (!timestamp) return '';
    const diff = Date.now() - new Date(timestamp).getTime();
    if (diff < 1000) return 'now';
    if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`;
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
    return `${Math.floor(diff / 3600000)}h ago`;
  }

  formatTimestamp(ts: string | undefined | null): string {
    if (!ts) return '';
    try {
      const date = new Date(ts);
      const diffMs = Date.now() - date.getTime();
      if (diffMs < 60000) return 'just now';
      if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}m ago`;
      if (diffMs < 86400000) return date.toLocaleTimeString();
      return date.toLocaleString();
    } catch {
      return ts;
    }
  }

  /** EMA / x100 latency fields are stored as ms×100 for precision. */
  formatLatencyX100(x100: number | undefined): string {
    if (!x100 || x100 <= 0) return '';
    return `${(x100 / 100).toFixed(0)}ms`;
  }

  formatThroughputX100(x100: number | undefined): string {
    if (!x100 || x100 <= 0) return '';
    return `${(x100 / 100).toFixed(1)}/s`;
  }

  tuningDirectionIcon(direction: string | undefined): string {
    const d = (direction || '').toUpperCase();
    if (d === 'UP') return 'trending_up';
    if (d === 'DOWN') return 'trending_down';
    return 'trending_flat';
  }

  tuningDirectionClass(direction: string | undefined): string {
    return 'tuning-' + (direction || 'hold').toLowerCase();
  }
}
