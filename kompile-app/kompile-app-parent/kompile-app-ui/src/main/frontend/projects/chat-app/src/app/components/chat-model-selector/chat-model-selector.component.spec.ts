import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { CommandEventData } from '@shared/models/api-models';
import { LocalAgentChatService, SessionConfigSnapshot } from '@shared/services/local-agent-chat.service';
import { ChatModelSelectorComponent } from './chat-model-selector.component';

const menu: CommandEventData = {
  menu: 'model', provider: 'openai', currentModel: 'm1',
  vendors: [{ vendor: 'openai', display: 'OpenAI', current: true }, { vendor: 'anthropic', display: 'Anthropic' }],
  models: [{ id: 'm1', display: 'Model One', current: true }, { id: 'm2' }]
};

function snapshot(model: CommandEventData): SessionConfigSnapshot {
  return { menu: 'config', available: true, model };
}

describe('Chat model/vendor dropdowns', () => {
  let fixture: ComponentFixture<ChatModelSelectorComponent>;
  let component: ChatModelSelectorComponent;
  let service: jasmine.SpyObj<LocalAgentChatService>;
  let selected: jasmine.Spy;

  beforeEach(async () => {
    service = jasmine.createSpyObj('LocalAgentChatService', ['getSessionConfig']);
    service.getSessionConfig.and.returnValue(of(snapshot(menu)));
    await TestBed.configureTestingModule({
      imports: [ChatModelSelectorComponent],
      providers: [{ provide: LocalAgentChatService, useValue: service }]
    }).compileComponents();
    fixture = TestBed.createComponent(ChatModelSelectorComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('sessionId', 'chat-1');
    fixture.componentRef.setInput('workingDirectory', '/project');
    selected = jasmine.createSpy('modelSelected');
    component.modelSelected.subscribe(selected);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => fixture.destroy());

  function change(label: string, value: string): void {
    const select = fixture.nativeElement.querySelector(`select[aria-label="${label}"]`) as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('quietly loads the existing catalog into two accessible dropdowns', () => {
    expect(service.getSessionConfig).toHaveBeenCalledWith('chat-1', '/project', undefined);
    expect(selected).not.toHaveBeenCalled();
    // Vendor, model and effort: the effort control shows even before the route reports any levels.
    expect(fixture.nativeElement.querySelectorAll('select').length).toBe(3);
    expect(fixture.nativeElement.querySelector('[aria-label="Thinking effort"]').disabled).toBeTrue();
    expect(fixture.nativeElement.querySelector('[aria-label="Model vendor"]').value).toBe('openai');
    expect(fixture.nativeElement.querySelector('[aria-label="Chat model"]').value).toBe('m1');
    expect(fixture.nativeElement.textContent).toContain('Model One');
    change('Chat model', 'm2');
    expect(selected).toHaveBeenCalledWith('m2');
  });

  it('browses vendors without dispatch and scopes only the eventual model choice', async () => {
    service.getSessionConfig.and.returnValue(of(snapshot({ ...menu, provider: 'anthropic',
      vendor: 'anthropic', currentVendor: 'anthropic', models: [{ id: 'claude' }] })));
    change('Model vendor', 'anthropic');
    await fixture.whenStable();
    expect(service.getSessionConfig).toHaveBeenCalledWith('chat-1', '/project', 'anthropic');
    expect(selected).not.toHaveBeenCalled();
    expect(component.modelChoice).toBe('');
    change('Chat model', 'claude');
    expect(selected).toHaveBeenCalledWith('anthropic:claude');
  });

  it('reloads applied state quietly instead of retaining the previously browsed vendor', () => {
    service.getSessionConfig.and.returnValue(of(snapshot({ ...menu, provider: 'anthropic', currentModel: 'claude',
      vendors: [{ vendor: 'openai' }, { vendor: 'anthropic', current: true }], models: [{ id: 'claude', current: true }] })));
    fixture.componentRef.setInput('outcome', { command: '/model', ok: true,
      data: { menu: 'model', state: { model: 'claude', provider: 'anthropic' } } });
    fixture.detectChanges();
    expect(component.selectedVendor).toBe('anthropic');
    expect(component.modelChoice).toBe('claude');
    expect(service.getSessionConfig.calls.mostRecent().args).toEqual(['chat-1', '/project', undefined]);
    expect(selected).not.toHaveBeenCalled();
  });

  it('restores the applied model after a rejected CLI selection', async () => {
    change('Chat model', 'm2');
    expect(selected).toHaveBeenCalledWith('m2');
    fixture.componentRef.setInput('outcome', { command: '/model m2', ok: false, text: 'Unknown model' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.modelChoice).toBe('m1');
    expect(fixture.nativeElement.querySelector('[aria-label="Chat model"]').value).toBe('m1');
  });

  it('does not let an older quiet snapshot overwrite a newer conversation menu', () => {
    const stale = new Subject<SessionConfigSnapshot>();
    service.getSessionConfig.and.returnValue(stale);
    component.refreshModels();
    fixture.componentRef.setInput('outcome', { command: '/model', ok: true,
      data: { ...menu, currentModel: 'm2', models: [{ id: 'm2', current: true }] } });
    fixture.detectChanges();
    stale.next(snapshot(menu));
    expect(component.modelChoice).toBe('m2');
    expect(component.loading).toBeFalse();
    expect(stale.observed).toBeFalse();
  });

  it('cancels stale conversation requests and scopes the replacement snapshot', () => {
    const stale = new Subject<SessionConfigSnapshot>();
    service.getSessionConfig.and.returnValue(stale);
    component.refreshModels();
    service.getSessionConfig.and.returnValue(of(snapshot({ ...menu, currentModel: 'm2', models: [{ id: 'm2', current: true }] })));
    fixture.componentRef.setInput('sessionId', 'chat-2');
    fixture.componentRef.setInput('workingDirectory', '/other');
    fixture.detectChanges();
    stale.next(snapshot(menu));
    expect(component.modelChoice).toBe('m2');
    expect(service.getSessionConfig).toHaveBeenCalledWith('chat-2', '/other', undefined);
    expect(stale.observed).toBeFalse();
  });

  it('disables selections while the parent is busy or read-only', async () => {
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(Array.from(fixture.nativeElement.querySelectorAll('select')).every((select: any) => select.disabled)).toBeTrue();
    component.pickVendor('anthropic');
    component.selectModel('m2');
    expect(service.getSessionConfig.calls.count()).toBe(1);
    expect(selected).not.toHaveBeenCalled();
  });

  it('disables selections during catalog loading and recovers after an error', async () => {
    const pending = new Subject<SessionConfigSnapshot>();
    service.getSessionConfig.and.returnValue(pending);
    change('Model vendor', 'anthropic');
    await fixture.whenStable();
    component.selectModel('m2');
    expect(selected).not.toHaveBeenCalled();
    pending.error(new Error('offline'));
    fixture.detectChanges();
    expect(component.loading).toBeFalse();
    await fixture.whenStable();
    expect(component.selectedVendor).toBe('openai');
    expect(fixture.nativeElement.querySelector('[aria-label="Model vendor"]').value).toBe('openai');
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Retry');
    service.getSessionConfig.and.returnValue(of(snapshot(menu)));
    component.refreshModels();
    expect(component.loadError).toBeNull();
  });

  it('shows unavailable snapshot errors without dispatching a command', () => {
    service.getSessionConfig.and.returnValue(of({ menu: 'config', available: false, status: 'Not configured' }));
    component.refreshModels();
    expect(component.loadError).toBe('Not configured');
    expect(selected).not.toHaveBeenCalled();
  });

  it('renders provider thinking options and emits selections including the default', async () => {
    service.getSessionConfig.and.returnValue(of({ ...snapshot(menu), thinking: {
      menu: 'thinking', supported: true, currentThinking: 'high',
      thinkingOptions: [{ value: '', label: 'Provider default' }, { value: 'high', label: 'High effort' }]
    } }));
    const thinkingSelected = jasmine.createSpy('thinkingSelected');
    component.thinkingSelected.subscribe(thinkingSelected);
    component.refreshModels();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[aria-label="Thinking effort"]').value).toBe('high');
    change('Thinking effort', '');
    expect(thinkingSelected).toHaveBeenCalledWith('');
    expect(selected).not.toHaveBeenCalled();
    component.selectThinking('imaginary');
    expect(thinkingSelected.calls.count()).toBe(1);
  });

  it('reloads thinking after CLI success or rejection rather than retaining optimistic state', () => {
    service.getSessionConfig.and.returnValue(of({ ...snapshot(menu), thinking: {
      menu: 'thinking', supported: true, currentThinking: 'high',
      thinkingOptions: [{ value: '', label: 'Default' }, { value: 'high', label: 'High' }]
    } }));
    component.refreshModels();
    component.selectThinking('');
    fixture.componentRef.setInput('outcome', { command: '/thinking default', ok: false });
    fixture.detectChanges();
    expect(component.thinkingChoice).toBe('high');
    fixture.componentRef.setInput('outcome', { command: '/thinking', ok: true, data: { menu: 'thinking' } });
    fixture.detectChanges();
    expect(service.getSessionConfig.calls.count()).toBe(4);
    component.selectThinking('');
    const pending = new Subject<any>();
    service.getSessionConfig.and.returnValue(pending.asObservable());
    component.refreshModels();
    pending.error(new Error('offline'));
    expect(component.thinkingChoice).toBe('high');
  });

  it('does not apply thinking while busy, loading, or browsing another vendor', () => {
    service.getSessionConfig.and.returnValue(of({ ...snapshot(menu), thinking: {
      menu: 'thinking', supported: true, thinkingOptions: [{ value: 'high', label: 'High' }]
    } }));
    component.refreshModels();
    const thinkingSelected = jasmine.createSpy('thinkingSelected');
    component.thinkingSelected.subscribe(thinkingSelected);
    component.busy = true;
    component.selectThinking('high');
    component.busy = false;
    component.loading = true;
    component.selectThinking('high');
    component.loading = false;
    component.selectedVendor = 'anthropic';
    component.selectThinking('high');
    expect(thinkingSelected).not.toHaveBeenCalled();
  });

  it('clears thinking controls when switching to a conversation without support', () => {
    service.getSessionConfig.and.returnValue(of({ ...snapshot(menu), thinking: {
      menu: 'thinking', supported: true, currentThinking: 'high'
    } }));
    component.refreshModels();
    service.getSessionConfig.and.returnValue(of(snapshot(menu)));
    fixture.componentRef.setInput('sessionId', 'other-session');
    fixture.detectChanges();
    expect(component.thinkingMenu).toBeNull();
    expect(component.thinkingChoice).toBe('');
    const effort = fixture.nativeElement.querySelector('[aria-label="Thinking effort"]') as HTMLSelectElement;
    expect(effort.disabled).toBeTrue();
    expect(effort.textContent).toContain('Not offered');
  });

  it('shows a native framework\'s effort levels and says why a model has none', async () => {
    service.getSessionConfig.and.returnValue(of({ ...snapshot({ menu: 'model', provider: 'opencode',
      currentModel: 'zai/glm-5', nativeModelSelection: true, models: [{ id: 'zai/glm-5', current: true }] }), thinking: {
      menu: 'thinking', supported: true, currentThinking: 'max', provider: 'opencode',
      thinkingOptions: [{ value: '', label: 'framework default' }, { value: 'max', label: 'max' }] } }));
    component.refreshModels();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const effort = fixture.nativeElement.querySelector('[aria-label="Thinking effort"]') as HTMLSelectElement;
    expect(effort.disabled).toBeFalse();
    expect(effort.value).toBe('max');
    const thinkingSelected = jasmine.createSpy('thinkingSelected');
    component.thinkingSelected.subscribe(thinkingSelected);
    change('Thinking effort', '');
    expect(thinkingSelected).toHaveBeenCalledWith('');

    service.getSessionConfig.and.returnValue(of({ ...snapshot({ menu: 'model', provider: 'opencode',
      currentModel: 'zai/glm-4', nativeModelSelection: true, models: [{ id: 'zai/glm-4', current: true }] }), thinking: {
      menu: 'thinking', supported: false, thinkingOptions: [], note: 'opencode lists no effort levels for zai/glm-4.' } }));
    component.refreshModels();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(effort.disabled).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('opencode lists no effort levels for zai/glm-4.');
  });

  it('selects native framework models only from the live list', async () => {
    service.getSessionConfig.and.returnValue(of(snapshot({ menu: 'model', provider: 'opencode',
      currentModel: 'zai/glm-5', nativeModelSelection: true, models: [{ id: 'zai/glm-4' }] })));
    component.refreshModels();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[aria-label="Chat model"]').value).toBe('zai/glm-5');
    expect(fixture.nativeElement.querySelector('input')).toBeNull();
    const options = Array.from(fixture.nativeElement.querySelectorAll('[aria-label="Chat model"] option'))
      .map((option: any) => option.value);
    expect(options).toEqual(['zai/glm-5', 'zai/glm-4']);
    component.selectModel('typed/unlisted');
    expect(selected).not.toHaveBeenCalled();
    change('Chat model', 'zai/glm-4');
    expect(selected).toHaveBeenCalledWith('zai/glm-4');
  });

  it('switches a native chat to another framework like any vendor', async () => {
    const vendors = [{ vendor: 'opencode', display: 'OpenCode', current: true }, { vendor: 'codex', display: 'Codex' }];
    service.getSessionConfig.and.returnValue(of(snapshot({ menu: 'model', provider: 'opencode',
      currentModel: 'zai/glm-5', nativeModelSelection: true, vendors, models: [{ id: 'zai/glm-5', current: true }] })));
    component.refreshModels();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[aria-label="Model vendor"]').disabled).toBeFalse();
    expect(fixture.nativeElement.querySelector('[aria-label="Model vendor"]').value).toBe('opencode');
    service.getSessionConfig.and.returnValue(of(snapshot({ menu: 'model', provider: 'opencode', vendor: 'codex',
      currentModel: 'zai/glm-5', nativeModelSelection: true, vendors, models: [{ id: 'gpt-5-codex' }] })));
    change('Model vendor', 'codex');
    await fixture.whenStable();
    expect(service.getSessionConfig.calls.mostRecent().args).toEqual(['chat-1', '/project', 'codex']);
    change('Chat model', 'gpt-5-codex');
    expect(selected).toHaveBeenCalledWith('codex:gpt-5-codex');
  });

  it('polls the chat\'s route so an effort set elsewhere stays shown', async () => {
    const thinking = (current: string): SessionConfigSnapshot => ({ ...snapshot(menu), thinking: { menu: 'thinking', supported: true,
      currentThinking: current, thinkingOptions: [{ value: '', label: 'Default' }, { value: 'max', label: 'Max' }] } });
    service.getSessionConfig.and.returnValue(of(thinking('max')));
    component.refreshModels();
    fixture.detectChanges();
    expect(component.thinkingChoice).toBe('max');
    // Another tab or a typed /thinking default changes the route; the next poll shows it.
    service.getSessionConfig.and.returnValue(of(thinking('')));
    component.poll();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.thinkingChoice).toBe('');
    expect(component.loading).toBeFalse();
    expect(service.getSessionConfig.calls.mostRecent().args).toEqual(['chat-1', '/project']);
    // A finished turn polls too.
    service.getSessionConfig.and.returnValue(of(thinking('max')));
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    fixture.componentRef.setInput('busy', false);
    fixture.detectChanges();
    expect(component.thinkingChoice).toBe('max');
  });

  it('does not poll over a vendor being browsed or while a turn runs', () => {
    component.selectedVendor = 'anthropic';
    const calls = service.getSessionConfig.calls.count();
    component.poll();
    component.selectedVendor = 'openai';
    component.busy = true;
    component.poll();
    expect(service.getSessionConfig.calls.count()).toBe(calls);
  });

  it('polls on an interval and stops when destroyed', () => {
    jasmine.clock().install();
    try {
      const polled = TestBed.createComponent(ChatModelSelectorComponent);
      polled.componentRef.setInput('sessionId', 'chat-9');
      polled.componentRef.setInput('workingDirectory', '/project');
      polled.detectChanges();
      const poll = spyOn(polled.componentInstance, 'poll');
      jasmine.clock().tick(ChatModelSelectorComponent.POLL_MS);
      expect(poll).toHaveBeenCalledTimes(1);
      polled.destroy();
      jasmine.clock().tick(ChatModelSelectorComponent.POLL_MS * 2);
      expect(poll).toHaveBeenCalledTimes(1);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('says when the provider was unreachable and the list is its last known good catalog', async () => {
    expect(fixture.nativeElement.querySelector('[data-testid="model-catalog-note"]')).toBeNull();
    service.getSessionConfig.and.returnValue(of(snapshot({ ...menu, liveListingAvailable: false,
      note: 'Live discovery failed (timeout). Showing the last known good OpenAI catalog from 2h ago.' })));
    component.refreshModels();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[data-testid="model-catalog-note"]').textContent)
      .toContain('last known good');
    expect(fixture.nativeElement.querySelectorAll('[aria-label="Chat model"] option').length).toBe(2);
  });
});

/** The chat view is OnPush, like the real one: nothing else re-checks it when the catalog lands. */
@Component({
  standalone: true,
  imports: [ChatModelSelectorComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<app-chat-model-selector sessionId="chat-1" workingDirectory="/project"></app-chat-model-selector>'
})
class OnPushChatHost {}

describe('Chat model/vendor dropdowns inside an OnPush chat view', () => {
  it('renders a catalog that arrives after the first check', async () => {
    const reply = new Subject<SessionConfigSnapshot>();
    const service = jasmine.createSpyObj<LocalAgentChatService>('LocalAgentChatService', ['getSessionConfig']);
    service.getSessionConfig.and.returnValue(reply);
    await TestBed.configureTestingModule({
      imports: [OnPushChatHost],
      providers: [{ provide: LocalAgentChatService, useValue: service }]
    }).compileComponents();
    const host = TestBed.createComponent(OnPushChatHost);
    host.detectChanges();
    expect(host.nativeElement.textContent).toContain('Loading models…');

    reply.next(snapshot(menu));
    reply.complete();
    host.detectChanges();
    await host.whenStable();

    expect(host.nativeElement.textContent).not.toContain('Loading models…');
    expect(host.nativeElement.querySelector('[aria-label="Model vendor"]').value).toBe('openai');
    expect(host.nativeElement.querySelector('[aria-label="Chat model"]').value).toBe('m1');
    host.destroy();
  });
});
