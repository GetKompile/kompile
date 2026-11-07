import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';

export interface DurableChild {
  sessionId: string; name?: string | null; agentType?: string | null; model?: string | null;
  lastActivity?: string | null; locked: boolean;
}
export interface DurableSubagentsMenu {
  menu: 'durable-subagents'; action: 'list' | 'show' | 'resume'; sessionId?: string;
  children?: DurableChild[]; history?: { role: string; content: string }[];
}
export interface DurableSubagentsOutcome { ok: boolean; text: string; data: DurableSubagentsMenu | null; }
const CHILD_ID = /^subagent-[A-Za-z0-9_-]+$/;

/** A narrow view contract: never treat child transcript content as markup or configuration. */
export function durableSubagentsMenu(value: unknown): DurableSubagentsMenu | null {
  if (!value || typeof value !== 'object') return null;
  const data = value as Record<string, unknown>;
  if (data['menu'] !== 'durable-subagents' || !['list', 'show', 'resume'].includes(String(data['action']))) return null;
  const result: DurableSubagentsMenu = { menu: 'durable-subagents', action: data['action'] as DurableSubagentsMenu['action'] };
  if (typeof data['sessionId'] === 'string' && CHILD_ID.test(data['sessionId'])) result.sessionId = data['sessionId'];
  if (Array.isArray(data['children'])) result.children = data['children'].filter((row): row is DurableChild =>
    !!row && typeof row === 'object' && typeof row.sessionId === 'string' && CHILD_ID.test(row.sessionId)
      && typeof row.locked === 'boolean').map(row => ({ sessionId: row.sessionId,
        name: typeof row.name === 'string' ? row.name : null, agentType: typeof row.agentType === 'string' ? row.agentType : null,
        model: typeof row.model === 'string' ? row.model : null, lastActivity: typeof row.lastActivity === 'string' ? row.lastActivity : null,
        locked: row.locked }));
  if (Array.isArray(data['history'])) {
    let room = 65_536;
    result.history = data['history'].slice(-30).filter(row => row && typeof row.role === 'string' && typeof row.content === 'string')
      .map(row => {
        const content = row.content.slice(0, room); room -= content.length;
        return { role: row.role.slice(0, 40), content };
      });
  }
  return result;
}

@Component({
  selector: 'app-durable-subagents', standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule],
  template: `
    <section aria-label="Saved child conversations" data-testid="durable-subagents">
      <h3>Saved child conversations</h3>
      <p>Children from this project only. Resuming keeps the child identity and uses the current chat's permissions.</p>
      <button mat-button type="button" data-testid="durable-refresh" [disabled]="disabled || !!pending" (click)="refresh()">Refresh saved children</button>
      <p *ngIf="!loaded">Load saved children to inspect or continue one.</p>
      <p *ngIf="loaded && !children.length" data-testid="durable-empty">No saved children in this project.</p>
      <ul><li *ngFor="let child of children">
        <button mat-button type="button" [disabled]="disabled || !!pending" (click)="open(child)"
          [attr.data-child-id]="child.sessionId">{{ child.name || child.sessionId }}</button>
        <span>{{ child.agentType }} · {{ child.model || 'model unknown' }} · {{ child.lastActivity }}{{ child.locked ? ' · busy elsewhere' : '' }}</span>
      </li></ul>
      <div *ngIf="selected() as child" data-testid="durable-selected">
        <h4>{{ child.name || child.sessionId }}</h4>
        <p *ngIf="child.locked">This child is live or being resumed elsewhere. Refresh after it finishes.</p>
        <div *ngFor="let turn of history" class="child-turn"><strong>{{ turn.role }}</strong><pre>{{ turn.content }}</pre></div>
        <label>Follow-up for this child only
          <textarea data-testid="durable-prompt" rows="3" maxlength="8000" [(ngModel)]="prompt" [disabled]="disabled || !!pending || child.locked"></textarea>
        </label>
        <button mat-flat-button type="button" data-testid="durable-resume" [disabled]="!canResume()" (click)="resume()">Resume child</button>
      </div>
      <p *ngIf="pending" role="status">{{ pending === 'resume' ? 'Continuing child…' : 'Loading saved child…' }}</p>
      <p *ngIf="error" role="alert" data-testid="durable-error">{{ error }}</p>
      <pre *ngIf="status" role="status" data-testid="durable-result">{{ status }}</pre>
    </section>`,
  styles: [`:host { display:block; } section { border-top:1px solid var(--border-color,#ccc); padding:12px 0; }
    pre { white-space:pre-wrap; overflow-wrap:anywhere; max-height:16rem; overflow:auto; }
    textarea { box-sizing:border-box; display:block; width:100%; font:inherit; color:inherit; background:var(--bg-body,#fff); }
    ul { padding-left:1rem; } .child-turn { margin:8px 0; }`]
})
export class DurableSubagentsComponent implements OnChanges {
  @Input() menu: DurableSubagentsMenu | null = null;
  @Input() outcome: DurableSubagentsOutcome | null = null;
  @Input() disabled = false;
  @Output() dispatch = new EventEmitter<string>();
  children: DurableChild[] = [];
  loaded = false;
  selectedId = '';
  history: { role: string; content: string }[] = [];
  prompt = '';
  pending: 'list' | 'show' | 'resume' | null = null;
  error = '';
  status = '';

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['menu'] && this.menu) this.accept(this.menu);
    if (changes['outcome'] && this.outcome) {
      const data = this.outcome.data;
      // Ignore a late answer for a previously selected child.
      if (data?.sessionId && this.selectedId && data.sessionId !== this.selectedId) return;
      const wasResume = this.pending === 'resume';
      this.pending = null;
      if (!this.outcome.ok) { this.error = this.outcome.text.slice(0, 8000); return; }
      this.error = '';
      if (data) this.accept(data);
      if (wasResume) { this.prompt = ''; this.status = this.outcome.text.slice(0, 65_536); }
    }
  }
  private accept(data: DurableSubagentsMenu): void {
    if (data.children) {
      this.children = data.children; this.loaded = true;
      if (this.selectedId && !this.selected()) { this.selectedId = ''; this.history = []; }
    }
    if (data.sessionId === this.selectedId && data.history) this.history = data.history;
  }
  selected(): DurableChild | undefined { return this.children.find(child => child.sessionId === this.selectedId); }
  refresh(): void {
    if (this.disabled || this.pending) return;
    this.pending = 'list'; this.error = '';
    this.dispatch.emit('/subagents saved');
  }
  open(child: DurableChild): void {
    if (this.disabled || this.pending || !CHILD_ID.test(child.sessionId)) return;
    this.selectedId = child.sessionId; this.history = []; this.prompt = ''; this.error = ''; this.status = ''; this.pending = 'show';
    this.dispatch.emit('/subagents show ' + child.sessionId);
  }
  canResume(): boolean {
    return !this.disabled && !this.pending && !!this.selected() && !this.selected()!.locked
      && !!this.prompt.trim() && this.prompt.length <= 8000;
  }
  resume(): void {
    if (!this.canResume()) return;
    this.pending = 'resume'; this.error = ''; this.status = '';
    this.dispatch.emit('/subagents resume ' + JSON.stringify({ session_id: this.selectedId, prompt: this.prompt.trim() }));
  }
}
