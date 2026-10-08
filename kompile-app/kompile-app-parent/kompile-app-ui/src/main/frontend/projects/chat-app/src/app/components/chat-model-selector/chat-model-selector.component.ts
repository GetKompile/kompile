import { ChangeDetectorRef, Component, EventEmitter, Input, NgZone, OnChanges, OnDestroy, OnInit, Output, SimpleChanges, ViewChild, ElementRef } from '@angular/core';
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
          [disabled]="busy || loading || !vendors().length">
          <option *ngIf="!vendors().length" [value]="selectedVendor">{{ modelMenu?.provider || 'No vendor available' }}</option>
          <option *ngFor="let vendor of vendors(); trackBy: byVendor" [value]="vendor.vendor">{{ vendor.display || vendor.vendor }}</option>
        </select>
      </label>
      <label>Model
        <select #modelSelect aria-label="Chat model" data-testid="chat-model-select"
          [ngModel]="modelChoice" (ngModelChange)="selectModel($event)"
          [disabled]="busy || loading || !modelMenu">
          <option *ngIf="!currentListed()" [value]="currentModel()">{{ currentModel() || 'Select model…' }}</option>
          <option *ngFor="let model of models(); trackBy: byId" [value]="model.id">{{ model.display || model.id }}</option>
        </select>
      </label>
      <!-- Always shown: a route without effort levels says so (and why, in the note) instead of hiding the control. -->
      <label>Thinking / effort
        <select aria-label="Thinking effort" data-testid="chat-thinking-select"
          [ngModel]="thinkingChoice" (ngModelChange)="selectThinking($event)"
          [disabled]="busy || loading || !thinkingMenu?.supported || selectedVendor !== currentVendor()">
          <option *ngIf="!thinkingMenu?.thinkingOptions?.length" value="">{{ loading ? 'Loading…' : 'Not offered' }}</option>
          <option *ngFor="let option of thinkingMenu?.thinkingOptions; trackBy: byValue" [value]="option.value">{{ option.label }}</option>
        </select>
      </label>
      <span *ngIf="thinkingMenu?.note">{{ thinkingMenu?.note }}</span>
      <span *ngIf="modelMenu?.note && !loading" role="status" data-testid="model-catalog-note">{{ modelMenu?.note }}</span>
      <button type="button" (click)="refreshModels()" [disabled]="busy || loading" aria-label="Refresh model catalog">↻</button>
      <span *ngIf="loading" role="status">Loading models…</span>
      <span *ngIf="loadError" role="alert">{{ loadError }}</span>
    </div>
  `,
  styles: [`
    :host { display: block; flex-shrink: 0; }
    .model-selectors { display: flex; align-items: center; flex-wrap: wrap; gap: 12px;
      padding: 6px 16px; background: var(--bg-surface); border-bottom: 1px solid var(--border-color);
      color: var(--text-secondary); font-size: .85em; }
    label { display: flex; align-items: center; gap: 8px; font-weight: 600; min-width: 0; }
    select, input, button { background: var(--bg-body); color: var(--text-primary);
      border: 1px solid var(--border-color); border-radius: 6px; padding: 6px 10px; }
    select { min-width: 150px; max-width: 320px; }
    select:focus-visible, input:focus-visible, button:focus-visible { outline: 2px solid var(--color-primary, #1976d2); }
    button { cursor: pointer; }
    :disabled { opacity: .6; cursor: default; }
    [role=alert] { color: var(--status-error-text, #c62828); }
    @media (max-width: 600px) {
      .model-selectors { padding: 8px 12px; gap: 8px; }
      label { width: 100%; } select { flex: 1; min-width: 0; max-width: 100%; }
    }
  `]
})
export class ChatModelSelectorComponent implements OnInit, OnChanges, OnDestroy {
  /** How often the selector re-reads the chat's route, so changes made elsewhere show up. */
  static readonly POLL_MS = 15000;
  @Input() sessionId?: string;
  @Input() workingDirectory?: string;
  @Input() busy = false;
  /** Only this conversation's outcomes; never the shared cross-pane command bus. */
  @Input() outcome: CommandOutcome | null = null;
  @Output() modelSelected = new EventEmitter<string>();
  @Output() thinkingSelected = new EventEmitter<string>();
  thinkingMenu: CommandEventData | null = null;
  thinkingChoice = '';
  @ViewChild('modelSelect') private modelSelect?: ElementRef<HTMLSelectElement>;

  modelMenu: CommandEventData | null = null;
  selectedVendor = '';
  modelChoice = '';
  loading = false;
  loadError: string | null = null;
  private request?: Subscription;
  private pollRequest?: Subscription;
  private pollTimer?: ReturnType<typeof setInterval>;

  /** The chat view hosting this selector is OnPush, so a catalog that arrives later must mark it for check. */
  constructor(private readonly agentChat: LocalAgentChatService, private readonly zone: NgZone,
              private readonly cdr: ChangeDetectorRef) {}

  ngOnInit(): void {
    // The timer lives outside Angular so a standing interval never keeps the app from settling;
    // each tick re-enters the zone to poll and render.
    this.zone.runOutsideAngular(() => {
      this.pollTimer = setInterval(() => this.zone.run(() => this.poll()), ChatModelSelectorComponent.POLL_MS);
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    // A finished turn may have changed the route (a /model or /thinking typed in the chat).
    if (changes['busy'] && changes['busy'].previousValue && !this.busy
      && !changes['sessionId'] && !changes['workingDirectory'] && !changes['outcome']) this.poll();
    if (changes['sessionId'] || changes['workingDirectory']) {
      this.modelMenu = null;
      this.thinkingMenu = null;
      this.thinkingChoice = '';
      this.selectedVendor = '';
      this.modelChoice = '';
      this.refreshModels();
    } else if (changes['outcome'] && this.outcome) {
      // Applied outcomes carry state without a catalog. Failed selections also
      // reload so the dropdown never presents a rejected choice as applied.
      if (!this.outcome.ok || this.outcome.data?.state?.model || this.outcome.data?.menu === 'thinking') this.fetchModels();
      else if (this.outcome.data?.menu === 'model') {
        this.request?.unsubscribe();
        this.loading = false;
        this.applyMenu(this.outcome.data);
      }
    }
  }

  ngOnDestroy(): void {
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.request?.unsubscribe();
    this.pollRequest?.unsubscribe();
  }

  byVendor(_: number, vendor: { vendor: string }): string { return vendor.vendor; }
  byId(_: number, model: CommandModelEntry): string { return model.id; }
  byValue(_: number, option: { value: string }): string { return option.value; }

  /**
   * Quiet re-read of the chat's route: no loading state, so the controls never flicker or close. Skipped while
   * a turn or an explicit load runs, while the page is hidden, and while another vendor is being browsed.
   */
  poll(): void {
    if (!this.sessionId || this.busy || this.loading || !this.modelMenu || document.visibilityState === 'hidden'
      || this.selectedVendor !== this.currentVendor()) return;
    this.pollRequest?.unsubscribe();
    this.pollRequest = this.agentChat.getSessionConfig(this.sessionId, this.workingDirectory).subscribe({
      next: snapshot => {
        if (this.loading || this.busy || snapshot.available === false || !snapshot.model) return;
        this.applyMenu(snapshot.model);
        this.thinkingMenu = snapshot.thinking ?? null;
        this.thinkingChoice = snapshot.thinking?.currentThinking ?? '';
        this.cdr.markForCheck();
      },
      // A failed background read keeps the last good state; the refresh button reports errors.
      error: () => undefined
    });
  }

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
    if (this.busy || this.loading || vendor === this.selectedVendor) return;
    this.selectedVendor = vendor;
    this.fetchModels(vendor);
  }

  refreshModels(): void { this.fetchModels(); }

  /** Reuse the same vendor-scoped /model argument as the former dialog; ids come only from the live catalog. */
  selectModel(modelId: string): void {
    if (!modelId || this.busy || this.loading || !this.models().some(model => model.id === modelId)) return;
    // Native frameworks are vendors too: "<framework>:<model>" switches the chat's framework.
    const browsingOtherVendor = this.selectedVendor && this.selectedVendor !== this.currentVendor();
    this.modelChoice = modelId;
    this.modelSelected.emit(browsingOtherVendor ? this.selectedVendor + ':' + modelId : modelId);
  }

  selectThinking(value: string): void {
    if (this.busy || this.loading || !this.thinkingMenu?.supported
      || this.selectedVendor !== this.currentVendor()
      || !this.thinkingMenu.thinkingOptions?.some(option => option.value === value)) return;
    this.thinkingChoice = value;
    this.thinkingSelected.emit(value);
  }

  currentVendor(): string {
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
    this.thinkingChoice = this.thinkingMenu?.currentThinking ?? '';
  }

  private fetchModels(vendor?: string): void {
    // Cancel the previous context's request when switching conversations; an explicit load supersedes a poll.
    this.request?.unsubscribe();
    this.pollRequest?.unsubscribe();
    this.loading = true;
    this.loadError = null;
    this.request = this.agentChat.getSessionConfig(this.sessionId, this.workingDirectory, vendor).subscribe({
      next: snapshot => {
        this.loading = false;
        if (snapshot.available === false || !snapshot.model) {
          this.loadError = snapshot.status || 'Model configuration is unavailable.';
          this.restoreVendor();
        } else {
          this.applyMenu(snapshot.model, vendor);
          this.thinkingMenu = snapshot.thinking ?? null;
          this.thinkingChoice = snapshot.thinking?.currentThinking ?? '';
        }
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.loadError = 'Could not load model catalog. Retry with refresh.';
        this.restoreVendor();
        this.cdr.markForCheck();
      }
    });
  }
}
