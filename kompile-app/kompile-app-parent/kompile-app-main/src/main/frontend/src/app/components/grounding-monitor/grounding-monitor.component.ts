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

import { Component, Input, OnInit, OnDestroy, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { WebSocketService, GroundingCascadeEvent } from '../../services/websocket.service';

/** Max number of completed cascades to keep in memory (newest first). */
const MAX_HISTORY = 5;

/** Human-readable labels for each cascade stage value. */
const STAGE_LABELS: Record<string, string> = {
  PROJECTION:           'Graph projection',
  PROGRAM_BUILD:        'Program build',
  ONTOLOGY_RULES:       'Ontology rules',
  WEIGHT_RELOAD:        'Weight reload',
  MAP_SOLVE:            'Rule derivation (MAP inference)',
  MATERIALIZE:          'Fact materialisation',
  PROMOTION:            'Fact promotion',
  PSL_LEARNING:         'PSL weight learning',
  JUSTIFICATION:        'Justification',
  CONTRADICTION:        'Contradiction detection',
  EPOCH:                'Learning epoch',
  MEBN_LEARNING:        'MEBN weight learning',
  CONSENSUS:            'Consensus / voting',
  PRUNE_COMPACT:        'Pruning / compaction',
  ONTOLOGY_CONFORMANCE: 'Ontology conformance check',
  HEALTH:               'Graph health snapshot',
  COMPLETE:             'Cascade complete'
};

/** Human-readable trigger labels. */
const TRIGGER_LABELS: Record<string, string> = {
  CRAWL:   'Crawl',
  CHANNEL: 'Channel message',
  ASSERT:  'Manual assertion',
  MANUAL:  'Manual trigger',
  CASCADE: 'Cascade chain'
};

/** Trigger icon names (Material Icons). */
const TRIGGER_ICONS: Record<string, string> = {
  CRAWL:   'travel_explore',
  CHANNEL: 'cable',
  ASSERT:  'edit_note',
  MANUAL:  'play_circle',
  CASCADE: 'sync'
};

/** A single cascade with its ordered step events. */
export interface CascadeGroup {
  cascadeId: string;
  factSheetId: number;
  trigger: string;
  steps: GroundingCascadeEvent[];
  isComplete: boolean;
  hasError: boolean;
  startedAt: number;
  finishedAt: number | null;
}

@Component({
  selector: 'app-grounding-monitor',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    MatCardModule,
    MatProgressBarModule,
    MatTooltipModule,
    MatChipsModule
  ],
  templateUrl: './grounding-monitor.component.html',
  styleUrls: ['./grounding-monitor.component.css']
})
export class GroundingMonitorComponent implements OnInit, OnDestroy, OnChanges {
  /** Fact sheet to watch. If null the component shows all cascades from /topic/grounding/all. */
  @Input() factSheetId: number | null = null;

  /** Recent cascades, newest first. Active (incomplete) cascade is always index 0. */
  cascades: CascadeGroup[] = [];

  /** Live connection status — true once at least one event is received. */
  connected = false;

  private destroy$ = new Subject<void>();
  private prevFactSheetId: number | null = null;

  constructor(private wsService: WebSocketService) {}

  ngOnInit(): void {
    this.subscribe();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange) {
      // Unsubscribe from old fact sheet and re-subscribe to new one.
      if (this.prevFactSheetId != null) {
        this.wsService.unsubscribeFromGroundingCascade(this.prevFactSheetId);
      }
      this.cascades = [];
      this.subscribe();
    }
  }

  ngOnDestroy(): void {
    this.wsService.unsubscribeFromGroundingCascade(this.factSheetId);
    this.destroy$.next();
    this.destroy$.complete();
  }

  private subscribe(): void {
    this.prevFactSheetId = this.factSheetId;
    this.wsService
      .subscribeToGroundingCascade(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe(event => this.handleEvent(event));
  }

  private handleEvent(event: GroundingCascadeEvent): void {
    this.connected = true;

    let group = this.cascades.find(c => c.cascadeId === event.cascadeId);
    if (!group) {
      group = {
        cascadeId: event.cascadeId,
        factSheetId: event.factSheetId,
        trigger: event.trigger,
        steps: [],
        isComplete: false,
        hasError: false,
        startedAt: event.timestamp,
        finishedAt: null
      };
      // Prepend new cascade to the list
      this.cascades.unshift(group);
      // Keep only the last MAX_HISTORY completed + 1 active
      const completed = this.cascades.filter(c => c.isComplete || c.hasError);
      if (completed.length > MAX_HISTORY) {
        const lastCompleted = completed[completed.length - 1];
        const idx = this.cascades.indexOf(lastCompleted);
        if (idx !== -1) {
          this.cascades.splice(idx, 1);
        }
      }
    }

    // Update or append the step event for this stage
    const existingIdx = group.steps.findIndex(s => s.stage === event.stage);
    if (existingIdx >= 0) {
      group.steps[existingIdx] = event;
    } else {
      group.steps.push(event);
      // Keep steps ordered by stepIndex
      group.steps.sort((a, b) => a.stepIndex - b.stepIndex);
    }

    if (event.stage === 'COMPLETE' || event.status === 'DONE' && event.stage === 'COMPLETE') {
      group.isComplete = true;
      group.finishedAt = event.timestamp;
    }
    if (event.status === 'ERROR') {
      group.hasError = true;
      group.finishedAt = event.timestamp;
    }
  }

  // ── Template helpers ──────────────────────────────────────────────

  stageLabel(stage: string): string {
    return STAGE_LABELS[stage] ?? stage;
  }

  triggerLabel(trigger: string): string {
    return TRIGGER_LABELS[trigger] ?? trigger;
  }

  triggerIcon(trigger: string): string {
    return TRIGGER_ICONS[trigger] ?? 'bolt';
  }

  statusIcon(status: string): string {
    switch (status) {
      case 'DONE':    return 'check_circle';
      case 'ERROR':   return 'error';
      case 'RUNNING': return 'pending';
      case 'STARTED': return 'radio_button_unchecked';
      default:        return 'radio_button_unchecked';
    }
  }

  cascadeProgress(group: CascadeGroup): number {
    if (!group.steps.length) return 0;
    const totalSteps = group.steps[group.steps.length - 1]?.totalSteps ?? group.steps.length;
    if (totalSteps === 0) return 0;
    const done = group.steps.filter(s => s.status === 'DONE').length;
    return Math.round((done / totalSteps) * 100);
  }

  formatDataSummary(data: GroundingCascadeEvent['data']): string {
    if (!data) return '';
    const parts: string[] = [];
    if (data['rulesUpdated'] != null)      parts.push(`${data['rulesUpdated']} rules updated`);
    if (data['factsMaterialized'] != null) parts.push(`${data['factsMaterialized']} facts materialised`);
    if (data['promoted'] != null)          parts.push(`${data['promoted']} promoted`);
    if (data['contradictions'] != null)    parts.push(`${data['contradictions']} contradictions`);
    if (data['meanWeightDelta'] != null)   parts.push(`mean Δw ${(data['meanWeightDelta'] as number).toFixed(4)}`);
    if (data['maxWeightDelta'] != null)    parts.push(`max Δw ${(data['maxWeightDelta'] as number).toFixed(4)}`);
    if (data['meanStrengthDelta'] != null) parts.push(`mean Δs ${(data['meanStrengthDelta'] as number).toFixed(4)}`);
    return parts.join(', ');
  }

  formatDuration(group: CascadeGroup): string {
    if (!group.finishedAt) return '';
    const ms = group.finishedAt - group.startedAt;
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  }

  trackByCascadeId(_: number, c: CascadeGroup): string { return c.cascadeId; }
  trackByStage(_: number, e: GroundingCascadeEvent): string { return e.stage; }
}
