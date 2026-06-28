/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Citation } from '../../models/api-models';

const BASIS_TYPE_ABBREV: Record<string, string> = {
  STRUCTURAL:     'STRUCT',
  LLM_EXTRACTION: 'LLM',
  PSL_INFERENCE:  'PSL',
  MEBN_INFERENCE: 'MEBN',
  CORROBORATION:  'CORR',
  ASSERTED:       'ASSERT'
};

const BASIS_TYPE_COLOR: Record<string, string> = {
  STRUCTURAL:     '#2196F3',
  LLM_EXTRACTION: '#9C27B0',
  PSL_INFERENCE:  '#00BCD4',
  MEBN_INFERENCE: '#FF9800',
  CORROBORATION:  '#4CAF50',
  ASSERTED:       '#F44336'
};

@Component({
  selector: 'app-source-citation',
  standalone: true,
  imports: [CommonModule],
  template: `
    <ng-container *ngIf="hasContent">

      <!-- Full mode -->
      <div *ngIf="!compact" class="source-citation">
        <div class="citation-main">
          <span class="citation-label">
            <a *ngIf="citation!.sourceUrl; else plainLabel"
               [href]="citation!.sourceUrl" target="_blank" rel="noopener">{{ label }}</a>
            <ng-template #plainLabel><span>{{ label }}</span></ng-template>
          </span>
          <span *ngIf="citation!.basisType"
                class="basis-badge"
                [style.background]="basisColor"
                [title]="citation!.basisType">{{ basisAbbrev }}</span>
          <span *ngIf="citation!.score != null" class="meta-chip">score {{ citation!.score!.toFixed(2) }}</span>
          <span *ngIf="citation!.confidence != null" class="meta-chip">conf {{ (citation!.confidence! * 100).toFixed(0) }}%</span>
          <span *ngIf="citation!.pageNumber != null" class="meta-chip">p.{{ citation!.pageNumber }}</span>
          <span *ngIf="citation!.chunkIndex != null" class="meta-chip">chunk #{{ citation!.chunkIndex! + 1 }}</span>
          <span *ngIf="citation!.crawlRunId"
                class="meta-chip crawl-id"
                [title]="citation!.crawlRunId!">{{ citation!.crawlRunId!.slice(0, 12) }}</span>
        </div>
        <div *ngIf="hasProvenance" class="provenance-section">
          <button type="button" class="prov-toggle" (click)="provenanceOpen = !provenanceOpen">
            {{ provenanceOpen ? '▼' : '▶' }} provenance
          </button>
          <div *ngIf="provenanceOpen" class="prov-rows">
            <div *ngFor="let kv of provenanceEntries" class="prov-row">
              <span class="prov-key">{{ kv.key }}</span>
              <span class="prov-val">{{ kv.value }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Compact mode: single inline row (label + score/confidence + basisType only) -->
      <span *ngIf="compact" class="source-citation compact">
        <span class="citation-label">
          <a *ngIf="citation!.sourceUrl; else compactPlain"
             [href]="citation!.sourceUrl" target="_blank" rel="noopener">{{ label }}</a>
          <ng-template #compactPlain><span>{{ label }}</span></ng-template>
        </span>
        <span *ngIf="citation!.basisType"
              class="basis-badge"
              [style.background]="basisColor"
              [title]="citation!.basisType">{{ basisAbbrev }}</span>
        <span *ngIf="citation!.score != null" class="meta-chip">score {{ citation!.score!.toFixed(2) }}</span>
        <span *ngIf="citation!.confidence != null" class="meta-chip">conf {{ (citation!.confidence! * 100).toFixed(0) }}%</span>
      </span>

    </ng-container>
  `,
  styles: [`
    .source-citation {
      font-size: 12px;
      color: var(--text-secondary, #6b7280);
    }

    .citation-main {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 4px;
    }

    .citation-label {
      color: var(--text-primary, #1a1f36);
      font-weight: 500;
    }

    .citation-label a {
      color: var(--primary-color, #667eea);
      text-decoration: none;
    }

    .citation-label a:hover {
      text-decoration: underline;
    }

    .basis-badge {
      display: inline-block;
      padding: 1px 5px;
      border-radius: 3px;
      font-size: 10px;
      font-weight: 700;
      color: #fff;
      letter-spacing: 0.3px;
      white-space: nowrap;
    }

    .meta-chip {
      display: inline-block;
      padding: 1px 5px;
      border-radius: 3px;
      font-size: 11px;
      background: var(--bg-card, #f8f9fa);
      border: 1px solid var(--border-color, #e3e8ee);
      color: var(--text-secondary, #6b7280);
      white-space: nowrap;
    }

    .crawl-id {
      font-family: monospace;
    }

    .provenance-section {
      margin-top: 4px;
    }

    .prov-toggle {
      background: none;
      border: none;
      cursor: pointer;
      font-size: 11px;
      color: var(--text-secondary, #6b7280);
      padding: 2px 0;
    }

    .prov-rows {
      margin-top: 4px;
      padding: 4px 8px;
      background: var(--bg-card, #f8f9fa);
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 4px;
    }

    .prov-row {
      display: flex;
      gap: 8px;
      font-size: 11px;
      padding: 1px 0;
    }

    .prov-key {
      color: var(--text-secondary, #6b7280);
      min-width: 80px;
      font-weight: 500;
    }

    .prov-val {
      color: var(--text-primary, #1a1f36);
      word-break: break-all;
    }

    .source-citation.compact {
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }
  `]
})
export class SourceCitationComponent {
  @Input() citation?: Citation | null;
  @Input() compact = false;

  provenanceOpen = false;

  get hasContent(): boolean {
    if (!this.citation) return false;
    const c = this.citation;
    return !!(c.sourceName || c.sourceId || c.score != null || c.confidence != null
      || c.basisType || c.pageNumber != null || c.chunkIndex != null
      || c.crawlRunId || c.sourceUrl);
  }

  get label(): string {
    return this.citation?.sourceName || this.citation?.sourceId || '';
  }

  get basisAbbrev(): string {
    if (!this.citation?.basisType) return '';
    return BASIS_TYPE_ABBREV[this.citation.basisType] ?? this.citation.basisType;
  }

  get basisColor(): string {
    if (!this.citation?.basisType) return '#999';
    return BASIS_TYPE_COLOR[this.citation.basisType] ?? '#999';
  }

  get hasProvenance(): boolean {
    const p = this.citation?.provenance;
    return !!p && Object.keys(p).length > 0;
  }

  get provenanceEntries(): { key: string; value: string }[] {
    if (!this.citation?.provenance) return [];
    return Object.entries(this.citation.provenance).map(([key, value]) => ({
      key,
      value: typeof value === 'object' ? JSON.stringify(value) : String(value)
    }));
  }
}
