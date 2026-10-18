import { Component, Input, OnChanges, OnDestroy } from '@angular/core';
import { ToolPresentation } from '@shared/models/api-models';
import { ToolPresentationRegistration, validateToolPresentation } from '@shared/services/markdown-renderer.service';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
interface TrackerEntry { name: string; total: number; player: boolean; turn: number; }
interface TrackerBinding {
  campaignId: number; trackerId: number; runtimeId: string;
  round: number; currentTurn: number; entries: TrackerEntry[];
}

function trackerData(data: Readonly<Record<string, unknown>>): boolean {
  const keys = ['campaignId', 'trackerId', 'runtimeId', 'round', 'currentTurn', 'entries'];
  if (Object.keys(data).length !== keys.length || !keys.every(k => Object.prototype.hasOwnProperty.call(data, k))) return false;
  if (typeof data['campaignId'] !== 'number' || !Number.isSafeInteger(data['campaignId']) || data['campaignId'] < 1) return false;
  if (typeof data['trackerId'] !== 'number' || !Number.isSafeInteger(data['trackerId']) || data['trackerId'] < 1) return false;
  if (typeof data['runtimeId'] !== 'string' || !UUID.test(data['runtimeId'])) return false;
  if (typeof data['round'] !== 'number' || !Number.isSafeInteger(data['round']) || data['round'] < 1) return false;
  if (typeof data['currentTurn'] !== 'number' || !Number.isSafeInteger(data['currentTurn']) || data['currentTurn'] < 0) return false;
  if (!Array.isArray(data['entries']) || (data['entries'] as unknown[]).length === 0
    || (data['entries'] as unknown[]).length > 64) return false;
  return (data['entries'] as unknown[]).every(item => {
    if (!item || typeof item !== 'object') return false;
    const e = item as Record<string, unknown>;
    return typeof e['name'] === 'string' && e['name'].length > 0 && e['name'].length <= 128
      && typeof e['total'] === 'number' && Number.isSafeInteger(e['total'])
      && typeof e['player'] === 'boolean'
      && typeof e['turn'] === 'number' && Number.isSafeInteger(e['turn']);
  });
}

export const PRODUCT_INLINE_INITIATIVE_REGISTRATIONS: readonly ToolPresentationRegistration[] = Object.freeze([
  Object.freeze({ kind: 'elthoria.initiative-tracker', serverId: 'elthoria', toolName: 'show_initiative_tracker', validateData: trackerData })
]);

let nextCard = 0;

/** Read-only initiative order for CURRENT combat; live state, not a historical snapshot. */
@Component({
  selector: 'app-product-inline-initiative-card', standalone: false,
  template: `
    <section class="inline-initiative-card" aria-label="Live read-only initiative tracker" data-testid="inline-initiative-card"
      [attr.data-presentation-id]="presentationId">
      <header>
        <strong>Initiative · Round {{ binding?.round ?? '—' }}</strong>
        <span *ngIf="binding" class="turn-marker">Turn {{ binding.currentTurn + 1 }} of {{ binding.entries.length }}</span>
      </header>
      <p role="status" *ngIf="!binding">Tracker unavailable: invalid binding. No game command was sent.</p>
      <ol *ngIf="binding" class="initiative-list">
        <li *ngFor="let entry of binding.entries; let i = index"
          [class.current]="i === binding.currentTurn" [class.player]="entry.player">
          <span class="init-total">{{ entry.total }}</span>
          <span class="init-name">{{ entry.name }}</span>
          <span class="init-badge" *ngIf="entry.player">PC</span>
          <span class="init-now" *ngIf="i === binding.currentTurn">◀ acting</span>
        </li>
      </ol>
      <p class="hint">Live read-only order. Turn advancement requires an explicit GM command.</p>
    </section>
  `,
  styles: [`:host{display:block}
    .inline-initiative-card{border:1px solid #455166;border-radius:4px;padding:.5rem;background:#1a2330}
    header{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:.4rem}
    .turn-marker{font-size:.85rem;color:#9ab}
    .initiative-list{list-style:none;margin:0;padding:0}
    .initiative-list li{display:flex;align-items:center;gap:.5rem;padding:.2rem .3rem;border-radius:3px}
    .initiative-list li.current{background:#2a3a4d;font-weight:600}
    .init-total{min-width:2.2rem;text-align:right;font-variant-numeric:tabular-nums;color:#cda}
    .init-name{flex:1}
    .init-badge{font-size:.7rem;color:#7ac;border:1px solid #7ac;border-radius:2px;padding:0 .25rem}
    .init-now{font-size:.8rem;color:#fd6}
    .hint{margin-top:.4rem;font-size:.75rem;color:#789}`]
})
export class ProductInlineInitiativeCardComponent implements OnChanges, OnDestroy {
  @Input() presentation?: ToolPresentation;
  @Input() sessionId = '';
  @Input() runIdle = false;
  binding: TrackerBinding | undefined;
  presentationId = '';
  private readonly cardId = `initiative-${++nextCard}`;
  private destroyed = false;

  ngOnChanges(): void {
    if (this.destroyed) return;
    const value = this.presentation;
    const validated = validateToolPresentation({ tool: 'show_initiative_tracker', input: '',
      callId: value?.identity?.callId, status: 'completed', ok: true,
      detail: { displayName: '', presentation: value } },
      { sessionId: this.sessionId, registrations: PRODUCT_INLINE_INITIATIVE_REGISTRATIONS });
    this.binding = validated ? validated.data as unknown as TrackerBinding : undefined;
    this.presentationId = validated ? [validated.identity.sessionId, validated.identity.turnId, validated.identity.callId].join(':') : '';
  }

  ngOnDestroy(): void { this.destroyed = true; }
}
