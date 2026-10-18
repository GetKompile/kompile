import { Component, ElementRef, Input, OnChanges, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Subscription } from 'rxjs';
import { backendUrl } from '@shared/services/base.service';
import { ToolPresentation } from '@shared/models/api-models';
import { ToolPresentationRegistration, validateToolPresentation } from '@shared/services/markdown-renderer.service';

const ENDPOINT = `${backendUrl}/agents/chat/product`;
const INLINE_ASSET = ENDPOINT + '/assets/index.html#inline-board';
const PROTOCOL = 'elthoria.play-panels.v1';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
interface BoardBinding { campaignId: number; savedGridId: number; runtimeId: string; observedRevision: string; }

function boardData(data: Readonly<Record<string, unknown>>): boolean {
  return Object.keys(data).length === 4
    && ['campaignId', 'savedGridId', 'runtimeId', 'observedRevision'].every(key => Object.prototype.hasOwnProperty.call(data, key))
    && typeof data['campaignId'] === 'number' && Number.isSafeInteger(data['campaignId']) && data['campaignId'] > 0
    && typeof data['savedGridId'] === 'number' && Number.isSafeInteger(data['savedGridId']) && data['savedGridId'] > 0
    && typeof data['runtimeId'] === 'string' && UUID.test(data['runtimeId'])
    && typeof data['observedRevision'] === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(data['observedRevision']);
}

/** The registry belongs to product composition, not shared chat or model Markdown. */
export const PRODUCT_INLINE_BOARD_REGISTRATIONS: readonly ToolPresentationRegistration[] = Object.freeze([
  Object.freeze({ kind: 'elthoria.tactical-board', serverId: 'elthoria', toolName: 'show_tactical_board', validateData: boardData })
]);
let nextCard = 0;

/** Read-only view of CURRENT confirmed state; the producer revision is not a historical snapshot. */
@Component({
  selector: 'app-product-inline-board-card', standalone: false,
  templateUrl: './product-inline-board-card.component.html',
  styleUrls: ['./product-inline-board-card.component.scss']
})
export class ProductInlineBoardCardComponent implements OnInit, OnChanges, OnDestroy {
  @Input() presentation?: ToolPresentation;
  @Input() sessionId = '';
  /** False until the host has observed a real terminal event or server idle discovery. */
  @Input() runIdle = false;
  @Input() refreshEpoch = 0;
  @ViewChild('board') set boardFrame(value: ElementRef<HTMLIFrameElement> | undefined) {
    if (this.frame?.nativeElement === value?.nativeElement) return;
    this.frame = value; this.frameReady = false; this.frameLoadedOnce = false; this.pendingReady = false;
    this.attached = false; ++this.generation;
  }
  readonly frameUrl: SafeResourceUrl | null;
  observedRevision = '';
  presentationId = '';
  available = false;
  frameTickets: number[] = [];
  private frameSerial = 0;
  reading = false;
  status = 'Waiting for confirmed idle play.';
  private binding?: BoardBinding;
  private frame?: ElementRef<HTMLIFrameElement>;
  private frameReady = false;
  private frameLoadedOnce = false;
  private pendingReady = false;
  private attached = false;
  private destroyed = false;
  private generation = 0;
  private readEpoch = 0;
  private read?: Subscription;
  private readyTimeout?: ReturnType<typeof setTimeout>;
  private readonly cardId = `inline-board-${++nextCard}`;
  private requestId = '';
  private readonly receive = (event: MessageEvent) => this.receivePanel(event);

  constructor(private readonly http: HttpClient, sanitizer: DomSanitizer) {
    // Deployment configuration is not a descriptor-selected capability. Fail closed off origin.
    // Karma serves the app under a webpack base path, so compare origin only and trust the
    // resolved same-origin URL; production and the sandboxed host check remain unchanged.
    const asset = new URL(INLINE_ASSET, window.location.origin);
    this.frameUrl = asset.origin === window.location.origin
      ? sanitizer.bypassSecurityTrustResourceUrl(asset.href) : null;
  }
  ngOnInit(): void { window.addEventListener('message', this.receive); }
  ngOnChanges(): void {
    this.disconnect();
    const value = this.presentation;
    const validated = validateToolPresentation({ tool: 'show_tactical_board', input: '',
      callId: value?.identity?.callId, status: 'completed', ok: true,
      detail: { displayName: '', presentation: value } },
      { sessionId: this.sessionId, registrations: PRODUCT_INLINE_BOARD_REGISTRATIONS });
    this.binding = validated ? validated.data as unknown as BoardBinding : undefined;
    this.presentationId = validated ? [validated.identity.sessionId, validated.identity.turnId, validated.identity.callId].join(':') : '';
    this.observedRevision = this.binding?.observedRevision || '';
    if (!this.binding || !this.frameUrl) { this.status = 'Board unavailable: invalid binding.'; return; }
    if (!this.runIdle) { this.status = 'Waiting for the current run to finish. No board read was requested.'; return; }
    this.refresh();
  }
  refresh(): void {
    if (this.destroyed || !this.runIdle || !this.binding || !this.frameUrl) return;
    this.disconnect();
    const binding = this.binding, epoch = ++this.readEpoch;
    this.reading = true; this.status = 'Checking the owned play connection…';
    this.read = this.http.get<unknown>(ENDPOINT + '/connection').subscribe({
      next: value => {
        if (!this.current(epoch, binding)) return;
        this.reading = false;
        const connection = value as Record<string, unknown> | null;
        if (!connection || connection['status'] !== 'READY' || connection['runtimeId'] !== binding.runtimeId) {
          this.status = 'Board unavailable: the producing runtime is no longer ready or owned.'; return;
        }
        this.available = true; this.frameTickets = [++this.frameSerial];
        this.status = 'Waiting for the read-only board renderer…';
        this.clearReadyTimeout();
        this.readyTimeout = setTimeout(() => {
          if (this.current(epoch, binding) && !this.frameReady) this.unavailable();
        }, 10000);
      },
      error: () => {
        if (!this.current(epoch, binding)) return;
        this.reading = false; this.status = 'Board unavailable. Refresh after play is idle; no game command was retried.';
      }
    });
  }
  frameLoaded(): void {
    if (this.destroyed || !this.available || !this.runIdle) return;
    // A later navigation/reload shares WindowProxy identity. Recheck ownership and replace the frame.
    if (this.frameLoadedOnce) { this.refresh(); return; }
    this.frameLoadedOnce = true;
    if (this.pendingReady) this.acceptReady();
  }
  private acceptReady(): void {
    if (this.frameReady || !this.frameLoadedOnce) return;
    this.frameReady = true; this.pendingReady = false; this.clearReadyTimeout(); this.post('attach');
  }
  private current(epoch: number, binding: BoardBinding): boolean {
    return !this.destroyed && this.runIdle && epoch === this.readEpoch && this.binding === binding;
  }
  private clearReadyTimeout(): void {
    if (this.readyTimeout !== undefined) clearTimeout(this.readyTimeout);
    this.readyTimeout = undefined;
  }
  private post(type: 'attach' | 'disconnect'): void {
    if (!this.frameReady || !this.binding || !this.frame?.nativeElement.contentWindow) return;
    const binding = this.binding;
    this.requestId = `${this.cardId}-${++this.generation}`;
    this.frame.nativeElement.contentWindow.postMessage({ protocol: PROTOCOL, type,
      requestId: this.requestId, generation: this.generation,
      scope: { campaignId: binding.campaignId, savedGridId: binding.savedGridId }, runtimeId: binding.runtimeId,
      ...(type === 'attach' ? { apiBaseUrl: ENDPOINT + '/domain/' + binding.runtimeId } : {}) }, window.location.origin);
    this.attached = type === 'attach';
  }
  private receivePanel(event: MessageEvent): void {
    if (this.destroyed || !this.runIdle || !this.available || !this.frame || event.origin !== window.location.origin
      || event.source !== this.frame.nativeElement.contentWindow) return;
    const message = event.data;
    if (!message || typeof message !== 'object' || Array.isArray(message) || message.protocol !== PROTOCOL) return;
    if (message.type === 'ready' && Object.keys(message).length === 2) {
      this.pendingReady = true;
      this.acceptReady(); return; // handshake replay alone is not a refresh request
    }
    const binding = this.binding, scope = message.scope;
    if (!this.attached || !binding || message.requestId !== this.requestId || message.generation !== this.generation
      || message.runtimeId !== binding.runtimeId || !scope || typeof scope !== 'object' || Array.isArray(scope)
      || Object.keys(scope).length !== 2 || scope.campaignId !== binding.campaignId || scope.savedGridId !== binding.savedGridId) return;
    if (message.type === 'error') { this.unavailable(); return; }
    if (message.type === 'status' && message.status === 'ready')
      this.status = 'Live read-only board loaded. Current revision is shown inside the board.';
  }
  private unavailable(): void {
    this.disconnect(); this.status = 'Board unavailable. Refresh after play is idle; no game command was retried.';
  }
  private disconnect(): void {
    if (this.attached) this.post('disconnect');
    ++this.readEpoch; ++this.generation; this.read?.unsubscribe(); this.read = undefined;
    this.clearReadyTimeout(); this.reading = false; this.available = false; this.frameTickets = []; this.frameReady = false;
    this.frameLoadedOnce = false; this.pendingReady = false; this.attached = false;
  }
  ngOnDestroy(): void {
    this.disconnect(); this.destroyed = true;
    window.removeEventListener('message', this.receive);
    // Local cancellation only: never close, save, activate, move or restart the game.
  }
}
