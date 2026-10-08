import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';

export interface Choice { id: string; label: string; available?: boolean; webSupported?: boolean; authMethods?: Choice[];
  endpointRequired?: boolean; acceptsKey?: boolean; signIn?: boolean; }
export interface Profile { name: string; label: string; mode?: string; provider?: string; vendor?: string; model?: string; }
export interface ChatSetupSelection {
  [key: string]: string | boolean | undefined;
  mode?: string; runtime?: string; leadMode?: string; profile?: string; vendor?: string; authMethod?: string;
  authenticationScope?: string; credentialName?: string; apiKey?: string; baseUrl?: string; model?: string;
  thinking?: string; fastMode?: boolean; ultracode?: boolean; passthroughAgent?: string; passthroughManaged?: boolean;
  workflow?: string; saveProfile?: string; replaceProfile?: boolean; saveScope?: string;
}
export interface ChatSetupCatalog {
  available: boolean; defaults: ChatSetupSelection; profiles: Profile[]; modes: Choice[]; runtimes: Choice[]; webSupported: boolean;
  leadModes: Choice[]; passthroughStyles: {managed: boolean; label: string}[];
  frameworks: Choice[]; vendors: Choice[]; credentials: Profile[]; models: Choice[];
  thinkingOptions: {value: string; label: string}[]; workflows: Profile[]; judgeProfiles: Profile[];
  fastModeSupported: boolean; ultracodeSupported: boolean; errors?: string[]; status?: string; session?: boolean;
}

/** Fields a change invalidates: the CLI rediscovers them for the new choice instead of keeping a stale value. */
const DEPENDENTS: Record<string, string[]> = {
  mode: ['profile', 'vendor', 'authMethod', 'model', 'thinking', 'passthroughAgent', 'workflow', 'credentialName', 'apiKey', 'baseUrl'],
  profile: ['vendor', 'runtime', 'authMethod', 'model', 'thinking', 'credentialName', 'apiKey', 'baseUrl', 'passthroughAgent'],
  runtime: ['profile', 'vendor', 'authMethod', 'model', 'thinking', 'credentialName', 'apiKey', 'baseUrl'],
  vendor: ['profile', 'authMethod', 'model', 'thinking', 'credentialName', 'apiKey', 'baseUrl'],
  authMethod: ['profile', 'credentialName', 'apiKey', 'baseUrl', 'model', 'thinking'],
  passthroughAgent: ['profile', 'model', 'thinking'], leadMode: ['profile', 'model', 'thinking'], model: ['thinking']
};

/** Clears what a changed field invalidates; fast mode and ultracode are re-decided by the next catalog. */
export function clearRouteDependents(selection: ChatSetupSelection, field: string): void {
  for (const key of DEPENDENTS[field] || []) delete selection[key];
  selection.fastMode = false; selection.ultracode = false;
}

/**
 * The selection after a catalog answer: the CLI's defaults under what the user already chose, with any
 * choice the route no longer offers dropped. Secrets and save-only fields stay out of discovery requests
 * and are restored here.
 */
export function mergeRouteCatalog(catalog: ChatSetupCatalog, requested: ChatSetupSelection,
                                  kept: ChatSetupSelection): ChatSetupSelection {
  const merged: ChatSetupSelection = {...catalog.defaults, ...requested};
  for (const [key, value] of Object.entries(kept)) if (value !== undefined) merged[key] = value;
  if (!catalog.fastModeSupported) merged.fastMode = false;
  if (!catalog.ultracodeSupported) merged.ultracode = false;
  if (merged.thinking && !catalog.thinkingOptions.some(t => t.value === merged.thinking)) delete merged.thinking;
  return merged;
}

/**
 * Runtime, authentication and model fields of the CLI setup wizard, shared by the new-chat dialog and the
 * session configuration so the two cannot drift. Every list comes from the CLI catalog; nothing is typed
 * except a session API key or an endpoint the vendor lacks.
 */
@Component({
  selector: 'app-chat-route-fields', standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule],
  template: `
    <fieldset [disabled]="disabled || !catalog" data-testid="route-runtime-fields">
      <legend>{{ step(0) }}Runtime and authentication</legend>
      <ng-container *ngIf="nativeMode; else standardRuntime">
        <label>Native framework<select name="agent" [(ngModel)]="selection.passthroughAgent" (ngModelChange)="fieldChange.emit('passthroughAgent')">
          <option value="">Choose a framework</option><option *ngFor="let f of catalog?.frameworks; trackBy: byId" [value]="f.id" [disabled]="!f.available">{{f.label}}{{!f.available ? ' (not installed)' : f.webSupported ? '' : ' (terminal only)'}}</option>
        </select></label>
        <label>Management<select name="managed" [(ngModel)]="selection.passthroughManaged" (ngModelChange)="fieldChange.emit('passthroughManaged')">
          <option *ngFor="let s of catalog?.passthroughStyles; trackBy: byManaged" [ngValue]="s.managed">{{s.label}}</option>
        </select></label>
        <p>Native frameworks use their own login. New login is available in the complete wizard.</p>
      </ng-container>
      <ng-template #standardRuntime>
        <label>Runtime<select name="runtime" [(ngModel)]="selection.runtime" (ngModelChange)="fieldChange.emit('runtime')">
          <option *ngFor="let r of catalog?.runtimes; trackBy: byId" [value]="r.id">{{r.label}}</option>
        </select></label>
        <label>Vendor / provider<select name="vendor" [(ngModel)]="selection.vendor" (ngModelChange)="fieldChange.emit('vendor')">
          <option *ngFor="let v of catalog?.vendors; trackBy: byId" [value]="v.id">{{v.label}}</option>
        </select></label>
        <label>Authentication<select name="auth" [(ngModel)]="selection.authMethod" (ngModelChange)="fieldChange.emit('authMethod')">
          <option *ngFor="let a of authMethods; trackBy: byId" [value]="a.id">{{a.label}}</option>
        </select></label>
        <label>Authentication scope<select name="authScope" [(ngModel)]="selection.authenticationScope"><option value="session">Session</option><option value="global">Global</option></select></label>
        <label>Existing account<select name="credential" [(ngModel)]="selection.credentialName" (ngModelChange)="fieldChange.emit('credentialName')">
          <option value="">Default account</option><option *ngFor="let c of catalog?.credentials; trackBy: byName" [value]="c.name">{{c.label}}</option>
        </select></label>
        <label *ngIf="keyAuth">API key (optional, session input)<input name="apiKey" type="password" autocomplete="new-password" [(ngModel)]="selection.apiKey"></label>
        <p *ngIf="authChoice?.signIn">Select an existing login, or use the complete wizard to sign in. Secret keys are never returned by discovery.</p>
        <label *ngIf="vendorChoice?.endpointRequired">Endpoint URL (this vendor has no endpoint of its own)<input name="baseUrl" type="url" [(ngModel)]="selection.baseUrl" (change)="refresh.emit()" placeholder="OpenAI-compatible HTTP(S) endpoint"></label>
      </ng-template>
    </fieldset>
    <fieldset [disabled]="disabled || !catalog" data-testid="route-model-fields">
      <legend>{{ step(1) }}Model and generation options</legend>
      <label>Model ({{catalog?.models?.length || 0}} discovered live)<select name="modelChoice" [ngModel]="selection.model || ''" (ngModelChange)="selection.model = $event; fieldChange.emit('model')">
        <option value="">{{nativeMode ? 'Framework default model' : 'Choose a discovered model'}}</option>
        <option *ngIf="savedModelUnlisted" [value]="selection.model">{{selection.model}} (saved; not in the live list)</option>
        <option *ngFor="let m of catalog?.models; trackBy: byId" [value]="m.id">{{m.label}}</option>
      </select></label>
      <button mat-button type="button" (click)="refresh.emit()">Refresh live models and options</button>
      <p *ngFor="let warning of catalog?.errors" role="status">{{warning}}</p>
      <label>Thinking / effort<select name="thinking" [(ngModel)]="selection.thinking" [disabled]="!hasThinking"><option value="">Provider default</option>
        <ng-container *ngFor="let t of catalog?.thinkingOptions; trackBy: byValue"><option *ngIf="t.value" [value]="t.value">{{t.label}}</option></ng-container></select></label>
      <p *ngIf="catalog && !hasThinking" role="status" data-testid="route-thinking-note">{{ selection.model ? 'This model offers no effort levels.' : 'Choose a model to see its effort levels.' }}</p>
      <ng-container *ngIf="toggles">
        <label><input name="fast" type="checkbox" [(ngModel)]="selection.fastMode" [disabled]="!catalog?.fastModeSupported"> Fast mode (higher cost; eligible routes only)</label>
        <label><input name="ultracode" type="checkbox" [(ngModel)]="selection.ultracode" [disabled]="!catalog?.ultracodeSupported"> Ultracode (eligible Claude routes; replaces thinking)</label>
      </ng-container>
      <button *ngIf="selection.runtime === 'kompile-local' && wizard.observed" mat-button type="button" (click)="wizard.emit()">Install / download a local model</button>
    </fieldset>
  `,
  styles: [`:host {display:block;}
    fieldset {border:1px solid var(--border-color,#ccc);border-radius:8px;padding:16px;margin:16px 0;min-width:0;}
    legend {font-weight:600;} label {display:block;margin:10px 0;} input:not([type=checkbox]), select {display:block;box-sizing:border-box;width:100%;padding:8px;font:inherit;
      color:var(--text-primary,#212529);background:var(--bg-body,#fff);border:1px solid var(--border-color,#ccc);border-radius:4px;}
    input[type=checkbox] {margin-right:8px;} :host-context(body.dark-theme) {color-scheme:dark;}
    @media(max-width:600px) {fieldset {padding:10px;} input,select {font-size:16px;min-height:44px;}}`]
})
export class ChatRouteFieldsComponent {
  @Input() catalog?: ChatSetupCatalog;
  /** Edited in place: the owning dialog submits it. */
  @Input() selection: ChatSetupSelection = {};
  @Input() disabled = false;
  @Input() nativeMode = false;
  /** Fast mode and ultracode; a dialog with its own controls for them leaves these out. */
  @Input() toggles = true;
  /** Legend number of the first fieldset; 0 leaves the legends unnumbered. */
  @Input() firstStep = 0;
  @Output() fieldChange = new EventEmitter<string>();
  @Output() refresh = new EventEmitter<void>();
  @Output() wizard = new EventEmitter<void>();

  /**
   * Options keyed by what they select. Every rediscovery answers with new lists; options rebuilt from
   * them leave the browser select showing its blank first option although the choice is still made.
   */
  byId(_index: number, choice: Choice): string { return choice.id; }
  byName(_index: number, profile: Profile): string { return profile.name; }
  byValue(_index: number, option: {value: string}): string { return option.value; }
  byManaged(_index: number, style: {managed: boolean}): boolean { return style.managed; }
  step(offset: number): string { return this.firstStep ? `${this.firstStep + offset} · ` : ''; }
  get vendorChoice(): Choice | undefined { return this.catalog?.vendors.find(v => v.id === this.selection.vendor); }
  get authMethods(): Choice[] { return this.vendorChoice?.authMethods || []; }
  get authChoice(): Choice | undefined { return this.authMethods.find(a => a.id === this.selection.authMethod); }
  get keyAuth(): boolean { return !!this.authChoice?.acceptsKey; }
  get hasThinking(): boolean { return !!this.catalog?.thinkingOptions?.some(t => !!t.value); }
  /** A saved profile/folder model stays visible when live discovery cannot list it; nothing new is typed. */
  get savedModelUnlisted(): boolean { return !!this.selection.model && !this.catalog?.models.some(m => m.id === this.selection.model); }
}
