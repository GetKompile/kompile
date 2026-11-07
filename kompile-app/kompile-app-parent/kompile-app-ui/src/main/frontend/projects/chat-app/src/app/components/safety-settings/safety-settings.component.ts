import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';

interface PermissionRow { key: string; defaultLevel: string; effectiveLevel: string; overrideLevel?: string | null; roleLevel?: string | null; }
interface HookCommand { command: string; timeout: number; }
interface HookRow { id: string; scope: string; event: string; matcher: string; enabled: boolean; hooks: HookCommand[]; }
interface SettingsMenu { menu?: string; canManage?: boolean; rows?: PermissionRow[]; hooks?: HookRow[]; events?: string[]; error?: string; notes?: string; }
interface HookDraft { action: string; id?: string; scope: string; event: string; matcher: string; hooks: HookCommand[]; }
export interface SafetySettingsOutcome { command: string; ok: boolean; text: string; data: unknown; }
const LEVELS = ['allow', 'ask', 'deny'];
function menu(value: unknown, name: string): SettingsMenu | null {
  if (!value || typeof value !== 'object') return null;
  const data = value as SettingsMenu;
  return data.menu === name ? data : null;
}

@Component({
  selector: 'app-safety-settings', standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule],
  template: `
    <section data-testid="permissions-settings"><h3>Tool permissions for this web chat</h3>
      <p>{{ permissions?.notes || 'Overrides apply to following web turns. Role rules always take precedence.' }}</p>
      <p *ngIf="permissions?.error" role="alert">{{ permissions?.error }}</p>
      <button mat-button type="button" [disabled]="locked()" (click)="refresh('permissions')">Refresh permissions</button>
      <button mat-button type="button" data-testid="permissions-edit" [disabled]="locked() || !permissions?.canManage" (click)="editPermissions()">Edit overrides</button>
      <table *ngIf="permissions?.rows?.length"><thead><tr><th>Permission</th><th>Default</th><th>Role</th><th>Effective</th></tr></thead>
        <tbody><tr *ngFor="let row of permissions?.rows"><td>{{ row.key }}</td><td>{{ row.defaultLevel }}</td>
          <td>{{ row.roleLevel || '—' }}</td><td>{{ row.effectiveLevel }}</td></tr></tbody></table>
      <fieldset *ngIf="permissionDraft" [disabled]="disabled || !!pending"><legend>Draft chat overrides</legend>
        <label *ngFor="let row of permissions?.rows">{{ row.key }}
          <select [attr.data-permission]="row.key" [(ngModel)]="permissionDraft[row.key]" [disabled]="!!row.roleLevel">
            <option value="default">Default</option><option *ngFor="let level of levels" [value]="level">{{ level }}</option>
          </select><span *ngIf="row.roleLevel">Fixed by current role: {{ row.roleLevel }}</span>
        </label>
        <button mat-button type="button" (click)="resetPermissionDraft()">Restore default overrides</button>
        <label><input type="checkbox" data-testid="permissions-confirm" [(ngModel)]="confirmed"> I authorize these permission changes for this chat.</label>
        <button mat-button type="button" data-testid="permissions-save" [disabled]="!confirmed || disabled || !!pending" (click)="savePermissions()">Save permissions</button>
        <button mat-button type="button" (click)="cancel()">Cancel</button>
      </fieldset>
    </section>
    <section data-testid="hooks-settings"><h3>Automatic tool hooks</h3>
      <p>{{ hooks?.notes || 'Hooks run trusted shell commands on future tool calls. Global hooks affect other projects. Saving never runs a command.' }}</p>
      <p *ngIf="hooks?.error" role="alert">{{ hooks?.error }}</p>
      <button mat-button type="button" [disabled]="locked()" (click)="refresh('hooks')">Refresh hooks</button>
      <button mat-button type="button" data-testid="hook-add" [disabled]="locked() || !hooks?.canManage" (click)="editHook()">Add hook</button>
      <p *ngIf="hooks && !hooks.error && !hooks.hooks?.length">No hooks configured.</p>
      <article *ngFor="let hook of hooks?.hooks" [attr.data-hook-id]="hook.id">
        <strong>{{ hook.scope }} · {{ hook.event }} · {{ hook.enabled ? 'enabled' : 'disabled' }}</strong><code>{{ hook.matcher }}</code>
        <pre *ngFor="let command of hook.hooks">{{ command.command }} (timeout {{ command.timeout }}s)</pre>
        <button mat-button type="button" [disabled]="locked() || !hooks?.canManage" (click)="editHook(hook, 'edit')">Edit</button>
        <button mat-button type="button" [disabled]="locked() || !hooks?.canManage" (click)="editHook(hook, hook.enabled ? 'disable' : 'enable')">{{ hook.enabled ? 'Disable' : 'Enable' }}</button>
        <button mat-button type="button" [disabled]="locked() || !hooks?.canManage" (click)="editHook(hook, 'delete')">Delete</button>
      </article>
      <fieldset *ngIf="hookDraft as draft" [disabled]="disabled || !!pending"><legend>{{ draft.action }} hook (draft)</legend>
        <label>Scope<select data-testid="hook-scope" [(ngModel)]="draft.scope" (ngModelChange)="confirmed = false" [disabled]="draft.action !== 'add'"><option value="project">This project</option><option value="global">Global — all projects</option></select></label>
        <p *ngIf="draft.scope === 'global'" role="alert">This changes hooks used by other projects.</p>
        <ng-container *ngIf="draft.action === 'add' || draft.action === 'edit'">
          <label>Event<select data-testid="hook-event" [(ngModel)]="draft.event" (ngModelChange)="confirmed = false" [disabled]="draft.action !== 'add'"><option *ngFor="let event of hooks?.events" [value]="event">{{ event }}</option></select></label>
          <label>Tool matcher (regular expression)<input data-testid="hook-matcher" [(ngModel)]="draft.matcher" (ngModelChange)="confirmed = false"></label>
          <p>Commands run in the order shown. Saving replaces the entire command list; it does not run them.</p>
          <div *ngFor="let command of draft.hooks; let i = index" data-testid="hook-command-row">
            <label>Trusted shell command {{ i + 1 }}<textarea data-testid="hook-command" rows="3" maxlength="8000" [(ngModel)]="command.command" (ngModelChange)="confirmed = false"></textarea></label>
            <label>Timeout (seconds)<input type="number" data-testid="hook-timeout" min="1" max="600" [(ngModel)]="command.timeout" (ngModelChange)="confirmed = false"></label>
            <button mat-button type="button" data-testid="hook-command-up" [disabled]="i === 0" (click)="moveHookCommand(i, -1)" [attr.aria-label]="'Move command ' + (i + 1) + ' up'">Up</button>
            <button mat-button type="button" data-testid="hook-command-down" [disabled]="i === draft.hooks.length - 1" (click)="moveHookCommand(i, 1)" [attr.aria-label]="'Move command ' + (i + 1) + ' down'">Down</button>
            <button mat-button type="button" data-testid="hook-command-remove" [disabled]="draft.hooks.length === 1" (click)="removeHookCommand(i)" [attr.aria-label]="'Remove command ' + (i + 1)">Remove</button>
          </div>
          <button mat-button type="button" data-testid="hook-command-add" (click)="addHookCommand()">Add command</button>
        </ng-container>
        <label><input type="checkbox" data-testid="hook-confirm" [(ngModel)]="confirmed"> I authorize this change to automatic shell execution.</label>
        <button mat-button type="button" data-testid="hook-save" [disabled]="!canSaveHook()" (click)="saveHook()">Confirm {{ draft.action }}</button>
        <button mat-button type="button" (click)="cancel()">Cancel</button>
      </fieldset>
    </section>
    <p *ngIf="pending" role="status">Saving or loading {{ pending }}…</p>
    <p *ngIf="error" role="alert" data-testid="safety-settings-error">{{ error }}</p>
    <p *ngIf="status" role="status">{{ status }}</p>`,
  styles: [`section{border-top:1px solid var(--border-color,#ccc);padding:12px 0;} table{width:100%;text-align:left;} td,th{padding:4px;}
    label{display:block;margin:8px 0;} select,input:not([type=checkbox]),textarea{display:block;box-sizing:border-box;width:100%;font:inherit;color:inherit;background:var(--bg-body,#fff);}
    pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:12rem;overflow:auto;} article{padding:8px 0;} code{display:block;} fieldset{min-width:0;}`]
})
export class SafetySettingsComponent implements OnChanges {
  @Input() permissionMenu: unknown;
  @Input() hooksMenu: unknown;
  @Input() outcome: SafetySettingsOutcome | null = null;
  @Input() disabled = false;
  @Output() dispatch = new EventEmitter<string>();
  permissions: SettingsMenu | null = null;
  hooks: SettingsMenu | null = null;
  readonly levels = LEVELS;
  permissionDraft: Record<string, string> | null = null;
  hookDraft: HookDraft | null = null;
  confirmed = false;
  pending: 'hooks' | 'permissions' | null = null;
  private saving = false;
  error = '';
  status = '';
  ngOnChanges(changes: SimpleChanges): void {
    if (changes['permissionMenu']) this.permissions = menu(this.permissionMenu, 'permissions');
    if (changes['hooksMenu']) this.hooks = menu(this.hooksMenu, 'hooks');
    if (changes['outcome'] && this.outcome && ['hooks', 'permissions'].includes(this.outcome.command)) {
      const completed = this.pending === this.outcome.command;
      const saved = completed && this.saving;
      if (completed) { this.pending = null; this.saving = false; }
      if (!this.outcome.ok) { this.error = this.outcome.text; this.confirmed = false; return; }
      if (this.outcome.command === 'hooks') this.hooks = menu(this.outcome.data, 'hooks') ?? this.hooks;
      else this.permissions = menu(this.outcome.data, 'permissions') ?? this.permissions;
      if (saved) this.cancel();
      this.status = this.outcome.text;
    }
  }
  locked(): boolean { return this.disabled || !!this.pending || !!this.hookDraft || !!this.permissionDraft; }
  refresh(kind: 'hooks' | 'permissions'): void {
    if (this.locked()) return;
    this.pending = kind; this.saving = false; this.error = ''; this.dispatch.emit('/' + kind + ' list');
  }
  cancel(): void {
    if (this.pending) return;
    this.permissionDraft = null; this.hookDraft = null; this.confirmed = false; this.error = ''; this.status = '';
  }
  editPermissions(): void {
    if (this.locked() || !this.permissions?.canManage) return;
    this.permissionDraft = Object.fromEntries((this.permissions.rows || []).map(row => [row.key, row.overrideLevel || 'default']));
    this.confirmed = false; this.error = ''; this.status = '';
  }
  resetPermissionDraft(): void {
    if (!this.permissionDraft || this.disabled || this.pending) return;
    for (const key of Object.keys(this.permissionDraft)) this.permissionDraft[key] = 'default';
    this.confirmed = false;
  }
  savePermissions(): void {
    if (this.disabled || this.pending || !this.confirmed || !this.permissions?.canManage || !this.permissionDraft) return;
    const overrides: Record<string, string> = {};
    for (const [key, value] of Object.entries(this.permissionDraft)) {
      if (value === 'default') continue;
      if (!LEVELS.includes(value)) { this.error = 'Invalid permission level'; return; }
      overrides[key] = value;
    }
    this.pending = 'permissions'; this.saving = true; this.dispatch.emit('/permissions save ' + JSON.stringify({ overrides, confirmed: true }));
  }
  editHook(row?: HookRow, action = 'add'): void {
    if (this.locked() || !this.hooks?.canManage) return;
    this.hookDraft = { action, id: row?.id, scope: row?.scope || 'project', event: row?.event || this.hooks.events?.[0] || '',
      matcher: row?.matcher || '.*', hooks: row ? row.hooks.map(command => ({ ...command })) : [{ command: '', timeout: 30 }] };
    this.confirmed = false; this.error = ''; this.status = '';
  }
  private canEditHookCommands(): boolean {
    return !this.disabled && !this.pending && !!this.hooks?.canManage && !!this.hookDraft
      && ['add', 'edit'].includes(this.hookDraft.action);
  }
  addHookCommand(): void {
    if (!this.canEditHookCommands()) return;
    this.hookDraft!.hooks.push({ command: '', timeout: 30 }); this.confirmed = false;
  }
  removeHookCommand(index: number): void {
    if (!this.canEditHookCommands() || !Number.isInteger(index) || index < 0 || index >= this.hookDraft!.hooks.length || this.hookDraft!.hooks.length === 1) return;
    this.hookDraft!.hooks.splice(index, 1); this.confirmed = false;
  }
  moveHookCommand(index: number, direction: number): void {
    if (!this.canEditHookCommands() || !Number.isInteger(index) || ![-1, 1].includes(direction)) return;
    const commands = this.hookDraft!.hooks, next = index + direction;
    if (index < 0 || index >= commands.length || next < 0 || next >= commands.length) return;
    [commands[index], commands[next]] = [commands[next], commands[index]]; this.confirmed = false;
  }
  canSaveHook(): boolean {
    const draft = this.hookDraft;
    if (this.disabled || this.pending || !this.confirmed || !this.hooks?.canManage || !draft) return false;
    return ['enable', 'disable', 'delete'].includes(draft.action) || (['add', 'edit'].includes(draft.action) && !!draft.matcher.trim()
      && !!this.hooks.events?.includes(draft.event) && draft.hooks.length > 0 && draft.hooks.every(command => !!command.command.trim()
        && Number.isInteger(command.timeout) && command.timeout >= 1 && command.timeout <= 600));
  }
  saveHook(): void {
    if (!this.canSaveHook() || !this.hookDraft) return;
    const draft = this.hookDraft;
    const request: Record<string, unknown> = { action: draft.action, scope: draft.scope, confirmed: true };
    if (draft.id) request['id'] = draft.id;
    if (draft.action === 'add' || draft.action === 'edit') Object.assign(request, { event: draft.event,
      matcher: draft.matcher, hooks: draft.hooks.map(command => ({ type: 'command', command: command.command, timeout: command.timeout })) });
    this.pending = 'hooks'; this.saving = true; this.dispatch.emit('/hooks apply ' + JSON.stringify(request));
  }
}
