import { TestBed, ComponentFixture } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, Subject } from 'rxjs';
import { LocalAgentChatService } from '@shared/services/local-agent-chat.service';
import { CommandOutcome } from '@shared/models/api-models';
import { CommandConfigDialogComponent, CommandConfigDialogData } from './command-config-dialog.component';

describe('Native framework session configuration', () => {
  let fixture: ComponentFixture<CommandConfigDialogComponent>;
  let outcomes: Subject<CommandOutcome>;

  beforeEach(async () => {
    outcomes = new Subject<CommandOutcome>();
    const data: CommandConfigDialogData = {
      modelMenu: null, roleMenu: null, fastMenu: null, ultracodeMenu: null,
      reminders: null, remindersGlobal: null, loops: null, loopsGlobal: null,
      queue: null, continueMenu: null, judgeMenu: null,
      sessionId: 'chat-1', workingDirectory: '/project',
      busy: () => false, liveSession: () => false,
      dispatch: jasmine.createSpy('dispatch'),
      selectRole: jasmine.createSpy('selectRole'), toggleFastMode: jasmine.createSpy('toggleFastMode'),
      toggleUltracode: jasmine.createSpy('toggleUltracode'), clearConversation: jasmine.createSpy('clearConversation')
    };
    await TestBed.configureTestingModule({
      imports: [CommandConfigDialogComponent, NoopAnimationsModule],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close: jasmine.createSpy('close') } },
        { provide: LocalAgentChatService, useValue: {
          getCommandOutcomes: () => outcomes,
          getSessionConfig: () => of({ menu: 'config', available: true, model: {
            menu: 'model', provider: 'opencode', currentModel: 'zai/glm-5', nativeModelSelection: true
          } })
        } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(CommandConfigDialogComponent);
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('shows the pinned native framework and hides standard-only controls', () => {
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('[data-testid="native-model-editor"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="session-model-config"]')).not.toBeNull();
    expect(element.textContent).toContain('opencode');
    expect(element.textContent).not.toContain('Load roles');
    expect(element.textContent).toContain('Load catalog');
  });

  it('keeps the restored model buttons alongside the conversation dropdowns', () => {
    expect(fixture.nativeElement.querySelector('select[aria-label="Model vendor"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('select[aria-label="Chat model"]')).toBeNull();
  });

  it('renders dynamic mid-session models, effort and every existing control section', () => {
    const component = fixture.componentInstance;
    component.modelMenu = { menu: 'model', provider: 'openai', models: [{ id: 'gpt-test', display: 'Test GPT', current: true }],
      vendors: [{ vendor: 'openai', display: 'OpenAI', current: true }, { vendor: 'anthropic', display: 'Anthropic' }] };
    component.thinkingMenu = { menu: 'thinking', supported: true, currentThinking: 'high',
      thinkingOptions: [{ value: '', label: 'Default' }, { value: 'high', label: 'High effort' }] };
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    for (const label of ['Test GPT', 'OpenAI', 'Anthropic', 'Thinking / effort', 'Role', 'Fast mode',
      'Ultracode', 'Reminders', 'Loops', 'Queue', 'Continue', 'Judge']) expect(text).toContain(label);
    const dispatch = component.data.dispatch as jasmine.Spy;
    component.selectModel('gpt-test');
    expect(dispatch).toHaveBeenCalledWith('/model gpt-test');
    component.selectThinking('high');
    expect(dispatch).toHaveBeenCalledWith('/thinking high');
    component.data.busy = () => true;
    dispatch.calls.reset();
    component.selectModel('gpt-test'); component.selectThinking('high');
    expect(dispatch).not.toHaveBeenCalled();
  });

  it('browses vendors quietly and dispatches a vendor-scoped model only on selection', () => {
    const component = fixture.componentInstance;
    const service = TestBed.inject(LocalAgentChatService);
    const fetch = spyOn(service, 'getSessionConfig').and.returnValue(of({ menu: 'config', model: {
      menu: 'model', models: [{ id: 'sonnet-test' }], vendors: [{ vendor: 'openai', current: true }, { vendor: 'anthropic' }]
    } }));
    component.pickVendor('anthropic');
    expect(fetch).toHaveBeenCalledWith('chat-1', '/project', 'anthropic');
    expect(component.data.dispatch).not.toHaveBeenCalled();
    component.selectModel('sonnet-test');
    expect(component.data.dispatch).toHaveBeenCalledWith('/model anthropic:sonnet-test');
  });
});
