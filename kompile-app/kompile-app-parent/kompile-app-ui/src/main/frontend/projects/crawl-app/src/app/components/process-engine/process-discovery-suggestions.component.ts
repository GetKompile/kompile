/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ProcessEngineService } from '@shared/services/process-engine.service';
import { GraphService } from '@shared/services/graph.service';
import { MebnVariableMeta } from '@shared/models/attribution-models';
import {
  FlatProcessReasoningStep,
  ProcessHybridActivityReasoning,
  ProcessHybridReasoning,
  ProcessReasoningFields,
  ProcessReasoningTrace,
  flattenProcessReasoningTrace,
  processHybridModeLabel,
  rankedProcessHybridActivities,
  sortProcessSuggestions
} from '@shared/models/process-reasoning-models';
import { MermaidRendererComponent } from './mermaid-renderer.component';

interface StructuredEvidence {
  type: string;
  description: string;
  score: number | null;
  supportingNodeIds: string[];
}

interface SuggestedStep {
  name: string;
  stepType: string;
  description: string;
  occurredAt: string | null;
  /** Mined/entailed control flow: names of steps this step waits for (kept through acceptance). */
  dependsOn?: string[];
  /** Role derived from the knowledge graph via the ConjunctiveQueryEngine role binding. */
  roleBinding?: string | null;
  /** Provenance of roleBinding: OBSERVED (majority actor from crawl relations), KB, or HEURISTIC. */
  roleSource?: string | null;
  /** SpEL routing stub for mined XOR branches (default-true, e.g. "#take_approval != false"). */
  conditionExpression?: string | null;
  /** Human-readable choice-branch provenance ("Choice: Approval branch — observed in 5 of 8 cases"). */
  conditionLabel?: string | null;
}

interface SuggestedPhase {
  name: string;
  description: string;
  steps: SuggestedStep[];
  earliestOccurrence: string | null;
  latestOccurrence: string | null;
}

interface ProcessSuggestion extends ProcessReasoningFields {
  id: string;
  factSheetId: number | null;
  discoveredAt: string;
  name: string;
  description: string;
  discoverySource: string;
  confidence: number;
  phases: SuggestedPhase[];
  sourceGraphNodeIds: string[];
  evidence: string[];
  bayesianPosteriors: Record<string, number>;
  bayesianPriors: Record<string, number>;
  mebnMeta: Record<string, MebnVariableMeta>;
  structuredEvidence: StructuredEvidence[];
  childSuggestions: ProcessSuggestion[];
  accepted: boolean | null;
  acceptedProcessDefinitionId: string | null;
  /** Stable identity of the business process across mining generations. */
  processKey?: string | null;
  /** The predecessor generation this suggestion was matched to (drift is diffed against it). */
  previousSuggestionId?: string | null;
  /** Set when a newer generation replaced this one (lineage — superseded entries hide by default). */
  supersededAt?: string | null;
  /** When set, accepting revises this live ProcessDefinition (version bump) instead of creating a new one. */
  revisesProcessDefinitionId?: string | null;
  /** Business-process description: deterministic template, upgraded to grounded LLM prose. */
  narrative?: string | null;
  /** "TEMPLATE" or the LLM source that narrated. */
  narrativeSource?: string | null;
  /** Learned acceptance likelihood from the accept/dismiss-fitted re-ranker; null until trained. */
  learnedScore?: number | null;
  /** Agent-synthesized full process document (markdown); null until synthesis is requested. */
  processDocument?: string | null;
  /** Registry name of the agent that produced the document. */
  processDocumentSource?: string | null;
}

interface SynthesisAgent {
  name: string;
  displayName: string;
}

@Component({
  standalone: true,
  selector: 'app-process-discovery-suggestions',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatCardModule,
    MatChipsModule, MatProgressBarModule, MatTooltipModule,
    MatExpansionModule, MatSnackBarModule, MermaidRendererComponent
  ],
  template: `
    <div class="suggestions-container">
      <!-- Header with actions -->
      <div class="section-header">
        <div class="header-left">
          <h3>Discovered Process Suggestions</h3>
          <span class="count-badge" *ngIf="suggestions.length > 0">{{ suggestions.length }}</span>
        </div>
        <div class="header-actions">
          <button mat-stroked-button (click)="runDiscovery()" [disabled]="loading">
            <mat-icon>search</mat-icon> Run Discovery
          </button>
          <button mat-button (click)="loadSuggestions()" [disabled]="loading">
            <mat-icon>refresh</mat-icon> Refresh
          </button>
        </div>
      </div>

      <!-- Loading -->
      <mat-progress-bar *ngIf="loading" mode="indeterminate" class="loading-bar"></mat-progress-bar>

      <!-- Empty state -->
      <div class="empty-state" *ngIf="!loading && suggestions.length === 0">
        <mat-icon class="empty-icon">auto_awesome</mat-icon>
        <p>No process suggestions yet. Run discovery to analyze your knowledge graph for repeatable patterns.</p>
      </div>

      <!-- Suggestions list -->
      <mat-accordion *ngIf="suggestions.length > 0" multi>
        <mat-expansion-panel *ngFor="let suggestion of suggestions; trackBy: trackById"
                             class="suggestion-panel"
                             [class.accepted]="suggestion.accepted">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon class="source-icon" [matTooltip]="suggestion.discoverySource">
                {{ getSourceIcon(suggestion.discoverySource) }}
              </mat-icon>
              <span class="suggestion-name">{{ suggestion.name }}</span>
            </mat-panel-title>
            <mat-panel-description>
              <div class="header-meta">
                <span class="rank-badge" *ngIf="suggestion.reasoningRank != null"
                      matTooltip="Rank among candidates generated from the same graph snapshot">
                  #{{ suggestion.reasoningRank }}
                </span>
                <span class="confidence-badge"
                      [class.high]="suggestion.confidence >= 0.7"
                      [class.medium]="suggestion.confidence >= 0.4 && suggestion.confidence < 0.7"
                      [class.low]="suggestion.confidence < 0.4">
                  {{ (suggestion.confidence * 100) | number:'1.0-0' }}%
                </span>
                <span class="learned-badge" *ngIf="suggestion.learnedScore != null"
                      matTooltip="Learned acceptance likelihood — a logistic re-ranker fitted to your accept/dismiss history over this suggestion's reasoning signals">
                  ★ {{ (suggestion.learnedScore * 100) | number:'1.0-0' }}%
                </span>
                <mat-chip *ngIf="suggestion.accepted" class="accepted-chip">
                  <mat-icon>check_circle</mat-icon> Accepted
                </mat-chip>
                <mat-chip *ngIf="suggestion.previousSuggestionId" class="revision-chip"
                          [matTooltip]="suggestion.revisesProcessDefinitionId
                            ? 'Same process re-mined — accepting revises the live definition (version bump); DRIFT evidence lists what changed'
                            : 'Same process re-mined — DRIFT evidence lists what changed since the previous generation'">
                  <mat-icon>update</mat-icon>
                  {{ suggestion.revisesProcessDefinitionId ? 'Revision' : 'Updated' }}
                </mat-chip>
              </div>
            </mat-panel-description>
          </mat-expansion-panel-header>

          <!-- Expanded content -->
          <div class="suggestion-detail">
            <!-- The business-process description: what a human should read first -->
            <div class="narrative" *ngIf="suggestion.narrative">
              <mat-icon class="narrative-icon">auto_stories</mat-icon>
              <span class="narrative-text">{{ suggestion.narrative }}</span>
              <span class="narrative-source" *ngIf="suggestion.narrativeSource"
                    [matTooltip]="suggestion.narrativeSource === 'LLM'
                      ? 'Narrated by the configured chat model from the mined structure (grounding-validated)'
                      : 'Deterministic narrative generated from the mined structure'">
                {{ suggestion.narrativeSource }}
              </span>
            </div>
            <p class="description">{{ suggestion.description }}</p>

            <!-- Source graph node IDs -->
            <div class="source-nodes-section" *ngIf="suggestion.sourceGraphNodeIds?.length">
              <div class="source-nodes-chips">
                <mat-chip *ngFor="let nodeId of suggestion.sourceGraphNodeIds" class="source-node-chip"
                          [matTooltip]="nodeId">
                  <mat-icon class="chip-icon">hub</mat-icon>
                  {{ (graphNodeLabels[nodeId] || nodeId) | slice:0:20 }}
                </mat-chip>
              </div>
            </div>

            <div class="reasoning-summary" *ngIf="hasReasoningSummary(suggestion)">
              <div class="reasoning-summary-header">
                <mat-icon>psychology_alt</mat-icon>
                <span>Graph reasoning</span>
                <span class="reasoning-path" *ngIf="suggestion.reasoningProjection">
                  {{ formatReasoningLabel(suggestion.reasoningProjection) }}
                </span>
                <span class="reasoning-path" *ngIf="suggestion.reasoningFamily">
                  {{ formatReasoningLabel(suggestion.reasoningFamily) }}
                </span>
              </div>
              <div class="reasoning-metrics">
                <div class="reasoning-metric" *ngIf="suggestion.hybridScore != null">
                  <span>Hybrid activation</span>
                  <strong>{{ (suggestion.hybridScore * 100) | number:'1.0-1' }}%</strong>
                </div>
                <div class="reasoning-metric" *ngIf="suggestion.entailmentScore != null">
                  <span>Entailment</span>
                  <strong>{{ (suggestion.entailmentScore * 100) | number:'1.0-1' }}%</strong>
                </div>
                <div class="reasoning-metric" *ngIf="suggestion.processCaseCount != null">
                  <span>Cases</span><strong>{{ suggestion.processCaseCount }}</strong>
                </div>
                <div class="reasoning-metric" *ngIf="suggestion.processActivityCount != null">
                  <span>Activities</span><strong>{{ suggestion.processActivityCount }}</strong>
                </div>
                <div class="reasoning-metric" *ngIf="suggestion.directlyFollowsCount != null">
                  <span>Direct follows</span><strong>{{ suggestion.directlyFollowsCount }}</strong>
                </div>
                <div class="reasoning-metric" *ngIf="suggestion.acceptedPrecedenceCount != null">
                  <span>Accepted order</span><strong>{{ suggestion.acceptedPrecedenceCount }}</strong>
                </div>
                <div class="reasoning-metric" *ngIf="suggestion.entailedOnlyPrecedenceCount != null">
                  <span>Entailed only</span><strong>{{ suggestion.entailedOnlyPrecedenceCount }}</strong>
                </div>
                <div class="reasoning-metric" *ngIf="suggestion.sourceGraphRelationIds?.length">
                  <span>Graph relations</span><strong>{{ suggestion.sourceGraphRelationIds?.length }}</strong>
                </div>
              </div>
              <details class="hybrid-interpretation" *ngIf="suggestion.hybridReasoning as hybrid">
                <summary>
                  <span>Hybrid activity interpretation</span>
                  <span class="hybrid-mode">{{ hybridModeLabel(hybrid) }}</span>
                  <span class="hybrid-coverage">
                    {{ hybrid.embeddedActivityCount }}/{{ hybrid.activityCount }} embedded
                  </span>
                </summary>
                <div class="hybrid-overview">
                  <span><small>PSL blend</small><strong>{{ (hybrid.pslScore * 100) | number:'1.0-1' }}%</strong></span>
                  <span><small>Bayesian blend</small><strong>{{ (hybrid.bayesianScore * 100) | number:'1.0-1' }}%</strong></span>
                  <span><small>Semantic mean</small><strong>{{ (hybrid.semanticScore * 100) | number:'1.0-1' }}%</strong></span>
                  <span><small>Weights S / M</small><strong>{{ (hybrid.structuralWeight * 100) | number:'1.0-0' }} / {{ (hybrid.semanticWeight * 100) | number:'1.0-0' }}</strong></span>
                </div>
                <div class="hybrid-embedding-source" *ngIf="hybrid.embeddingSource">
                  <span>{{ formatReasoningLabel(hybrid.embeddingSource) }}</span>
                  <span *ngIf="hybrid.embeddingModel">{{ hybrid.embeddingModel }}</span>
                  <span>{{ hybrid.directlyEmbeddedActivityCount || 0 }} direct</span>
                  <span *ngIf="hybrid.inferredEmbeddingActivityCount">{{ hybrid.inferredEmbeddingActivityCount }} graph-resolved</span>
                  <span *ngIf="hybrid.contextualizedActivityCount">{{ hybrid.contextualizedActivityCount }} context-enriched</span>
                </div>
                <div class="hybrid-warning" *ngFor="let warning of hybrid.warnings">{{ warning }}</div>
                <div class="hybrid-activity-list">
                  <div class="hybrid-activity" *ngFor="let activity of hybridActivities(suggestion); trackBy: trackHybridActivity">
                    <div class="hybrid-activity-header">
                      <span>{{ activity.activity }}</span>
                      <small *ngIf="activity.embedded">embedded</small>
                      <strong>{{ (activity.score * 100) | number:'1.0-1' }}%</strong>
                    </div>
                    <div class="hybrid-components">
                      <span>PSL {{ (activity.pslScore * 100) | number:'1.0-1' }}% <small>struct {{ (activity.pslStructuralScore * 100) | number:'1.0-1' }}%</small></span>
                      <span>Bayes {{ (activity.bayesianScore * 100) | number:'1.0-1' }}% <small>struct {{ (activity.bayesianStructuralScore * 100) | number:'1.0-1' }}%</small></span>
                      <span>Semantic {{ (activity.semanticScore * 100) | number:'1.0-1' }}%</span>
                    </div>
                  </div>
                </div>
              </details>
            </div>

            <!-- Bayesian Posteriors -->
            <div class="bayesian-section" *ngIf="hasBayesianData(suggestion)">
              <h4><mat-icon class="section-icon">insights</mat-icon> Bayesian Posteriors</h4>
              <div class="posteriors-grid">
                <div class="posterior-item" *ngFor="let entry of getPosteriorEntries(suggestion)">
                  <div class="posterior-header">
                    <span class="posterior-label" [matTooltip]="entry.nodeId">{{ (graphNodeLabels[entry.nodeId] || entry.nodeId) | slice:0:24 }}</span>
                    <span class="posterior-values">
                      <span class="prior-value" *ngIf="entry.prior !== undefined"
                            matTooltip="Prior">{{ (entry.prior * 100) | number:'1.1-1' }}%</span>
                      <mat-icon class="shift-arrow" *ngIf="entry.prior !== undefined">arrow_forward</mat-icon>
                      <span class="posterior-value">{{ (entry.value * 100) | number:'1.1-1' }}%</span>
                    </span>
                  </div>
                  <ng-container *ngIf="suggestion.mebnMeta?.[entry.nodeId] as meta">
                    <div class="posterior-mebn-meta">
                      <span class="mebn-badge-sm mfrag-sm">{{meta.mfragName}}</span>
                      <span class="mebn-badge-sm role-sm"
                            [class.role-sm-resident]="meta.nodeRole === 'RESIDENT'"
                            [class.role-sm-input]="meta.nodeRole === 'INPUT'">{{meta.nodeRole}}</span>
                      <span *ngIf="meta.entityType" class="mebn-badge-sm etype-sm">{{meta.entityType}}</span>
                    </div>
                  </ng-container>
                  <div class="posterior-bar-container">
                    <mat-progress-bar mode="determinate" [value]="entry.value * 100"
                                      [class.bar-high]="entry.value >= 0.7"
                                      [class.bar-medium]="entry.value >= 0.4 && entry.value < 0.7"
                                      [class.bar-low]="entry.value < 0.4">
                    </mat-progress-bar>
                    <div *ngIf="entry.prior !== undefined"
                         class="prior-marker"
                         [style.left.%]="entry.prior * 100"
                         matTooltip="Prior: {{ (entry.prior * 100) | number:'1.1-1' }}%">
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Structured Evidence -->
            <div class="evidence-section" *ngIf="suggestion.structuredEvidence?.length">
              <h4><mat-icon class="section-icon">fact_check</mat-icon> Evidence</h4>
              <div class="evidence-list">
                <div class="evidence-item" *ngFor="let ev of sortedEvidence(suggestion.structuredEvidence)">
                  <mat-chip class="evidence-type-chip" [class]="'ev-' + ev.type.toLowerCase()">
                    {{ ev.type }}
                  </mat-chip>
                  <span class="evidence-desc">{{ ev.description }}</span>
                  <span class="evidence-score" *ngIf="ev.score != null">
                    {{ (ev.score * 100) | number:'1.1-1' }}%
                  </span>
                </div>
              </div>
            </div>

            <!-- Phases & Steps -->
            <div class="phases-section" *ngIf="suggestion.phases?.length">
              <h4><mat-icon class="section-icon">account_tree</mat-icon> Suggested Workflow</h4>
              <div class="phase" *ngFor="let phase of suggestion.phases; let i = index">
                <div class="phase-header">
                  Phase {{ i + 1 }}: {{ phase.name }}
                  <span class="temporal-range" *ngIf="phase.earliestOccurrence || phase.latestOccurrence">
                    <mat-icon class="temporal-icon">schedule</mat-icon>
                    <span *ngIf="phase.earliestOccurrence && phase.latestOccurrence">
                      {{ phase.earliestOccurrence | date:'mediumDate' }} &ndash; {{ phase.latestOccurrence | date:'mediumDate' }}
                    </span>
                    <span *ngIf="phase.earliestOccurrence && !phase.latestOccurrence">
                      from {{ phase.earliestOccurrence | date:'mediumDate' }}
                    </span>
                    <span *ngIf="!phase.earliestOccurrence && phase.latestOccurrence">
                      until {{ phase.latestOccurrence | date:'mediumDate' }}
                    </span>
                  </span>
                </div>
                <div class="step" *ngFor="let step of phase.steps; let j = index">
                  <span class="step-number">{{ i + 1 }}.{{ j + 1 }}</span>
                  <mat-chip class="step-type-chip">{{ step.stepType }}</mat-chip>
                  <span class="step-name" [matTooltip]="step.description">
                    {{ step.name || step.description }}
                  </span>
                  <mat-chip class="step-role-chip" *ngIf="step.roleBinding"
                            [class.role-observed]="step.roleSource === 'OBSERVED'"
                            [matTooltip]="roleTooltip(step)">
                    <mat-icon class="role-icon" *ngIf="step.roleSource === 'OBSERVED'">person</mat-icon>
                    {{ step.roleBinding }}
                  </mat-chip>
                  <span class="step-deps" *ngIf="step.dependsOn?.length"
                        matTooltip="Mined/entailed control flow — this step waits for these">
                    <mat-icon class="deps-icon">merge_type</mat-icon>
                    after {{ step.dependsOn?.join(', ') }}
                  </span>
                  <span class="step-condition" *ngIf="step.conditionLabel"
                        [matTooltip]="step.conditionLabel + (step.conditionExpression ? ' — routing stub: ' + step.conditionExpression : '')">
                    <mat-icon class="condition-icon">alt_route</mat-icon>
                  </span>
                  <span class="step-timestamp" *ngIf="step.occurredAt" [matTooltip]="step.occurredAt">
                    {{ step.occurredAt | date:'short' }}
                  </span>
                </div>
              </div>
            </div>

            <!-- Text evidence -->
            <div class="text-evidence" *ngIf="suggestion.evidence?.length">
              <h4><mat-icon class="section-icon">info</mat-icon> Discovery Evidence</h4>
              <ul>
                <li *ngFor="let e of suggestion.evidence">{{ e }}</li>
              </ul>
            </div>

            <div class="reasoning-trace" *ngIf="suggestion.reasoningTraceId">
              <button mat-stroked-button (click)="toggleReasoningTrace(suggestion)"
                      [disabled]="traceLoading[suggestion.id]">
                <mat-icon>account_tree</mat-icon>
                {{ traceLoading[suggestion.id] ? 'Loading trace...' :
                   (reasoningTraces[suggestion.id] ? 'Hide reasoning trace' : 'Show reasoning trace') }}
              </button>
              <span class="trace-error" *ngIf="traceErrors[suggestion.id]">
                {{ traceErrors[suggestion.id] }}
              </span>
              <div class="trace-content" *ngIf="reasoningTraces[suggestion.id] as trace">
                <div class="trace-summary">
                  <span>{{ trace.size }} steps</span>
                  <span>Depth {{ trace.depth }}</span>
                </div>
                <div class="trace-step" *ngFor="let step of reasoningSteps(suggestion); trackBy: trackTraceStep"
                     [style.padding-left.px]="8 + step.depthLevel * 16">
                  <span class="trace-kind">{{ formatReasoningLabel(step.kind) }}</span>
                  <div class="trace-step-body">
                    <span class="trace-conclusion">{{ step.conclusion }}</span>
                    <span class="trace-operation" *ngIf="step.operation || step.source">
                      {{ step.operation }}<ng-container *ngIf="step.operation && step.source"> | </ng-container>{{ step.source }}
                    </span>
                  </div>
                  <strong>{{ (step.confidence * 100) | number:'1.0-1' }}%</strong>
                </div>
              </div>
            </div>

            <!-- Child suggestions -->
            <div class="children-section" *ngIf="suggestion.childSuggestions?.length">
              <h4><mat-icon class="section-icon">subdirectory_arrow_right</mat-icon> Sub-Processes</h4>
              <mat-card *ngFor="let child of suggestion.childSuggestions" class="child-card">
                <mat-card-header>
                  <mat-card-title>{{ child.name }}</mat-card-title>
                  <mat-card-subtitle>{{ child.discoverySource }} | {{ (child.confidence * 100) | number:'1.0-0' }}% confidence</mat-card-subtitle>
                </mat-card-header>
                <mat-card-content>
                  <p>{{ child.description }}</p>
                  <!-- Child Bayesian posteriors -->
                  <div class="child-bayesian" *ngIf="hasBayesianData(child)">
                    <div class="posteriors-grid">
                      <div class="posterior-item" *ngFor="let entry of getPosteriorEntries(child)">
                        <div class="posterior-header">
                          <span class="posterior-label" [matTooltip]="entry.nodeId">{{ (graphNodeLabels[entry.nodeId] || entry.nodeId) | slice:0:20 }}</span>
                          <span class="posterior-values">
                            <span class="prior-value" *ngIf="entry.prior !== undefined"
                                  matTooltip="Prior">{{ (entry.prior * 100) | number:'1.1-1' }}%</span>
                            <mat-icon class="shift-arrow" *ngIf="entry.prior !== undefined">arrow_forward</mat-icon>
                            <span class="posterior-value">{{ (entry.value * 100) | number:'1.1-1' }}%</span>
                          </span>
                        </div>
                        <ng-container *ngIf="child.mebnMeta?.[entry.nodeId] as meta">
                          <div class="posterior-mebn-meta">
                            <span class="mebn-badge-sm mfrag-sm">{{meta.mfragName}}</span>
                            <span class="mebn-badge-sm role-sm"
                                  [class.role-sm-resident]="meta.nodeRole === 'RESIDENT'"
                                  [class.role-sm-input]="meta.nodeRole === 'INPUT'">{{meta.nodeRole}}</span>
                            <span *ngIf="meta.entityType" class="mebn-badge-sm etype-sm">{{meta.entityType}}</span>
                          </div>
                        </ng-container>
                        <div class="posterior-bar-container">
                          <mat-progress-bar mode="determinate" [value]="entry.value * 100"
                                            [class.bar-high]="entry.value >= 0.7"
                                            [class.bar-medium]="entry.value >= 0.4 && entry.value < 0.7"
                                            [class.bar-low]="entry.value < 0.4">
                          </mat-progress-bar>
                          <div *ngIf="entry.prior !== undefined"
                               class="prior-marker"
                               [style.left.%]="entry.prior * 100"
                               matTooltip="Prior: {{ (entry.prior * 100) | number:'1.1-1' }}%">
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                  <!-- Child structured evidence -->
                  <div *ngIf="child.structuredEvidence?.length" class="child-evidence">
                    <div class="evidence-item" *ngFor="let ev of sortedEvidence(child.structuredEvidence)">
                      <mat-chip class="evidence-type-chip" [class]="'ev-' + ev.type.toLowerCase()">
                        {{ ev.type }}
                      </mat-chip>
                      <span class="evidence-desc">{{ ev.description }}</span>
                      <span class="evidence-score" *ngIf="ev.score != null">
                        {{ (ev.score * 100) | number:'1.1-1' }}%
                      </span>
                    </div>
                  </div>
                </mat-card-content>
              </mat-card>
            </div>

            <!-- Mined process map: directly-follows + Inductive Miner block structure (Mermaid) -->
            <div class="process-map" *ngIf="suggestion.factSheetId != null">
              <button mat-stroked-button (click)="toggleProcessMap(suggestion)"
                      [disabled]="mapLoading[suggestion.id]"
                      matTooltip="Render the mined directly-follows map and process tree for this fact sheet">
                <mat-icon>schema</mat-icon>
                {{ mapLoading[suggestion.id] ? 'Loading map…' :
                   (processMaps[suggestion.id] ? 'Hide process map' : 'Show process map') }}
              </button>
              <div class="process-map-panels" *ngIf="processMaps[suggestion.id] as map">
                <div class="map-panel" *ngIf="map.dfg">
                  <h5>Directly-follows map</h5>
                  <app-mermaid-renderer [code]="map.dfg"></app-mermaid-renderer>
                </div>
                <div class="map-panel" *ngIf="map.tree">
                  <h5>Process tree (Inductive Miner)</h5>
                  <app-mermaid-renderer [code]="map.tree"></app-mermaid-renderer>
                </div>
              </div>
            </div>

            <!-- Agent-synthesized full description -->
            <div class="process-document" *ngIf="suggestion.processDocument">
              <h4>
                <mat-icon class="section-icon">menu_book</mat-icon> Full process description
                <span class="narrative-source"
                      matTooltip="Synthesized by this agent from the mined structure + the graph (MCP tools), grounding-validated">
                  {{ suggestion.processDocumentSource }}
                </span>
              </h4>
              <pre class="process-document-body">{{ suggestion.processDocument }}</pre>
            </div>

            <!-- Agentic synthesis: pick an agent, let it explore the graph, document the process -->
            <div class="synthesis-row" *ngIf="synthesisAgents.length > 0">
              <select class="agent-select" [(ngModel)]="selectedAgent"
                      [disabled]="synthesizing[suggestion.id]">
                <option *ngFor="let a of synthesisAgents" [value]="a.name">{{ a.displayName }}</option>
              </select>
              <button mat-stroked-button (click)="synthesize(suggestion)"
                      [disabled]="synthesizing[suggestion.id]"
                      matTooltip="Run the selected agent with the kompile MCP graph tools over this fact sheet and bring back a fully described process (takes minutes)">
                <mat-icon>smart_toy</mat-icon>
                {{ synthesizing[suggestion.id] ? 'Synthesizing…' :
                   (suggestion.processDocument ? 'Re-synthesize description' : 'Synthesize full description') }}
              </button>
            </div>

            <!-- Actions -->
            <div class="suggestion-actions" *ngIf="!suggestion.accepted">
              <button mat-raised-button color="primary" (click)="acceptSuggestion(suggestion)">
                <mat-icon>check</mat-icon> Accept & Create Process
              </button>
              <button mat-button color="warn" (click)="deleteSuggestion(suggestion)">
                <mat-icon>delete</mat-icon> Dismiss
              </button>
            </div>
            <div class="accepted-info" *ngIf="suggestion.accepted">
              <mat-icon>check_circle</mat-icon>
              Accepted as process: {{ suggestion.acceptedProcessDefinitionId }}
            </div>
          </div>
        </mat-expansion-panel>
      </mat-accordion>
    </div>
  `,
  styles: [`
    .suggestions-container { padding: 4px 0; }

    .section-header {
      display: flex; justify-content: space-between; align-items: center;
      margin-bottom: 16px; flex-wrap: wrap; gap: 8px;
    }
    .header-left { display: flex; align-items: center; gap: 8px; }
    .header-left h3 { margin: 0; font-size: 16px; }
    .count-badge {
      background: rgba(144,202,249,0.2); color: #90caf9;
      border-radius: 10px; padding: 2px 8px; font-size: 12px; font-weight: 600;
    }
    .header-actions { display: flex; gap: 8px; }

    .loading-bar { margin-bottom: 16px; }

    .empty-state {
      text-align: center; padding: 48px 16px; color: #888;
    }
    .empty-icon { font-size: 48px; width: 48px; height: 48px; color: #555; }

    .suggestion-panel { margin-bottom: 8px !important; }
    .suggestion-panel.accepted { opacity: 0.7; }

    .source-icon { font-size: 18px; width: 18px; height: 18px; margin-right: 8px; color: #90caf9; }
    .suggestion-name { font-weight: 500; }

    .header-meta { display: flex; align-items: center; gap: 8px; }
    .rank-badge {
      min-width: 26px; text-align: center; font-size: 11px; font-weight: 700;
      color: #4dd0e1; border: 1px solid rgba(77,208,225,0.35); border-radius: 4px; padding: 1px 5px;
    }
    .confidence-badge {
      font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 4px;
    }
    .confidence-badge.high { background: rgba(102,187,106,0.2); color: #66bb6a; }
    .confidence-badge.medium { background: rgba(255,183,77,0.2); color: #ffb74d; }
    .confidence-badge.low { background: rgba(239,83,80,0.2); color: #ef5350; }

    .accepted-chip { font-size: 11px !important; }

    .suggestion-detail { width: 100%; min-width: 0; padding: 8px 0; box-sizing: border-box; }
    .narrative {
      display: flex; align-items: flex-start; gap: 8px;
      background: rgba(144,202,249,0.06); border-left: 3px solid #90caf9;
      border-radius: 4px; padding: 10px 12px; margin: 0 0 12px;
    }
    .narrative-icon { font-size: 18px; width: 18px; height: 18px; color: #90caf9; margin-top: 1px; }
    .narrative-text { flex: 1; color: #ccc; font-size: 13px; line-height: 1.55; }
    .narrative-source {
      font-size: 10px; font-weight: 600; color: #888; white-space: nowrap;
      border: 1px solid rgba(255,255,255,0.15); border-radius: 3px; padding: 1px 5px;
    }
    .description { color: #bbb; margin: 0 0 16px; font-size: 12px; }

    h4 { display: flex; align-items: center; gap: 6px; font-size: 14px; margin: 16px 0 8px; color: #e0e0e0; }
    .section-icon { font-size: 18px; width: 18px; height: 18px; }

    /* Bayesian posteriors */
    .bayesian-section { margin-bottom: 16px; }
    .posteriors-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px;
    }
    .posterior-item { padding: 8px; background: rgba(255,255,255,0.03); border-radius: 4px; }
    .posterior-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
    .posterior-label { font-size: 12px; color: #aaa; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 140px; }
    .posterior-values { display: flex; align-items: center; gap: 4px; }
    .prior-value { font-size: 11px; color: #888; }
    .shift-arrow { font-size: 12px; height: 12px; width: 12px; color: #666; }
    .posterior-value { font-size: 12px; font-weight: 600; color: #e0e0e0; }
    .posterior-bar-container { position: relative; }
    .prior-marker { position: absolute; top: 0; width: 2px; height: 100%; background: #fff; opacity: 0.6; border-radius: 1px; pointer-events: auto; cursor: default; }
    .bar-high ::ng-deep .mdc-linear-progress__bar-inner { border-color: #66bb6a !important; }
    .bar-medium ::ng-deep .mdc-linear-progress__bar-inner { border-color: #ffb74d !important; }
    .bar-low ::ng-deep .mdc-linear-progress__bar-inner { border-color: #ef5350 !important; }

    /* Structured evidence */
    .evidence-section { margin-bottom: 16px; }
    .evidence-list { display: flex; flex-direction: column; gap: 6px; }
    .evidence-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .evidence-type-chip { font-size: 10px !important; height: 22px !important; min-height: 22px !important; }
    .ev-bayesian { background: rgba(206,147,216,0.2) !important; color: #ce93d8 !important; }
    .ev-causal { background: rgba(144,202,249,0.2) !important; color: #90caf9 !important; }
    .ev-temporal { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; }
    .ev-statistical { background: rgba(102,187,106,0.2) !important; color: #66bb6a !important; }
    /* Entailment outputs: violet = inferred (matches the graph's INFERRED-edge accent),
       teal = fused confidence breakdown, red = temporal/KB contradictions. */
    .ev-entailed { background: rgba(179,157,219,0.2) !important; color: #b39ddb !important; }
    .ev-fusion { background: rgba(77,208,225,0.2) !important; color: #4dd0e1 !important; }
    .ev-contradiction { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }
    .ev-learned { background: rgba(244,143,177,0.2) !important; color: #f48fb1 !important; }
    /* Observed performers (the OCPM resource perspective) — lime, matching the observed role chip. */
    .ev-resource { background: rgba(174,213,129,0.2) !important; color: #aed581 !important; }
    /* Operator semantics grounded in the log: XOR branch shares and AND order-instability. */
    .ev-choice { background: rgba(255,213,79,0.2) !important; color: #ffd54f !important; }
    .ev-parallel { background: rgba(128,203,196,0.2) !important; color: #80cbc4 !important; }
    /* Embedding-unified activity labels — a merge is a visible decision, never a silent rewrite. */
    .ev-alias { background: rgba(159,168,218,0.2) !important; color: #9fa8da !important; }
    /* Same process, different time: what changed since the previous mining generation. */
    .ev-drift { background: rgba(255,171,145,0.2) !important; color: #ffab91 !important; }
    /* Two live disagreeing accounts of the process — both sides surface with their sources. */
    .ev-conflict { background: rgba(255,138,101,0.25) !important; color: #ff8a65 !important; }
    /* The explicitly-GUESSED reconciliation (fused Opinion / BeliefReviser outcome). */
    .ev-reconciliation { background: rgba(197,225,165,0.2) !important; color: #c5e1a5 !important; }
    /* OWL closure consumption: is-a roll-ups and has-a relations between step concepts. */
    .ev-taxonomy { background: rgba(206,147,216,0.18) !important; color: #ce93d8 !important; }
    .revision-chip {
      font-size: 10px !important; height: 22px !important; min-height: 22px !important;
      background: rgba(255,171,145,0.15) !important; color: #ffab91 !important;
    }
    .learned-badge {
      font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 4px;
      background: rgba(244,143,177,0.15); color: #f48fb1; white-space: nowrap;
    }
    .evidence-desc { flex: 1; color: #bbb; }
    .evidence-score { font-weight: 600; color: #e0e0e0; font-size: 12px; }

    /* Phases */
    .phases-section { min-width: 0; max-width: 100%; margin-bottom: 16px; }
    .phase { min-width: 0; max-width: 100%; margin-bottom: 12px; }
    .phase-header {
      display: flex; align-items: center; flex-wrap: wrap; gap: 4px;
      min-width: 0; font-weight: 500; font-size: 13px; color: #e0e0e0; margin-bottom: 4px;
    }
    .step {
      display: flex; align-items: center; flex-wrap: wrap; gap: 8px;
      min-width: 0; max-width: 100%; padding: 4px 8px; box-sizing: border-box;
      font-size: 13px; color: #bbb;
    }
    .step-number { flex: 0 0 24px; font-size: 11px; color: #888; }
    .step-type-chip { font-size: 10px !important; height: 20px !important; min-height: 20px !important; }
    .step-name { flex: 1 1 140px; min-width: 0; font-weight: 500; color: #ddd; overflow-wrap: anywhere; }
    .step-role-chip {
      font-size: 10px !important; height: 20px !important; min-height: 20px !important;
      background: rgba(77,208,225,0.15) !important; color: #4dd0e1 !important;
    }
    /* An OBSERVED binding is a real person/role seen doing the work — distinct from KB/keyword teal. */
    .step-role-chip.role-observed {
      background: rgba(174,213,129,0.15) !important; color: #aed581 !important;
    }
    .role-icon { font-size: 12px !important; width: 12px !important; height: 12px !important; margin-right: 2px; }
    .step-deps {
      display: inline-flex; align-items: center; gap: 2px; flex: 1 1 100%; min-width: 0;
      font-size: 11px; color: #b39ddb; white-space: normal; overflow-wrap: anywhere;
    }
    .deps-icon { font-size: 14px; width: 14px; height: 14px; }
    .step-condition { display: inline-flex; align-items: center; color: #ffd54f; }
    .condition-icon { font-size: 14px; width: 14px; height: 14px; }
    .step-timestamp { font-size: 11px; color: #90caf9; white-space: nowrap; }

    .temporal-range {
      display: inline-flex; align-items: center; gap: 4px;
      font-size: 11px; color: #ffb74d; font-weight: 400; margin-left: 8px;
    }
    .temporal-icon { font-size: 14px; width: 14px; height: 14px; }

    .text-evidence ul { margin: 0; padding-left: 20px; }
    .text-evidence li { font-size: 13px; color: #bbb; margin-bottom: 4px; }

    .child-card { margin-bottom: 8px; }
    .child-bayesian { margin-top: 8px; }
    .child-evidence { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }

    /* MEBN badges for posterior entries */
    .posterior-mebn-meta { display: flex; gap: 4px; margin: 2px 0 4px; flex-wrap: wrap; }
    .mebn-badge-sm {
      font-size: 9px; padding: 1px 5px; border-radius: 3px; font-weight: 500;
      white-space: nowrap;
    }
    .mfrag-sm { background: rgba(144,202,249,0.15); color: #90caf9; }
    .role-sm { background: rgba(206,147,216,0.15); color: #ce93d8; }
    .role-sm-resident { background: rgba(102,187,106,0.15); color: #66bb6a; }
    .role-sm-input { background: rgba(255,183,77,0.15); color: #ffb74d; }
    .etype-sm { background: rgba(255,255,255,0.08); color: #bbb; }

    .source-nodes-section { margin-bottom: 12px; }
    .source-nodes-chips { display: flex; flex-wrap: wrap; gap: 4px; }
    .source-node-chip {
      font-size: 10px !important; height: 22px !important; min-height: 22px !important;
      background: rgba(144,202,249,0.1) !important; color: #90caf9 !important;
    }
    .chip-icon { font-size: 12px !important; width: 12px !important; height: 12px !important; margin-right: 2px; }

    .reasoning-summary {
      margin: 12px 0 16px; padding: 10px 0;
      border-top: 1px solid rgba(77,208,225,0.2);
      border-bottom: 1px solid rgba(77,208,225,0.2);
      background: rgba(77,208,225,0.035);
    }
    .reasoning-summary-header {
      display: flex; align-items: center; flex-wrap: wrap; gap: 7px;
      padding: 0 8px 8px; color: #e0f7fa; font-size: 12px; font-weight: 600;
    }
    .reasoning-summary-header mat-icon { color: #4dd0e1; font-size: 18px; width: 18px; height: 18px; }
    .reasoning-path {
      color: #80cbc4; border: 1px solid rgba(128,203,196,0.25);
      border-radius: 3px; padding: 1px 5px; font-size: 10px; font-weight: 500;
    }
    .reasoning-metrics {
      display: grid; grid-template-columns: repeat(auto-fit, minmax(92px, 1fr));
      gap: 1px; background: rgba(255,255,255,0.06);
    }
    .reasoning-metric {
      display: flex; flex-direction: column; gap: 2px; min-width: 0;
      padding: 7px 8px; background: #282828;
    }
    .reasoning-metric span { color: #999; font-size: 10px; white-space: normal; }
    .reasoning-metric strong { color: #e0e0e0; font-size: 13px; font-weight: 600; }

    .hybrid-interpretation { border-top: 1px solid rgba(255,255,255,0.08); margin-top: 8px; }
    .hybrid-interpretation summary {
      display: flex; align-items: center; flex-wrap: wrap; gap: 8px; cursor: pointer;
      padding: 9px 8px 5px; color: #d6f3f5; font-size: 11px; font-weight: 600;
    }
    .hybrid-mode, .hybrid-coverage {
      border: 1px solid rgba(128,203,196,0.22); border-radius: 3px;
      color: #80cbc4; font-size: 9px; font-weight: 500; padding: 1px 5px;
    }
    .hybrid-overview {
      display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
      gap: 1px; margin-top: 4px; background: rgba(255,255,255,0.05);
    }
    .hybrid-overview > span { display: flex; flex-direction: column; gap: 2px; padding: 6px 8px; background: #252525; }
    .hybrid-overview small { color: #888; font-size: 9px; }
    .hybrid-overview strong { color: #ddd; font-size: 11px; font-weight: 600; }
    .hybrid-embedding-source { display: flex; flex-wrap: wrap; gap: 4px 10px; padding: 6px 8px 0; color: #9e9e9e; font-size: 9px; overflow-wrap: anywhere; }
    .hybrid-embedding-source span:first-child { color: #80cbc4; font-weight: 600; }
    .hybrid-warning { color: #ffcc80; font-size: 10px; padding: 5px 8px 0; overflow-wrap: anywhere; }
    .hybrid-activity-list { max-height: 260px; overflow-y: auto; margin-top: 6px; }
    .hybrid-activity { padding: 6px 8px; border-top: 1px solid rgba(255,255,255,0.05); min-width: 0; }
    .hybrid-activity-header { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 7px; }
    .hybrid-activity-header > span { color: #ddd; font-size: 11px; overflow-wrap: anywhere; }
    .hybrid-activity-header > small { color: #80cbc4; font-size: 8px; text-transform: uppercase; }
    .hybrid-activity-header > strong { color: #4dd0e1; font-size: 11px; }
    .hybrid-components { display: flex; flex-wrap: wrap; gap: 5px 12px; padding-top: 3px; color: #aaa; font-size: 9px; }
    .hybrid-components small { color: #777; font-size: 9px; }

    .reasoning-trace { margin: 14px 0; }
    .trace-error { margin-left: 10px; color: #ef9a9a; font-size: 11px; }
    .trace-content {
      max-height: 360px; overflow: auto; margin-top: 8px;
      border-top: 1px solid rgba(255,255,255,0.1);
      border-bottom: 1px solid rgba(255,255,255,0.1);
    }
    .trace-summary {
      display: flex; gap: 14px; padding: 7px 8px;
      color: #80cbc4; background: rgba(77,208,225,0.05); font-size: 11px;
    }
    .trace-step {
      display: grid; grid-template-columns: minmax(72px, auto) minmax(0, 1fr) auto;
      align-items: start; gap: 8px; padding-top: 6px; padding-bottom: 6px; padding-right: 8px;
      border-top: 1px solid rgba(255,255,255,0.05); font-size: 11px;
    }
    .trace-kind { color: #b39ddb; font-weight: 600; overflow-wrap: anywhere; }
    .trace-step-body { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
    .trace-conclusion { color: #ddd; overflow-wrap: anywhere; }
    .trace-operation { color: #888; font-size: 10px; overflow-wrap: anywhere; }
    .trace-step strong { color: #4dd0e1; font-size: 10px; font-weight: 600; }

    .process-document { margin-top: 16px; }
    .process-document-body {
      background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08);
      border-radius: 4px; padding: 12px; color: #ccc; font-size: 12px; line-height: 1.5;
      white-space: pre-wrap; word-break: break-word; max-height: 420px; overflow-y: auto;
      font-family: inherit; margin: 0;
    }
    .synthesis-row { display: flex; align-items: center; gap: 8px; margin-top: 12px; }

    .process-map { margin-top: 12px; }
    .process-map-panels { display: flex; flex-direction: column; gap: 12px; margin-top: 8px; }
    .map-panel h5 { margin: 0 0 4px 0; font-size: 12px; font-weight: 500; color: #90caf9; }
    .agent-select {
      background: rgba(255,255,255,0.06); color: #ddd; border: 1px solid rgba(255,255,255,0.15);
      border-radius: 4px; padding: 6px 8px; font-size: 13px;
    }

    .suggestion-actions { display: flex; gap: 8px; margin-top: 16px; }
    .accepted-info {
      display: flex; align-items: center; gap: 6px;
      margin-top: 16px; color: #66bb6a; font-size: 13px;
    }
  `]
})
export class ProcessDiscoverySuggestionsComponent implements OnInit {
  suggestions: ProcessSuggestion[] = [];
  loading = false;
  /** nodeId → human title, resolved lazily so chips/posteriors show names not raw node ids. */
  graphNodeLabels: Record<string, string> = {};
  /** CLI agents available for full-description synthesis. */
  synthesisAgents: SynthesisAgent[] = [];
  /** Lazily fetched mined process maps (Mermaid dfg + tree), keyed by suggestion id. */
  processMaps: Record<string, { dfg: string; tree: string }> = {};
  mapLoading: Record<string, boolean> = {};
  reasoningTraces: Record<string, ProcessReasoningTrace | undefined> = {};
  traceLoading: Record<string, boolean> = {};
  traceErrors: Record<string, string | undefined> = {};
  selectedAgent = '';
  /** Per-suggestion in-flight flag (agent runs take minutes). */
  synthesizing: Record<string, boolean> = {};

  constructor(
    private processEngineService: ProcessEngineService,
    private graphService: GraphService,
    private http: HttpClient,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadSuggestions();
    this.http.get<SynthesisAgent[]>('/api/process/synthesis/agents').subscribe({
      next: (agents) => {
        this.synthesisAgents = agents || [];
        if (this.synthesisAgents.length > 0 && !this.selectedAgent) {
          this.selectedAgent = this.synthesisAgents[0].name;
        }
      },
      error: () => { this.synthesisAgents = []; }
    });
  }

  /** Lazily fetch and toggle the mined process map (Mermaid dfg + tree) for a suggestion. */
  toggleProcessMap(suggestion: ProcessSuggestion): void {
    if (this.processMaps[suggestion.id]) {
      delete this.processMaps[suggestion.id];
      return;
    }
    if (suggestion.factSheetId == null) { return; }
    this.mapLoading[suggestion.id] = true;
    this.processEngineService.miningMermaid(suggestion.factSheetId).subscribe({
      next: (map) => {
        this.mapLoading[suggestion.id] = false;
        this.processMaps[suggestion.id] = map;
      },
      error: (err) => {
        this.mapLoading[suggestion.id] = false;
        this.snackBar.open(
          'Process map failed: ' + (err.error?.error || err.message), 'Close', { duration: 5000 });
      }
    });
  }

  toggleReasoningTrace(suggestion: ProcessSuggestion): void {
    if (this.reasoningTraces[suggestion.id]) {
      delete this.reasoningTraces[suggestion.id];
      return;
    }
    this.traceLoading[suggestion.id] = true;
    delete this.traceErrors[suggestion.id];
    this.processEngineService.getStoredSuggestionTrace(suggestion.id).subscribe({
      next: (trace) => {
        this.traceLoading[suggestion.id] = false;
        this.reasoningTraces[suggestion.id] = trace;
      },
      error: (err) => {
        this.traceLoading[suggestion.id] = false;
        this.traceErrors[suggestion.id] = err.status === 404
          ? 'No persisted trace is available'
          : 'Trace failed: ' + (err.error?.message || err.message);
      }
    });
  }

  reasoningSteps(suggestion: ProcessSuggestion): FlatProcessReasoningStep[] {
    return flattenProcessReasoningTrace(this.reasoningTraces[suggestion.id]);
  }

  hybridActivities(suggestion: ProcessSuggestion): ProcessHybridActivityReasoning[] {
    return rankedProcessHybridActivities(suggestion);
  }

  hybridModeLabel(reasoning: ProcessHybridReasoning): string {
    return processHybridModeLabel(reasoning);
  }

  trackHybridActivity(_index: number, activity: ProcessHybridActivityReasoning): string {
    return activity.activity;
  }

  trackTraceStep(index: number, step: FlatProcessReasoningStep): string {
    return `${index}:${step.kind}:${step.conclusion}`;
  }

  hasReasoningSummary(suggestion: ProcessSuggestion): boolean {
    return suggestion.reasoningProjection != null
      || suggestion.reasoningFamily != null
      || suggestion.hybridScore != null
      || suggestion.entailmentScore != null
      || suggestion.processCaseCount != null
      || suggestion.processActivityCount != null
      || suggestion.directlyFollowsCount != null
      || suggestion.acceptedPrecedenceCount != null
      || suggestion.entailedOnlyPrecedenceCount != null
      || (suggestion.sourceGraphRelationIds?.length ?? 0) > 0;
  }

  formatReasoningLabel(value: string | null | undefined): string {
    return (value || '').replace(/_/g, ' ').toLowerCase();
  }

  /** Run the selected MCP-tooled agent over the fact sheet's graph for a full description. */
  synthesize(suggestion: ProcessSuggestion): void {
    if (!this.selectedAgent) { return; }
    this.synthesizing[suggestion.id] = true;
    this.http.post<ProcessSuggestion>(
        `/api/process/synthesis/suggestions/${suggestion.id}?agent=${encodeURIComponent(this.selectedAgent)}`,
        {}).subscribe({
      next: (updated) => {
        this.synthesizing[suggestion.id] = false;
        suggestion.processDocument = updated.processDocument;
        suggestion.processDocumentSource = updated.processDocumentSource;
        this.snackBar.open('Full process description synthesized', 'Close', { duration: 3000 });
      },
      error: (err) => {
        this.synthesizing[suggestion.id] = false;
        this.snackBar.open(
          'Synthesis failed: ' + (err.error?.error || err.error?.message || err.message),
          'Close', { duration: 6000 });
      }
    });
  }

  loadSuggestions(): void {
    this.loading = true;
    this.processEngineService.listStoredSuggestions().subscribe({
      next: (response) => {
        this.suggestions = sortProcessSuggestions(
          (response.suggestions || []) as ProcessSuggestion[]);
        this.loading = false;
        this.resolveNodeLabels();
      },
      error: () => {
        this.suggestions = [];
        this.loading = false;
      }
    });
  }

  /**
   * Resolve the raw graph node ids referenced by suggestions (source nodes + Bayesian posterior
   * keys, including child suggestions) to human titles via the graph store, so the UI shows readable
   * names ("Trade & promo discounts") instead of raw ids ("entity_tbl:excel:…cell:R1C2"). Falls back
   * to the raw id when a node has no title; budget-capped to bound lookups.
   */
  private resolveNodeLabels(): void {
    const ids = new Set<string>();
    const collect = (s: any) => {
      if (!s) { return; }
      (s.sourceGraphNodeIds || []).forEach((id: string) => ids.add(id));
      Object.keys(s.bayesianPosteriors || {}).forEach((id) => ids.add(id));
      (s.childSuggestions || []).forEach(collect);
    };
    this.suggestions.forEach(collect);

    let budget = 80;
    for (const id of ids) {
      if (budget-- <= 0) { break; }
      if (this.graphNodeLabels[id]) { continue; }
      this.graphService.getNode(id).subscribe({
        next: (node: any) => { if (node && node.title) { this.graphNodeLabels[id] = node.title; } },
        error: () => { /* node may not exist; keep raw id */ }
      });
    }
  }

  runDiscovery(): void {
    this.loading = true;
    // Run BOTH engines: the legacy heuristic matchers AND the LLM-free mining + entailment engine,
    // so the suggestion list carries the reasoning outputs (ENTAILED / FUSION / CONTRADICTION
    // evidence, entailed step dependencies) alongside the heuristic ones. One engine failing must
    // not sink the other.
    forkJoin({
      legacy: this.processEngineService.discoverProcesses().pipe(
        catchError(err => of({ error: err.error?.message || err.message }))),
      mined: this.processEngineService.mineAllProcesses().pipe(
        catchError(err => of({ error: err.error?.message || err.message })))
    }).subscribe(({ legacy, mined }) => {
      if (legacy.error && mined.error) {
        this.loading = false;
        this.snackBar.open(`Discovery failed: ${legacy.error}`, 'Close', { duration: 5000 });
        return;
      }
      const legacyCount = legacy.error != null ? 'failed' : (legacy.count ?? 0);
      const minedCount = mined.error != null ? 'failed' : (mined.count ?? 0);
      this.snackBar.open(
        `Discovery complete: ${legacyCount} heuristic, ${minedCount} mined+entailed`,
        'Close', { duration: 4000 }
      );
      this.loadSuggestions();
    });
  }

  acceptSuggestion(suggestion: ProcessSuggestion): void {
    this.processEngineService.acceptStoredSuggestion(suggestion.id).subscribe({
      next: (definition) => {
        this.snackBar.open(
          `Process "${definition.name}" created (${definition.id})`,
          'Close', { duration: 3000 }
        );
        this.loadSuggestions();
      },
      error: (err) => {
        this.snackBar.open(
          'Accept failed: ' + (err.error?.message || err.message),
          'Close', { duration: 5000 }
        );
      }
    });
  }

  deleteSuggestion(suggestion: ProcessSuggestion): void {
    this.processEngineService.deleteStoredSuggestion(suggestion.id).subscribe({
      next: () => {
        this.suggestions = this.suggestions.filter(s => s.id !== suggestion.id);
        this.snackBar.open('Suggestion dismissed', 'Close', { duration: 2000 });
      },
      error: (err) => {
        this.snackBar.open(
          'Delete failed: ' + (err.error?.message || err.message),
          'Close', { duration: 5000 }
        );
      }
    });
  }

  /**
   * Evidence ordered by decision relevance: the fused-confidence breakdown first, contradictions
   * (temporal/KB) next, entailed orderings third, then everything else in produced order.
   */
  sortedEvidence(list: StructuredEvidence[]): StructuredEvidence[] {
    const rank = (t: string) => {
      switch ((t || '').toUpperCase()) {
        case 'FUSION': return 0;
        case 'CONFLICT': return 1;
        case 'RECONCILIATION': return 2;
        case 'DRIFT': return 3;
        case 'CONTRADICTION': return 4;
        case 'ENTAILED': return 5;
        case 'RESOURCE': return 6;
        default: return 7;
      }
    };
    return [...(list || [])].sort((a, b) => rank(a.type) - rank(b.type));
  }

  roleTooltip(step: SuggestedStep): string {
    switch (step.roleSource) {
      case 'OBSERVED': return 'Observed performer — the majority actor on this activity\'s own instances (crawl relations)';
      case 'KB': return 'Role answered by a knowledge-base query (hasRole/performedBy facts)';
      case 'HEURISTIC': return 'Role guessed from the step name — no actor was observed';
      default: return 'Role derived from the knowledge graph';
    }
  }

  hasBayesianData(suggestion: ProcessSuggestion): boolean {
    return suggestion.bayesianPosteriors != null
      && Object.keys(suggestion.bayesianPosteriors).length > 0;
  }

  getPosteriorEntries(suggestion: ProcessSuggestion): Array<{ nodeId: string; value: number; prior?: number }> {
    if (!suggestion.bayesianPosteriors) return [];
    return Object.entries(suggestion.bayesianPosteriors)
      .map(([nodeId, value]) => ({
        nodeId,
        value,
        prior: suggestion.bayesianPriors?.[nodeId]
      }))
      .sort((a, b) => b.value - a.value);
  }

  getSourceIcon(source: string): string {
    switch (source?.toUpperCase()) {
      case 'EMAIL_FLOW': return 'email';
      case 'EXCEL_COMPUTATION': return 'table_chart';
      case 'DOCUMENT_PIPELINE': return 'description';
      case 'CROSS_DOCUMENT': return 'link';
      case 'COMMUNITY': return 'groups';
      case 'PROCESS_MINING': return 'schema';
      case 'REASONING_GRAPH_PROCESS_MINING': return 'account_tree';
      default: return 'auto_awesome';
    }
  }

  trackById(_index: number, item: ProcessSuggestion): string {
    return item.id;
  }
}
