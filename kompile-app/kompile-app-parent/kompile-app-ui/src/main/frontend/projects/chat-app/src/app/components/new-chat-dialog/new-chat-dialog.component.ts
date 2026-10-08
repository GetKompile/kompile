import { Component, Inject, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { CliTerminalConsoleComponent } from '../cli-terminal-console/cli-terminal-console.component';
import {
  ChatRouteFieldsComponent, ChatSetupCatalog, ChatSetupSelection, Profile, clearRouteDependents, mergeRouteCatalog
} from '../chat-route-fields/chat-route-fields.component';

export type { ChatSetupCatalog, ChatSetupSelection } from '../chat-route-fields/chat-route-fields.component';
export interface NewChatDialogData { url: string; workingDirectory: string; name: string; }
export interface NewChatDialogResult { chat?: { id: string; name: string; framework?: string; model?: string }; refresh?: boolean; }

/** Setup discovery/validation is owned by the CLI; no vendor or model catalog is duplicated here. */
@Component({
  selector: 'app-new-chat-dialog', standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, CliTerminalConsoleComponent, ChatRouteFieldsComponent],
  template: `
    <h2 mat-dialog-title>New chat</h2>
    <mat-dialog-content>
      <p class="directory">{{ data.workingDirectory }}</p>
      <p>Existing chats keep their own configuration and continue running.</p>
      <div class="tabs">
        <button mat-stroked-button type="button" (click)="fullWizard = false" [attr.aria-pressed]="!fullWizard">Browser setup</button>
        <button mat-stroked-button type="button" (click)="openWizard()" [disabled]="saving" [attr.aria-pressed]="fullWizard">Complete CLI wizard</button>
      </div>
      <p *ngIf="error" role="alert">{{ error }}</p>
      <p *ngIf="loading" role="status">Discovering setup options…</p>
      <section [hidden]="!fullWizard" *ngIf="wizardMounted">
        <p>The original CLI wizard runs here in this folder. Use <strong>Launch kompile chat</strong> below.
          It includes new sign-in and credential management, installed/downloaded local models (Hugging Face repository, revision or local path),
          workflow templates and team creation (roles, permissions, lead, routing, delegation, approval gates and participant models),
          saved profiles, judge creation/replacement/deletion and fallback defaults, resume and bulk resume, and terminal/browser destination.
          Browser-form selections are not submitted to this separate wizard.</p>
        <app-cli-terminal-console [workingDirectory]="data.workingDirectory" [visible]="fullWizard"></app-cli-terminal-console>
        <button mat-button type="button" (click)="reload()" [disabled]="loading || saving">Reload saved profiles and teams</button>
      </section>
      <form [hidden]="fullWizard" (ngSubmit)="create()">
        <label>Chat title<input name="title" [(ngModel)]="name" required maxlength="256" [disabled]="saving"></label>
        <fieldset [disabled]="loading || saving || !catalog">
          <legend>1 · Chat mode and saved configuration</legend>
          <label>Mode<select name="mode" [(ngModel)]="selection.mode" (ngModelChange)="change('mode')">
            <option *ngFor="let m of catalog?.modes" [value]="m.id">{{m.label}}</option>
          </select></label>
          <p *ngIf="resumeMode">Open an existing chat from the workspace folders, or use the complete wizard for the CLI resume picker and bulk resume time windows.</p>
          <button *ngIf="resumeMode" mat-button type="button" (click)="openWizard()">Open resume wizard</button>
          <label *ngIf="!resumeMode">Saved project profile<select name="profile" [(ngModel)]="selection.profile" (ngModelChange)="change('profile')">
            <option value="">Fresh setup / folder defaults</option><option *ngFor="let p of catalog?.profiles" [value]="p.name">{{p.label}} · {{p.mode}}</option>
          </select></label>
          <label *ngIf="selection.mode === 'workflow'">Lead mode<select name="leadMode" [(ngModel)]="selection.leadMode" (ngModelChange)="change('leadMode')">
            <option *ngFor="let l of catalog?.leadModes" [value]="l.id">{{l.label}}</option>
          </select></label>
        </fieldset>
        <app-chat-route-fields *ngIf="!resumeMode" [catalog]="catalog" [selection]="selection" [disabled]="loading || saving"
          [nativeMode]="nativeMode" [firstStep]="2" (fieldChange)="change($event)" (refresh)="reload()" (wizard)="openWizard()"></app-chat-route-fields>
        <fieldset *ngIf="selection.mode === 'workflow'" [disabled]="loading || saving || !catalog">
          <legend>4 · Workflow</legend>
          <label>Saved team<select name="workflow" [(ngModel)]="selection.workflow"><option value="">Choose a team</option>
            <option *ngFor="let w of catalog?.workflows" [value]="w.name">{{w.label}}</option></select></label>
          <button mat-button type="button" (click)="openWizard()">Templates / create or edit a team in the complete wizard</button>
        </fieldset>
        <fieldset *ngIf="!resumeMode" [disabled]="loading || saving || !catalog">
          <legend>5 · Destination, profile and judge defaults</legend>
          <label>Destination<select name="destination" [(ngModel)]="destination"><option value="web">Web chat</option><option value="terminal">CLI terminal</option></select></label>
          <label>Configuration save scope<select name="saveScope" [(ngModel)]="selection.saveScope"><option value="session">This session only</option>
            <option value="project">Project defaults</option><option value="global">Global defaults</option></select></label>
          <label>Save as project profile (optional)<input name="saveProfile" [(ngModel)]="selection.saveProfile"></label>
          <label><input name="replace" type="checkbox" [(ngModel)]="selection.replaceProfile"> Replace a profile with that name</label>
          <p>Judge defaults do not enable judges or change chat authentication.</p>
          <label *ngFor="let vendor of judgeVendors">{{vendor}} judge profile<select [name]="'judge-' + vendor" [(ngModel)]="judges[vendor]">
            <option value="">Keep current default</option><option *ngFor="let j of judgeProfiles(vendor)" [value]="j.name">{{j.label}} · {{j.model}}</option></select></label>
          <button mat-button type="button" (click)="openWizard()">Create / replace / delete judge profiles and edit fallbacks</button>
        </fieldset>
        <p *ngIf="terminalRequired">This launch needs the complete CLI wizard. Browser selections are not silently saved or discarded as a web session.</p>
        <button mat-flat-button type="submit" [disabled]="loading || saving || !catalog || (!terminalRequired && !canCreate)">{{saving ? 'Creating…' : terminalRequired ? 'Continue in complete wizard' : 'Create chat'}}</button>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button type="button" (click)="close()" [disabled]="saving">{{wizardMounted ? 'Close (CLI keeps running)' : 'Cancel'}}</button></mat-dialog-actions>
  `,
  styles: [`:host {display:block;} .directory {overflow-wrap:anywhere; font-size:.85em;} .tabs {display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px;}
    fieldset {border:1px solid var(--border-color,#ccc);border-radius:8px;padding:16px;margin:16px 0;min-width:0;}
    legend {font-weight:600;} label {display:block;margin:10px 0;} input:not([type=checkbox]), select {display:block;box-sizing:border-box;width:100%;padding:8px;font:inherit;
      color:var(--text-primary,#212529);background:var(--bg-body,#fff);border:1px solid var(--border-color,#ccc);border-radius:4px;}
    input[type=checkbox] {margin-right:8px;} [role=alert] {color:var(--status-error-text,#c62828);} section {min-width:0;} [hidden] {display:none!important;}
    :host-context(body.dark-theme) {color-scheme:dark;} @media(max-width:600px) {fieldset {padding:10px;} input,select {font-size:16px;min-height:44px;}}`]
})
export class NewChatDialogComponent implements OnInit, OnDestroy {
  catalog?: ChatSetupCatalog;
  selection: ChatSetupSelection = { mode: 'standard', profile: '', saveScope: 'session' };
  judges: Record<string, string> = {};
  name: string;
  destination = 'web';
  loading = false;
  saving = false;
  error = '';
  fullWizard = false;
  wizardMounted = false;
  private catalogRequest?: Subscription;
  private createRequest?: Subscription;
  constructor(@Inject(MAT_DIALOG_DATA) public data: NewChatDialogData, private http: HttpClient,
    private dialog: MatDialogRef<NewChatDialogComponent, NewChatDialogResult>) { this.name = data.name; }
  ngOnInit(): void { this.reload(); }
  ngOnDestroy(): void { this.catalogRequest?.unsubscribe(); this.createRequest?.unsubscribe(); delete this.selection.apiKey; }
  get nativeMode(): boolean { return this.selection.mode === 'passthrough' || (this.selection.mode === 'workflow' && this.selection.leadMode === 'passthrough'); }
  get resumeMode(): boolean { return this.selection.mode === 'resume' || this.selection.mode === 'resume-all'; }
  /** The CLI decides whether the discovered route can run in a browser; the dialog never guesses. */
  get terminalRequired(): boolean { return this.resumeMode || this.destination === 'terminal' || (!!this.catalog && !this.catalog.webSupported); }
  get judgeVendors(): string[] { return [...new Set((this.catalog?.judgeProfiles || []).map(j => j.provider!).filter(Boolean))]; }
  judgeProfiles(provider: string): Profile[] { return this.catalog?.judgeProfiles.filter(j => j.provider === provider) || []; }
  get canCreate(): boolean { return !!this.name.trim() && (this.nativeMode ? !!this.selection.passthroughAgent : !!this.selection.model?.trim()) && (this.selection.mode !== 'workflow' || !!this.selection.workflow); }
  openWizard(): void { if (this.saving) return; this.fullWizard = true; this.wizardMounted = true; delete this.selection.apiKey; }
  close(): void { this.dialog.close(this.wizardMounted ? {refresh: true} : undefined); }
  change(field: string): void {
    if (field === 'mode' && this.resumeMode) { this.catalogRequest?.unsubscribe(); this.loading = false; return; }
    clearRouteDependents(this.selection, field);
    if (field === 'profile' && this.selection.profile) {
      const profile = this.catalog?.profiles.find(p => p.name === this.selection.profile);
      if (profile && this.selection.mode !== 'workflow') this.selection.mode = profile.mode;
    }
    this.reload();
  }
  reload(): void {
    if (this.saving || this.resumeMode) return;
    this.catalogRequest?.unsubscribe(); this.loading = true; this.error = '';
    // Discovery never receives a newly typed secret. Only create submits it over the request body.
    const {apiKey, judges, saveProfile, replaceProfile, ...selection} = this.selection;
    this.catalogRequest = this.http.post<ChatSetupCatalog>(`${this.data.url}/chat-setup/options`, {selection}).subscribe({
      next: catalog => {
        this.loading = false;
        if (!catalog.available) { this.error = catalog.status || 'CLI setup is unavailable. Check the local CLI installation.'; return; }
        this.catalog = catalog;
        this.selection = mergeRouteCatalog(catalog, selection, {apiKey: apiKey || undefined, saveProfile: saveProfile || undefined, replaceProfile});
      }, error: err => { this.loading = false; this.error = err?.error?.message || 'Cannot discover setup options'; }
    });
  }
  create(): void {
    if (this.loading || this.saving || !this.catalog) return;
    if (this.terminalRequired) { this.openWizard(); return; }
    if (!this.canCreate) return;
    this.saving = true; this.error = ''; this.dialog.disableClose = true;
    const judges = Object.entries(this.judges).filter(([,profile]) => !!profile).map(([provider, profile]) => ({action: 'activate', provider, profile}));
    this.createRequest = this.http.post<NewChatDialogResult['chat']>(`${this.data.url}/chat-setup/create`, {name: this.name.trim(), selection: {...this.selection, judges}}).subscribe({
      next: chat => { this.saving = false; this.dialog.disableClose = false; delete this.selection.apiKey; this.dialog.close({chat}); },
      error: err => { this.saving = false; this.dialog.disableClose = false; this.error = err?.error?.message || 'Cannot create chat'; }
    });
  }
}
