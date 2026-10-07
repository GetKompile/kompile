import { Component, Inject, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { CliTerminalConsoleComponent } from '../cli-terminal-console/cli-terminal-console.component';

interface Choice { id: string; label: string; available?: boolean; authMethods?: Choice[]; }
interface Profile { name: string; label: string; mode?: string; provider?: string; vendor?: string; model?: string; }
export interface ChatSetupSelection {
  [key: string]: string | boolean | undefined;
  mode?: string; runtime?: string; leadMode?: string; profile?: string; vendor?: string; authMethod?: string;
  authenticationScope?: string; credentialName?: string; apiKey?: string; baseUrl?: string; model?: string;
  thinking?: string; fastMode?: boolean; ultracode?: boolean; passthroughAgent?: string; passthroughManaged?: boolean;
  workflow?: string; saveProfile?: string; replaceProfile?: boolean; saveScope?: string;
}
export interface ChatSetupCatalog {
  available: boolean; defaults: ChatSetupSelection; profiles: Profile[]; runtimes: Choice[];
  frameworks: Choice[]; vendors: Choice[]; credentials: Profile[]; models: Choice[];
  thinkingOptions: {value: string; label: string}[]; workflows: Profile[]; judgeProfiles: Profile[];
  fastModeSupported: boolean; ultracodeSupported: boolean; errors?: string[]; status?: string;
}
export interface NewChatDialogData { url: string; workingDirectory: string; name: string; }
export interface NewChatDialogResult { chat?: { id: string; name: string; framework?: string; model?: string }; refresh?: boolean; }

/** Setup discovery/validation is owned by the CLI; no vendor or model catalog is duplicated here. */
@Component({
  selector: 'app-new-chat-dialog', standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, CliTerminalConsoleComponent],
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
            <option value="standard">Standard Kompile chat</option><option value="passthrough">Native CLI framework</option>
            <option value="workflow">Workflow team</option><option value="resume">Resume previous chat</option><option value="resume-all">Resume recent chats</option>
          </select></label>
          <p *ngIf="resumeMode">Open an existing chat from the workspace folders, or use the complete wizard for the CLI resume picker and bulk resume time windows.</p>
          <button *ngIf="resumeMode" mat-button type="button" (click)="openWizard()">Open resume wizard</button>
          <label *ngIf="!resumeMode">Saved project profile<select name="profile" [(ngModel)]="selection.profile" (ngModelChange)="change('profile')">
            <option value="">Fresh setup / folder defaults</option><option *ngFor="let p of catalog?.profiles" [value]="p.name">{{p.label}} · {{p.mode}}</option>
          </select></label>
          <label *ngIf="selection.mode === 'workflow'">Lead mode<select name="leadMode" [(ngModel)]="selection.leadMode" (ngModelChange)="change('leadMode')">
            <option value="standard">Standard Kompile lead</option><option value="passthrough">Native framework lead (complete wizard)</option>
          </select></label>
        </fieldset>
        <fieldset *ngIf="!resumeMode" [disabled]="loading || saving || !catalog">
          <legend>2 · Runtime and authentication</legend>
          <ng-container *ngIf="nativeMode; else standardRuntime">
            <label>Native framework<select name="agent" [(ngModel)]="selection.passthroughAgent" (ngModelChange)="change('passthroughAgent')">
              <option value="">Choose a framework</option><option *ngFor="let f of catalog?.frameworks" [value]="f.id" [disabled]="!f.available">{{f.label}}{{f.available ? '' : ' (not installed)'}}</option>
            </select></label>
            <label>Management<select name="managed" [(ngModel)]="selection.passthroughManaged" (ngModelChange)="change('passthroughManaged')">
              <option [ngValue]="true">Kompile-managed</option><option [ngValue]="false">Direct native CLI (complete wizard)</option>
            </select></label>
            <p>Native frameworks use their own login. New login is available in the complete wizard.</p>
          </ng-container>
          <ng-template #standardRuntime>
            <label>Runtime<select name="runtime" [(ngModel)]="selection.runtime" (ngModelChange)="change('runtime')">
              <option *ngFor="let r of catalog?.runtimes" [value]="r.id">{{runtimeLabel(r)}}</option>
            </select></label>
            <label>Vendor / provider<select name="vendor" [(ngModel)]="selection.vendor" (ngModelChange)="change('vendor')">
              <option *ngFor="let v of catalog?.vendors" [value]="v.id">{{v.label}}</option>
            </select></label>
            <label>Authentication<select name="auth" [(ngModel)]="selection.authMethod" (ngModelChange)="change('authMethod')">
              <option *ngFor="let a of authMethods" [value]="a.id">{{a.label}}</option>
            </select></label>
            <label>Authentication scope<select name="authScope" [(ngModel)]="selection.authenticationScope"><option value="session">Session</option><option value="global">Global</option></select></label>
            <label>Existing account<select name="credential" [(ngModel)]="selection.credentialName" (ngModelChange)="change('credentialName')">
              <option value="">Default account</option><option *ngFor="let c of catalog?.credentials" [value]="c.name">{{c.label}}</option>
            </select></label>
            <label *ngIf="keyAuth">API key (optional, session input)<input name="apiKey" type="password" autocomplete="new-password" [(ngModel)]="selection.apiKey"></label>
            <p *ngIf="selection.authMethod === 'oauth' || selection.authMethod === 'native'">Select an existing login, or use the complete wizard to sign in. Secret keys are never returned by discovery.</p>
            <label>Endpoint URL<input name="baseUrl" type="url" [(ngModel)]="selection.baseUrl" (change)="reload()" placeholder="Provider default or custom HTTP(S) endpoint"></label>
          </ng-template>
        </fieldset>
        <fieldset *ngIf="!resumeMode" [disabled]="loading || saving || !catalog">
          <legend>3 · Model and generation options</legend>
          <label>Discovered model<select name="modelChoice" [ngModel]="selection.model" (ngModelChange)="selection.model = $event; change('model')">
            <option value="">Provider default / choose a model</option><option *ngFor="let m of catalog?.models" [value]="m.id">{{m.label}}</option>
          </select></label>
          <label>Model ID / native alias<input name="model" [(ngModel)]="selection.model" (change)="change('model')" maxlength="256"></label>
          <button mat-button type="button" (click)="reload()">Refresh live models and options</button>
          <p *ngFor="let warning of catalog?.errors" role="status">{{warning}}</p>
          <label>Thinking / effort<select name="thinking" [(ngModel)]="selection.thinking"><option value="">Provider default</option>
            <ng-container *ngFor="let t of catalog?.thinkingOptions"><option *ngIf="t.value" [value]="t.value">{{t.label}}</option></ng-container></select></label>
          <label><input name="fast" type="checkbox" [(ngModel)]="selection.fastMode" [disabled]="!catalog?.fastModeSupported"> Fast mode (higher cost; eligible routes only)</label>
          <label><input name="ultracode" type="checkbox" [(ngModel)]="selection.ultracode" [disabled]="!catalog?.ultracodeSupported"> Ultracode (eligible Claude routes; replaces thinking)</label>
          <button *ngIf="selection.runtime === 'kompile-local'" mat-button type="button" (click)="openWizard()">Install / download a local model</button>
        </fieldset>
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
  get keyAuth(): boolean { return this.selection.authMethod === 'api-key' || this.selection.authMethod === 'api-key-credits'; }
  get terminalRequired(): boolean { return this.resumeMode || this.destination === 'terminal' || (!this.nativeMode && this.selection.runtime === 'kompile') || (this.nativeMode && (this.selection.passthroughManaged === false || this.selection.mode === 'workflow')); }
  get authMethods(): Choice[] { return this.catalog?.vendors.find(v => v.id === this.selection.vendor)?.authMethods || []; }
  get judgeVendors(): string[] { return [...new Set((this.catalog?.judgeProfiles || []).map(j => j.provider!).filter(Boolean))]; }
  judgeProfiles(provider: string): Profile[] { return this.catalog?.judgeProfiles.filter(j => j.provider === provider) || []; }
  get canCreate(): boolean { return !!this.name.trim() && (this.nativeMode ? !!this.selection.passthroughAgent : !!this.selection.model?.trim()) && (this.selection.mode !== 'workflow' || !!this.selection.workflow); }
  runtimeLabel(r: Choice): string { return ({direct: 'Direct vendor API', 'kompile-local': 'Kompile local serving', 'external-local': 'External local provider', kompile: 'Kompile instance'} as Record<string, string>)[r.id] || r.label; }
  openWizard(): void { if (this.saving) return; this.fullWizard = true; this.wizardMounted = true; delete this.selection.apiKey; }
  close(): void { this.dialog.close(this.wizardMounted ? {refresh: true} : undefined); }
  change(field: string): void {
    if (field === 'mode' && this.resumeMode) { this.catalogRequest?.unsubscribe(); this.loading = false; return; }
    const clear: Record<string, string[]> = {
      mode: ['profile', 'vendor', 'authMethod', 'model', 'thinking', 'passthroughAgent', 'workflow', 'credentialName', 'apiKey', 'baseUrl'],
      profile: ['vendor', 'runtime', 'authMethod', 'model', 'thinking', 'credentialName', 'apiKey', 'baseUrl', 'passthroughAgent'],
      runtime: ['profile', 'vendor', 'authMethod', 'model', 'thinking', 'credentialName', 'apiKey', 'baseUrl'],
      vendor: ['profile', 'authMethod', 'model', 'thinking', 'credentialName', 'apiKey', 'baseUrl'],
      authMethod: ['profile', 'credentialName', 'apiKey', 'baseUrl', 'model', 'thinking'],
      passthroughAgent: ['profile', 'model', 'thinking'], leadMode: ['profile', 'model', 'thinking'], model: ['thinking']
    };
    for (const key of clear[field] || []) delete this.selection[key];
    if (field === 'profile' && this.selection.profile) {
      const profile = this.catalog?.profiles.find(p => p.name === this.selection.profile);
      if (profile && this.selection.mode !== 'workflow') this.selection.mode = profile.mode;
    }
    this.selection.fastMode = false; this.selection.ultracode = false;
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
        this.selection = {...catalog.defaults, ...selection, ...(apiKey ? {apiKey} : {}), ...(saveProfile ? {saveProfile} : {}), ...(replaceProfile !== undefined ? {replaceProfile} : {})};
        if (!catalog.fastModeSupported) this.selection.fastMode = false;
        if (!catalog.ultracodeSupported) this.selection.ultracode = false;
        if (this.selection.thinking && !catalog.thinkingOptions.some(t => t.value === this.selection.thinking)) delete this.selection.thinking;
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
