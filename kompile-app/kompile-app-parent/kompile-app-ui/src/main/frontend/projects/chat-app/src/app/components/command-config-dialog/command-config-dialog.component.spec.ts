import { TestBed, ComponentFixture } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Observable, of, Subject, throwError } from 'rxjs';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { CommandEventData, CommandOutcome } from '@shared/models/api-models';
import { CommandConfigDialogComponent, CommandConfigDialogData } from './command-config-dialog.component';
import { ChatSetupCatalog, ChatSetupSelection } from '../chat-route-fields/chat-route-fields.component';

const auth = [{ id: 'api-key', label: 'API key', acceptsKey: true }];

/** What the CLI answers for this chat: its route under whatever the form already chose. */
function standardCatalog(selection: ChatSetupSelection): ChatSetupCatalog {
  return {
    available: true, session: true,
    defaults: { mode: 'standard', runtime: 'direct', leadMode: 'standard', vendor: 'openai', authMethod: 'api-key',
      ...(selection.vendor === 'anthropic' ? {} : { model: 'gpt-a', thinking: 'high' }), authenticationScope: 'session', saveScope: 'session', fastMode: true,
      ultracode: false, passthroughManaged: false, ...selection },
    profiles: [], modes: [{ id: 'standard', label: 'Standard chat' }, { id: 'passthrough', label: 'Native framework' }],
    runtimes: [{ id: 'direct', label: 'Direct vendor' }], webSupported: true, leadModes: [], passthroughStyles: [],
    frameworks: [], credentials: [{ name: 'work', label: 'Work account' }],
    vendors: [{ id: 'openai', label: 'OpenAI', authMethods: auth }, { id: 'anthropic', label: 'Anthropic', authMethods: auth }],
    models: selection.vendor === 'anthropic' ? [{ id: 'sonnet-test', label: 'Sonnet' }]
      : [{ id: 'gpt-a', label: 'GPT A' }, { id: 'gpt-b', label: 'GPT B' }],
    thinkingOptions: [{ value: '', label: 'Provider default' }, { value: 'high', label: 'High' }],
    workflows: [], judgeProfiles: [], fastModeSupported: true, ultracodeSupported: false
  };
}

function nativeCatalog(selection: ChatSetupSelection): ChatSetupCatalog {
  const codex = selection.passthroughAgent === 'codex';
  return {
    ...standardCatalog({}),
    defaults: { mode: 'passthrough', leadMode: 'passthrough', passthroughAgent: 'opencode', passthroughManaged: true,
      model: codex ? 'gpt-5-codex' : 'zai/glm-5', saveScope: 'session', ...selection },
    frameworks: [{ id: 'opencode', label: 'OpenCode', available: true, webSupported: true },
      { id: 'codex', label: 'Codex', available: true, webSupported: true }],
    passthroughStyles: [{ managed: true, label: 'Managed' }],
    models: codex ? [{ id: 'gpt-5-codex', label: 'GPT-5 Codex' }]
      : [{ id: 'zai/glm-5', label: 'GLM 5' }, { id: 'zai/glm-4', label: 'GLM 4' }],
    thinkingOptions: [{ value: '', label: 'framework default' }, { value: 'max', label: 'max' }]
  };
}

/** The catalog the CLI answers for whichever mode the request names. */
function byMode(selection: ChatSetupSelection): ChatSetupCatalog {
  return selection.mode === 'passthrough' ? nativeCatalog(selection) : standardCatalog(selection);
}

describe('Session configuration: the CLI setup wizard for this chat', () => {
  let fixture: ComponentFixture<CommandConfigDialogComponent>;
  let outcomes: Subject<CommandOutcome>;
  let setupSession: jasmine.Spy;
  let getSessionConfig: jasmine.Spy;
  let data: CommandConfigDialogData;

  async function open(model: CommandEventData, catalog: (selection: ChatSetupSelection) => ChatSetupCatalog,
                      update: () => Observable<unknown> = () => of({ ok: true, framework: 'standard', model: 'gpt-b' }),
                      snapshot: () => Observable<unknown> = () => of({ menu: 'config', available: true, model })) {
    outcomes = new Subject<CommandOutcome>();
    data = {
      modelMenu: null, roleMenu: null, fastMenu: null, ultracodeMenu: null,
      reminders: null, remindersGlobal: null, loops: null, loopsGlobal: null,
      queue: null, continueMenu: null, judgeMenu: null,
      sessionId: 'chat-1', workingDirectory: '/project',
      busy: () => false, liveSession: () => false,
      dispatch: jasmine.createSpy('dispatch'),
      selectRole: jasmine.createSpy('selectRole'), toggleFastMode: jasmine.createSpy('toggleFastMode'),
      toggleUltracode: jasmine.createSpy('toggleUltracode'), clearConversation: jasmine.createSpy('clearConversation'),
      routeUpdated: jasmine.createSpy('routeUpdated')
    };
    getSessionConfig = jasmine.createSpy('getSessionConfig').and.callFake(snapshot);
    setupSession = jasmine.createSpy('setupSession').and.callFake(
      (_id: string, _directory: string, action: string, selection: ChatSetupSelection) =>
        action === 'catalog' ? of(catalog(selection)) : update());
    await TestBed.configureTestingModule({
      imports: [CommandConfigDialogComponent, NoopAnimationsModule],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close: jasmine.createSpy('close') } },
        { provide: LocalAgentChatService, useValue: {
          getCommandOutcomes: () => outcomes, getSessionConfig, setupSession
        } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(CommandConfigDialogComponent);
    fixture.detectChanges();
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function element(selector: string): HTMLInputElement & HTMLSelectElement {
    return fixture.nativeElement.querySelector(selector);
  }

  async function choose(selector: string, value: string): Promise<void> {
    const select = element(selector);
    select.value = value;
    select.dispatchEvent(new Event('change'));
    await settle();
  }

  function lastCatalogRequest(): ChatSetupSelection {
    const calls = setupSession.calls.all().filter(call => call.args[2] === 'catalog');
    return calls[calls.length - 1].args[3];
  }

  afterEach(() => fixture.destroy());

  function progressShown(): boolean {
    return !element('[data-testid="command-config-progress"]').classList.contains('cc-progress-idle');
  }

  it('shows what it is waiting on for every CLI round trip instead of sitting silently', async () => {
    const snapshot = new Subject<unknown>();
    await open({ menu: 'model', provider: 'openai' }, standardCatalog, undefined, () => snapshot);
    expect(progressShown()).toBeTrue();
    expect(element('[data-testid="command-config-loading"]').textContent).toContain('Loading');
    snapshot.next({ menu: 'config', available: true, model: { menu: 'model', provider: 'openai' } });
    snapshot.complete();
    await settle();
    expect(element('[data-testid="command-config-loading"]')).toBeNull();
    expect(progressShown()).toBeFalse();

    // A field change rediscovers through the CLI: the bar and the route section say so until it answers.
    const catalog = new Subject<ChatSetupCatalog>();
    setupSession.and.callFake((_id: string, _directory: string, action: string) =>
      action === 'catalog' ? catalog : of({ ok: true, framework: 'standard', model: 'sonnet-test' }));
    await choose('select[name="vendor"]', 'anthropic');
    expect(progressShown()).toBeTrue();
    expect(element('[data-testid="session-route-loading"]').textContent).toContain('Updating');
    catalog.next(standardCatalog({ vendor: 'anthropic' }));
    catalog.complete();
    await settle();
    expect(element('[data-testid="session-route-loading"]')).toBeNull();
    expect(progressShown()).toBeFalse();

    // Apply, then the reload of the sections it changed.
    const applied = new Subject<unknown>();
    const refreshed = new Subject<unknown>();
    setupSession.and.callFake((_id: string, _directory: string, action: string, selection: ChatSetupSelection) =>
      action === 'catalog' ? of(standardCatalog(selection)) : applied);
    await choose('select[name="modelChoice"]', 'sonnet-test');
    expect(progressShown()).toBeFalse();
    getSessionConfig.and.returnValue(refreshed);
    element('[data-testid="session-route-apply"]').click();
    await settle();
    expect(element('[data-testid="session-route-loading"]').textContent).toContain('Applying');
    expect(progressShown()).toBeTrue();
    applied.next({ ok: true, framework: 'standard', model: 'sonnet-test' });
    applied.complete();
    await settle();
    expect(element('[data-testid="session-route-loading"]')).toBeNull();
    expect(progressShown()).toBeTrue();
    refreshed.next({ menu: 'config', available: true, model: { menu: 'model', provider: 'anthropic' } });
    refreshed.complete();
    await settle();
    expect(progressShown()).toBeFalse();
  });

  it('seeds the full route form from the chat and keeps every other section', async () => {
    await open({ menu: 'model', provider: 'openai' }, standardCatalog);
    expect(setupSession).toHaveBeenCalledWith('chat-1', '/project', 'catalog', jasmine.any(Object));
    expect(element('select[name="runtime"]').value).toBe('direct');
    expect(element('select[name="vendor"]').value).toBe('openai');
    expect(element('select[name="auth"]').value).toBe('api-key');
    expect(element('select[name="modelChoice"]').value).toBe('gpt-a');
    expect(element('select[name="thinking"]').value).toBe('high');
    expect(element('select[name="credential"]')).not.toBeNull();
    expect(element('input[name="apiKey"]')).not.toBeNull();
    // Fast mode and ultracode keep their own immediate controls below; the route form does not duplicate them.
    expect(element('input[name="fast"]')).toBeNull();
    const text = fixture.nativeElement.textContent;
    for (const label of ['Standard chat', 'Work account', 'GPT B', 'Role', 'Fast mode', 'Ultracode', 'Reminders',
      'Loops', 'Queue', 'Continue', 'Judge']) expect(text).toContain(label);
  });

  it('rediscovers on a vendor change without a typed key and applies the route with it', async () => {
    await open({ menu: 'model', provider: 'openai' }, standardCatalog);
    const key = element('input[name="apiKey"]');
    key.value = 'typed-secret';
    key.dispatchEvent(new Event('input'));
    await choose('select[name="vendor"]', 'anthropic');
    expect(element('select[name="vendor"]').value).toBe('anthropic');
    const rediscovery = lastCatalogRequest();
    expect(rediscovery.vendor).toBe('anthropic');
    expect(rediscovery.model).toBeUndefined();
    expect(rediscovery.apiKey).toBeUndefined();
    expect(JSON.stringify(setupSession.calls.allArgs())).not.toContain('typed-secret');

    await choose('select[name="modelChoice"]', 'sonnet-test');
    // The chosen model stays selected once its rediscovery answers, and its effort levels can be chosen.
    expect(element('select[name="modelChoice"]').value).toBe('sonnet-test');
    expect(element('select[name="thinking"]').disabled).toBeFalse();
    await choose('select[name="thinking"]', 'high');
    expect(fixture.componentInstance.route.thinking).toBe('high');
    expect(element('select[name="modelChoice"]').value).toBe('sonnet-test');
    const typed = element('input[name="apiKey"]');
    typed.value = 'typed-secret';
    typed.dispatchEvent(new Event('input'));
    await settle();
    getSessionConfig.calls.reset();
    element('[data-testid="session-route-apply"]').click();
    await settle();
    const update = setupSession.calls.all().find(call => call.args[2] === 'update')!.args[3];
    expect(update).toEqual(jasmine.objectContaining({ vendor: 'anthropic', model: 'sonnet-test', apiKey: 'typed-secret',
      saveScope: 'session', mode: 'standard' }));
    for (const field of ['leadMode', 'workflow', 'profile', 'fastMode', 'ultracode']) expect(update[field]).toBeUndefined();
    expect(data.routeUpdated).toHaveBeenCalled();
    expect(getSessionConfig).toHaveBeenCalled();
    expect(fixture.componentInstance.route.apiKey).toBeUndefined();
    expect(element('[data-testid="session-route-status"]').textContent).toContain('Takes effect on the next turn');
  });

  it('shows the CLI refusal and leaves the selector alone when an update is refused', async () => {
    await open({ menu: 'model', provider: 'openai' }, standardCatalog,
      () => throwError(() => ({ error: { message: "'gpt-z' is not in the live model list" } })));
    element('[data-testid="session-route-apply"]').click();
    await settle();
    expect(element('[data-testid="session-route-error"]').textContent).toContain('live model list');
    expect(data.routeUpdated).not.toHaveBeenCalled();
  });

  it('edits a native chat\'s model and effort like any vendor', async () => {
    await open({ menu: 'model', provider: 'opencode', currentModel: 'zai/glm-5', nativeModelSelection: true }, nativeCatalog);
    expect(element('select[name="agent"]').disabled).toBeFalse();
    expect(element('select[name="managed"]').disabled).toBeFalse();
    expect(element('select[name="modelChoice"]').disabled).toBeFalse();
    expect(element('[data-testid="session-route-mode"]').value).toBe('passthrough');
    expect(fixture.nativeElement.textContent).toContain('GLM 4');
    expect(fixture.nativeElement.textContent).not.toContain('Load roles');
    await choose('select[name="modelChoice"]', 'zai/glm-4');
    await choose('select[name="thinking"]', 'max');
    element('[data-testid="session-route-apply"]').click();
    await settle();
    const update = setupSession.calls.all().find(call => call.args[2] === 'update')!.args[3];
    expect(update).toEqual(jasmine.objectContaining({ mode: 'passthrough', passthroughAgent: 'opencode',
      passthroughManaged: true, model: 'zai/glm-4', thinking: 'max' }));
    expect(update.leadMode).toBeUndefined();
  });

  it('switches a native chat to another framework, rediscovering its models', async () => {
    await open({ menu: 'model', provider: 'opencode', currentModel: 'zai/glm-5', nativeModelSelection: true }, nativeCatalog);
    await choose('select[name="agent"]', 'codex');
    const rediscovery = lastCatalogRequest();
    expect(rediscovery.passthroughAgent).toBe('codex');
    expect(rediscovery.model).toBeUndefined();
    expect(element('select[name="modelChoice"]').value).toBe('gpt-5-codex');
    element('[data-testid="session-route-apply"]').click();
    await settle();
    const update = setupSession.calls.all().find(call => call.args[2] === 'update')!.args[3];
    expect(update).toEqual(jasmine.objectContaining({ mode: 'passthrough', passthroughAgent: 'codex', model: 'gpt-5-codex' }));
  });

  it('switches a standard chat to a native framework from the mode select', async () => {
    await open({ menu: 'model', provider: 'openai' }, byMode);
    const modes = Array.from(element('[data-testid="session-route-mode"]').options).map(o => o.value);
    expect(modes).toEqual(['standard', 'passthrough']);
    await choose('[data-testid="session-route-mode"]', 'passthrough');
    const rediscovery = lastCatalogRequest();
    expect(rediscovery.mode).toBe('passthrough');
    expect(rediscovery.vendor).toBeUndefined();
    expect(rediscovery.model).toBeUndefined();
    expect(element('select[name="agent"]').value).toBe('opencode');
    element('[data-testid="session-route-apply"]').click();
    await settle();
    const update = setupSession.calls.all().find(call => call.args[2] === 'update')!.args[3];
    expect(update).toEqual(jasmine.objectContaining({ mode: 'passthrough', passthroughAgent: 'opencode', model: 'zai/glm-5' }));
  });

  it('reseeds from the chat when the selector above the chat changes the model', async () => {
    await open({ menu: 'model', provider: 'openai' }, standardCatalog);
    setupSession.calls.reset();
    outcomes.next({ ok: true, command: '/model', data: { menu: 'model', state: { model: 'gpt-b' } } } as CommandOutcome);
    await settle();
    expect(setupSession).toHaveBeenCalledWith('chat-1', '/project', 'catalog', jasmine.any(Object));
  });
});
