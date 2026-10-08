/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
import { Component, Inject, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subject, takeUntil } from 'rxjs';
import {
  CommandEventData,
  CommandOutcome,
  CommandRoleEntry
} from '@shared/models/api-models';
import { LocalAgentChatService, SessionConfigSnapshot } from '@shared/services/local-agent-chat.service';
import {
  ChatRouteFieldsComponent, ChatSetupCatalog, ChatSetupSelection, Choice, clearRouteDependents, mergeRouteCatalog
} from '../chat-route-fields/chat-route-fields.component';

/** What the session wizard's update answers: the CLI's ok/status plus the route it pinned. */
interface RouteUpdate { ok?: boolean; status?: string; framework?: string; model?: string; }

/**
 * Data for the command configuration modal. The parent owns all CLI
 * interaction (selection dispatches through the normal send path); the dialog
 * renders state and forwards clicks.
 */
export interface CommandConfigDialogData {
  /** Current model menu snapshot (may be null until the CLI answers). */
  modelMenu: CommandEventData | null;
  /** Current role menu snapshot (may be null). */
  roleMenu: CommandEventData | null;
  /** Current fast-mode snapshot (may be null). */
  fastMenu: CommandEventData | null;
  /** Current ultracode snapshot (may be null). */
  ultracodeMenu: CommandEventData | null;
  /** Session reminder list snapshot (may be null until the CLI answers). */
  reminders: CommandEventData | null;
  /** Project-global reminder list snapshot (may be null). */
  remindersGlobal: CommandEventData | null;
  /** Session loop list snapshot (may be null). */
  loops: CommandEventData | null;
  /** Project-global loop list snapshot (may be null). */
  loopsGlobal: CommandEventData | null;
  /** Session message-queue snapshot (may be null). */
  queue: CommandEventData | null;
  /** /continue auto-reply snapshot (may be null). */
  continueMenu: CommandEventData | null;
  /** /judge durable posture snapshot (may be null). */
  judgeMenu: CommandEventData | null;
  /** Durable harness session id for the quiet config snapshot (session-scoped state). */
  sessionId?: string;
  /** Working directory for the quiet config snapshot (project-scoped state). */
  workingDirectory?: string;
  /** Whether a harness turn is currently streaming. */
  busy: () => boolean;
  /** Whether a live harness run can accept loop run-now dispatches. */
  liveSession: () => boolean;
  /** Dispatch a bare CLI command (e.g. '/model') as if typed. */
  dispatch: (commandLine: string) => void;
  /** Select a role (raw '/role <name>' dispatch); 'none' clears (an empty argument is never sent). */
  selectRole: (roleName: string) => void;
  /** Toggle fast mode (raw '/fast on|off' dispatch). */
  toggleFastMode: (enabled: boolean) => void;
  /** Toggle Claude Code ultracode (raw '/ultracode on|off' dispatch). */
  toggleUltracode: (enabled: boolean) => void;
  /** Start a fresh conversation (browser-side /clear hand-off). */
  clearConversation: () => void;
  /** The session wizard re-pinned this chat's route; the selector above the chat reloads. */
  routeUpdated?: () => void;
}

/**
 * Session configuration modal for the CLI harness pickers (model / role /
 * fast mode / ultracode). Lives alongside the other configuration settings: opened from a
 * section in the settings sidebar. Every click dispatches the raw CLI command
 * through the normal send path; the CLI resolves, validates, and persists.
 */
@Component({
  selector: 'app-command-config-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatProgressBarModule,
    ChatRouteFieldsComponent],
  template: `
    <div class="command-config">
      <h2 mat-dialog-title>Session Configuration</h2>
      <!-- Every CLI round trip the dialog waits on shows here, wherever the content is scrolled. -->
      <mat-progress-bar class="cc-progress" mode="indeterminate" [class.cc-progress-idle]="!working()"
        data-testid="command-config-progress" [attr.aria-hidden]="!working()"></mat-progress-bar>
      <mat-dialog-content>
        <!-- Quiet-load state: the snapshot is one background CLI query; nothing is
             dispatched into the transcript, so until it resolves this is ALL the
             dialog shows. -->
        <div class="cc-loading" *ngIf="loading" role="status" data-testid="command-config-loading">
          <span class="cc-loading-spinner"></span>
          Loading this chat's configuration from the CLI…
        </div>
        <p class="cc-error" *ngIf="!loading && loadError" data-testid="command-config-error">{{ loadError }}</p>

        <ng-container *ngIf="!loading">
        <p class="cc-hint" *ngIf="busy()">Working…</p>

        <!-- The CLI setup wizard for this chat: the same route fields as a new chat, seeded from this chat. -->
        <section class="cc-section" data-testid="session-route-config">
          <header class="cc-section-header">
            <span class="cc-title">Route and model</span>
            <span class="cc-meta" *ngIf="modelMenu?.provider">{{ modelMenu!.provider }}</span>
            <button mat-icon-button type="button" class="cc-refresh" [disabled]="busy() || routeLoading || routeSaving"
              (click)="loadRoute()" title="Rediscover vendors, accounts and models from the CLI"
              aria-label="Rediscover setup options"><mat-icon>refresh</mat-icon></button>
          </header>
          <div class="cc-loading cc-loading-inline" *ngIf="routeLoading || routeSaving" role="status"
            data-testid="session-route-loading">
            <span class="cc-loading-spinner"></span>{{ routeActivity() }}
          </div>
          <label class="cc-grow" *ngIf="routeCatalog">Chat mode
            <select class="cc-input" name="routeMode" data-testid="session-route-mode" [(ngModel)]="route.mode"
              [disabled]="busy() || routeLoading || routeSaving" (ngModelChange)="routeChange('mode')">
              <option *ngFor="let m of routeModes(); trackBy: modeId" [value]="m.id">{{ m.label }}</option></select></label>
          <p class="cc-hint" *ngIf="routeCatalog">Mode, native framework, vendor, authentication, account, endpoint, model
            and effort apply from the next turn. A newly chosen native framework starts its own session.</p>
          <p class="cc-error" *ngIf="routeError" role="alert" data-testid="session-route-error">{{ routeError }}</p>
          <app-chat-route-fields *ngIf="routeCatalog" [catalog]="routeCatalog" [selection]="route"
            [disabled]="busy() || routeLoading || routeSaving" [nativeMode]="routeNative()"
            [toggles]="false" (fieldChange)="routeChange($event)" (refresh)="loadRoute()"></app-chat-route-fields>
          <div class="cc-add-row" *ngIf="routeCatalog">
            <label class="cc-grow">Also save as
              <select class="cc-input" name="routeSaveScope" [(ngModel)]="route.saveScope" [disabled]="routeSaving">
                <option value="session">This chat only</option><option value="project">Project defaults</option>
                <option value="global">Global defaults</option></select></label>
            <button mat-flat-button type="button" class="cc-add-btn" data-testid="session-route-apply"
              [disabled]="!canApplyRoute()" (click)="applyRoute()">{{ routeSaving ? 'Applying…' : 'Apply' }}</button>
          </div>
          <p class="cc-hint" role="status" *ngIf="routeStatus" data-testid="session-route-status">{{ routeStatus }}</p>
        </section>

        <ng-container *ngIf="!modelMenu?.nativeModelSelection">
        <!-- Role -->
        <section class="cc-section">
          <header class="cc-section-header">
            <span class="cc-title">Role</span>
            <span class="cc-meta" *ngIf="roleMenu?.currentRole">current: {{ roleMenu!.currentRole }}</span>
            <button mat-icon-button type="button" class="cc-refresh" [disabled]="busy()"
              (click)="dispatch('/role')" title="Reload the role roster from the CLI"
              aria-label="Reload the role roster"><mat-icon>refresh</mat-icon></button>
          </header>
          <div class="cc-options" role="listbox" aria-label="Available roles" *ngIf="roles().length">
            <button type="button" role="option" class="cc-option"
              *ngFor="let r of roles()"
              [class.current]="r.current"
              [attr.aria-selected]="r.current ? 'true' : 'false'"
              [disabled]="busy()"
              (click)="selectRole(r.name)"
              [title]="r.description || roleLabel(r)">
              <span class="cc-option-label">{{ roleLabel(r) }}</span>
              <span class="cc-option-meta" *ngIf="r.category">{{ r.category }}</span>
              <span class="cc-current" *ngIf="r.current">current</span>
            </button>
          </div>
          <p class="cc-empty" *ngIf="!roles().length && !busy()">
            No roles listed yet.
            <button mat-button type="button" class="cc-link" [disabled]="busy()" (click)="dispatch('/role')"
              title="Load the role roster from the CLI">Load roles</button>
          </p>
          <div class="cc-footer-row" *ngIf="roleMenu?.currentRole">
            <!-- 'none' is the web route's clear; the parent drops an empty argument. -->
            <button mat-button type="button" class="cc-link" [disabled]="busy()" (click)="selectRole('none')"
              title="Clear the session role and use the default persona">
              Clear role (default persona)
            </button>
          </div>
        </section>

        <!-- Fast mode -->
        <section class="cc-section">
          <header class="cc-section-header">
            <span class="cc-title">Fast mode</span>
          </header>
          <ng-container *ngIf="fastMenu; else fastUnknown">
            <div class="cc-fast-row">
              <span class="cc-fast-state" [class.on]="fastMenu!.fastMode">
                {{ fastMenu!.fastMode ? 'ON (requested)' : 'OFF' }}
              </span>
              <button mat-stroked-button type="button" class="cc-fast-toggle" *ngIf="fastMenu!.supported"
                [disabled]="busy()" (click)="toggleFastMode(!fastMenu!.fastMode)"
                [title]="fastMenu!.fastMode ? 'Use standard speed, as /fast off does in the terminal' : 'Request fast mode (higher cost), as /fast on does in the terminal'">
                {{ fastMenu!.fastMode ? 'Turn off' : 'Turn on' }}
              </button>
            </div>
            <p class="cc-hint" *ngIf="!fastMenu!.supported">
              Not supported for the configured provider/model — switch model first.
            </p>
          </ng-container>
          <ng-template #fastUnknown>
            <p class="cc-empty">
              State unknown.
              <button mat-button type="button" class="cc-link" [disabled]="busy()" (click)="dispatch('/fast')"
                title="Show fast-mode preference, as /fast status does in the terminal">Check fast mode</button>
            </p>
          </ng-template>
        </section>

        <!-- Ultracode (Claude Code route) -->
        <section class="cc-section">
          <header class="cc-section-header">
            <span class="cc-title">Ultracode</span>
          </header>
          <ng-container *ngIf="ultracodeMenu; else ultracodeUnknown">
            <div class="cc-fast-row">
              <span class="cc-fast-state" [class.on]="ultracodeMenu!.ultracode">
                {{ ultracodeMenu!.ultracode ? 'ON (requested)' : 'OFF' }}
              </span>
              <button mat-stroked-button type="button" class="cc-fast-toggle" *ngIf="ultracodeMenu!.supported"
                [disabled]="busy()" (click)="toggleUltracode(!ultracodeMenu!.ultracode)"
                [title]="ultracodeMenu!.ultracode ? 'Use the selected effort level, as /ultracode off does in the terminal' : 'Plan workflows at xhigh effort (more tokens), as /ultracode on does in the terminal'">
                {{ ultracodeMenu!.ultracode ? 'Turn off' : 'Turn on' }}
              </button>
            </div>
            <p class="cc-hint" *ngIf="ultracodeMenu!.supported && ultracodeMenu!.note">{{ ultracodeMenu!.note }}</p>
            <p class="cc-hint" *ngIf="!ultracodeMenu!.supported">
              Only available on the Claude Code route (Anthropic signed in through Claude Code) — switch model first.
            </p>
          </ng-container>
          <ng-template #ultracodeUnknown>
            <p class="cc-empty">
              State unknown.
              <button mat-button type="button" class="cc-link" [disabled]="busy()" (click)="dispatch('/ultracode')"
                title="Show ultracode preference, as /ultracode status does in the terminal">Check ultracode</button>
            </p>
          </ng-template>
        </section>

        <!-- Reminders -->
        <section class="cc-section">
          <header class="cc-section-header">
            <span class="cc-title">Reminders</span>
            <button mat-icon-button type="button" class="cc-refresh" [disabled]="busy()"
              (click)="dispatch('/reminder')" title="Reload session reminders from the CLI"
              aria-label="Reload session reminders"><mat-icon>refresh</mat-icon></button>
          </header>
          <div class="cc-options" *ngIf="reminderEntries().length">
            <div class="cc-option cc-static" *ngFor="let r of reminderEntries()">
              <span class="cc-option-label">{{ r.text }}</span>
            </div>
          </div>
          <p class="cc-empty" *ngIf="!reminderEntries().length">No session reminders configured.</p>
          <div class="cc-add-row">
            <input type="text" class="cc-input" [(ngModel)]="newReminder" [ngModelOptions]="{standalone: true}"
              placeholder="Add a session reminder…" (keyup.enter)="addReminder()" [disabled]="busy()">
            <button mat-flat-button color="primary" type="button" class="cc-add-btn" [disabled]="busy() || !newReminder.trim()"
              (click)="addReminder()" title="Add a reminder, as /reminder add does in the terminal">Add</button>
          </div>
          <div class="cc-footer-row">
            <button mat-button type="button" class="cc-link" [disabled]="busy() || !reminderEntries().length"
              (click)="dispatch('/reminder clear')"
              title="Clear configured reminders, as /reminder clear does in the terminal">Clear all session reminders</button>
            <span class="cc-sep">·</span>
            <button mat-button type="button" class="cc-link" [disabled]="busy()"
              (click)="dispatch('/reminder-global')" title="Reload project reminders from the CLI">
              <mat-icon>refresh</mat-icon>
              Project reminders
            </button>
          </div>
          <div class="cc-scope-list" *ngIf="globalReminderEntries().length">
            <span class="cc-meta">Project:</span>
            <div class="cc-option cc-static" *ngFor="let r of globalReminderEntries()">
              <span class="cc-option-label">{{ r.text }}</span>
            </div>
            <button mat-button type="button" class="cc-link" [disabled]="busy()"
              (click)="dispatch('/reminder-global clear')"
              title="Clear configured reminders, as /reminder-global clear does in the terminal">Clear project reminders</button>
          </div>
        </section>

        <!-- Scheduled loops -->
        <section class="cc-section">
          <header class="cc-section-header">
            <span class="cc-title">Scheduled Loops</span>
            <button mat-icon-button type="button" class="cc-refresh" [disabled]="busy()"
              (click)="dispatch('/loop')" title="Reload session loops from the CLI"
              aria-label="Reload session loops"><mat-icon>refresh</mat-icon></button>
          </header>
          <div class="cc-options" *ngIf="loopEntries().length; else noLoops">
            <div class="cc-option cc-static" *ngFor="let l of loopEntries()">
              <span class="cc-option-label">[{{ l.id }}] {{ l.schedule }}</span>
              <span class="cc-option-meta">{{ l.interval }} · {{ l.status.toLowerCase() }} · {{ l.fireCount }} fired</span>
              <span class="cc-loop-actions">
                <button mat-button type="button" class="cc-link" [disabled]="busy()"
                  (click)="dispatch('/loop pause ' + l.id)" *ngIf="l.status === 'ACTIVE'"
                  title="Pause this loop, as /loop pause does in the terminal">pause</button>
                <button mat-button type="button" class="cc-link" [disabled]="busy()"
                  (click)="dispatch('/loop resume ' + l.id)" *ngIf="l.status === 'PAUSED'"
                  title="Resume this loop, as /loop resume does in the terminal">resume</button>
                <button mat-button color="warn" type="button" class="cc-link cc-danger" [disabled]="busy()"
                  (click)="dispatch('/loop remove ' + l.id)"
                  title="Remove this loop, as /loop remove does in the terminal">remove</button>
                <button mat-button disabledInteractive type="button" class="cc-link" [disabled]="busy() || !liveSession()"
                  [title]="liveSession() ? 'Run this loop now, as /loop run does in the terminal' : 'Requires a live chat session'"
                  (click)="runLoopNow('/loop', l.id)">run now</button>
              </span>
            </div>
          </div>
          <ng-template #noLoops>
            <p class="cc-empty">No session loops scheduled.</p>
          </ng-template>
          <div class="cc-add-row">
            <input type="text" class="cc-input" [(ngModel)]="newLoopSchedule" [ngModelOptions]="{standalone: true}"
              placeholder="every (5m, 2h30m)" [disabled]="busy()">
            <input type="text" class="cc-input cc-grow" [(ngModel)]="newLoopPrompt" [ngModelOptions]="{standalone: true}"
              placeholder="Prompt to fire…" (keyup.enter)="addLoop()" [disabled]="busy()">
            <button mat-flat-button color="primary" type="button" class="cc-add-btn" [disabled]="busy() || !newLoopSchedule.trim() || !newLoopPrompt.trim()"
              (click)="addLoop()" title="Add a recurring prompt, as /loop add does in the terminal">Add</button>
          </div>
          <div class="cc-scope-list" *ngIf="globalLoopEntries().length">
            <span class="cc-meta">Project loops:</span>
            <div class="cc-option cc-static" *ngFor="let l of globalLoopEntries()">
              <span class="cc-option-label">[{{ l.id }}] {{ l.schedule }}</span>
              <span class="cc-option-meta">{{ l.interval }} · {{ l.status.toLowerCase() }}</span>
              <span class="cc-loop-actions">
                <button mat-button type="button" class="cc-link" [disabled]="busy()"
                  (click)="dispatch('/loop-global pause ' + l.id)" *ngIf="l.status === 'ACTIVE'"
                  title="Pause this loop, as /loop-global pause does in the terminal">pause</button>
                <button mat-button type="button" class="cc-link" [disabled]="busy()"
                  (click)="dispatch('/loop-global resume ' + l.id)" *ngIf="l.status === 'PAUSED'"
                  title="Resume this loop, as /loop-global resume does in the terminal">resume</button>
                <button mat-button color="warn" type="button" class="cc-link cc-danger" [disabled]="busy()"
                  (click)="dispatch('/loop-global remove ' + l.id)"
                  title="Remove this loop, as /loop-global remove does in the terminal">remove</button>
                <button mat-button disabledInteractive type="button" class="cc-link" [disabled]="busy() || !liveSession()"
                  [title]="liveSession() ? 'Run this loop now, as /loop-global run does in the terminal' : 'Requires a live chat session'"
                  (click)="runLoopNow('/loop-global', l.id)">run now</button>
              </span>
            </div>
          </div>
        </section>

        <!-- Message queue -->
        <section class="cc-section">
          <header class="cc-section-header">
            <span class="cc-title">Message Queue</span>
            <span class="cc-meta" *ngIf="queueEntries().length">{{ queueEntries().length }} waiting</span>
            <button mat-icon-button type="button" class="cc-refresh" [disabled]="busy()"
              (click)="dispatch('/queues')" title="Reload the queue from the CLI"
              aria-label="Reload the queue"><mat-icon>refresh</mat-icon></button>
          </header>
          <div class="cc-options" *ngIf="queueEntries().length; else noQueued">
            <div class="cc-option cc-static" *ngFor="let q of queueEntries(); let i = index">
              <span class="cc-option-label">{{ i + 1 }}. [{{ q.id }}] {{ q.content }}</span>
              <span class="cc-option-meta" *ngIf="q.status !== 'PENDING'">{{ q.status.toLowerCase() }}</span>
              <span class="cc-loop-actions">
                <button mat-button color="warn" type="button" class="cc-link cc-danger" [disabled]="busy()"
                  (click)="dispatch('/queue-remove ' + q.id)"
                  title="Remove this message from the queue, as /queue-remove does in the terminal">remove</button>
              </span>
            </div>
          </div>
          <ng-template #noQueued>
            <p class="cc-empty">Queue is empty.</p>
          </ng-template>
          <div class="cc-add-row">
            <input type="text" class="cc-input cc-grow" [(ngModel)]="newQueued" [ngModelOptions]="{standalone: true}"
              placeholder="Message to queue…" (keyup.enter)="addQueued()" [disabled]="busy()">
            <button mat-flat-button color="primary" type="button" class="cc-add-btn" [disabled]="busy() || !newQueued.trim()"
              (click)="addQueued()" title="Add a message to the queue, as /queue does in the terminal">Queue</button>
          </div>
          <div class="cc-footer-row">
            <button mat-button disabledInteractive type="button" class="cc-link" [disabled]="busy() || !liveSession() || !queueEntries().length"
              [title]="liveSession() ? 'Send the next queued message, as /queue-send does in the terminal' : 'Requires a live chat session'"
              (click)="sendQueued()">Send first now</button>
            <span class="cc-sep">·</span>
            <button mat-button disabledInteractive type="button" class="cc-link" [disabled]="busy() || !liveSession() || !queueEntries().length"
              [title]="liveSession() ? 'Send all queued messages, as /queue-send-all does in the terminal' : 'Requires a live chat session'"
              (click)="sendQueuedAll()">Send all now</button>
            <span class="cc-sep">·</span>
            <button mat-button color="warn" type="button" class="cc-link cc-danger" [disabled]="busy() || !queueEntries().length"
              (click)="dispatch('/queue-clear')" title="Clear the queue, as /queue-clear does in the terminal">Clear queue</button>
          </div>
          <p class="cc-hint" *ngIf="!liveSession() && queueEntries().length">
            Queued messages dispatch when the interactive CLI runs (or auto-dequeue at its next turn boundary).
          </p>
        </section>

        <!-- Continue auto-reply -->
        <section class="cc-section">
          <header class="cc-section-header">
            <span class="cc-title">Continue auto-reply</span>
            <span class="cc-meta" *ngIf="continueMenu">{{ continueMenu!.continueEnabled ? 'on' : 'off' }}</span>
          </header>
          <p class="cc-hint">Automatically answers yes-style questions at the end of a turn (project-global; the live CLI does the replying).</p>
          <div class="cc-fast-row" *ngIf="continueMenu">
            <button mat-stroked-button type="button" class="cc-fast-toggle" [disabled]="busy()"
              (click)="dispatch(continueMenu!.continueEnabled ? '/continue off' : '/continue on')"
              [title]="continueMenu!.continueEnabled ? 'Disable auto-reply, as /continue off does in the terminal' : 'Enable auto-reply, as /continue on does in the terminal'">
              {{ continueMenu!.continueEnabled ? 'Disable' : 'Enable' }}
            </button>
          </div>
          <div class="cc-keywords" *ngIf="continueEntries().length">
            <span class="cc-keyword" *ngFor="let k of continueEntries()">{{ k.keyword }}</span>
          </div>
          <p class="cc-hint" *ngIf="continueMenu && !continueEntries().length">Using the default trigger keywords.</p>
        </section>

        <!-- Judge (durable controls) -->
        <section class="cc-section">
          <header class="cc-section-header">
            <span class="cc-title">Judge</span>
            <span class="cc-meta" *ngIf="judgeMenu">global: {{ judgeGlobalEnabled() ? 'on' : 'off' }}</span>
          </header>
          <p class="cc-hint">The enforcer reviews each turn and can block corrections. These controls persist; live-only actions (override, approvals) need the interactive session.</p>
          <ng-container *ngIf="judgeMenu">
            <div class="cc-fast-row">
              <button mat-stroked-button type="button" class="cc-fast-toggle" [disabled]="busy()"
                (click)="dispatch(judgeGlobalEnabled() ? '/judge global off' : '/judge global on')"
                [title]="judgeGlobalEnabled() ? 'Disable the judge globally, as /judge global off does in the terminal' : 'Enable the judge globally, as /judge global on does in the terminal'">
                {{ judgeGlobalEnabled() ? 'Disable globally' : 'Enable globally' }}
              </button>
            </div>
            <p class="cc-hint" *ngIf="judgeGuidance()">
              Guidance: {{ judgeGuidance() }}
              <button mat-button type="button" class="cc-link" [disabled]="busy()" (click)="dispatch('/judge feedback clear')"
                title="Clear the durable guidance, as /judge feedback clear does in the terminal">clear</button>
            </p>
            <p class="cc-hint" *ngIf="!judgeGuidance()">
              No guidance set. Use the chat input:
              <code>/judge feedback &lt;text&gt;</code>
            </p>
          </ng-container>
        </section>

        <!-- New conversation -->
        <section class="cc-section">
          <header class="cc-section-header">
            <span class="cc-title">Conversation</span>
          </header>
          <p class="cc-hint">Start a fresh conversation. The previous transcript stays resumable; queued messages and reminders are kept.</p>
          <div class="cc-fast-row">
            <button mat-stroked-button type="button" class="cc-fast-toggle"
              [disabled]="busy()" (click)="clearConversation()"
              title="Start a new conversation, as /clear does in the terminal">
              New conversation
            </button>
          </div>
        </section>
        </ng-container>
        </ng-container>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <!-- Staging connection stays reachable without leaving the chat: new tab,
             stream untouched. -->
        <button mat-button (click)="openStagingSettings()" title="Opens in a new tab — the chat keeps running">
          <mat-icon>model_training</mat-icon>
          Model Staging…
        </button>
        <span style="flex: 1 1 auto"></span>
        <button mat-button (click)="close()">Close</button>
      </mat-dialog-actions>
    </div>
  `,
  styles: [`
    /* Compact Material buttons: the 48px touch target would stretch every row. */
    :host {
      --mdc-text-button-container-height: 32px;
      --mdc-outlined-button-container-height: 32px;
      --mdc-filled-button-container-height: 32px;
      --mat-text-button-touch-target-display: none;
      --mat-outlined-button-touch-target-display: none;
      --mat-filled-button-touch-target-display: none;
      --mat-icon-button-touch-target-display: none;
    }
    :host-context(body.dark-theme) { color-scheme: dark; }
    /* Material's M2 dark dialog surface (#424242) is lighter than the chat palette this
       content is styled for: borders, hints and option rows lose contrast on it. Paint
       the chat's elevated surface so the dialog reads like the chat panels. */
    :host-context(body.dark-theme) .command-config { background: var(--bg-surface-elevated); }
    .command-config { min-width: 420px; max-width: 560px; }
    mat-dialog-content { display: flex; flex-direction: column; gap: 16px; }

    .cc-loading { display: flex; align-items: center; gap: 10px; padding: 24px 4px;
      color: var(--text-tertiary, #888); font-size: 0.95em; }
    .cc-loading-spinner { width: 16px; height: 16px; border-radius: 50%;
      border: 2px solid var(--border-color, #ccc); border-top-color: var(--text-secondary, #555);
      animation: cc-spin 0.8s linear infinite; }
    @keyframes cc-spin { to { transform: rotate(360deg); } }
    .cc-loading-inline { padding: 4px 2px 10px; font-size: 0.9em; color: var(--text-secondary, #555); }
    /* Hidden, not removed, when idle so the content never jumps as loads start and finish. */
    .cc-progress { flex: none; }
    .cc-progress-idle { visibility: hidden; }
    .cc-error { margin: 0; color: var(--status-error-text, #c62828); font-size: 0.9em; }

    .cc-hint { margin: 0; color: var(--text-tertiary, #888); font-size: 0.85em; }

    .cc-section { border: 1px solid var(--border-color, #ddd); border-radius: 8px; padding: 10px 12px; }
    .cc-section-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .cc-title { font-weight: 600; }
    .cc-meta { color: var(--text-tertiary, #888); font-size: 0.8em; }

    .cc-vendors { display:flex; flex-wrap:wrap; gap:6px; margin-bottom:10px; }
    .cc-vendor-chip { border:1px solid var(--border-color, #ccc); border-radius:14px;
      padding:6px 12px; background:transparent; color:var(--text-secondary, #555); cursor:pointer; }
    .cc-vendor-chip.current { border-color:var(--status-success-text, #2e7d32); }
    .cc-vendor-chip.selected { background:var(--color-primary-light, rgba(25,118,210,.1)); }
    .cc-vendor-chip:disabled { opacity:.5; cursor:default; }
    .cc-keywords { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
    .cc-keyword { border: 1px solid var(--border-color, #ccc); border-radius: 12px;
      padding: 2px 10px; font-size: 0.78em; color: var(--text-secondary, #555); }
    .cc-refresh.mat-mdc-icon-button.mat-mdc-button-base {
      --mdc-icon-button-state-layer-size: 28px; --mdc-icon-button-icon-size: 18px;
      padding: 5px; margin-left: auto; flex: none;
    }
    .cc-refresh .mat-icon { width: 18px; height: 18px; font-size: 18px; line-height: 18px; }

    .cc-options { display: flex; flex-direction: column; gap: 4px; max-height: 220px; overflow-y: auto; }
    .cc-option {
      display: flex; align-items: baseline; gap: 8px; text-align: left;
      padding: 7px 10px; border: 1px solid var(--border-color, #ddd); border-radius: 6px;
      background: var(--bg-body, #fafafa); color: inherit; font: inherit; cursor: pointer;
    }
    .cc-option:hover:not(:disabled) { border-color: var(--color-primary, #1976d2); background: var(--status-info-bg, #eef); }
    .cc-option.current { border-color: var(--color-primary, #1976d2); background: var(--status-info-bg, #eef); }
    .cc-option:disabled { opacity: 0.6; cursor: not-allowed; }
    .cc-option-label { font-weight: 500; }
    .cc-option-meta { margin-left: auto; color: var(--text-tertiary, #888); font-size: 0.85em; }
    .cc-current { color: var(--color-primary, #1976d2); font-size: 0.75em; font-weight: 600; }

    .cc-empty { margin: 0; color: var(--text-tertiary, #888); font-size: 0.9em; }
    .cc-footer-row { display: flex; justify-content: flex-end; align-items: center; margin-top: 6px; }
    .cc-scope-list > .cc-link { align-self: flex-end; }

    .cc-fast-row { display: flex; align-items: center; gap: 10px; }
    .cc-fast-state { font-weight: 600; color: var(--text-secondary, #666); }
    .cc-fast-state.on { color: var(--status-success-text, #2e7d32); }
    .cc-fast-toggle { flex: none; }

    .cc-static { cursor: default; }
    .cc-add-row { display: flex; align-items: center; gap: 6px; margin-top: 8px; }
    .cc-input {
      flex: 1 1 auto; min-width: 0; padding: 6px 10px;
      border: 1px solid var(--border-color, #ddd); border-radius: 6px;
      font: inherit; background: var(--bg-body, #fff); color: var(--text-primary, #212529);
    }
    .cc-input:focus { outline: none; border-color: var(--color-primary, #1976d2); }
    .cc-input:disabled { opacity: 0.6; }
    .cc-grow { flex: 2 1 auto; }
    .cc-add-btn { flex: none; }
    .cc-sep { color: var(--text-tertiary, #888); }
    .cc-scope-list { display: flex; flex-direction: column; gap: 4px; margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--border-color, #ddd); }
    .cc-scope-list .cc-meta { font-size: 0.8em; color: var(--text-tertiary, #888); }
    .cc-loop-actions { margin-left: auto; display: flex; align-items: center; gap: 4px; flex: none; }
    .cc-loop-actions .mat-mdc-button-base {
      --mdc-text-button-container-height: 24px; --mat-text-button-horizontal-padding: 6px;
      --mdc-text-button-label-text-size: 12px; min-width: 0;
    }
  `]
})
export class CommandConfigDialogComponent implements OnInit, OnDestroy {
  modelMenu: CommandEventData | null;
  /** The CLI wizard catalog for this chat and the route being edited (seeded from the chat's own configuration). */
  routeCatalog?: ChatSetupCatalog;
  route: ChatSetupSelection = {};
  routeLoading = false;
  routeSaving = false;
  routeError: string | null = null;
  routeStatus: string | null = null;
  roleMenu: CommandEventData | null;
  fastMenu: CommandEventData | null;
  ultracodeMenu: CommandEventData | null;
  reminders: CommandEventData | null;
  remindersGlobal: CommandEventData | null;
  loops: CommandEventData | null;
  loopsGlobal: CommandEventData | null;
  queue: CommandEventData | null;
  continueMenu: CommandEventData | null;
  judgeMenu: CommandEventData | null;
  newReminder = '';
  newLoopSchedule = '';
  newLoopPrompt = '';
  newQueued = '';
  /** True until the quiet snapshot resolves (or fails); the dialog shows only "Loading…". */
  loading = true;
  /** A later snapshot reload (after Apply) is in flight; the sections stay shown meanwhile. */
  refreshing = false;
  /** Human-readable error when the quiet snapshot could not be fetched. */
  loadError: string | null = null;
  private readonly destroyed = new Subject<void>();

  constructor(
    public dialogRef: MatDialogRef<CommandConfigDialogComponent, void>,
    @Inject(MAT_DIALOG_DATA) public data: CommandConfigDialogData,
    private agentChat: LocalAgentChatService
  ) {
    this.modelMenu = data.modelMenu;
    this.roleMenu = data.roleMenu;
    this.fastMenu = data.fastMenu;
    this.ultracodeMenu = data.ultracodeMenu ?? null;
    this.reminders = data.reminders;
    this.remindersGlobal = data.remindersGlobal;
    this.loops = data.loops;
    this.loopsGlobal = data.loopsGlobal;
    this.queue = data.queue;
    this.continueMenu = data.continueMenu ?? null;
    this.judgeMenu = data.judgeMenu ?? null;
    // CLI command outcomes stream back here regardless of who dispatched them;
    // the dialog's panels update in place as each dispatch resolves.
    agentChat.getCommandOutcomes()
      .pipe(takeUntil(this.destroyed))
      .subscribe((outcome: CommandOutcome) => this.applyOutcome(outcome));
  }

  ngOnInit(): void {
    // Quiet fetch: one background HTTP call resolves every section from the
    // CLI headlessly. No chat messages, no transcript entries. Live command
    // outcomes (from explicit user actions elsewhere) still update panels.
    this.loadSnapshot();
    this.loadRoute();
  }

  private loadSnapshot(): void {
    this.refreshing = !this.loading;
    this.agentChat.getSessionConfig(this.data.sessionId, this.data.workingDirectory)
      .pipe(takeUntil(this.destroyed))
      .subscribe({
        next: (snapshot: SessionConfigSnapshot) => {
          this.loading = false;
          this.refreshing = false;
          if (snapshot?.available === false) {
            this.loadError = snapshot.status || 'Session configuration is unavailable.';
            return;
          }
          this.modelMenu = snapshot?.model ?? this.modelMenu;
          this.roleMenu = snapshot?.role ?? this.roleMenu;
          this.fastMenu = snapshot?.fast ?? this.fastMenu;
          this.ultracodeMenu = snapshot?.ultracode ?? this.ultracodeMenu;
          this.reminders = snapshot?.reminders ?? this.reminders;
          this.remindersGlobal = snapshot?.remindersGlobal ?? this.remindersGlobal;
          this.loops = snapshot?.loops ?? this.loops;
          this.loopsGlobal = snapshot?.loopsGlobal ?? this.loopsGlobal;
          this.queue = snapshot?.queue ?? this.queue;
          this.continueMenu = snapshot?.continue ?? this.continueMenu;
          this.judgeMenu = snapshot?.judge ?? this.judgeMenu;
        },
        error: (error: unknown) => {
          this.loading = false;
          this.refreshing = false;
          this.loadError = (error as { message?: string })?.message
            || 'Could not load session configuration.';
        }
      });
  }

  ngOnDestroy(): void {
    this.destroyed.next();
    this.destroyed.complete();
  }

  busy(): boolean {
    return this.data.busy();
  }

  /** Anything the dialog is waiting on: the snapshot, route discovery, an Apply, or a dispatched command. */
  working(): boolean {
    return this.loading || this.refreshing || this.routeLoading || this.routeSaving || this.busy();
  }

  /** What the route section is waiting on, in words. */
  routeActivity(): string {
    if (this.routeSaving) return 'Applying the new route…';
    return this.routeCatalog ? 'Updating vendors, accounts and models for your selection…'
      : 'Discovering vendors, accounts and models from the CLI…';
  }

  liveSession(): boolean {
    return this.data.liveSession();
  }

  routeNative(): boolean {
    const mode = this.route.mode;
    return mode === 'passthrough' || (mode === 'workflow' && this.route.leadMode === 'passthrough');
  }
  /** The CLI wizard's modes; a workflow team is chosen when a chat is created. */
  routeModes(): Choice[] {
    return (this.routeCatalog?.modes || []).filter(m => m.id !== 'workflow');
  }
  /** Keeps the selected mode's option across rediscoveries, which answer with a new list. */
  modeId(_index: number, mode: Choice): string { return mode.id; }
  canApplyRoute(): boolean {
    return !this.busy() && !this.routeLoading && !this.routeSaving && !!this.routeCatalog
      && (this.routeNative() ? !!this.route.passthroughAgent : !!this.route.model?.trim());
  }
  routeChange(field: string): void {
    clearRouteDependents(this.route, field);
    this.loadRoute();
  }
  /** The chat's route fields only: the CLI keeps its workflow team and the dialog's own fast/ultracode toggles. */
  private routeSelection(): ChatSetupSelection {
    const { apiKey, workflow, leadMode, profile, saveProfile, replaceProfile, judges, fastMode, ultracode, ...selection }
      = this.route;
    return selection;
  }
  /** Discovery never receives a typed API key; only Apply sends it. */
  loadRoute(): void {
    if (!this.data.sessionId || this.routeSaving) return;
    const apiKey = this.route.apiKey;
    const requested = this.routeSelection();
    this.routeLoading = true;
    this.routeError = null;
    this.agentChat.setupSession<ChatSetupCatalog>(this.data.sessionId, this.data.workingDirectory, 'catalog', requested)
      .pipe(takeUntil(this.destroyed)).subscribe({
        next: catalog => {
          this.routeLoading = false;
          if (!catalog?.available) { this.routeError = catalog?.status || 'The CLI setup wizard is unavailable.'; return; }
          this.routeCatalog = catalog;
          this.route = mergeRouteCatalog(catalog, requested, { apiKey });
        },
        error: (error: { error?: { message?: string } }) => {
          this.routeLoading = false;
          this.routeError = error?.error?.message || 'Could not discover setup options for this chat.';
        }
      });
  }
  applyRoute(): void {
    if (!this.canApplyRoute() || !this.data.sessionId) return;
    this.routeSaving = true;
    this.routeError = null;
    this.routeStatus = null;
    const selection = { ...this.routeSelection(), ...(this.route.apiKey ? { apiKey: this.route.apiKey } : {}) };
    this.agentChat.setupSession<RouteUpdate>(this.data.sessionId, this.data.workingDirectory, 'update', selection)
      .pipe(takeUntil(this.destroyed)).subscribe({
        next: result => {
          this.routeSaving = false;
          delete this.route.apiKey;
          if (!result?.ok) { this.routeError = result?.status || 'The chat route was not changed.'; return; }
          this.routeStatus = `Applied: ${[result.framework, result.model].filter(Boolean).join(' · ')}. Takes effect on the next turn.`;
          this.loadRoute();
          this.loadSnapshot();
          this.data.routeUpdated?.();
        },
        error: (error: { error?: { message?: string } }) => {
          this.routeSaving = false;
          this.routeError = error?.error?.message || 'The chat route was not changed.';
        }
      });
  }

  roles(): CommandRoleEntry[] {
    return this.roleMenu?.roles ?? [];
  }

  reminderEntries(): { text: string }[] {
    return this.reminders?.reminders ?? [];
  }

  globalReminderEntries(): { text: string }[] {
    return this.remindersGlobal?.reminders ?? [];
  }

  loopEntries(): { id: string; schedule: string; prompt: string; status: string; interval: string; fireCount: number }[] {
    return this.loops?.loops ?? [];
  }

  globalLoopEntries(): { id: string; schedule: string; prompt: string; status: string; interval: string; fireCount: number }[] {
    return this.loopsGlobal?.loops ?? [];
  }

  queueEntries(): { id: string; content: string; status: string; createdAt: string }[] {
    return this.queue?.queued ?? [];
  }

  continueEntries(): { keyword: string }[] {
    return this.continueMenu?.keywords ?? [];
  }

  /** Judge global master switch (durable harness config). */
  judgeGlobalEnabled(): boolean {
    return this.judgeMenu?.globalEnabled === true;
  }

  /** Durable judge guidance for this session (may be empty). */
  judgeGuidance(): string {
    return this.judgeMenu?.guidance ?? '';
  }

  roleLabel(role: CommandRoleEntry): string {
    return role.display || role.name;
  }

  dispatch(commandLine: string): void {
    if (!this.busy()) this.data.dispatch(commandLine);
  }

  selectRole(roleName: string): void {
    if (!this.busy()) this.data.selectRole(roleName);
  }

  toggleFastMode(enabled: boolean): void {
    if (!this.busy()) this.data.toggleFastMode(enabled);
  }

  toggleUltracode(enabled: boolean): void {
    if (!this.busy()) this.data.toggleUltracode(enabled);
  }

  clearConversation(): void {
    if (this.busy()) return;
    this.data.dispatch('/clear');
  }

  /** Staging connection page in a new tab — the chat stream is never torn down. */
  openStagingSettings(): void {
    const base = window.location.origin + window.location.pathname;
    window.open(`${base}#/settings`, '_blank', 'noopener');
  }

  addReminder(): void {
    const text = this.newReminder.trim();
    if (!text || this.busy()) return;
    this.data.dispatch('/reminder add ' + text);
    this.newReminder = '';
  }

  addLoop(): void {
    const schedule = this.newLoopSchedule.trim();
    const prompt = this.newLoopPrompt.trim();
    if (!schedule || !prompt || this.busy()) return;
    this.data.dispatch('/loop add ' + schedule + ' ' + prompt);
    this.newLoopSchedule = '';
    this.newLoopPrompt = '';
  }

  addQueued(): void {
    const content = this.newQueued.trim();
    if (!content || this.busy()) return;
    this.data.dispatch('/queue ' + content);
    this.newQueued = '';
  }

  /**
   * The run-now and send-now buttons are disabledInteractive so their "Requires
   * a live chat session" title still shows on hover. Clicks therefore reach these
   * handlers while the button looks disabled, so each one re-checks its condition.
   */
  runLoopNow(command: string, id: string): void {
    if (this.liveSession()) this.dispatch(command + ' run ' + id);
  }

  sendQueued(): void {
    if (!this.data.liveSession() || this.busy() || !this.queueEntries().length) return;
    this.data.dispatch('/queue-send');
  }

  sendQueuedAll(): void {
    if (!this.data.liveSession() || this.busy() || !this.queueEntries().length) return;
    this.data.dispatch('/queue-send-all');
  }

  close(): void {
    this.dialogRef.close();
  }

  private applyOutcome(outcome: CommandOutcome): void {
    const data = outcome?.data;
    if (!data?.menu) return;
    if (data.menu === 'model') this.modelMenu = data;
    // A /model or /thinking from the selector above the chat re-pinned the route; reseed the wizard from it.
    if (outcome.ok && (data.state?.model || data.state?.thinking !== undefined)) this.loadRoute();
    if (data.menu === 'role') this.roleMenu = data;
    if (data.menu === 'fast') this.fastMenu = data;
    if (data.menu === 'ultracode') this.ultracodeMenu = data;
    if (data.menu === 'reminders') {
      if (data.scope === 'project') this.remindersGlobal = data;
      else this.reminders = data;
    }
    if (data.menu === 'loops') {
      if (data.scope === 'project') this.loopsGlobal = data;
      else this.loops = data;
    }
    if (data.menu === 'queue') this.queue = data;
    if (data.menu === 'continue') this.continueMenu = data;
    if (data.menu === 'judge') this.judgeMenu = data;
  }
}
