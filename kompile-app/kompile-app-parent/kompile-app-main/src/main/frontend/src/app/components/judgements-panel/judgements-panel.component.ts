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

import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  EnforcerService,
  JudgementRecord,
  JudgementSessionSummary
} from '../../services/enforcer.service';

/**
 * Per-decision judgement viewer. Reads the durable judgement log the CLI enforcer writes to
 * ~/.kompile/sessions/<id>/judgements.jsonl (served by GET /api/enforcer/judgements). This is
 * how the managed judge/enforcer session becomes observable in the web UI — including, for an
 * LLM judge, the raw prompt → response → decision transcript. Auto-refreshes for a near-live view.
 */
@Component({
  selector: 'app-judgements-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <div class="jp">
    <div class="jp-head">
      <h3>Judgements</h3>
      <span class="jp-sub">Every decision the judge/enforcer made — including the raw LLM judge transcript.</span>
      <span class="jp-spacer"></span>
      <label class="jp-toggle"><input type="checkbox" [(ngModel)]="autoRefresh"> auto-refresh</label>
      <button class="jp-btn" (click)="refresh()">↻ Refresh</button>
    </div>

    <div class="jp-body">
      <div class="jp-sessions">
        <div class="jp-sessions-head">Sessions ({{ sessions.length }})</div>
        <div *ngIf="sessions.length === 0" class="jp-empty">
          No judgements yet. Enforcement evaluates agent sessions against your rules — start one
          from the <strong>Sessions</strong> tab (New Session button), or configure a project judge
          in the <strong>Project Judge</strong> tab.
        </div>
        <div *ngFor="let s of sessions"
             class="jp-session" [class.active]="s.sessionId === selectedSession"
             (click)="selectSession(s.sessionId)">
          <div class="jp-session-id">{{ s.sessionId }}</div>
          <div class="jp-session-meta">
            <span>{{ s.records }} rec</span>
            <span class="jp-pill">{{ s.lastStatus }}</span>
          </div>
          <div class="jp-session-backend" *ngIf="s.lastBackend">{{ s.lastBackend }}</div>
        </div>
      </div>

      <div class="jp-records">
        <div class="jp-filters" *ngIf="selectedSession">
          <span class="jp-rec-count">{{ filteredRecords.length }} / {{ records.length }} records</span>
          <span class="jp-spacer"></span>
          <select [(ngModel)]="filterPhase" class="jp-select">
            <option value="all">All phases</option>
            <option value="judge">Judge calls (LLM transcript)</option>
            <option value="outcome">Outcomes (attempt / result)</option>
            <option value="swap">Model swaps</option>
          </select>
        </div>

        <div *ngIf="loading" class="jp-loading">Loading…</div>

        <div *ngFor="let r of filteredRecords; let i = index" class="jp-rec"
             [class.fail]="!r.compliant" [class.ok]="r.compliant">
          <div class="jp-rec-line">
            <span class="jp-sym" [class.bad]="!r.compliant">{{ symbol(r) }}</span>
            <span class="jp-time">{{ shortTime(r.timestamp) }}</span>
            <span class="jp-phase">{{ r.phase }}</span>
            <span class="jp-attempt" *ngIf="r.attempt">a{{ r.attempt }}</span>
            <span class="jp-mode">{{ r.judgeMode }}</span>
            <span class="jp-latency" *ngIf="r.latencyMs">{{ r.latencyMs }}ms</span>
            <span class="jp-status" *ngIf="r.status">{{ r.status }}</span>
            <span class="jp-sev" *ngIf="!r.status && r.severity">{{ r.severity }}</span>
            <span class="jp-backend" *ngIf="r.backend">{{ r.backend }}</span>
            <span class="jp-tool" *ngIf="r.toolName">tool={{ r.toolName }}</span>
          </div>
          <div class="jp-violations" *ngIf="r.violations && r.violations.length">
            ⚠ {{ r.violations.join('; ') }}
          </div>
          <div class="jp-reason" *ngIf="r.reasoning">{{ r.reasoning }}</div>
          <div class="jp-correction" *ngIf="r.correctionPrompt">↳ correction: {{ r.correctionPrompt }}</div>
          <div class="jp-raw-wrap" *ngIf="r.judgeRawResponse">
            <button class="jp-raw-toggle" (click)="toggleRaw(i)">
              {{ isExpanded(i) ? '▾' : '▸' }} judge response
            </button>
            <pre class="jp-raw" *ngIf="isExpanded(i)">{{ r.judgeRawResponse }}</pre>
          </div>
        </div>

        <div *ngIf="selectedSession && !loading && records.length === 0" class="jp-empty">
          No records for this session yet.
        </div>
      </div>
    </div>
  </div>
  `,
  styles: [`
    .jp { display: flex; flex-direction: column; height: 100%; font-size: 13px; }
    .jp-head { display: flex; align-items: center; gap: 10px; padding: 8px 4px; flex-wrap: wrap; }
    .jp-head h3 { margin: 0; }
    .jp-sub { opacity: 0.65; font-size: 12px; }
    .jp-spacer { flex: 1; }
    .jp-toggle { font-size: 12px; opacity: 0.8; display: flex; align-items: center; gap: 4px; }
    .jp-btn, .jp-raw-toggle { cursor: pointer; border: 1px solid rgba(128,128,128,0.35);
      background: transparent; color: inherit; border-radius: 4px; padding: 3px 8px; font-size: 12px; }
    .jp-btn:hover, .jp-raw-toggle:hover { background: rgba(128,128,128,0.12); }
    .jp-body { display: flex; gap: 12px; flex: 1; min-height: 0; }
    .jp-sessions { width: 240px; flex: 0 0 240px; overflow-y: auto; border-right: 1px solid rgba(128,128,128,0.25); padding-right: 8px; }
    .jp-sessions-head { font-weight: 600; opacity: 0.7; margin-bottom: 6px; font-size: 12px; }
    .jp-session { padding: 6px 8px; border-radius: 6px; cursor: pointer; margin-bottom: 4px; border: 1px solid transparent; }
    .jp-session:hover { background: rgba(128,128,128,0.10); }
    .jp-session.active { background: rgba(63,81,181,0.14); border-color: rgba(63,81,181,0.4); }
    .jp-session-id { font-family: monospace; font-size: 12px; word-break: break-all; }
    .jp-session-meta { display: flex; gap: 6px; font-size: 11px; opacity: 0.75; margin-top: 2px; }
    .jp-session-backend { font-size: 11px; opacity: 0.55; margin-top: 2px; }
    .jp-pill, .jp-status, .jp-sev { font-size: 11px; padding: 0 5px; border-radius: 3px; background: rgba(128,128,128,0.18); }
    .jp-records { flex: 1; overflow-y: auto; min-width: 0; }
    .jp-filters { display: flex; align-items: center; gap: 8px; position: sticky; top: 0;
      padding: 4px 0 8px; backdrop-filter: blur(2px); }
    .jp-rec-count { font-size: 12px; opacity: 0.7; }
    .jp-select { background: transparent; color: inherit; border: 1px solid rgba(128,128,128,0.35);
      border-radius: 4px; padding: 3px 6px; font-size: 12px; }
    .jp-loading, .jp-empty { opacity: 0.6; padding: 16px 8px; font-style: italic; }
    .jp-rec { border-left: 3px solid rgba(128,128,128,0.35); padding: 5px 10px; margin-bottom: 6px; }
    .jp-rec.fail { border-left-color: #e53935; }
    .jp-rec.ok { border-left-color: #43a047; }
    .jp-rec-line { display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; }
    .jp-sym { font-weight: 700; color: #43a047; }
    .jp-sym.bad { color: #e53935; }
    .jp-time { font-family: monospace; opacity: 0.7; }
    .jp-phase { font-weight: 600; }
    .jp-attempt, .jp-mode, .jp-latency, .jp-backend, .jp-tool { font-size: 11px; opacity: 0.7; }
    .jp-backend { font-family: monospace; }
    .jp-violations { color: #e53935; margin: 2px 0 0 22px; }
    .jp-reason { opacity: 0.8; margin: 2px 0 0 22px; }
    .jp-correction { opacity: 0.7; margin: 2px 0 0 22px; font-style: italic; }
    .jp-raw-wrap { margin: 4px 0 0 22px; }
    .jp-raw { margin: 4px 0 0 0; padding: 8px; background: rgba(128,128,128,0.10);
      border-radius: 4px; overflow-x: auto; font-size: 12px; white-space: pre-wrap; word-break: break-word; }
  `]
})
export class JudgementsPanelComponent implements OnInit, OnDestroy {
  sessions: JudgementSessionSummary[] = [];
  selectedSession: string | null = null;
  records: JudgementRecord[] = [];
  filterPhase: 'all' | 'judge' | 'outcome' | 'swap' = 'all';
  autoRefresh = true;
  loading = false;
  private expanded = new Set<number>();
  private pollHandle: any = null;

  constructor(private enforcer: EnforcerService) {}

  ngOnInit(): void {
    this.loadSessions();
    this.pollHandle = setInterval(() => {
      if (this.autoRefresh) {
        this.loadSessions();
        this.loadRecords();
      }
    }, 4000);
  }

  ngOnDestroy(): void {
    if (this.pollHandle) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }

  loadSessions(): void {
    this.enforcer.getJudgementSessions().subscribe({
      next: (s) => {
        this.sessions = s;
        if (!this.selectedSession && s.length > 0) {
          this.selectSession(s[0].sessionId);
        }
      },
      error: () => {}
    });
  }

  selectSession(id: string): void {
    if (this.selectedSession !== id) {
      this.expanded.clear();
    }
    this.selectedSession = id;
    this.loadRecords();
  }

  loadRecords(): void {
    if (!this.selectedSession) {
      return;
    }
    this.loading = this.records.length === 0;
    this.enforcer.getJudgements(this.selectedSession).subscribe({
      next: (r) => { this.records = r; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }

  get filteredRecords(): JudgementRecord[] {
    switch (this.filterPhase) {
      case 'judge':
        return this.records.filter(r => (r.phase || '').startsWith('JUDGE'));
      case 'outcome':
        return this.records.filter(r => r.phase === 'RESULT' || r.phase === 'ATTEMPT');
      case 'swap':
        return this.records.filter(r => r.phase === 'SWAP');
      default:
        return this.records;
    }
  }

  refresh(): void {
    this.loadSessions();
    this.loadRecords();
  }

  toggleRaw(i: number): void {
    if (this.expanded.has(i)) {
      this.expanded.delete(i);
    } else {
      this.expanded.add(i);
    }
  }

  isExpanded(i: number): boolean {
    return this.expanded.has(i);
  }

  symbol(r: JudgementRecord): string {
    return r.compliant ? '✓' : (r.stop ? '■' : '✗');
  }

  shortTime(iso?: string): string {
    if (!iso) {
      return '';
    }
    const t = iso.indexOf('T');
    return t >= 0 ? iso.substring(t + 1, t + 9) : iso;
  }
}
