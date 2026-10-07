import { Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { CommandEventData, CommandModelEntry, CommandOutcome } from '@shared/models/api-models';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';

/** The former configuration-dialog model picker, kept on the conversation surface. */
@Component({
  selector: 'app-chat-model-selector',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="model-selectors" aria-label="Chat model configuration">
      <label>Vendor
        <select aria-label="Model vendor" data-testid="model-vendor-select"
          [ngModel]="selectedVendor" (ngModelChange)="pickVendor($event)"
          [disabled]="busy || loading || !!modelMenu?.nativeModelSelection || !vendors().length">
          <option *ngIf="!vendors().length" [value]="selectedVendor">{{ modelMenu?.provider || 'No vendor available' }}</option>
          <option *ngFor="let vendor of vendors()" [value]="vendor.vendor">{{ vendor.display || vendor.vendor }}</option>
        </select>
      </label>
      <label>Model
        <select #modelSelect aria-label="Chat model" data-testid="chat-model-select"
          [ngModel]="modelChoice" (ngModelChange)="selectModel($event)"
          [disabled]="busy || loading || !modelMenu">
          <option *ngIf="!currentListed()" [value]="currentModel()">{{ currentModel() || 'Select model…' }}</option>
          <option *ngFor="let model of models()" [value]="model.id">{{ model.display || model.id }}</option>
          <option *ngIf="modelMenu?.nativeModelSelection" value="__custom__">Custom model…</option>
        </select>
      </label>
      <button type="button" (click)="refreshModels()" [disabled]="busy || loading" aria-label="Refresh model catalog">↻</button>
      <span *ngIf="loading" role="status">Loading models…</span>
      <span *ngIf="loadError" role="alert">{{ loadError }}</span>
      <div *ngIf="modelChoice === '__custom__' && modelMenu?.nativeModelSelection" class="native-model-editor">
        <input [(ngModel)]="nativeModelId" maxlength="256" aria-label="Native model ID"
          placeholder="Framework model ID, e.g. zai/glm-5" [disabled]="busy || loading">
        <button type="button" [disabled]="busy || loading || !nativeModelId.trim()"
          (click)="selectModel(nativeModelId.trim())">Apply model</button>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; flex-shrink: 0; }
    .model-selectors { display: flex; align-items: center; flex-wrap: wrap; gap: 12px;
      padding: 10px 20px; background: var(--bg-surface); border-bottom: 1px solid var(--border-color);
      color: var(--text-secondary); font-size: .85em; }
    label { display: flex; align-items: center; gap: 8px; font-weight: 600; min-width: 0; }
    select, input, button { background: var(--bg-body); color: var(--text-primary);
      border: 1px solid var(--border-color); border-radius: 6px; padding: 6px 10px; }
    select { min-width: 150px; max-width: 320px; }
    select:focus-visible, input:focus-visible, button:focus-visible { outline: 2px solid var(--color-primary, #1976d2); }
    button { cursor: pointer; }
    :disabled { opacity: .6; cursor: default; }
    .native-model-editor { display: flex; flex-wrap: wrap; gap: 8px; }
    [role=alert] { color: var(--status-error-text, #c62828); }
    @media (max-width: 600px) {
      .model-selectors { padding: 8px 12px; gap: 8px; }
      label { width: 100%; } select { flex: 1; min-width: 0; max-width: 100%; }
    }
  `]
})
export class ChatModelSelectorComponent implements OnChanges, OnDestroy {
  @Input() sessionId?: string;
  @Input() workingDirectory?: string;
  @Input() busy = false;
  /** Only this conversation's outcomes; never the shared cross-pane command bus. */
  @Input() outcome: CommandOutcome | null = null;
  @Output() modelSelected = new EventEmitter<string>();
  @ViewChild('modelSelect') private modelSelect?: ElementRef<HTMLSelectElement>;

  modelMenu: CommandEventData | null = null;
  selectedVendor = '';
  modelChoice = '';
  nativeModelId = '';
  loading = false;
  loadError: string | null = null;
  private request?: Subscription;

  constructor(private readonly agentChat: LocalAgentChatService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['sessionId'] || changes['workingDirectory']) {
      this.modelMenu = null;
      this.selectedVendor = '';
      this.modelChoice = '';
      this.nativeModelId = '';
      this.refreshModels();
    } else if (changes['outcome'] && this.outcome) {
      // Applied outcomes carry state without a catalog. Failed selections also
      // reload so the dropdown never presents a rejected choice as applied.
      if (!this.outcome.ok || this.outcome.data?.state?.model) this.fetchModels();
      else if (this.outcome.data?.menu === 'model') {
        this.request?.unsubscribe();
        this.loading = false;
        this.applyMenu(this.outcome.data);
      }
    }
  }

  ngOnDestroy(): void { this.request?.unsubscribe(); }

  focus(): void { this.modelSelect?.nativeElement.focus(); }

  models(): CommandModelEntry[] { return this.modelMenu?.models ?? []; }
  vendors(): { vendor: string; display?: string; current?: boolean }[] { return this.modelMenu?.vendors ?? []; }
  currentModel(): string {
    return this.models().find(model => model.current)?.id
      || (this.selectedVendor === this.currentVendor() ? this.modelMenu?.currentModel : '') || '';
  }
  currentListed(): boolean { return this.models().some(model => model.id === this.currentModel()); }

  /** Browsing vendors uses the existing quiet HTTP snapshot, not a chat turn. */
  pickVendor(vendor: string): void {
    if (this.busy || this.loading || this.modelMenu?.nativeModelSelection || vendor === this.selectedVendor) return;
    this.selectedVendor = vendor;
    this.fetchModels(vendor);
  }

  refreshModels(): void { this.fetchModels(); }

  /** Reuse the same vendor-scoped /model argument as the former dialog. */
  selectModel(modelId: string): void {
    if (!modelId || this.busy || this.loading) return;
    if (modelId === '__custom__') {
      this.modelChoice = modelId;
      return;
    }
    const browsingOtherVendor = !this.modelMenu?.nativeModelSelection
      && this.selectedVendor && this.selectedVendor !== this.currentVendor();
    this.modelChoice = modelId;
    this.modelSelected.emit(browsingOtherVendor ? this.selectedVendor + ':' + modelId : modelId);
  }

  private currentVendor(): string {
    return this.modelMenu?.vendors?.find(vendor => vendor.current)?.vendor
      || this.modelMenu?.currentVendor || this.modelMenu?.provider || '';
  }

  private applyMenu(menu: CommandEventData, vendor?: string): void {
    this.modelMenu = menu;
    this.selectedVendor = vendor || menu.vendor || this.currentVendor();
    this.modelChoice = this.currentModel();
  }

  private restoreVendor(): void {
    this.selectedVendor = this.modelMenu?.vendor || this.currentVendor();
    this.modelChoice = this.currentModel();
  }

  private fetchModels(vendor?: string): void {
    // Cancel the previous context's request when switching conversations.
    this.request?.unsubscribe();
    this.loading = true;
    this.loadError = null;
    this.request = this.agentChat.getSessionConfig(this.sessionId, this.workingDirectory, vendor).subscribe({
      next: snapshot => {
        this.loading = false;
        if (snapshot.available === false || !snapshot.model) {
          this.loadError = snapshot.status || 'Model configuration is unavailable.';
          this.restoreVendor();
          return;
        }
        this.applyMenu(snapshot.model, vendor);
      },
      error: () => {
        this.loading = false;
        this.loadError = 'Could not load model catalog. Retry with refresh.';
        this.restoreVendor();
      }
    });
  }
}
