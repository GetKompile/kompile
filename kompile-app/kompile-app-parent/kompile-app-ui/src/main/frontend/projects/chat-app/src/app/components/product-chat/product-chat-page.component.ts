import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { firstValueFrom } from 'rxjs';
import { backendUrl } from '@shared/services/base.service';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { AgentService } from '@shared/services/agent.service';
import { FolderService } from '@shared/services/folder.service';
import { ConversationalRagService } from '@shared/services/conversational-rag.service';
import { ChatHostEvent } from '../unified-chat/chat-host-context';
import { ProductChatContextService, ProductChatDescriptor } from './product-chat-context.service';
import { PRODUCT_INLINE_BOARD_REGISTRATIONS } from './product-inline-board-card.component';
import { PRODUCT_INLINE_INITIATIVE_REGISTRATIONS } from './product-inline-initiative-card.component';

interface PlayScope { campaignId: number; simulationId?: number; savedGridId?: number; }
interface ContentApproval { approvalId: string; summary: string; }
const PROTOCOL = 'elthoria.play-panels.v1';
const ENDPOINT = `${backendUrl}/agents/chat/product`;

/** Composition only: no independent agent loop or game-state authority. */
@Component({
  selector: 'app-product-chat-page', standalone: false,
  providers: [LocalAgentChatService, AgentService, FolderService, ConversationalRagService],
  template: `
    <main class="play-shell" *ngIf="profile as product">
      <header>
        <h1>{{ product.title }} <small>trusted-local GM play</small></h1>
        <button (click)="openSession()" [disabled]="busy || sessionOpen">Open / reattach play session</button>
        <button (click)="closeSession()" [disabled]="busy">Close play session</button>
        <button (click)="refreshPanels()" [disabled]="busy || !sessionOpen">Refresh confirmed state</button>
        <p role="status">{{ status }}</p><p role="alert" *ngIf="error">{{ error }}</p>
      </header>
      <section class="play-layout">
        <section class="chat-pane">
          <section *ngIf="sessionOpen" aria-label="GM pending content approvals">
            <h2>GM content approvals</h2>
            <button (click)="refreshApprovals()" [disabled]="busy || approvalBusy">Refresh pending approvals</button>
            <p role="status">{{ approvalStatus }}</p>
            <p *ngIf="!approvals.length">No pending content approvals shown.</p>
            <div *ngFor="let approval of approvals">
              <p>{{ approval.summary }}</p>
              <button (click)="resolveApproval(approval.approvalId, true)" [disabled]="busy || approvalBusy">Approve</button>
              <button (click)="resolveApproval(approval.approvalId, false)" [disabled]="busy || approvalBusy">Deny</button>
            </div>
          </section>
          <ng-template #inlineToolCard let-presentation>
            <app-product-inline-board-card *ngIf="presentation.kind === 'elthoria.tactical-board'"
              [presentation]="presentation" [sessionId]="product.chat.id"
              [runIdle]="inlineRunIdle" [refreshEpoch]="inlineRefreshEpoch"></app-product-inline-board-card>
            <app-product-inline-initiative-card *ngIf="presentation.kind === 'elthoria.initiative-tracker'"
              [presentation]="presentation" [sessionId]="product.chat.id"
              [runIdle]="inlineRunIdle"></app-product-inline-initiative-card>
          </ng-template>
          <app-unified-chat *ngIf="sessionOpen" [hostContext]="product" (hostEvent)="observeChat($event)"
            [toolPresentationTemplate]="inlineToolCard" [toolPresentationRegistrations]="inlineBoardRegistrations"></app-unified-chat>
          <p *ngIf="!sessionOpen">Configure a standard provider with Elthoria's terminal setup before opening play. Opening a session does not request a model response.</p>
        </section>
        <section class="world-pane">
          <fieldset [disabled]="!sessionOpen || busy">
            <legend>Explicit game selection — GM information, not player-safe</legend>
            <label>Campaign ID <input type="number" min="1" [(ngModel)]="campaignId"></label>
            <label>Simulation ID <input type="number" min="1" [(ngModel)]="simulationId"></label>
            <label>Saved grid ID <input type="number" min="1" [(ngModel)]="savedGridId"></label>
            <button (click)="attachPanels()">Inspect selected play</button>
          </fieldset>
          <p>Confirmed state only. Board movement requires separate GM approval and is not rules-validated movement.</p>
          @if (panelsUrl) {
            <iframe #panels [src]="panelsUrl" title="Elthoria board and reasoning"
              sandbox="allow-scripts allow-same-origin" referrerpolicy="no-referrer"></iframe>
          }
          <p role="status">{{ panelStatus }}</p>
        </section>
      </section>
    </main>
    <p role="alert" *ngIf="!profile && error">{{ error }}</p>
  `,
  styles: [`:host{display:block;height:100vh;color:#e8edf3;background:#141b25;font-family:system-ui}
    .play-shell{height:100%;display:flex;flex-direction:column}header{padding:.75rem 1rem;border-bottom:1px solid #455166}
    h1{margin:0 0 .5rem}small{font-size:.85rem;font-weight:normal}button,input{margin:.25rem;padding:.4rem}
    .play-layout{display:grid;grid-template-columns:minmax(320px,1fr) minmax(360px,1fr);flex:1;min-height:0}
    .chat-pane{min-height:0;overflow:auto}.world-pane{display:flex;flex-direction:column;padding:.5rem;min-height:0}
    iframe{width:100%;flex:1;min-height:340px;border:1px solid #455166;background:#fff}label{display:inline-block}
    input{width:7rem}p{margin:.35rem 0}[role=alert]{color:#ffb0a7}
    @media(max-width:850px){:host{height:auto;min-height:100vh}.play-layout{display:flex;flex-direction:column}.chat-pane{min-height:500px}}`]
})
export class ProductChatPageComponent implements OnInit, OnDestroy {
  @ViewChild('panels') panels?: ElementRef<HTMLIFrameElement>;
  profile: ProductChatDescriptor | null = null;
  panelsUrl: SafeResourceUrl | null = null;
  busy = false;
  inlineRunIdle = false;
  inlineRefreshEpoch = 0;
  readonly inlineBoardRegistrations = [
    ...PRODUCT_INLINE_BOARD_REGISTRATIONS, ...PRODUCT_INLINE_INITIATIVE_REGISTRATIONS];
  sessionOpen = false;
  panelLoaded = false;
  status = 'No play session attached.';
  panelStatus = '';
  error = '';
  approvals: ContentApproval[] = [];
  approvalStatus = '';
  approvalBusy = false;
  private approvalEpoch = 0;
  private approvalRead = 0;
  private approvalGeneration: string | null = null;
  campaignId: number | null = null;
  simulationId: number | null = null;
  savedGridId: number | null = null;
  private runtimeId: string | null = null;
  private generation = 0;
  private operation = 0;
  private destroyed = false;
  private selectedScope: PlayScope | null = null;
  private refreshPending = false;
  private readonly receive = (event: MessageEvent) => this.receivePanel(event);
  constructor(private readonly context: ProductChatContextService, private readonly http: HttpClient,
    private readonly sanitizer: DomSanitizer) {}

  async ngOnInit(): Promise<void> {
    window.addEventListener('message', this.receive);
    try {
      const profile = await this.context.load();
      if (this.destroyed) return;
      if (!profile) throw new Error('No managed play profile');
      // The managed product descriptor (installed policy) owns the user-facing
      // product name; the shell fallback title is replaced as soon as it loads.
      document.title = profile.title;
      this.profile = profile;
      // Descriptor validation accepts only the fixed same-origin installed asset route.
      this.panelsUrl = this.sanitizer.bypassSecurityTrustResourceUrl(profile.panelsUrl);
      if (['READY', 'IDLE', 'BUSY', 'SETTLING'].includes(profile.status.toUpperCase())) await this.openSession();
      else if (['QUARANTINED', 'FAILED', 'CLOSING'].includes(profile.status.toUpperCase()))
        this.status = 'This runtime needs explicit closure before further play. Its outcome is not confirmed.';
    } catch { if (!this.destroyed) this.error = 'The managed play profile could not be loaded.'; }
  }
  async openSession(): Promise<void> {
    if (this.destroyed || this.busy || !this.profile) return;
    const operation = ++this.operation;
    this.busy = true; this.error = '';
    try {
      const opened = await firstValueFrom(this.http.post<Record<string, unknown>>(`${ENDPOINT}/session`, null));
      if (!this.current(operation)) return;
      ++this.approvalEpoch; this.approvals = []; this.approvalBusy = false;
      this.approvalGeneration = typeof opened?.['generation'] === 'string' ? opened['generation'] : null;
      // Mount reconnect/Stop controls even when a resumed turn temporarily blocks metadata reads.
      this.sessionOpen = true;
      this.status = 'Play session attached. Detaching this view does not close the game server.';
      try {
        const connection = await firstValueFrom(this.http.get<unknown>(`${ENDPOINT}/connection`));
        if (this.current(operation)) this.runtimeId = this.connectionId(connection);
      } catch {
        if (this.current(operation)) this.status = 'Chat attached; refresh confirmed board metadata after the active turn.';
      }
      if (this.current(operation)) await this.refreshApprovals();
    } catch { if (this.current(operation)) this.error = 'Unable to open the runtime. Check provider setup and harness permissions.'; }
    finally { if (this.current(operation)) this.busy = false; }
  }
  async closeSession(): Promise<void> {
    if (this.destroyed || this.busy || !this.profile) return;
    const operation = ++this.operation;
    this.busy = true; this.error = '';
    this.post('disconnect');
    this.sessionOpen = false; this.inlineRunIdle = false; this.runtimeId = null; this.selectedScope = null;
    ++this.approvalEpoch; this.approvals = []; this.approvalBusy = false; this.approvalStatus = ''; this.approvalGeneration = null;
    this.status = 'Closing play; resource exit has not been confirmed yet.';
    try {
      const response = await firstValueFrom(this.http.delete<unknown>(`${ENDPOINT}/session`));
      if (!this.current(operation)) return;
      if (!response || typeof response !== 'object'
        || (response as Record<string, unknown>)['status'] !== 'closed') throw new Error('Closure not confirmed');
      this.status = 'Play session closed. Confirmed game changes remain saved.';
    } catch { if (this.current(operation)) this.error = 'Closure was not confirmed; do not start another database owner.'; }
    finally { if (this.current(operation)) this.busy = false; }
  }
  attachPanels(): void {
    if (!this.sessionOpen || !this.runtimeId) return;
    try {
      const positive = (value: number | null) => {
        if (value === null || !Number.isSafeInteger(value) || value < 1) throw new Error('Invalid selection');
        return value;
      };
      this.selectedScope = Object.freeze({ campaignId: positive(this.campaignId),
        ...(this.simulationId === null ? {} : { simulationId: positive(this.simulationId) }),
        ...(this.savedGridId === null ? {} : { savedGridId: positive(this.savedGridId) }) });
      this.error = ''; this.post('attach');
    } catch { this.error = 'Select positive safe-integer campaign, simulation and grid IDs.'; }
  }
  async refreshPanels(): Promise<void> {
    if (!this.sessionOpen || this.busy) return;
    const operation = ++this.operation;
    this.busy = true; this.error = '';
    try {
      const connection = await firstValueFrom(this.http.get<unknown>(`${ENDPOINT}/connection`));
      if (!this.current(operation)) return;
      const runtime = this.connectionId(connection);
      if (this.runtimeId !== null && runtime !== this.runtimeId) {
        this.post('disconnect'); this.selectedScope = null; this.runtimeId = runtime;
        ++this.approvalEpoch; this.approvals = []; this.approvalBusy = false;
        this.error = 'The runtime changed. Re-select play; no command was retried.';
        return;
      }
      const firstConnection = this.runtimeId === null;
      this.runtimeId = runtime;
      this.post(firstConnection ? 'attach' : 'refresh'); this.refreshPending = false;
    } catch { if (this.current(operation)) this.error = 'Refresh is unavailable or busy. Confirmed state was not replaced.'; }
    finally { if (this.current(operation)) this.busy = false; }
  }
  observeChat(event: ChatHostEvent): void {
    if (this.destroyed || !this.sessionOpen || !this.profile || event.productId !== this.profile.productId
      || event.sessionId !== this.profile.chat.id || event.agentName !== this.profile.agentName) return;
    if (event.type === 'readiness') {
      this.inlineRunIdle = event.status === 'idle';
      if (this.inlineRunIdle) ++this.inlineRefreshEpoch;
    }
    if (event.type === 'run') {
      this.inlineRunIdle = event.status === 'completed' || event.status === 'cancelled';
      if (this.inlineRunIdle) ++this.inlineRefreshEpoch;
    }
    // Transport loss does not prove that the model worker released domain reads.
    if (event.type === 'stream') this.inlineRunIdle = false;
    if (event.type === 'run' && ['completed', 'cancelled', 'failed'].includes(event.status)) void this.refreshApprovals();
    if (event.type === 'tool' && event.ok) this.refreshPending = true;
    if (event.type === 'run' && ['completed', 'cancelled'].includes(event.status)
      && (this.refreshPending || this.runtimeId === null)) void this.refreshPanels();
  }
  async refreshApprovals(): Promise<void> {
    if (this.destroyed || !this.sessionOpen || !this.profile || this.approvalBusy) return;
    const source = this.profile, epoch = this.approvalEpoch, generation = this.approvalGeneration;
    const read = ++this.approvalRead;
    try {
      const pending = await firstValueFrom(this.http.get<unknown>(`${ENDPOINT}/content-approvals`));
      if (!this.approvalCurrent(source, epoch, generation) || read !== this.approvalRead) return;
      if (!Array.isArray(pending) || pending.length > 128) throw new Error('Invalid approval metadata');
      const ids = new Set<string>();
      this.approvals = pending.map(value => {
        if (!value || typeof value !== 'object' || typeof value.approvalId !== 'string'
          || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value.approvalId) || ids.has(value.approvalId)) throw new Error('Invalid approval id');
        ids.add(value.approvalId);
        // Only the manager's bounded summary fields are rendered. Never raw prompts/arguments or HTML.
        const display = value.display && typeof value.display === 'object' && !Array.isArray(value.display) ? value.display : value;
        const fields = ['tool', 'action', 'kind', 'provider', 'model', 'operation', 'source', 'pages', 'budget',
          'maxPages', 'maxResponseChars', 'timeoutMinutes', 'digest', 'requestDigest'];
        const summary = fields.flatMap(field => {
          const item: unknown = display[field];
          return (typeof item === 'string' && item.length <= 512) || (typeof item === 'number' && Number.isFinite(item))
            ? [`${field}: ${item}`] : [];
        }).join(' · ');
        return { approvalId: value.approvalId, summary: summary || 'Content operation awaiting GM approval' };
      });
      if (this.approvalStatus.startsWith('Pending approvals unavailable')) this.approvalStatus = '';
    } catch {
      if (this.approvalCurrent(source, epoch, generation) && read === this.approvalRead) {
        this.approvals = []; this.approvalStatus = 'Pending approvals unavailable. Refresh after the turn; nothing was approved.';
      }
    }
  }
  async resolveApproval(approvalId: string, allow: boolean): Promise<void> {
    if (this.destroyed || !this.sessionOpen || !this.profile || this.approvalBusy || this.busy
      || typeof allow !== 'boolean' || !this.approvals.some(value => value.approvalId === approvalId)) return;
    const source = this.profile, epoch = this.approvalEpoch, generation = this.approvalGeneration;
    ++this.approvalRead; this.approvalBusy = true;
    try {
      const result = await firstValueFrom(this.http.post<Record<string, unknown>>(
        `${ENDPOINT}/content-approvals/${encodeURIComponent(approvalId)}`, { allow }));
      if (!this.approvalCurrent(source, epoch, generation)) return;
      if (result?.['ok'] !== true || result['approvalId'] !== approvalId) throw new Error('Approval not confirmed');
      this.approvals = this.approvals.filter(value => value.approvalId !== approvalId);
      this.approvalStatus = allow ? 'Approved; ask Elthoria to continue' : 'Denied; no content operation was started.';
    } catch {
      if (this.approvalCurrent(source, epoch, generation)) {
        this.approvals = []; this.approvalStatus = 'Approval was not confirmed. Refresh pending approvals; no operation was started here.';
      }
    } finally { if (this.approvalCurrent(source, epoch, generation)) this.approvalBusy = false; }
  }
  private approvalCurrent(source: ProductChatDescriptor, epoch: number, generation: string | null): boolean {
    return !this.destroyed && this.sessionOpen && this.profile === source && epoch === this.approvalEpoch
      && generation === this.approvalGeneration;
  }
  private current(operation: number): boolean { return !this.destroyed && operation === this.operation; }
  private connectionId(value: unknown): string {
    const connection = value as Record<string, unknown> | null;
    if (!connection || connection['status'] !== 'READY' || typeof connection['runtimeId'] !== 'string'
      || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$/.test(connection['runtimeId'])) throw new Error('Runtime not ready');
    return connection['runtimeId'];
  }
  private post(type: 'attach' | 'refresh' | 'disconnect'): void {
    if (!this.panelLoaded || !this.profile || !this.runtimeId || !this.selectedScope) return;
    ++this.generation;
    this.panels?.nativeElement.contentWindow?.postMessage({ protocol: PROTOCOL, type,
      requestId: `panels-${this.generation}`, generation: this.generation,
      scope: this.selectedScope, runtimeId: this.runtimeId,
      ...(type === 'attach' ? { apiBaseUrl: this.profile.domainBaseUrl + '/' + this.runtimeId } : {}) }, window.location.origin);
  }
  private receivePanel(event: MessageEvent): void {
    if (this.destroyed || event.origin !== window.location.origin || event.source !== this.panels?.nativeElement.contentWindow) return;
    const message = event.data;
    if (!message || typeof message !== 'object' || message.protocol !== PROTOCOL) return;
    if (message.type === 'ready' && Object.keys(message).length === 2) {
      this.panelLoaded = true; this.post('attach'); return;
    }
    if (!this.selectedScope || message.generation !== this.generation || message.runtimeId !== this.runtimeId
      || message.requestId !== `panels-${this.generation}` || JSON.stringify(message.scope) !== JSON.stringify(this.selectedScope)) return;
    if (['status', 'error'].includes(message.type) && typeof message.message === 'string' && message.message.length <= 2048) this.panelStatus = message.message;
  }
  ngOnDestroy(): void {
    this.destroyed = true; ++this.operation; ++this.approvalEpoch; ++this.approvalRead; this.approvals = [];
    window.removeEventListener('message', this.receive);
    // Detaching a view is deliberately not a session-close operation.
  }
}
