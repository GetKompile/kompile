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
    expect(fixture.nativeElement.querySelectorAll('select').length).toBe(2);
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

  it('preserves native framework IDs through the custom option and pins its vendor', async () => {
    service.getSessionConfig.and.returnValue(of(snapshot({ menu: 'model', provider: 'opencode',
      currentModel: 'zai/glm-5', nativeModelSelection: true })));
    component.refreshModels();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[aria-label="Model vendor"]').disabled).toBeTrue();
    expect(fixture.nativeElement.querySelector('[aria-label="Chat model"]').value).toBe('zai/glm-5');
    change('Chat model', '__custom__');
    const input = fixture.nativeElement.querySelector('[aria-label="Native model ID"]') as HTMLInputElement;
    input.value = 'zai/glm-4';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const button = Array.from(fixture.nativeElement.querySelectorAll('button'))
      .find((b: any) => b.textContent.includes('Apply model')) as HTMLButtonElement;
    button.click();
    expect(selected).toHaveBeenCalledWith('zai/glm-4');
  });
});
