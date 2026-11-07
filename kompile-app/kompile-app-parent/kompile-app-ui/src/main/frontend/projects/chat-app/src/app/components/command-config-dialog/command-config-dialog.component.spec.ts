import { TestBed, ComponentFixture } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Observable, of, Subject, throwError } from 'rxjs';
import { LocalAgentChatService, SessionConfigSnapshot } from '@shared/services/local-agent-chat.service';
import { CommandEventData, CommandOutcome, ModelRung } from '@shared/models/api-models';
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

  const rung = (model: string): ModelRung => ({ provider: 'saved-provider', model, credentialName: 'work',
    label: null, thinking: 'high', authenticationMethod: 'api-key' });

  function policySnapshot(): SessionConfigSnapshot {
    return { menu: 'config', available: true, model: { menu: 'model', provider: 'openai' },
      subagents: { menu: 'subagent-config', maxConcurrent: 2, bannedModels: ['banned-model'], activeCount: null,
        activeCountScope: 'See the owning run’s Activity panel; admission is per JVM.',
        failover: { enabled: true, rungs: [rung('child-a')] } },
      failover: { menu: 'failover', scope: 'session', enabled: false, rungs: [rung('lead-a'), rung('lead-b')] } };
  }

  function fallbackCatalog(selection: ChatSetupSelection): ChatSetupCatalog {
    const catalog = standardCatalog(selection);
    return { ...catalog, fallbackRoute: { provider: 'canonical-from-backend', authenticationMethod: selection.authMethod || 'api-key' },
      vendors: catalog.vendors.map(vendor => ({ ...vendor, endpointRequired: true,
        authMethods: [...auth, { id: 'oauth', label: 'Existing login', signIn: true }] })) };
  }

  async function openPolicies(catalog = fallbackCatalog): Promise<void> {
    await open({ menu: 'model', provider: 'openai' }, catalog, undefined, () => of(policySnapshot()));
  }

  async function input(selector: string, value: string): Promise<void> {
    element(selector).value = value;
    element(selector).dispatchEvent(new Event('input'));
    await settle();
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

  it('shows saved policy and per-process concurrency without inventing a live child count', async () => {
    await openPolicies();
    expect(element('[data-testid="subagent-persisted"]').textContent).toContain('per process/JVM 2');
    expect(element('[data-testid="subagent-policy-config"]').textContent).toContain('Activity panel');
    expect(element('[data-testid="subagent-policy-config"]').textContent).toContain('banned-model');
    expect(element('[data-testid="failover-persisted-lead"]').textContent).toContain('OFF');
    expect(element('[data-testid="failover-persisted-child"]').textContent).toContain('ON');
    expect(element('[data-testid="failover-child"]').textContent).toContain('child-a');
    expect(data.dispatch).not.toHaveBeenCalled();
  });

  it('cancels concurrency and ban drafts without changing saved state or dispatching', async () => {
    await openPolicies();
    element('[data-testid="subagent-edit"]').click();
    await settle();
    await input('[data-testid="subagent-max"]', '0');
    await input('[data-testid="subagent-bans"]', 'new-ban');
    expect(element('[data-testid="failover-edit-lead"]').disabled).toBeTrue();
    element('[data-testid="subagent-cancel"]').click();
    await settle();
    expect(data.dispatch).not.toHaveBeenCalled();
    expect(element('[data-testid="subagent-persisted"]').textContent).toContain('2');
    element('[data-testid="subagent-edit"]').click();
    await settle();
    expect(element('[data-testid="subagent-max"]').value).toBe('2');
    expect(element('[data-testid="subagent-bans"]').value).toBe('banned-model');
  });

  it('validates concurrency and atomically saves disabled/unlimited limits and bans without secrets', async () => {
    await openPolicies();
    for (const max of [0, -1]) {
      element('[data-testid="subagent-edit"]').click();
      await settle();
      for (const invalid of ['', '-2', '1.5']) {
        await input('[data-testid="subagent-max"]', invalid);
        expect(element('[data-testid="subagent-save"]').disabled).toBeTrue();
      }
      await input('[data-testid="subagent-max"]', String(max));
      await input('[data-testid="subagent-bans"]', ' first-model \nsecond-model\nfirst-model\n');
      // Unknown future fields from a snapshot are not permission to serialize credential material.
      fixture.componentInstance.subagents!.failover!.rungs[0] = Object.assign(rung('child-a'), { apiKey: 'saved-secret', token: 'saved-token' });
      element('[data-testid="subagent-save"]').click();
      await settle();
      const command = (data.dispatch as jasmine.Spy).calls.mostRecent().args[0] as string;
      expect(command.startsWith('/subagent-config save ')).toBeTrue();
      expect(JSON.parse(command.substring('/subagent-config save '.length))).toEqual({ maxConcurrent: max,
        bannedModels: ['first-model', 'second-model'], failoverEnabled: true, failoverRungs: [rung('child-a')] });
      expect(command).not.toContain('secret');
      expect(command).not.toContain('token');
      expect(fixture.componentInstance.policySaving).toBe('subagents');
      const saved = policySnapshot();
      saved.subagents!.maxConcurrent = max;
      saved.subagents!.bannedModels = ['first-model', 'second-model'];
      getSessionConfig.and.returnValue(of(saved));
      outcomes.next({ command: '/subagent-config', ok: true, data: saved.subagents } as CommandOutcome);
      await settle();
      expect(element('[data-testid="subagent-max"]')).toBeNull();
      expect(element('[data-testid="subagent-persisted"]').textContent).toContain(max === 0 ? 'disabled' : 'unlimited');
    }
  });

  it('uses canonical catalog routes, orders a main draft and saves only credential references', async () => {
    await openPolicies();
    element('[data-testid="failover-edit-lead"]').click();
    await settle();
    const picker = '[data-testid="fallback-route-fields"]';
    for (const secretInput of ['input[name="apiKey"]', 'input[name="baseUrl"]', 'select[name="authScope"]']) {
      expect(element(picker + ' ' + secretInput)).toBeNull();
    }
    expect(element(picker + ' select[name="credential"]')).not.toBeNull();
    await choose(picker + ' select[name="auth"]', 'oauth');
    await choose(picker + ' select[name="credential"]', 'work');
    await choose(picker + ' select[name="modelChoice"]', 'gpt-b');
    await choose(picker + ' select[name="thinking"]', 'high');
    // Neither discovery nor a saved rung can inherit secret-only wizard fields.
    Object.assign(fixture.componentInstance.fallbackRoute, { apiKey: 'typed-secret', token: 'typed-token',
      refreshToken: 'typed-refresh', baseUrl: 'https://private-endpoint' });
    fixture.componentInstance.loadFallbackCatalog();
    await settle();
    const catalogRequest = lastCatalogRequest();
    expect(catalogRequest).toEqual({ mode: 'standard', runtime: 'direct', vendor: 'openai', authMethod: 'oauth',
      credentialName: 'work', model: 'gpt-b', thinking: 'high' });
    element('[data-testid="failover-add"]').click();
    fixture.componentInstance.moveRung(2, -1);
    fixture.componentInstance.moveRung(1, -1);
    fixture.componentInstance.removeRung(1);
    element('[data-testid="failover-enabled"]').click();
    await settle();
    expect(fixture.componentInstance.failover!.rungs!.map(r => r.model)).toEqual(['lead-a', 'lead-b']);
    element('[data-testid="failover-save-lead"]').click();
    await settle();
    const command = (data.dispatch as jasmine.Spy).calls.mostRecent().args[0] as string;
    const expected = { enabled: true, rungs: [{ provider: 'canonical-from-backend', model: 'gpt-b', credentialName: 'work',
      label: 'GPT B', thinking: 'high', authenticationMethod: 'oauth' }, rung('lead-b')] };
    expect(command).toBe('/model failover save ' + JSON.stringify(expected));
    for (const secret of ['typed-secret', 'typed-token', 'typed-refresh', 'private-endpoint', 'apiKey', 'baseUrl']) {
      expect(command).not.toContain(secret);
      expect(JSON.stringify(setupSession.calls.allArgs())).not.toContain(secret);
    }
    expect(element('[data-testid="failover-persisted-lead"]').textContent).toContain('OFF');
    // A refusal retains the draft; no optimistic saved state.
    outcomes.next({ command: '/model', ok: false, text: 'Route is unavailable' } as CommandOutcome);
    await settle();
    expect(element('[data-testid="policy-error"]').textContent).toContain('Route is unavailable');
    expect(fixture.componentInstance.failoverDraft!.rungs.length).toBe(2);
    element('[data-testid="failover-save-lead"]').click();
    const saved = policySnapshot();
    saved.failover = { menu: 'failover', scope: 'session', ...expected };
    getSessionConfig.and.returnValue(of(saved));
    outcomes.next({ command: '/model', ok: true, data: saved.failover } as CommandOutcome);
    await settle();
    expect(fixture.componentInstance.failoverDraft).toBeNull();
    expect(element('[data-testid="failover-persisted-lead"]').textContent).toContain('ON');
  });

  it('cancels a reordered/cleared ladder and saves the child ladder with its own atomic command', async () => {
    await openPolicies();
    element('[data-testid="failover-edit-lead"]').click();
    await settle();
    fixture.componentInstance.moveRung(0, 1);
    fixture.componentInstance.clearRungs();
    element('[data-testid="failover-cancel"]').click();
    await settle();
    expect(data.dispatch).not.toHaveBeenCalled();
    expect(fixture.componentInstance.failover!.rungs!.map(r => r.model)).toEqual(['lead-a', 'lead-b']);
    element('[data-testid="failover-edit-child"]').click();
    await settle();
    element('[data-testid="failover-clear"]').click();
    element('[data-testid="failover-enabled"]').click();
    await settle();
    element('[data-testid="failover-save-child"]').click();
    expect(data.dispatch).toHaveBeenCalledOnceWith('/subagent-config failover save {"enabled":false,"rungs":[]}');
    const saved = policySnapshot();
    saved.subagents!.failover = { enabled: false, rungs: [] };
    getSessionConfig.and.returnValue(of(saved));
    outcomes.next({ command: '/subagent-config', ok: true, status: 'ok', text: '', exit: 0,
      data: { menu: 'failover', scope: 'subagent', enabled: false, rungs: [] } });
    await settle();
    expect(fixture.componentInstance.failover!.rungs!.map(r => r.model)).toEqual(['lead-a', 'lead-b']);
    expect(fixture.componentInstance.subagents!.failover).toEqual({ enabled: false, rungs: [] });
  });

  it('refuses guessed providers and ignores discovery responses after Cancel', async () => {
    await openPolicies(standardCatalog); // Older CLI with no canonical route metadata.
    element('[data-testid="failover-edit-lead"]').click();
    await settle();
    expect(element('[data-testid="failover-add"]').disabled).toBeTrue();
    expect(element('[data-testid="fallback-error"]').textContent).toContain('canonical');
    const late = new Subject<ChatSetupCatalog>();
    setupSession.and.returnValue(late);
    fixture.componentInstance.loadFallbackCatalog();
    await settle();
    expect(progressShown()).toBeTrue();
    element('[data-testid="failover-cancel"]').click();
    await settle();
    late.next(fallbackCatalog({ model: 'gpt-b' }));
    late.complete();
    await settle();
    expect(fixture.componentInstance.fallbackCatalog).toBeUndefined();
    expect(fixture.componentInstance.failoverDraft).toBeNull();
    expect(progressShown()).toBeFalse();
    expect(data.dispatch).not.toHaveBeenCalled();
  });

  it('loads project children into the picker and routes explicit child actions and refusals', async () => {
    const children = { menu: 'durable-subagents', action: 'list', children: [
      { sessionId: 'subagent-saved', name: 'Saved child', locked: false }
    ] };
    await open({ menu: 'model', provider: 'openai' }, standardCatalog, undefined,
      () => of({ menu: 'config', available: true, durableSubagents: children }));
    element('[data-child-id="subagent-saved"]').click(); await settle();
    expect(data.dispatch).toHaveBeenCalledWith('/subagents show subagent-saved');
    outcomes.next({ command: '/subagents', ok: true, status: 'COMPLETED', text: '', exit: 0,
      data: { menu: 'durable-subagents', action: 'show', sessionId: 'subagent-saved',
        history: [{ role: 'assistant', content: '<script>not markup</script>' }] } } as unknown as CommandOutcome);
    await settle();
    expect(element('.child-turn pre').textContent).toContain('<script>not markup</script>');
    expect(element('.child-turn script')).toBeNull();
    const prompt = element('[data-testid="durable-prompt"]');
    prompt.value = 'continue the child'; prompt.dispatchEvent(new Event('input')); await settle();
    element('[data-testid="durable-resume"]').click(); await settle();
    expect(data.dispatch).toHaveBeenCalledWith('/subagents resume ' + JSON.stringify({ session_id: 'subagent-saved', prompt: 'continue the child' }));
    outcomes.next({ command: '/subagents', ok: false, status: 'INVALID', text: 'Permission denied', exit: 2 });
    await settle();
    expect(element('[data-testid="durable-error"]').textContent).toContain('Permission denied');
    expect(element('[data-testid="durable-prompt"]').value).toBe('continue the child');
  });

  it('loads hook/permission forms and preserves confirmed safety changes on refusal', async () => {
    await open({ menu: 'model', provider: 'openai' }, standardCatalog, undefined, () => of({ menu: 'config', available: true,
      permissions: { menu: 'permissions', canManage: true, rows: [{ key: 'bash', defaultLevel: 'allow', effectiveLevel: 'allow' }] },
      hooks: { menu: 'hooks', canManage: true, events: ['PreToolUse'], hooks: [] } }));
    element('[data-testid="permissions-edit"]').click(); await settle();
    await choose('[data-permission="bash"]', 'deny');
    element('[data-testid="permissions-confirm"]').click(); await settle();
    element('[data-testid="permissions-save"]').click(); await settle();
    expect(data.dispatch).toHaveBeenCalledWith('/permissions save ' + JSON.stringify({ overrides: { bash: 'deny' }, confirmed: true }));
    outcomes.next({ command: '/permissions', ok: false, status: 'INVALID', text: 'Permission denied', exit: 2 }); await settle();
    expect(element('[data-testid="safety-settings-error"]').textContent).toContain('Permission denied');
    expect(element('[data-permission="bash"]').value).toBe('deny');
  });

  it('edits durable judge guidance without text commands and preserves a refused draft', async () => {
    await open({ menu: 'model', provider: 'openai' }, standardCatalog, undefined, () => of({ menu: 'config', available: true,
      judge: { menu: 'judge', guidance: 'old guidance', globalEnabled: true } }));
    element('[data-testid="judge-edit"]').click(); await settle();
    await input('[data-testid="judge-guidance"]', 'Check the exact source before rejecting this action');
    expect(data.dispatch).not.toHaveBeenCalled();
    element('[data-testid="judge-save"]').click(); await settle();
    expect(data.dispatch).toHaveBeenCalledWith('/judge feedback Check the exact source before rejecting this action');
    expect(progressShown()).toBeTrue();
    outcomes.next({ command: '/judge', ok: false, status: 'INVALID', text: 'Not saved', exit: 2 }); await settle();
    expect(element('[data-testid="judge-guidance"]').value).toContain('Check the exact source');
    expect(element('[data-testid="judge-error"]').textContent).toContain('Not saved');
    expect(progressShown()).toBeFalse();
  });

  it('edits and reorders queued messages by stable id, committing only after an acknowledgement', async () => {
    await open({ menu: 'model', provider: 'openai' }, standardCatalog, undefined, () => of({ menu: 'config', available: true,
      queue: { menu: 'queue', queued: [{ id: 'q1', content: 'first', status: 'PENDING' }, { id: 'q2', content: 'second', status: 'PENDING' }] } }));
    fixture.componentInstance.moveQueued('q2', 1);
    expect(data.dispatch).toHaveBeenCalledWith('/queue-move q2 1');
    element('[data-queue-edit="q1"]').click(); await settle();
    await input('[data-testid="queue-edit-content"]', 'edited message with spaces');
    element('[data-testid="queue-edit-save"]').click(); await settle();
    expect(data.dispatch).toHaveBeenCalledWith('/queue-edit q1 edited message with spaces');
    outcomes.next({ command: '/queue-edit', ok: false, status: 'INVALID', text: 'Refused', exit: 2 }); await settle();
    expect(element('[data-testid="queue-edit-content"]').value).toBe('edited message with spaces');
    expect(element('[data-testid="queue-edit-error"]').textContent).toContain('Refused');
  });

  it('reseeds from the chat when the selector above the chat changes the model', async () => {
    await open({ menu: 'model', provider: 'openai' }, standardCatalog);
    setupSession.calls.reset();
    outcomes.next({ ok: true, command: '/model', data: { menu: 'model', state: { model: 'gpt-b' } } } as CommandOutcome);
    await settle();
    expect(setupSession).toHaveBeenCalledWith('chat-1', '/project', 'catalog', jasmine.any(Object));
  });
});
