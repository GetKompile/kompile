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
    expect(element.querySelector('[data-testid="native-model-editor"]')).toBeNull();
    expect(element.textContent).toContain('Model and vendor are selected directly above the conversation.');
    expect(element.textContent).toContain('opencode');
    expect(element.textContent).not.toContain('Load roles');
    expect(element.textContent).not.toContain('Load catalog');
  });

  it('does not duplicate the model/vendor dropdowns in the side-panel dialog', () => {
    expect(fixture.nativeElement.querySelector('select[aria-label="Model vendor"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('select[aria-label="Chat model"]')).toBeNull();
  });
});
